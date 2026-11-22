// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** What a Sonos group is playing (only real music/radio; TV sound and grouped members are left out). */
data class NowPlaying(
    val room: String,
    val coordinatorIp: String,
    val title: String,
    val artist: String?,
    val art: String?,
    val playing: Boolean,          // false = paused
    val source: String = "sonos",  // sonos | cast | app
    val key: String = "sonos:$coordinatorIp",
    val image: androidx.compose.ui.graphics.ImageBitmap? = null,   // ready-made cover (apps on the TV)
    val pkg: String? = null,       // app on the TV
    val extra: String? = null,     // cast: transport|session
    val changedAt: Long? = null,   // wall-clock time of the last play/pause change, when the source reports it
)

/**
 * Sonos, locally over UPnP/SOAP on port 1400 (no account, no cloud, no permission). Speakers are found with mDNS
 * (`_sonos._tcp`); the zone-group topology says which speaker leads a group, and only leaders report the music.
 */
object Sonos {
    private val speakers = ConcurrentHashMap<String, String>()     // ip -> name
    fun discover(c: Context) {
        Lan.discover(c, "_sonos._tcp") { f -> speakers[f.host] = f.name.substringAfter('@') }
    }

    fun roomNames(): List<String> = speakers.values.sorted()

    /** The playing (or paused) groups, playing first. Blocking network calls: run off the main thread. */
    fun nowPlaying(): List<NowPlaying> {
        // Any speaker knows the whole household; take the first that answers (one may be unplugged).
        val topo = speakers.keys.asSequence().take(3).firstNotNullOfOrNull { ip ->
            runCatching { soap(ip, "/ZoneGroupTopology/Control", "ZoneGroupTopology", "GetZoneGroupState", "") }
                .onFailure { Log.w("MyTV", "sonos topology $ip: $it"); speakers.remove(ip) }.getOrNull()
        } ?: return emptyList()
        return parseGroups(topo).mapNotNull { (room, ip) -> runCatching { groupState(room, ip) }.onFailure { Log.w("MyTV", "sonos $room: $it") }.getOrNull() }
            .sortedBy { !it.playing }
    }

    private fun groupState(room: String, ip: String): NowPlaying? {
        val state = Net.tag(soap(ip, "/MediaRenderer/AVTransport/Control", "AVTransport", "GetTransportInfo", "<InstanceID>0</InstanceID>"), "CurrentTransportState")
        if (state != "PLAYING" && state != "PAUSED_PLAYBACK") return null
        val pos = soap(ip, "/MediaRenderer/AVTransport/Control", "AVTransport", "GetPositionInfo", "<InstanceID>0</InstanceID>")
        return parseTrack(room, ip, state == "PLAYING", pos)
    }

    /** Pure parsing, unit-tested: track info from a GetPositionInfo response. */
    fun parseTrack(room: String, ip: String, playing: Boolean, positionXml: String, port: Int = 1400): NowPlaying? {
        val uri = Net.unescape(Net.tag(positionXml, "TrackURI") ?: "")
        // TV sound through the soundbar, or a member that just follows its group leader: not "music".
        if (uri.startsWith("x-sonos-htastream:") || uri.startsWith("x-rincon:")) return null
        val meta = Net.unescape(Net.tag(positionXml, "TrackMetaData") ?: "")
        var title = Net.tag(meta, "dc:title")?.let(Net::unescape)
        val artist = Net.tag(meta, "dc:creator")?.let(Net::unescape)
        // Radio: the stream title carries "Artist - Title".
        Net.tag(meta, "r:streamContent")?.let(Net::unescape)?.takeIf { it.isNotBlank() }?.let { title = it }
        if (title.isNullOrBlank()) return null
        val art = Net.tag(meta, "upnp:albumArtURI")?.let(Net::unescape)?.let { if (it.startsWith("http")) it else "http://$ip:$port$it" }
        return NowPlaying(room, ip, title!!, artist, art, playing)
    }

    /** (room name, leader ip) per group, from GetZoneGroupState. */
    fun parseGroups(topologyXml: String): List<Pair<String, String>> {
        val x = Net.unescape(topologyXml)
        return Regex("<ZoneGroup Coordinator=\"([^\"]+)\"[^>]*>(.*?)</ZoneGroup>", RegexOption.DOT_MATCHES_ALL).findAll(x).mapNotNull { g ->
            val members = Regex("<ZoneGroupMember UUID=\"([^\"]+)\" Location=\"http://([\\d.]+):1400[^\"]*\" ZoneName=\"([^\"]+)\"").findAll(g.groupValues[2])
                .map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toList()
            val lead = members.firstOrNull { it.first == g.groupValues[1] } ?: return@mapNotNull null
            members.map { it.third }.distinct().joinToString(" + ") to lead.second
        }.toList()
    }

    @Volatile private var lastArt: Pair<String, androidx.compose.ui.graphics.ImageBitmap>? = null

