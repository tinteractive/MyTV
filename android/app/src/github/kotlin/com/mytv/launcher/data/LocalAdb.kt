// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * A minimal ADB client that talks to the TV's own debug bridge: Network debugging on 127.0.0.1:5555, or else
 * Wireless debugging over TLS once MyTV is paired with a code ([AdbTls], Android 11+).
 * Used only for the owner-approved actions that need a shell: switching the stock launcher and pre-installed
 * packages off or back on (`pm disable-user` / `pm enable`), the screensaver delay, standby, the extra right, and
 * installing a verified MyTV update without Android's question ([Updates]).
 *
 * The RSA key lives in the Android Keystore (cannot be exported). The first connection on port 5555 makes the TV show
 * "Allow debugging?" for MyTV; after "Always allow" it works without a prompt. Revoking all debug
 * authorisations in Developer options removes that approval again (and a Wireless debugging pairing).
 */
object LocalAdb {
    /** This edition talks to the TV's own adb. */
    const val SUPPORTED = true
    open class NotAvailable(msg: String) : IOException(msg)      // network debugging off / port closed
    open class NotApproved(msg: String) : IOException(msg)       // prompt refused, or not approved yet (no prompt asked)
    /**
     * The whole wait for "Allow debugging?" passed without any answer. Some TVs (seen on a TCL) keep an
     * earlier question they believe is still open and never show a new one until the TV is restarted.
     */
    class NoAnswer(msg: String) : NotApproved(msg)

    private const val STLS = 0x534c5453; private const val CNXN = 0x4e584e43; private const val AUTH = 0x48545541; private const val OPEN = 0x4e45504f
    private const val OKAY = 0x59414b4f; private const val CLSE = 0x45534c43; private const val WRTE = 0x45545257
    private const val VERSION = 0x01000001; private const val MAX_DATA = 256 * 1024
    private const val AUTH_TOKEN = 1; private const val AUTH_SIGNATURE = 2; private const val AUTH_RSAPUBLICKEY = 3
    private const val ALIAS = "mytv-adb-key"
    private const val PORT = 5555
    /** adb's sync protocol carries a file in pieces of at most this size. */
    private const val SYNC_CHUNK = 64 * 1024

    /** PKCS#1 DigestInfo prefix for SHA-1: adb signs the 20-byte token as if it were a SHA-1 digest. */
    private val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

