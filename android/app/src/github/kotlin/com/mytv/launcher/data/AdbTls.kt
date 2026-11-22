// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Wireless debugging (Android 11+): the TV's own adb over TLS, for TVs that have no Network debugging on port 5555.
 * MyTV pairs once with the six-digit code the TV shows (Developer options → Wireless debugging → Pair device with
 * pairing code), then connects to the TV's TLS port, which changes and is found by mDNS (_adb-tls-connect._tcp).
 *
 * Pairing follows adb's own protocol (AOSP adb/pairing_auth, pairing_connection): TLS 1.3 with MyTV's certificate,
 * SPAKE2 ([Spake2]) over the code plus the TLS session's exported key, then the public keys are exchanged encrypted
 * with AES-128-GCM. Only answers from this TV's own addresses are used. The key lives in the Android Keystore.
 * MyTV's own implementation, without extra libraries.
 */
@RequiresApi(30)
internal object AdbTls {
    const val CONNECT = "_adb-tls-connect._tcp"
    const val PAIRING = "_adb-tls-pairing._tcp"
    private const val WRAP_ALIAS = "mytv-adb-tls-wrap"
    private const val PREFS = "adb_tls"
    private const val NAME = "MyTV@tv"
    private const val KEY = "mytv"
    private const val STLS_VERSION = 0x01000000
    private const val PEER_INFO_SIZE = 8192
    private val CLIENT_NAME = "adb pair client\u0000".toByteArray()
    private val SERVER_NAME = "adb pair server\u0000".toByteArray()

    class WrongCode : IOException("pairing code not accepted")

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun paired(c: Context) = prefs(c).getBoolean("paired", false)
    /** Wireless debugging switched on in Developer options (Android reports it in a global setting). */
    fun wirelessOn(c: Context): Boolean? = runCatching { android.provider.Settings.Global.getInt(c.contentResolver, "adb_wifi_enabled") == 1 }.getOrNull()

    // ---------------------------------------------------------------- key and TLS

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Volatile private var material: Pair<PrivateKey, X509Certificate>? = null

