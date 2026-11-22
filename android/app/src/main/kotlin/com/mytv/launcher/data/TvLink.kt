// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Other TVs: a TV with MyTV takes over all settings of another TV with MyTV in the house. All or nothing: it is the
 * same file as the settings backup ([Backup]), fetched from the other TV instead of a phone, and the same own photos
 * (sent from a phone, [PhotoInbox]): afterwards this TV has exactly the other TV's photos.
 *
 * Linking happens once: the other TV shows a code while its "Other TVs" screen is open, and this TV sends a proof
 * that it knows that code with a new key of its own (ECDH, P-256). Both TVs then keep a shared key, made from the
 * key exchange and the code. Later a TV asks a linked TV for its settings with that key, and the answer comes back
 * encrypted with it (AES-GCM): other devices on the network can neither read the settings nor ask for them.
 * Limit, accepted: a device that actively sits between the two TVs during the minute of linking could guess the
 * four-digit code from the first message. A code works for one link and closes after 5 wrong tries.
 *
 * Only TVs on the home network; one connection at a time, one JSON line each way. Nothing is listened for, and
 * nothing is announced on the network, on a TV that has no linked TVs and does not have the screen open.
 */
object TvLink {
    const val TYPE = "_mytv._tcp"
    private const val PREFS = "link"
    private const val MAX_WRONG = 5
    /** The longest answer: one own photo (at most [MAX_PHOTO]) or the settings file, as encrypted base64 in JSON. */
    private const val MAX_LINE = 12 * 1024 * 1024
    private const val MAX_PHOTO = 8 * 1024 * 1024
    private const val MAX_PHOTOS = 500
    private val ID = Regex("[A-Za-z0-9_-]{8,40}")
    private val PHOTO = Regex("[A-Za-z0-9._-]{1,80}\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)

    /** A TV this one is linked with; [key] is the shared key (never leaves this TV). */
    class Peer(val id: String, val name: String, val key: ByteArray)
    /** A TV with MyTV found on the network now. */
    data class Tv(val id: String, val name: String, val host: String, val port: Int)
    enum class Result { OK, WRONG_CODE, LOCKED, CLOSED, UNLINKED, FAILED }

    // ---- this TV ----

    fun myId(c: Context): String {
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString("id", null)?.takeIf { ID.matches(it) }?.let { return it }
        val id = LinkCrypto.b64(ByteArray(12).also { SecureRandom().nextBytes(it) }).replace('+', '-').replace('/', '_').trimEnd('=')
        sp.edit().putString("id", id).apply()
        return id
    }

    /** The name the TV has in Android (Settings → Device name), as the other TV shows it. */
    fun deviceName(c: Context): String =
        runCatching { android.provider.Settings.Global.getString(c.contentResolver, android.provider.Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.take(60) ?: android.os.Build.MODEL

    fun peers(c: Context): List<Peer> = runCatching {
        val a = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("peers", "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val key = runCatching { LinkCrypto.unb64(o.optString("key")) }.getOrNull()?.takeIf { it.size == 32 } ?: return@mapNotNull null
            Peer(o.optString("id").takeIf { ID.matches(it) } ?: return@mapNotNull null, o.optString("name"), key)
        }
    }.getOrDefault(emptyList())

    private fun savePeers(c: Context, list: List<Peer>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("key", LinkCrypto.b64(it.key))) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("peers", a.toString()).apply()
    }

    private fun savePeer(c: Context, p: Peer) = synchronized(this) { savePeers(c, peers(c).filter { it.id != p.id } + p) }

    /** Unlinks [id] here (the other TV notices at its next request and unlinks as well). */
    fun forget(c: Context, id: String) {
        synchronized(this) { savePeers(c, peers(c).filter { it.id != id }) }
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = sp.edit().remove("addr:$id").remove("seen:$id")
        if (sp.getString("follow", null) == id) e.remove("follow")
        e.apply()
        markFollower(c, id, false)
        start(c)      // no linked TVs left: stops listening
    }

    // ---- listening (on the TV whose settings are taken over, and while linking) ----

    @Volatile private var server: ServerSocket? = null
    private var boundTo: String? = null
    @Volatile private var code: String? = null
    private var wrong = 0
    @Volatile var locked = false; private set
    /** Called on the main thread when the linked TVs, the code or [locked] changed (the open screen). */
    @Volatile var onChange: (() -> Unit)? = null

    private fun changed() = android.os.Handler(android.os.Looper.getMainLooper()).post { onChange?.invoke() }

    fun code(): String? = code

    /** The "Other TVs" screen opened: this TV can be linked with the returned code while it stays open. */
    fun openPairing(c: Context): String = synchronized(this) {
        wrong = 0; locked = false
        newCode().also { code = it; start(c) }
    }

    fun closePairing(c: Context) { synchronized(this) { code = null; locked = false }; start(c) }

    private fun newCode() = (1000 + SecureRandom().nextInt(9000)).toString()

    /**
     * Listens on the home network (and announces this TV) while it has linked TVs or the screen is open; otherwise
     * stops. Called again when MyTV comes to the front: a new network address means a new listener.
     */
    fun start(c: Context) = synchronized(this) {
        val app = c.applicationContext
        val wanted = code != null || peers(app).isNotEmpty()
        val addr = if (wanted) PhotoInbox.address() else null
        if (addr != null && server?.isClosed == false && boundTo == addr) return@synchronized
        runCatching { server?.close() }
        server = null; boundTo = null
        if (addr == null) { Lan.unregister(app); return@synchronized }
        val s = runCatching { ServerSocket(0, 4, InetAddress.getByName(addr)) }.getOrNull() ?: return@synchronized
        server = s; boundTo = addr
        thread(name = "tv-link", isDaemon = true) {
            while (!s.isClosed) {
                val sock = runCatching { s.accept() }.getOrNull() ?: break
                runCatching { sock.use { handle(app, it) } }.onFailure { Log.w("MyTV", "link: $it") }
            }
        }
        Lan.register(app, TYPE, deviceName(app), s.localPort, mapOf("id" to myId(app)))
    }

    private fun handle(c: Context, sock: Socket) {
        // A slow or idle client may not block the listener.
        sock.soTimeout = 10_000
        val line = Net.readLineCapped(sock.getInputStream(), 16 * 1024) ?: return
        val req = runCatching { JSONObject(line) }.getOrNull() ?: return
        val answer = when (req.optString("op")) {
            "pair" -> answerPair(c, req)
            "get", "photos", "photo", "version", "follow" -> answerLinked(c, req)
            else -> error("refused")
        }
        sock.getOutputStream().apply { write((answer.toString() + "\n").toByteArray()); flush() }
    }

    private fun error(why: String) = JSONObject().put("error", why)

    private fun answerPair(c: Context, r: JSONObject): JSONObject = synchronized(this) {
        val now = code ?: return error(if (locked) "locked" else "closed")
        val id = r.optString("id"); val pub = r.optString("pub"); val me = myId(c)
        if (!ID.matches(id) || id == me || pub.length !in 1..400) return error("refused")
        if (!LinkCrypto.same(LinkCrypto.proof(now, "ask", id, pub), r.optString("proof"))) {
            if (++wrong >= MAX_WRONG) { code = null; locked = true; changed() }
            return error("code")
        }
        val kp = LinkCrypto.keyPair(); val myPub = LinkCrypto.pub(kp)
        val key = LinkCrypto.shared(kp, pub, now, id, me) ?: return error("refused")
        savePeer(c, Peer(id, r.optString("name").take(60).ifBlank { "TV" }, key))
        code = newCode()          // a code links one TV; someone who saw it cannot use it again
        changed()
        return JSONObject().put("id", me).put("name", deviceName(c)).put("pub", myPub)
            .put("proof", LinkCrypto.proof(now, "answer", me, myPub, pub))
    }

    /** The own photos: files in [BackgroundRepository.ownDir] with a photo's name. */
    private fun ownPhotos(c: Context): List<java.io.File> =
        BackgroundRepository.ownDir(c).listFiles { f -> f.isFile && PHOTO.matches(f.name) }?.sortedBy { it.name }.orEmpty()

    /**
     * A request of a linked TV: the settings ("get"), the list of own photos, one photo ("photo", arg = its name), the
     * fingerprint of both ("version", asked by a TV that follows this one), or "follow" (arg "1" or "0").
     */
    private fun answerLinked(c: Context, r: JSONObject): JSONObject {
        val op = r.optString("op"); val id = r.optString("id"); val nonce = r.optString("nonce"); val arg = r.optString("arg")
        val peer = peers(c).firstOrNull { it.id == id } ?: return error("unknown")
        if (nonce.length !in 16..64 || arg.length > 100 ||
            !LinkCrypto.same(LinkCrypto.requestMac(peer.key, op, id, nonce, arg), r.optString("mac"))) return error("unknown")
        val body = when (op) {
            "get" -> Backup.export(c).toByteArray()
            "version" -> { markFollower(c, id, true); version(c).toByteArray() }
            "follow" -> { markFollower(c, id, arg == "1"); "ok".toByteArray() }
            "photos" -> JSONArray().also { a ->
                ownPhotos(c).take(MAX_PHOTOS).forEach { a.put(JSONObject().put("name", it.name).put("size", it.length())) }
            }.toString().toByteArray()
            else -> ownPhotos(c).firstOrNull { it.name == arg }?.takeIf { it.length() <= MAX_PHOTO }?.readBytes() ?: return error("gone")
        }
        return JSONObject().put("data", LinkCrypto.seal(peer.key, body, "$op|$nonce|$arg"))
    }

    /** Settings that change by themselves while the TV is used: not a reason for a following TV to take over. */
    private val VOLATILE = setOf("lastLaunched", "profile")

    /**
     * A fingerprint of everything [take] brings (settings and own photos): it changes when one of them changes. Values
     * are written in a fixed order (sets sorted), so the same settings always give the same fingerprint.
     * Asked every few minutes by a following TV, so the settings part is kept: made again only when a MyTV setting
     * changed, or after an hour (permissions and the screensaver time live in Android, not in MyTV's settings). The
     * own photos are only a folder listing, looked at each time.
     */
    internal fun version(c: Context): String {
        val now = android.os.SystemClock.elapsedRealtime()
        val part = settingsPart?.takeIf { now - settingsPartAt < 3_600_000 } ?: settingsFingerprint(c).also {
            settingsPart = it; settingsPartAt = now; watchSettings(c)
        }
        val photos = ownPhotos(c).joinToString("\n") { "photo=${it.name}:${it.length()}" }
        return LinkCrypto.b64(MessageDigest.getInstance("SHA-256").digest("$part\n$photos".toByteArray()))
    }

    @Volatile private var settingsPart: String? = null
    @Volatile private var settingsPartAt = 0L
    /** Kept here: Android holds preference listeners only weakly. */
    private val settingsChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> settingsPart = null }
    private var watching = false

    private fun watchSettings(c: Context) = synchronized(this) {
        if (watching) return@synchronized
        watching = true
        listOf("mytv", "updates").forEach {
            c.applicationContext.getSharedPreferences(it, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(settingsChanged)
        }
    }

    private fun settingsFingerprint(c: Context): String {
        val o = JSONObject(Backup.export(c))
        val s = o.optJSONObject("settings") ?: JSONObject()
        val parts = s.keys().asSequence().filter { it.substringBefore('@') !in VOLATILE }.sorted().map { "$it=" + canonical(s.opt(it)) }.toList() +
            listOf("rights=" + canonical(o.optJSONObject("rights")), "screensaver=" + o.opt("screensaverMinutes"), "updates=" + o.opt("updatesAuto"))
        return parts.joinToString("\n")
    }

    internal fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { "$it:" + canonical(v.opt(it)) }
        is JSONArray -> (0 until v.length()).map { canonical(v.opt(it)) }.sorted().joinToString(",", "[", "]")
        else -> v.toString()
    }

    // ---- following: one TV keeps itself the same as another ----

    /** After this long without a question from a following TV, it no longer counts as following (it was switched off or unlinked). */
    private const val FOLLOWER_DAYS = 30L

    private fun markFollower(c: Context, id: String, on: Boolean) = synchronized(this) {
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = runCatching { JSONObject(sp.getString("followers", "{}")!!) }.getOrDefault(JSONObject())
        if (on) o.put(id, System.currentTimeMillis()) else o.remove(id)
        sp.edit().putString("followers", o.toString()).apply()
        changed()
    }

    /** The linked TVs that follow this one (asked recently). */
    fun followers(c: Context): List<Peer> {
        val o = runCatching { JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("followers", "{}")!!) }.getOrDefault(JSONObject())
        val since = System.currentTimeMillis() - FOLLOWER_DAYS * 24 * 3_600_000
        return peers(c).filter { o.optLong(it.id, 0) > since }
    }