    private class Msg(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

    @Volatile private var app: android.content.Context? = null

    /** Gives LocalAdb the application (for Wireless debugging: the pairing and its mDNS look-up). */
    fun init(c: android.content.Context) { app = c.applicationContext }

    private fun port5555(): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 300) }; true }.getOrDefault(false)

    /**
     * Whether MyTV can reach the TV's debugging: port 5555 answers (Network debugging on), or MyTV is paired for
     * Wireless debugging and its port answers. Blocking: ~0.3 s, up to a few seconds when the TLS port moved.
     */
    fun reachable(): Boolean = port5555() || tlsPort() != null

    /** MyTV is paired for Wireless debugging. */
    fun wirelessPaired(): Boolean = app?.let { android.os.Build.VERSION.SDK_INT >= 30 && AdbTls.paired(it) } ?: false

    /** Network debugging on port 5555 answers (for "Connection to the TV"). */
    fun plainReachable(): Boolean = port5555()

    private fun tlsPort(): AdbTls.Found? {
        val c = app ?: return null
        return if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { AdbTls.connectPort(c) }.getOrNull() else null
    }

    /**
     * Runs one shell command and returns its output. [onApprovalNeeded] is called when the TV is about to show the
     * "Allow debugging?" prompt (the call then waits up to [approvalTimeoutMs] for the viewer). With [prompt] false the
     * TV is never asked: when MyTV is not approved yet, it throws [NotApproved] at once.
     */
    fun shell(command: String, onApprovalNeeded: () -> Unit = {}, approvalTimeoutMs: Int = 90_000, prompt: Boolean = true): String =
        session(onApprovalNeeded, approvalTimeoutMs, prompt) { it.shell(command) }

    /**
     * Copies [file] onto the TV at [remote] over adb's sync service (what `adb push` uses), as adb's shell user, mode
     * rw-r--r--. Never asks the TV: only for a MyTV that is already approved. A failure's message starts with
     * "push failed:" followed by what the TV said (for example "No space left on device").
     */
    fun push(file: File, remote: String) { session({}, 0, prompt = false) { it.push(file, remote) } }

    @Synchronized
    private fun <T> session(onApprovalNeeded: () -> Unit, approvalTimeoutMs: Int, prompt: Boolean, work: (Conn) -> T): T {
        var socket = Socket()
        var keySent = false
        try {
            var tls: AdbTls.Found? = null
            try { socket.connect(InetSocketAddress("127.0.0.1", PORT), 3000) } catch (e: IOException) {
                // No Network debugging: Wireless debugging, when MyTV is paired.
                tls = tlsPort() ?: throw NotAvailable("adb port closed")
                if (android.os.Build.VERSION.SDK_INT < 30) throw NotAvailable("adb port closed")
                runCatching { socket.close() }
                socket = AdbTls.connect(tls) ?: run {
                    // The port moved (Wireless debugging switched off and on, or just paired): look it up once more.
                    app?.let { AdbTls.dropPort(it) }
                    tls = tlsPort() ?: throw NotAvailable("tls port closed")
                    AdbTls.connect(tls!!)
                } ?: throw NotAvailable("tls port closed")
            }
            socket.soTimeout = 15_000
            var inp = DataInputStream(socket.getInputStream().buffered())
            var out = socket.getOutputStream()
            send(out, CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
            var signed = false
            var maxData: Int
            while (true) {
                val m = read(inp)
                when (m.cmd) {
                    CNXN -> { maxData = m.a1.coerceIn(4096, MAX_DATA); break }
                    // Wireless debugging: the TV asks for TLS; MyTV's key in the TLS handshake is the approval.
                    STLS -> {
                        val at = tls
                        if (at == null || android.os.Build.VERSION.SDK_INT < 30) throw IOException("unexpected STLS")
                        send(out, STLS, AdbTls.stlsVersion(), 0, ByteArray(0))
                        val ssl = try { AdbTls.wrap(socket, at.port) } catch (e: javax.net.ssl.SSLException) {
                            // The TV no longer knows MyTV's key (pairing removed): pair again.
                            app?.let { AdbTls.forget(it) }
                            throw NotApproved("not paired")
                        }
                        socket = ssl
                        inp = DataInputStream(ssl.inputStream.buffered()); out = ssl.outputStream
                    }
                    AUTH -> if (m.a0 == AUTH_TOKEN) {
                        // A real adb token is 20 bytes; anything else (another app on this port) is not signed.
                        if (!signed && m.data.size == 20) { signed = true; send(out, AUTH, AUTH_SIGNATURE, 0, sign(m.data)) }
                        else if (!prompt) throw NotApproved("not approved yet")
                        else if (!keySent) {
                            keySent = true
                            send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKey())
                            onApprovalNeeded()
                            socket.soTimeout = approvalTimeoutMs
                        } else throw NotApproved("key refused")
                    }
                    else -> throw IOException("unexpected ${m.cmd.toString(16)}")
                }
            }
            socket.soTimeout = 30_000
            return work(Conn(inp, out, maxData))
        } catch (e: java.net.SocketTimeoutException) {
            throw if (keySent) NoAnswer("no answer to the debugging question") else NotApproved("timeout")
        } catch (e: java.io.EOFException) {
            // adbd closes the connection when the viewer chose Deny (on some TVs) or debugging was switched off.
            throw if (keySent) NotApproved("refused") else NotAvailable("closed")
        } finally {
            runCatching { socket.close() }
        }
    }

    /** One approved connection; its streams are used one at a time. */
    private class Conn(val inp: DataInputStream, val out: OutputStream, val maxData: Int) {
        private var nextLocal = 1

        /** Opens [service]; returns (local id, remote id). */
        fun open(service: String): Pair<Int, Int> {
            val local = nextLocal++
            send(out, OPEN, local, 0, "$service\u0000".toByteArray())
            while (true) {
                val m = read(inp)
                if (m.a1 != local) continue
                if (m.cmd == OKAY) return local to m.a0
                if (m.cmd == CLSE) throw IOException("$service refused")
            }
        }

        fun shell(command: String): String {
            val (local, remote) = open("shell:$command")
            val result = ByteArrayOutputStream()
            while (true) {
                val m = read(inp)
                if (m.a1 != local) continue
                when (m.cmd) {
                    WRTE -> { result.write(m.data); send(out, OKAY, local, remote, ByteArray(0)) }
                    CLSE -> { send(out, CLSE, local, remote, ByteArray(0)); return String(result.toByteArray(), Charsets.UTF_8) }
                }
            }
        }

        /** Writes [bytes] to the stream in pieces the TV accepts, each acknowledged before the next. */
        private fun write(local: Int, remote: Int, bytes: ByteArray) {
            var at = 0
            while (at < bytes.size) {
                val n = minOf(maxData, bytes.size - at)
                send(out, WRTE, local, remote, bytes.copyOfRange(at, at + n))
                at += n
                while (true) {
                    val m = read(inp)
                    if (m.a1 != local) continue
                    if (m.cmd == OKAY) break
                    if (m.cmd == CLSE) throw IOException("push failed: closed")
                    // The TV only writes to a sync stream before DONE to say it failed.
                    if (m.cmd == WRTE) throw IOException("push failed: " + syncError(m.data))
                }
            }
        }

        fun push(file: File, path: String) {
            require(path.startsWith("/") && !path.contains(',')) { "bad path" }
            val (local, remote) = open("sync:")
            val spec = "$path,${0x81a4}".toByteArray()      // a plain file, rw-r--r--
            write(local, remote, syncHead("SEND", spec.size) + spec)
            file.inputStream().use { input ->
                val buf = ByteArray(SYNC_CHUNK)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    write(local, remote, syncHead("DATA", n) + buf.copyOf(n))
                }
            }
            write(local, remote, syncHead("DONE", (System.currentTimeMillis() / 1000).toInt()))
            // The answer: "OKAY" + 0, or "FAIL" + length + why.
            val answer = ByteArrayOutputStream()
            while (!syncAnswerComplete(answer.toByteArray())) {
                val m = read(inp)
                if (m.a1 != local) continue
                when (m.cmd) {
                    WRTE -> { answer.write(m.data); send(out, OKAY, local, remote, ByteArray(0)) }
                    CLSE -> throw IOException("push failed: closed")
                }
            }
            send(out, CLSE, local, remote, ByteArray(0))
            val a = answer.toByteArray()
            if (String(a, 0, 4, Charsets.US_ASCII) != "OKAY") throw IOException("push failed: " + syncError(a))
        }

        private fun syncAnswerComplete(a: ByteArray): Boolean {
            if (a.size < 8) return false
            if (String(a, 0, 4, Charsets.US_ASCII) != "FAIL") return true
            return a.size >= 8 + ByteBuffer.wrap(a, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }

        private fun syncHead(id: String, n: Int): ByteArray =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.toByteArray(Charsets.US_ASCII)).putInt(n).array()

        private fun syncError(a: ByteArray): String =
            if (a.size > 8 && String(a, 0, 4, Charsets.US_ASCII) == "FAIL") String(a, 8, a.size - 8, Charsets.UTF_8) else "unknown"
    }

    private fun send(out: OutputStream, cmd: Int, a0: Int, a1: Int, data: ByteArray) {
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(data.sumOf { it.toInt() and 0xff }).putInt(cmd.inv())
        b.put(data)
        out.write(b.array()); out.flush()
    }

    private fun read(inp: DataInputStream): Msg {
        val h = ByteArray(24); inp.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        if (len < 0 || len > MAX_DATA * 4) throw IOException("bad length")
        val d = ByteArray(len); inp.readFully(d)
        return Msg(cmd, a0, a1, d)
    }

    // ---------------------------------------------------------------- key

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey() {
        if (keyStore().containsAlias(ALIAS)) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_NONE)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build())
        }.generateKeyPair()
    }

    private fun sign(token: ByteArray): ByteArray {
        ensureKey()
        val key = keyStore().getKey(ALIAS, null) as PrivateKey
        return Signature.getInstance("NONEwithRSA").run { initSign(key); update(SHA1_PREFIX + token); sign() }
    }

    private fun publicKey(): ByteArray {
        ensureKey()
        return adbPublicKey(keyStore().getCertificate(ALIAS).publicKey as RSAPublicKey, "MyTV@tv")
    }

    /** A public key in adb's own format: little-endian {len, n0inv, n[], rr[], e}, base64, a space, the name, NUL. */
    internal fun adbPublicKey(pub: RSAPublicKey, name: String): ByteArray {
        val n = pub.modulus; val words = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n)
        val b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words).putInt(n0inv.toLong().toInt())
        for (i in 0 until words) b.putInt(n.shiftRight(32 * i).mod(r32).toLong().toInt())
        for (i in 0 until words) b.putInt(rr.shiftRight(32 * i).mod(r32).toLong().toInt())
        b.putInt(pub.publicExponent.toInt())
        return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + " $name\u0000").toByteArray()
    }
}