    /**
     * MyTV's Wireless debugging key and its certificate. An ordinary RSA key (the TLS stack signs with it itself;
     * Keystore keys cannot do the raw signing TLS 1.3 asks for on every TV), kept in MyTV's own files, encrypted with an
     * AES key from the Android Keystore. Made once; the certificate is made again from it on each start.
     */
    @Synchronized
    private fun material(c: Context): Pair<PrivateKey, X509Certificate> {
        material?.let { return it }
        val file = java.io.File(c.applicationContext.filesDir, "adb_tls.key")
        val pair = runCatching {
            val blob = file.readBytes()
            val pkcs8 = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12))); doFinal(blob, 12, blob.size - 12)
            }
            val priv = java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(pkcs8))
            val crt = priv as java.security.interfaces.RSAPrivateCrtKey
            val pub = java.security.KeyFactory.getInstance("RSA").generatePublic(java.security.spec.RSAPublicKeySpec(crt.modulus, crt.publicExponent))
            priv to pub
        }.getOrNull() ?: run {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val enc = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
            file.writeBytes(enc.iv + enc.doFinal(kp.private.encoded))
            prefs(c).edit().remove("paired").remove("port").remove("host").apply()     // a new key is not paired
            kp.private to kp.public
        }
        return (pair.first to SelfSignedCert.create(pair.second, pair.first)).also { material = it }
    }

    private fun wrapKey(): javax.crypto.SecretKey {
        val ks = keyStore()
        (ks.getKey(WRAP_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        return javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Volatile private var app: Context? = null
    private fun certificate(): X509Certificate = material(app!!).second
    private fun privateKey(): PrivateKey = material(app!!).first

    /** Before any TLS: which application keeps the key. */
    fun use(c: Context) { app = c.applicationContext }

    private val keyManager = object : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(KEY)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?) = KEY
        override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?) = null
        override fun getCertificateChain(alias: String?) = arrayOf(certificate())
        override fun getPrivateKey(alias: String?) = privateKey()
    }

    /**
     * adbd shows a certificate it made itself, so there is nothing to check it against: pairing is protected by the
     * code (SPAKE2), and a connection by MyTV's key, which only this TV's adbd accepts. Connections go to this TV only.
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustAdbd = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val tls: SSLContext by lazy { SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(keyManager), arrayOf(trustAdbd), SecureRandom()) } }

    /** TLS over an already connected socket to this TV (adb's STLS upgrade, or pairing). */
    fun wrap(socket: Socket, port: Int): SSLSocket =
        (tls.socketFactory.createSocket(socket, "127.0.0.1", port, true) as SSLSocket).apply {
            enabledProtocols = arrayOf("TLSv1.3")
            startHandshake()
        }

    const val STLS = 0x534c5453
    fun stlsVersion() = STLS_VERSION

    // ---------------------------------------------------------------- pairing

    /** Pairs with the code shown on the TV; [port] is the pairing port. Throws [WrongCode] when the TV refuses it. */
    fun pair(c: Context, code: String, at: Found) {
        require(code.length == 6 && code.all { it.isDigit() })
        use(c)
        val raw = connect(at) ?: throw LocalAdb.NotAvailable("pairing port closed")
        val port = at.port
        raw.soTimeout = 15_000
        wrap(raw, port).use { ssl ->
            val ekm = exportKeyingMaterial(ssl) ?: throw IOException("no TLS key material")
            val spake = Spake2(true, CLIENT_NAME, SERVER_NAME)
            val out = ssl.outputStream
            val inp = DataInputStream(ssl.inputStream)
            val msg = spake.message(code.toByteArray() + ekm, ByteArray(64).also { SecureRandom().nextBytes(it) })
            out.write(header(0, msg.size) + msg); out.flush()
            val theirMsg = readPacket(inp, 0)
            val key = try { spake.key(theirMsg) } catch (e: IllegalArgumentException) { throw WrongCode() }
            val aes = hkdf(key, "adb pairing_auth aes-128-gcm key".toByteArray(), 16)
            val info = ByteArray(PEER_INFO_SIZE)
            info[0] = 0      // ADB_RSA_PUB_KEY
            val pub = LocalAdb.adbPublicKey(certificate().publicKey as RSAPublicKey, NAME)
            System.arraycopy(pub, 0, info, 1, minOf(pub.size, PEER_INFO_SIZE - 1))
            val sealed = gcm(Cipher.ENCRYPT_MODE, aes, info)
            out.write(header(1, sealed.size) + sealed); out.flush()
            // A wrong code: the TV cannot open our message and closes the connection.
            val theirs = try { readPacket(inp, 1) } catch (e: EOFException) { throw WrongCode() }
            try { gcm(Cipher.DECRYPT_MODE, aes, theirs) } catch (e: javax.crypto.AEADBadTagException) { throw WrongCode() }
        }
        prefs(c).edit().putBoolean("paired", true).remove("port").remove("host").apply()
        Log.i("MyTV", "wireless debugging: paired")
    }

    /**
     * 64 bytes of the TLS session's exported key (label "adb-label" + NUL), mixed into the code. Public API from Android
     * 12; on Android 11 the platform's own Conscrypt has the same call.
     */
    @SuppressLint("PrivateApi")
    private fun exportKeyingMaterial(ssl: SSLSocket): ByteArray? {
        val label = "adb-label\u0000"
        if (android.os.Build.VERSION.SDK_INT >= 31) return android.net.ssl.SSLSockets.exportKeyingMaterial(ssl, label, null, 64)
        return Class.forName("com.android.org.conscrypt.Conscrypt")
            .getMethod("exportKeyingMaterial", SSLSocket::class.java, String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType)
            .invoke(null, ssl, label, null, 64) as ByteArray?
    }

    private fun header(type: Int, size: Int): ByteArray = ByteBuffer.allocate(6).put(1).put(type.toByte()).putInt(size).array()

    private fun readPacket(inp: DataInputStream, type: Int): ByteArray {
        val h = ByteArray(6); inp.readFully(h)
        val b = ByteBuffer.wrap(h)
        val version = b.get().toInt(); val t = b.get().toInt(); val size = b.int
        if (version != 1 || t != type || size <= 0 || size > 2 * PEER_INFO_SIZE) throw IOException("bad pairing packet")
        return ByteArray(size).also { inp.readFully(it) }
    }

    /** AES-128-GCM with a zero counter as nonce (the first and only message each way). */
    private fun gcm(mode: Int, key: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ByteArray(12))); doFinal(data) }

    /** HKDF-SHA256 without salt (RFC 5869), first [length] bytes. */
    private fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(ByteArray(32), "HmacSHA256")); doFinal(ikm) }
        return Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(prk, "HmacSHA256")); update(info); update(1.toByte()); doFinal() }.copyOf(length)
    }

    // ---------------------------------------------------------------- the TLS port

    /** Where a service of this TV answers: its own address as announced, and the port. */
    data class Found(val host: String, val port: Int)

    /** A socket to [at]: this TV's loopback first (adbd listens on every address), then the announced address. */
    fun connect(at: Found, timeoutMs: Int = 3000): Socket? {
        for (h in listOf(InetAddress.getLoopbackAddress().hostAddress, at.host).distinct()) {
            val s = Socket()
            try { s.connect(InetSocketAddress(h, at.port), timeoutMs); s.soTimeout = 15_000; return s }
            catch (e: IOException) { runCatching { s.close() } }
        }
        return null
    }

    private fun open(at: Found) = connect(at, 300)?.let { runCatching { it.close() }; true } ?: false

    /**
     * The TV's TLS port, when MyTV is paired and Wireless debugging is on: the last one if it still answers, else found
     * again by mDNS (up to [waitMs]). Null otherwise. Blocking; never on the main thread.
     */
    fun connectPort(c: Context, waitMs: Long = 3000): Found? {
        use(c)
        if (!paired(c) || wirelessOn(c) == false) return null
        val p = prefs(c)
        val last = Found(p.getString("host", "") ?: "", p.getInt("port", 0))
        if (last.port > 0 && open(last)) return last
        val found = find(c, CONNECT, waitMs) ?: return null
        p.edit().putInt("port", found.port).putString("host", found.host).apply()
        return found
    }

    /** The remembered TLS port did not answer: look it up again next time. */
    fun dropPort(c: Context) = prefs(c).edit().remove("port").remove("host").apply()

    /** Forget the pairing (the TV no longer accepts MyTV's key). */
    fun forget(c: Context) = prefs(c).edit().remove("paired").remove("port").remove("host").apply()

    private fun ownAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { n -> n.inetAddresses.toList() }.mapNotNull { it.hostAddress?.substringBefore('%') }.toSet()
    }.getOrDefault(emptySet())

    /** Finds a service of [type] announced by this TV itself; its port, or null after [waitMs]. */
    fun find(c: Context, type: String, waitMs: Long): Found? {
        val nsd = c.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val mine = ownAddresses()
        val result = java.util.concurrent.atomic.AtomicReference<Found?>(null)
        val done = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) { resolve(nsd, s, 0) }
            fun resolve(nsd: NsdManager, s: NsdServiceInfo, tries: Int) {
                runCatching {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(r: NsdServiceInfo) {
                            @Suppress("DEPRECATION") val host = r.host?.hostAddress?.substringBefore('%')
                            if (host != null && (host in mine || r.host.isLoopbackAddress) && r.port > 0) { result.set(Found(host, r.port)); done.countDown() }
                        }
                        override fun onResolveFailed(r: NsdServiceInfo, e: Int) {
                            // Android 11 resolves one at a time (another one may be busy): try again shortly.
                            if (e == NsdManager.FAILURE_ALREADY_ACTIVE && tries < 8) main.postDelayed({ resolve(nsd, s, tries + 1) }, 300)
                        }
                    })
                }
            }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { done.countDown() }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { return null }
        try { done.await(waitMs, TimeUnit.MILLISECONDS) } finally { runCatching { nsd.stopServiceDiscovery(listener) } }
        return result.get()
    }
}