    /** The linked TV this one follows, or null. */
    fun following(c: Context): Peer? {
        val id = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("follow", null) ?: return null
        return peers(c).firstOrNull { it.id == id }
    }

    /**
     * Starts following [tv] (taking over everything now, like [take]) or stops. A TV that follows this one cannot be
     * followed (they would keep putting each other back). Blocking (network).
     */
    fun setFollow(c: Context, tv: Tv, on: Boolean): Result {
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val peer = peers(c).firstOrNull { it.id == tv.id } ?: return Result.UNLINKED
        if (!on) {
            sp.edit().remove("follow").apply()
            // Best effort (where it was last found, when not on the network now): the other TV forgets it by itself after a while.
            runCatching { fetch(c, if (tv.host.isEmpty()) address(c, peer) ?: tv else tv, peer, "follow", "0") }
            return Result.OK
        }
        if (followers(c).any { it.id == tv.id }) return Result.FAILED
        val r = take(c, tv)
        if (r == Result.OK) {
            sp.edit().putString("follow", tv.id).apply()
            runCatching { fetch(c, tv, peer, "follow", "1") }
        }
        return r
    }

    @Volatile private var lastCheck = 0L

    /**
     * On a TV that follows another, when MyTV comes to the front: asks the other TV for its fingerprint and takes over
     * everything when it changed. Quiet: when the other TV is off or away nothing happens (its new address is looked
     * up for the next time). Blocking (network); true when settings were taken over (MyTV must restart).
     */
    fun followCheck(c: Context): Boolean {
        val peer = following(c) ?: return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCheck < 20_000) return false
        lastCheck = now
        val tv = address(c, peer) ?: run { lookUp(c); return false }
        val v = try { String(fetch(c, tv, peer, "version"), Charsets.UTF_8) } catch (e: Refused) { lookUp(c); return false }
        if (v == seenVersion(c, peer.id)) return false
        Log.i("MyTV", "link: ${peer.name} changed, taking over")
        return take(c, tv, quiet = true) == Result.OK
    }

    private fun seenVersion(c: Context, id: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("seen:$id", null)

    /** Where a linked TV was last found (it may have another address now: then discovery finds it again). */
    private fun address(c: Context, p: Peer): Tv? {
        val a = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("addr:${p.id}", null) ?: return null
        val port = a.substringAfterLast(':').toIntOrNull() ?: return null
        return Tv(p.id, p.name, a.substringBeforeLast(':'), port)
    }

    private fun remember(c: Context, tv: Tv) {
        if (peers(c).none { it.id == tv.id }) return
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("addr:${tv.id}", "${tv.host}:${tv.port}").apply()
    }

    // ---- asking (on the TV that takes over) ----

    /** Token of the delayed stop of a quiet search ([lookUp]). */
    private val quiet = Any()

    /**
     * The TV this one follows did not answer: look for its (new) address for the next check, for 15 seconds only (not
     * the whole time MyTV is on screen). [discover] remembers the address.
     */
    private fun lookUp(c: Context) {
        val app = c.applicationContext
        discover(app) {}
        android.os.Handler(android.os.Looper.getMainLooper())
            .postAtTime({ Lan.stop(app, TYPE) }, quiet, android.os.SystemClock.uptimeMillis() + 15_000)
    }

    fun discover(c: Context, onFound: (Tv) -> Unit) {
        val me = myId(c)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.removeCallbacksAndMessages(quiet)     // the Other TVs screen's own search is not cut off by an earlier quiet one
        Lan.discover(c, TYPE) { f ->
            val id = f.attrs["id"].orEmpty()
            if (ID.matches(id) && id != me && f.port in 1..65535) {
                val tv = Tv(id, f.name.take(60), f.host, f.port)
                remember(c, tv)
                main.post { onFound(tv) }
            }
        }
    }

    fun stopDiscovery(c: Context) = Lan.stop(c, TYPE)

    private fun ask(tv: Tv, req: JSONObject): JSONObject? = runCatching {
        // Only a numeric address on the home network (never a name that could point elsewhere).
        if (Net.literalIp(tv.host)?.let(Net::isLan) != true) return null
        Socket().use { s ->
            s.connect(InetSocketAddress(tv.host, tv.port), 4000)
            s.soTimeout = 15_000
            s.getOutputStream().apply { write((req.toString() + "\n").toByteArray()); flush() }
            JSONObject(Net.readLineCapped(s.getInputStream(), MAX_LINE) ?: return null)
        }
    }.onFailure { Log.w("MyTV", "link ask: $it") }.getOrNull()

    private fun result(error: String) = when (error) {
        "code" -> Result.WRONG_CODE; "locked" -> Result.LOCKED; "closed" -> Result.CLOSED; "unknown" -> Result.UNLINKED
        else -> Result.FAILED
    }

    /** Links with [tv] using the code it shows. Blocking (network). */
    fun pair(c: Context, tv: Tv, typed: String): Result {
        val me = myId(c); val kp = LinkCrypto.keyPair(); val pub = LinkCrypto.pub(kp)
        val a = ask(tv, JSONObject().put("op", "pair").put("id", me).put("name", deviceName(c)).put("pub", pub)
            .put("proof", LinkCrypto.proof(typed, "ask", me, pub))) ?: return Result.FAILED
        a.optString("error").takeIf { it.isNotEmpty() }?.let { return result(it) }
        val id = a.optString("id"); val theirPub = a.optString("pub")
        if (id != tv.id || !LinkCrypto.same(LinkCrypto.proof(typed, "answer", id, theirPub, pub), a.optString("proof"))) return Result.FAILED
        val key = LinkCrypto.shared(kp, theirPub, typed, me, id) ?: return Result.FAILED
        savePeer(c, Peer(id, a.optString("name").take(60).ifBlank { tv.name }, key))
        remember(c, tv)
        start(c)
        return Result.OK
    }

    private class Refused(val result: Result) : Exception()

    /** One request to a linked TV: its answer, opened; [Refused] when it cannot be had. */
    private fun fetch(c: Context, tv: Tv, peer: Peer, op: String, arg: String = ""): ByteArray {
        val me = myId(c)
        val nonce = LinkCrypto.b64(ByteArray(18).also { SecureRandom().nextBytes(it) })
        val a = ask(tv, JSONObject().put("op", op).put("id", me).put("nonce", nonce).put("arg", arg)
            .put("mac", LinkCrypto.requestMac(peer.key, op, me, nonce, arg))) ?: throw Refused(Result.FAILED)
        a.optString("error").takeIf { it.isNotEmpty() }?.let {
            // The other TV no longer knows this one: unlinked there, so here too.
            if (it == "unknown") forget(c, tv.id)
            throw Refused(result(it))
        }
        return LinkCrypto.open(peer.key, a.optString("data"), "$op|$nonce|$arg") ?: throw Refused(Result.FAILED)
    }

    /**
     * Takes over all settings and own photos of the linked [tv]; MyTV must restart after OK. All or nothing: the
     * settings are put back ([Backup.restore]) only when every photo arrived. [quiet]: taken over because this TV
     * follows [tv]; this TV keeps its current profile, and the permissions are not asked again. Blocking (network).
     */
    fun take(c: Context, tv: Tv, quiet: Boolean = false): Result {
        val peer = peers(c).firstOrNull { it.id == tv.id } ?: return Result.UNLINKED
        return try {
            val version = String(fetch(c, tv, peer, "version"), Charsets.UTF_8)
            val settings = String(fetch(c, tv, peer, "get"), Charsets.UTF_8)
            val list = JSONArray(String(fetch(c, tv, peer, "photos"), Charsets.UTF_8))
            val theirs = (0 until minOf(list.length(), MAX_PHOTOS)).mapNotNull { i ->
                list.optJSONObject(i)?.let { it.optString("name") to it.optLong("size") }?.takeIf { PHOTO.matches(it.first) }
            }.toMap()
            val dir = BackgroundRepository.ownDir(c)
            for ((name, size) in theirs) {
                val f = java.io.File(dir, name)
                if (f.isFile && f.length() == size) continue
                val bytes = fetch(c, tv, peer, "photo", name)
                if (bytes.size > MAX_PHOTO || !isImage(bytes)) return Result.FAILED
                // Under a temporary name first: a half-written file never shows up as a background.
                val part = java.io.File(dir, "$name.part")
                part.writeBytes(bytes)
                if (!part.renameTo(f)) { part.delete(); return Result.FAILED }
            }
            ownPhotos(c).filter { it.name !in theirs }.forEach { it.delete() }
            val mytv = c.getSharedPreferences("mytv", Context.MODE_PRIVATE)
            val profile = mytv.getString("profile", null)
            if (!Backup.restore(c, settings)) return Result.FAILED
            if (quiet) {
                val e = mytv.edit().remove("restoreAsk")
                // The profile someone is using on this TV stays, when the other TV has it too.
                if (profile != null && mytv.getString("profiles", "").orEmpty().contains("\"$profile\"")) e.putString("profile", profile)
                e.commit()
            }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("seen:${tv.id}", version).apply()
            remember(c, tv)
            Result.OK
        } catch (e: Refused) {
            e.result
        } catch (e: org.json.JSONException) {
            Log.w("MyTV", "link take: $e"); Result.FAILED
        } catch (e: java.io.IOException) {
            Log.w("MyTV", "link take: $e"); Result.FAILED
        }
    }

    private fun isImage(b: ByteArray): Boolean {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
        return o.outWidth > 0 && o.outHeight > 0
    }
}

