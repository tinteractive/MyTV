// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Other speaker brands, locally and without accounts: Denon/Marantz HEOS (CLI on port 1255), Yamaha MusicCast
 * (HTTP API), Bluesound/NAD BluOS (port 11000), Bose SoundTouch (port 8090) and any UPnP/DLNA renderer (AVTransport).
 * Found via mDNS (BluOS, Bose) and SSDP (the rest). Sonos, Google Cast and this TV have their own sources and are skipped.
 */
object Speakers {
    data class Dev(val ip: String, val name: String, val kind: String, val port: Int, val control: String? = null)

    private val devs = ConcurrentHashMap<String, Dev>()
    private val failures = ConcurrentHashMap<String, Int>()
    private val locationRe = Regex("(?im)^location:\\s*(\\S+)")
    @Volatile private var lastSsdp = 0L

    fun names(): List<String> = devs.values.map { it.name }.sorted()

    /** Development check: a speaker at a fixed address (e.g. a simulator reached through adb reverse). */
    fun addForTest(d: Dev) { devs[d.ip + ":" + d.port] = d }

    fun discover(c: Context) {
        Lan.discover(c, "_musc._tcp") { f -> devs.putIfAbsent(f.host, Dev(f.host, f.name, "bluos", 11000)) }
        Lan.discover(c, "_soundtouch._tcp") { f -> devs.putIfAbsent(f.host, Dev(f.host, f.name, "bose", 8090)) }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSsdp > 5 * 60_000L) { lastSsdp = now; thread(name = "ssdp", isDaemon = true) { runCatching { ssdp() }.onFailure { Log.w("MyTV", "ssdp: $it") } } }
    }

    /** UPnP search; each answer's device description tells the brand and the AVTransport address. */
    private fun ssdp() {
        val own = PhotoInbox.address()
        val locations = LinkedHashSet<String>()
        DatagramSocket().use { s ->
            s.soTimeout = 800
            for (st in listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-denon-com:device:ACT-Denon:1")) {
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900))
            }
            val end = SystemClock.elapsedRealtime() + 3500
            val buf = ByteArray(4096)
            while (SystemClock.elapsedRealtime() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                // The description must come from the device that answered, on the home network (no redirect elsewhere).
                val from = p.address.hostAddress ?: continue
                locationRe.find(String(p.data, 0, p.length))?.groupValues?.get(1)?.takeIf { l ->
                    runCatching { URL(l).host == from && Net.isLocal(from) }.getOrDefault(false)
                }?.let { locations += it }
            }
        }
        for (loc in locations) runCatching {
            val u = URL(loc)
            if (u.host == own || devs.keys.any { it.startsWith(u.host) }) return@runCatching
            val xml = String(Sonos.http(u.host, if (u.port > 0) u.port else 80, "GET", u.file, null))
            describe(u, xml)?.let { devs[u.host] = it }
        }
    }

    /** Pure parsing, unit-tested: which reader fits this device description (null = skip). */
    fun describe(u: URL, xml: String): Dev? {
        val maker = Net.tag(xml, "manufacturer").orEmpty()
        val name = Net.tag(xml, "friendlyName")?.let(Net::unescape) ?: u.host
        if (Regex("(?i)sonos|google|philips|tpvision").containsMatchIn(maker)) return null     // own sources / this TV
        return when {
            Regex("(?i)denon|marantz").containsMatchIn(maker) -> Dev(u.host, name, "heos", 1255)
            Regex("(?i)yamaha").containsMatchIn(maker) && xml.contains("yamaha:X_yxcControlURL", true) -> Dev(u.host, name, "musiccast", 80)
            Regex("(?i)bose|bluesound|nad ").containsMatchIn(maker) -> null                     // found via mDNS
            else -> {
                val m = Regex("<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>.*?<controlURL>(.*?)</controlURL>",
                    RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
                val path = m.groupValues[1].let { if (it.startsWith("/")) it else "/$it" }
                Dev(u.host, name, "upnp", if (u.port > 0) u.port else 80, path)
            }
        }
    }

    /** Blocking. A device that does not answer three times in a row is forgotten (rediscovered when it is back). */
    fun nowPlaying(): List<NowPlaying> = devs.entries.mapNotNull { (k, d) ->
        runCatching { read(d) }.onSuccess { failures.remove(k) }.onFailure {
            Log.w("MyTV", "speaker ${d.name}: $it")
            if (failures.merge(k, 1, Int::plus)!! >= 3) { devs.remove(k); failures.remove(k); lastSsdp = 0L }
        }.getOrNull()
    }

    private fun get(d: Dev, path: String) = String(Sonos.http(d.ip, d.port, "GET", path, null))

    private fun read(d: Dev): NowPlaying? = when (d.kind) {
        "bluos" -> parseBluOS(d, get(d, "/Status"))
        "bose" -> parseBose(d, get(d, "/now_playing"))
        "musiccast" -> parseMusicCast(d, get(d, "/YamahaExtendedControl/v1/netusb/getPlayInfo"))
        "heos" -> heosNowPlaying(d)
        else -> {
            val st = Sonos.soapAt(d.ip, d.port, d.control!!, "AVTransport", "GetTransportInfo", "<InstanceID>0</InstanceID>")
            val state = Net.tag(st, "CurrentTransportState")
            if (state != "PLAYING" && state != "PAUSED_PLAYBACK") null
            else Sonos.parseTrack(d.name, d.ip, state == "PLAYING",
                Sonos.soapAt(d.ip, d.port, d.control, "AVTransport", "GetPositionInfo", "<InstanceID>0</InstanceID>"), d.port)
                ?.copy(source = "speaker", key = "spk:${d.ip}", extra = "upnp|${d.port}|${d.control}")
        }
    }

    // ---------------------------------------------------------------- parsers (unit-tested)
    fun parseBluOS(d: Dev, xml: String): NowPlaying? {
        val state = Net.tag(xml, "state") ?: return null
        if (state !in setOf("play", "stream", "pause")) return null
        val title = (Net.tag(xml, "title1") ?: Net.tag(xml, "name"))?.let(Net::unescape) ?: return null
        val artist = (Net.tag(xml, "artist") ?: Net.tag(xml, "title2"))?.let(Net::unescape)
        val img = Net.tag(xml, "image")?.let(Net::unescape)?.let { if (it.startsWith("http")) it else "http://${d.ip}:${d.port}$it" }
        return NowPlaying(d.name, d.ip, title, artist, img, state != "pause", source = "speaker", key = "spk:${d.ip}", extra = "bluos|${d.port}")
    }

    fun parseBose(d: Dev, xml: String): NowPlaying? {
        if (Regex("source=\"STANDBY\"").containsMatchIn(xml)) return null
        val status = Net.tag(xml, "playStatus") ?: return null
        if (status == "STOP_STATE") return null
        val title = (Net.tag(xml, "track") ?: Net.tag(xml, "stationName"))?.let(Net::unescape)?.takeIf { it.isNotBlank() } ?: return null
        val artist = Net.tag(xml, "artist")?.let(Net::unescape)?.takeIf { it.isNotBlank() }
        val art = Regex("<art(?: [^>]*)?>(.*?)</art>").find(xml)?.groupValues?.get(1)?.let(Net::unescape)?.takeIf { it.startsWith("http") }
        return NowPlaying(d.name, d.ip, title, artist, art, status == "PLAY_STATE" || status == "BUFFERING_STATE",
            source = "speaker", key = "spk:${d.ip}", extra = "bose|${d.port}")
    }

    fun parseMusicCast(d: Dev, json: String): NowPlaying? {
        val o = JSONObject(json)
        val pb = o.optString("playback")
        if (pb != "play" && pb != "pause") return null
        val title = o.optString("track").takeIf { it.isNotBlank() } ?: return null
        val artist = o.optString("artist").takeIf { it.isNotBlank() }
        val art = o.optString("albumart_url").takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "http://${d.ip}$it" }
        return NowPlaying(d.name, d.ip, title, artist, art, pb == "play", source = "speaker", key = "spk:${d.ip}", extra = "musiccast|${d.port}")
    }

    /** HEOS: [state] = message of get_play_state ("pid=1&state=play"), [media] = payload of get_now_playing_media. */
    fun parseHeos(d: Dev, pid: Int, state: String, media: JSONObject): NowPlaying? {
        val st = Regex("state=(\\w+)").find(state)?.groupValues?.get(1) ?: return null
        if (st != "play" && st != "pause") return null
        val title = (media.optString("song").takeIf { it.isNotBlank() } ?: media.optString("station").takeIf { it.isNotBlank() }) ?: return null
        return NowPlaying(d.name, d.ip, title, media.optString("artist").takeIf { it.isNotBlank() },
            media.optString("image_url").takeIf { it.startsWith("http") }, st == "play", source = "speaker", key = "spk:${d.ip}:$pid", extra = "heos|$pid")
    }

    private fun heosNowPlaying(d: Dev): NowPlaying? = heos(d.ip) { cmd ->
        val players = cmd("player/get_players").optJSONArray("payload") ?: return@heos null
        val p = (0 until players.length()).map { players.getJSONObject(it) }.firstOrNull { it.optString("ip") == d.ip } ?: players.optJSONObject(0) ?: return@heos null
        val pid = p.getInt("pid")
        val state = cmd("player/get_play_state?pid=$pid").optJSONObject("heos")?.optString("message").orEmpty()
        val media = cmd("player/get_now_playing_media?pid=$pid").optJSONObject("payload") ?: return@heos null
        parseHeos(d.copy(name = p.optString("name", d.name)), pid, state, media)
    }

    /** One connection to the HEOS command line; [block] sends commands and gets their final answers. */
    private fun <T> heos(ip: String, block: ((String) -> JSONObject) -> T): T = Socket().use { s ->
        s.connect(InetSocketAddress(ip, 1255), 3000); s.soTimeout = 3000
        val out = s.getOutputStream(); val r = java.io.BufferedInputStream(s.getInputStream())
        var answers = 0
        block { c ->
            out.write("heos://$c\r\n".toByteArray()); out.flush()
            var o: JSONObject
            do {
                // Lines and their number are limited: a device (or something posing as one) cannot fill the memory.
                if (++answers > 200) throw java.io.IOException("heos: too many answers")
                o = JSONObject(Net.readLineCapped(r) ?: throw java.io.IOException("heos closed"))
                val h = o.optJSONObject("heos")
            } while (h != null && (h.optString("command").startsWith("event/") || h.optString("message").contains("command under process")))
            o
        }
    }

    // ---------------------------------------------------------------- control
    fun command(np: NowPlaying, action: String): Boolean = runCatching {
        val parts = (np.extra ?: "").split('|')
        val d = Dev(np.coordinatorIp, np.room, parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 80, parts.getOrNull(2))
        when (d.kind) {
            "bluos" -> get(d, when (action) { "Play" -> "/Play"; "Pause" -> "/Pause"; else -> "/Skip" })
            "bose" -> {
                val key = when (action) { "Play" -> "PLAY"; "Pause" -> "PAUSE"; else -> "NEXT_TRACK" }
                for (st in listOf("press", "release")) Sonos.http(d.ip, d.port, "POST", "/key", "<key state=\"$st\" sender=\"Gabbo\">$key</key>".toByteArray(),
                    mapOf("Content-Type" to "text/xml"))
            }
            "musiccast" -> get(d, "/YamahaExtendedControl/v1/netusb/setPlayback?playback=" + when (action) { "Play" -> "play"; "Pause" -> "pause"; else -> "next" })
            "heos" -> heos(d.ip) { cmd ->
                val pid = parts.getOrNull(1)
                cmd(if (action == "Next") "player/play_next?pid=$pid" else "player/set_play_state?pid=$pid&state=" + (if (action == "Play") "play" else "pause"))
            }
            else -> Sonos.soapAt(d.ip, d.port, d.control!!, "AVTransport", action, "<InstanceID>0</InstanceID>" + if (action == "Play") "<Speed>1</Speed>" else "")
        }
        true
    }.onFailure { Log.w("MyTV", "speaker $action: $it") }.getOrDefault(false)

}
