// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Small shared helpers: bounded reads, image decoding, XML snippets, opening the first screen that works. */
object Net {
    /** Upper bound for anything read from the network or another app (an image, a status page). */
    const val MAX_BYTES = 4 * 1024 * 1024

    /** Reads at most [max] bytes; more is an error (a device or app sending endless data cannot exhaust memory). */
    fun readCapped(inp: InputStream, max: Int = MAX_BYTES, withinMs: Long = 20_000): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        // Also a limit on the whole answer: a device that sends a byte now and then would otherwise never finish.
        val deadline = android.os.SystemClock.elapsedRealtime() + withinMs
        while (true) {
            val n = inp.read(buf); if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("response too large")
            if (android.os.SystemClock.elapsedRealtime() > deadline) throw IOException("response too slow")
        }
        return out.toByteArray()
    }

    /** One text line (without its line end) of at most [max] bytes; null at the end of the stream. */
    fun readLineCapped(inp: InputStream, max: Int = 256 * 1024): String? {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
            out.write(b)
            if (out.size() > max) throw IOException("line too long")
        }
    }

    /** HTTPS (or HTTP through Android's own stack) GET with timeouts and a size limit. */
    fun get(url: String, connectMs: Int = 6000, readMs: Int = 8000, headers: Map<String, String> = emptyMap()): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = connectMs; c.readTimeout = readMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            return c.inputStream.use { readCapped(it) }
        } finally { c.disconnect() }
    }

    fun post(url: String, body: ByteArray, headers: Map<String, String> = emptyMap(), connectMs: Int = 6000, readMs: Int = 8000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = connectMs; c.readTimeout = readMs; c.requestMethod = "POST"; c.doOutput = true
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body) }
            return c.inputStream.use { readCapped(it) }
        } finally { c.disconnect() }
    }

    /** Decodes an image so its longest side is about [maxPx]; refuses absurd sizes (e.g. 100×100 000). */
    fun decodeSampled(bytes: ByteArray, maxPx: Int): ImageBitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val side = maxOf(o.outWidth, o.outHeight)
        if (side <= 0 || side > 8192) return null
        var s = 1; while (side / (s * 2) >= maxPx) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
    }

    /** A bitmap from another app scaled down to [maxPx] (the original stays theirs). */
    fun scaled(b: Bitmap, maxPx: Int): ImageBitmap {
        val s = maxPx.toFloat() / maxOf(b.width, b.height)
        return (if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b).asImageBitmap()
    }

    // ---------------------------------------------------------------- XML snippets from devices (no XML parser: no XXE)
    private val tagRegex = ConcurrentHashMap<String, Regex>()
    fun tag(x: String, name: String): String? =
        tagRegex.getOrPut(name) { Regex("<$name(?: [^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL) }.find(x)?.groupValues?.get(1)
    fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Starts the first intent that works (TVs differ in which settings screens they really have). */
    fun startFirst(c: Context, vararg intents: Intent?): Boolean = intents.filterNotNull().any { i ->
        runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    /** A home-network address as discovery reports it: private IPv4, or IPv6 link-local / unique-local. */
    fun isLan(a: java.net.InetAddress): Boolean = !a.isLoopbackAddress && !a.isAnyLocalAddress && !a.isMulticastAddress &&
        (a.isSiteLocalAddress || a.isLinkLocalAddress || (a is java.net.Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC))

    /**
     * True for a private home-network address (devices that answer discovery must live there). Only an address written
     * as numbers counts: a name is never looked up, because it could point to the home network now and elsewhere a
     * moment later, when the connection is made (DNS rebinding).
     */
    fun isLocal(host: String): Boolean = literalIp(host)?.let { it.isSiteLocalAddress && !it.isLoopbackAddress } ?: false

    /** [host] as an address when it is a literal IPv4 or IPv6 address; null for a name (no DNS lookup, ever). */
    internal fun literalIp(host: String): java.net.InetAddress? = runCatching {
        val h = host.removePrefix("[").removeSuffix("]")
        val v4 = h.split('.')
        when {
            v4.size == 4 && v4.all { p -> p.length in 1..3 && p.all(Char::isDigit) && p.toInt() <= 255 } ->
                java.net.InetAddress.getByAddress(ByteArray(4) { v4[it].toInt().toByte() })
            // With a colon Java parses the text as an IPv6 literal and never asks DNS.
            ':' in h && h.all { it in "0123456789abcdefABCDEF:." } -> java.net.InetAddress.getByName(h)
            else -> null
        }
    }.getOrNull()
}