/** The cryptography of [TvLink], free of Android (unit-tested). */
internal object LinkCrypto {
    private const val CONTEXT = "mytv-link1"

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    fun pub(kp: KeyPair): String = b64(kp.public.encoded)

    fun hmac(key: ByteArray, msg: String): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(msg.toByteArray()) }

    /** Proof that the sender knows [code], bound to what it sends. */
    fun proof(code: String, vararg parts: String): String = b64(hmac(code.toByteArray(), CONTEXT + "|" + parts.joinToString("|")))

    /** Compares in constant time. */
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** The shared key of two TVs: from the key exchange and the code; the same on both sides (ids sorted). */
    fun shared(mine: KeyPair, theirPub: String, code: String, idA: String, idB: String): ByteArray? = runCatching {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(theirPub)))
        val secret = KeyAgreement.getInstance("ECDH").run { init(mine.private); doPhase(pub, true); generateSecret() }
        val (x, y) = listOf(idA, idB).sorted()
        hmac(secret, "$CONTEXT|key|$code|$x|$y")
    }.getOrNull()

    fun requestMac(key: ByteArray, op: String, id: String, nonce: String, arg: String = ""): String =
        b64(hmac(hmac(key, "$CONTEXT|mac"), "$op|$id|$nonce|$arg"))

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray, aad: String) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(hmac(key, "$CONTEXT|enc"), "AES"), GCMParameterSpec(128, iv))
        updateAAD(aad.toByteArray())
    }

    /** Encrypts and authenticates [plain]; [aad] binds it to the request. */
    fun seal(key: ByteArray, plain: ByteArray, aad: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        return b64(iv + cipher(Cipher.ENCRYPT_MODE, key, iv, aad).doFinal(plain))
    }

    /** null when [sealed] was not made with [key] for [aad], or was changed. */
    fun open(key: ByteArray, sealed: String, aad: String): ByteArray? = runCatching {
        val b = unb64(sealed)
        require(b.size > 12 + 16)
        cipher(Cipher.DECRYPT_MODE, key, b.copyOfRange(0, 12), aad).doFinal(b, 12, b.size - 12)
    }.getOrNull()
}