    /** Album art, scaled down; only the current cover is kept (covers change every track). */
    fun art(uri: String): androidx.compose.ui.graphics.ImageBitmap? = lastArt?.takeIf { it.first == uri }?.second ?: runCatching {
        val u = URL(uri)
        // Plain http only from a device on the home network; anything else must be https.
        val bytes = when {
            u.protocol == "http" && Net.isLocal(u.host) -> http(u.host, if (u.port > 0) u.port else 80, "GET", u.file, null)
            u.protocol == "https" -> Net.get(uri, 3000, 5000)
            else -> return@runCatching null
        }
        Net.decodeSampled(bytes, 128)?.also { lastArt = uri to it }
    }.getOrNull()

    // ---------------------------------------------------------------- volume (quiet hours)

    /** Soundbars and the Amp: the Sonos models a TV plays through over HDMI. */
    private val HOME_THEATER = Regex("Arc|Beam|Ray|Playbar|Playbase|Amp", RegexOption.IGNORE_CASE)

    /** Found Sonos home-theater speakers as "ip|room|model" (blocking: reads each speaker's description once). */
    fun homeTheaters(): List<String> = speakers.mapNotNull { (ip, room) ->
        val model = models.getOrPut(ip) {
            runCatching { Net.tag(String(http(ip, 1400, "GET", "/xml/device_description.xml", null)), "modelName").orEmpty() }.getOrDefault("")
        }
        if (HOME_THEATER.containsMatchIn(model)) "$ip|$room|${model.removePrefix("Sonos ")}" else null
    }
    private val models = ConcurrentHashMap<String, String>()

    fun volume(ip: String): Int? = runCatching {
        Net.tag(soap(ip, "/MediaRenderer/RenderingControl/Control", "RenderingControl", "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>"), "CurrentVolume")?.toInt()
    }.getOrNull()

    fun setVolume(ip: String, v: Int) = runCatching {
        soap(ip, "/MediaRenderer/RenderingControl/Control", "RenderingControl", "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$v</DesiredVolume>")
    }.isSuccess

    fun command(ip: String, action: String) = runCatching {
        soap(ip, "/MediaRenderer/AVTransport/Control", "AVTransport", action, "<InstanceID>0</InstanceID>" + if (action == "Play") "<Speed>1</Speed>" else "")
    }.onFailure { Log.w("MyTV", "sonos $action: $it") }.isSuccess

    private fun soap(ip: String, path: String, svc: String, action: String, args: String) = soapAt(ip, 1400, path, svc, action, args)

    internal fun soapAt(ip: String, port: Int, path: String, svc: String, action: String, args: String): String {
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"urn:schemas-upnp-org:service:$svc:1\">$args</u:$action></s:Body></s:Envelope>"
        return String(http(ip, port, "POST", path, body.toByteArray(), mapOf(
            "Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPACTION" to "\"urn:schemas-upnp-org:service:$svc:1#$action\"")))
    }

    /**
     * Plain HTTP/1.0 over a socket, home network only. Android refuses cleartext HTTP through HttpURLConnection
     * (and that rule stays on for everything else); Sonos speakers only speak plain HTTP on port 1400.
     */
    internal fun http(host: String, port: Int, method: String, path: String, body: ByteArray?, headers: Map<String, String> = emptyMap()): ByteArray {
        // Host and path can come from a device's answer: a line break would add headers or a second request.
        require(safeRequestPart(host) && safeRequestPart(path)) { "line break in request" }
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(host, port), 3000)
            s.soTimeout = 5000
            val head = StringBuilder("$method $path HTTP/1.0\r\nHost: $host:$port\r\n")
            headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            if (body != null) head.append("Content-Length: ${body.size}\r\n")
            val out = s.getOutputStream()
            out.write(head.append("\r\n").toString().toByteArray())
            body?.let { out.write(it) }
            out.flush()
            val all = Net.readCapped(s.getInputStream())
            var i = 0
            while (i + 3 < all.size && !(all[i] == CR && all[i + 1] == LF && all[i + 2] == CR && all[i + 3] == LF)) i++
            val reply = String(all, 0, minOf(i, all.size))
            val status = reply.substringBefore("\r\n")
            if (status.split(' ').getOrNull(1) != "200") throw java.io.IOException("sonos: $status")
            val payload = all.copyOfRange(minOf(i + 4, all.size), all.size)
            // Some files (device_description.xml) come chunked even to an HTTP/1.0 request.
            return if (reply.contains("transfer-encoding: chunked", ignoreCase = true)) dechunk(payload) else payload
        }
    }

    /** Pure, unit-tested: no control characters (CR, LF, …) that could split an HTTP request line or header. */
    internal fun safeRequestPart(s: String) = s.none { it.code < 0x20 || it.code == 0x7F }

    /** Pure, unit-tested: joins a chunked HTTP body ("5", CRLF, "Sonos", CRLF, …, "0" → "Sonos…"). */
    internal fun dechunk(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(); var i = 0
        while (i < b.size) {
            var e = i; while (e + 1 < b.size && !(b[e] == CR && b[e + 1] == LF)) e++
            val size = String(b, i, e - i).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = e + 2; val end = minOf(start + size, b.size)
            out.write(b, start, end - start); i = end + 2
        }
        return out.toByteArray()
    }

    private const val CR: Byte = 13
    private const val LF: Byte = 10

}
