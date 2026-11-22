// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Google Cast speakers and screens (Google/Nest Mini, Chromecast, cast-enabled speakers), locally, no account:
 * the Cast protocol on port 8009 (TLS with the device's own certificate; JSON messages in a small protobuf frame).
 * Reads what the active app plays and sends play / pause / next. The TV itself is left out (its apps come from
 * MediaApps).
 */
object Cast {
    private val devices = ConcurrentHashMap<String, String>()     // ip -> friendly name
    private const val NS_CONN = "urn:x-cast:com.google.cast.tp.connection"
    private const val NS_RECV = "urn:x-cast:com.google.cast.receiver"
    private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

    fun discover(c: Context) {
        // This TV's own address is looked up only when a device is found (off the main thread), not at every poll.
        Lan.discover(c, "_googlecast._tcp") { f ->
            if (f.host != PhotoInbox.address()) devices[f.host] = f.attrs["fn"]?.takeIf { it.isNotBlank() } ?: f.name.substringBeforeLast('-')
        }
    }

    fun names(): List<String> = devices.values.sorted()

    /** Blocking. */
    private val failures = ConcurrentHashMap<String, Int>()

    /** Blocking. A device that does not answer three times in a row is forgotten until discovery finds it again. */
    fun nowPlaying(): List<NowPlaying> = devices.entries.mapNotNull { (ip, name) ->
        runCatching { status(ip, name) }.onSuccess { failures.remove(ip) }.onFailure {
            Log.w("MyTV", "cast $name: $it")
            if (failures.merge(ip, 1, Int::plus)!! >= 3) { devices.remove(ip); failures.remove(ip) }
        }.getOrNull()
    }

    private fun status(ip: String, name: String): NowPlaying? = Conn(ip).use { c ->
        c.send("receiver-0", NS_CONN, """{"type":"CONNECT"}""")
        c.send("receiver-0", NS_RECV, """{"type":"GET_STATUS","requestId":1}""")
        val recv = c.await("RECEIVER_STATUS") ?: return null
        val apps = recv.optJSONObject("status")?.optJSONArray("applications") ?: return null
        val app = (0 until apps.length()).map { apps.getJSONObject(it) }.firstOrNull { !it.optBoolean("isIdleScreen", false) } ?: return null
        val transport = app.optString("transportId")
        val appName = app.optString("displayName")
        val statusText = app.optString("statusText").takeIf { it.isNotBlank() && it != appName }
        var title: String? = null; var artist: String? = null; var image: String? = null
        var playing = statusText != null; var session = -1
        if (transport.isNotBlank()) {
            c.send(transport, NS_CONN, """{"type":"CONNECT"}""")
            c.send(transport, NS_MEDIA, """{"type":"GET_STATUS","requestId":2}""")
            c.await("MEDIA_STATUS", 1500)?.optJSONArray("status")?.optJSONObject(0)?.let { st ->
                session = st.optInt("mediaSessionId", -1)
                val ps = st.optString("playerState")
                if (ps == "IDLE") return@let
                playing = ps == "PLAYING" || ps == "BUFFERING"
                val md = st.optJSONObject("media")?.optJSONObject("metadata")
                title = md?.optString("title")?.takeIf { it.isNotBlank() }
                artist = (md?.optString("artist")?.takeIf { it.isNotBlank() } ?: md?.optString("subtitle")?.takeIf { it.isNotBlank() })
                image = md?.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.takeIf { it.startsWith("http") }
                if (title == null && statusText == null) playing = playing && md != null
            }
        }
        val t = title ?: statusText ?: return null
        NowPlaying(name, ip, t, artist ?: appName.takeIf { title != null }, image, playing,
            source = "cast", key = "cast:$ip", extra = "$transport|$session")
    }

    fun command(ip: String, extra: String?, action: String): Boolean = runCatching {
        val (transport, session) = (extra ?: "").split('|').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: -1) }
        if (transport.isBlank() || session < 0) return false
        val type = when (action) { "Play" -> "PLAY"; "Pause" -> "PAUSE"; else -> "QUEUE_NEXT" }
        Conn(ip).use { c ->
            c.send(transport, NS_CONN, """{"type":"CONNECT"}""")
            c.send(transport, NS_MEDIA, """{"type":"$type","mediaSessionId":$session,"requestId":3}""")
            c.await("MEDIA_STATUS", 1500)
        }
        true
    }.onFailure { Log.w("MyTV", "cast $action: $it") }.getOrDefault(false)

    /** One short-lived connection: CastMessage protobuf frames carrying JSON. */
    private class Conn(ip: String) : AutoCloseable {
        private val sock: SSLSocket
        private val out: OutputStream
        private val inp: DataInputStream

        init {
            // Cast devices use their own (self-signed) certificate; this is a local status read, no secrets are sent.
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
            val raw = java.net.Socket().apply { connect(InetSocketAddress(ip, 8009), 3000); soTimeout = 3000 }
            sock = ctx.socketFactory.createSocket(raw, ip, 8009, true) as SSLSocket
            sock.startHandshake()
            out = sock.outputStream
            inp = DataInputStream(sock.inputStream)
        }

        fun send(dest: String, ns: String, json: String) {
            val body = java.io.ByteArrayOutputStream()
            fun varint(v: Int) { var x = v; while (x >= 0x80) { body.write((x and 0x7F) or 0x80); x = x ushr 7 }; body.write(x) }
            fun str(tag: Int, s: String) { val b = s.toByteArray(); body.write(tag); varint(b.size); body.write(b) }
            body.write(0x08); body.write(0)          // protocol_version = CASTV2_1_0
            str(0x12, "sender-0"); str(0x1a, dest); str(0x22, ns)
            body.write(0x28); body.write(0)          // payload_type = STRING
            str(0x32, json)
            val b = body.toByteArray()
            out.write(java.nio.ByteBuffer.allocate(4).putInt(b.size).array()); out.write(b); out.flush()
        }

        /** Reads frames until a JSON payload with this "type" arrives (answers PINGs on the way). */
        fun await(type: String, timeoutMs: Long = 2500): JSONObject? {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val len = runCatching { inp.readInt() }.getOrNull() ?: return null
                if (len <= 0 || len > 1_000_000) return null
                val b = ByteArray(len); inp.readFully(b)
                val (ns, payload) = parse(b)
                if (payload == null) continue
                val o = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                if (o.optString("type") == "PING") { send("receiver-0", ns ?: "urn:x-cast:com.google.cast.tp.heartbeat", """{"type":"PONG"}"""); continue }
                if (o.optString("type") == type) return o
            }
            return null
        }

        private fun parse(b: ByteArray): Pair<String?, String?> {
            var i = 0; var ns: String? = null; var payload: String? = null
            fun varint(): Long { var r = 0L; var s = 0; while (true) { val x = b[i++].toInt() and 0xFF; r = r or ((x and 0x7F).toLong() shl s); if (x < 0x80) return r; s += 7 } }
            while (i < b.size) {
                val key = varint().toInt(); val field = key ushr 3
                when (key and 7) {
                    0 -> varint()
                    2 -> { val n = varint().toInt(); val s = String(b, i, n); i += n; if (field == 4) ns = s; if (field == 6) payload = s }
                    else -> return ns to payload
                }
            }
            return ns to payload
        }

        override fun close() { runCatching { sock.close() } }
    }
}
