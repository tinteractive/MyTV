// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Continue watching" from the viewer's own Plex Media Server. Plex's app publishes nothing to Android, so MyTV asks
 * the server itself. Linking uses Plex's own TV sign-in: MyTV shows a 4-character code, the viewer enters it at
 * plex.tv/link on a phone or computer, and plex.tv hands MyTV a token. plex.tv is contacted only to link and to find
 * the server; the list itself comes from the server in the house, over its own secure address (https …plex.direct).
 * Nothing about what is watched leaves the home network. Unlinking forgets the token.
 */
object Plex {
    const val PACKAGE = "com.plexapp.android"
    private const val PLEX_TV = "https://plex.tv/api/v2"

    private fun prefs(c: Context) = c.getSharedPreferences("plex", Context.MODE_PRIVATE)

    /** This TV's own name towards Plex (a random id, made once). */
    private fun clientId(c: Context): String = prefs(c).getString("client", null)
        ?: java.util.UUID.randomUUID().toString().also { prefs(c).edit().putString("client", it).apply() }

    private fun headers(c: Context, token: String? = null) = buildMap {
        put("Accept", "application/json"); put("X-Plex-Product", "MyTV Launcher"); put("X-Plex-Client-Identifier", clientId(c))
        put("X-Plex-Device-Name", "MyTV"); token?.let { put("X-Plex-Token", it) }
    }

    fun installed(c: Context) = runCatching { c.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)
    fun linked(c: Context) = prefs(c).getString("server", null) != null
    fun serverName(c: Context): String? = prefs(c).getString("name", null)

    fun unlink(c: Context) {
        val keep = clientId(c)
        prefs(c).edit().clear().putString("client", keep).apply()
    }

    /** A new sign-in code: (pin id, the 4 characters to show). Blocking. */
    fun newCode(c: Context): Pair<Long, String>? = runCatching {
        val o = JSONObject(String(Net.post("$PLEX_TV/pins?strong=false", ByteArray(0), headers(c))))
        o.getLong("id") to o.getString("code")
    }.onFailure { Log.w("MyTV", "plex: code: $it") }.getOrNull()

    /** Whether the code was entered: then MyTV keeps the token and looks for the server. Blocking. */
    fun check(c: Context, pin: Long): Result = runCatching {
        val o = JSONObject(String(Net.get("$PLEX_TV/pins/$pin", headers = headers(c))))
        val token = o.optString("authToken").takeIf { it.isNotEmpty() && it != "null" } ?: return Result.WAITING
        prefs(c).edit().putString("account", token).apply()     // kept, so finding the server can be tried again
        findServer(c, token)
    }.onFailure { Log.w("MyTV", "plex: check: $it") }.getOrDefault(Result.WAITING)

    enum class Result { WAITING, LINKED, NO_SERVER }

    /**
     * The viewer's own server, at a secure address that answers from this TV. Tried in order: the addresses plex.tv
     * lists for it on the home network, any server in this part of the network that answers Plex's own search (GDM),
     * then the addresses plex.tv lists elsewhere (a "custom server access URL", or the server's outside address).
     * Every address is used through Plex's secure name for it (https://a-b-c-d.<server>.plex.direct), never plain
     * http, and never Plex's relay (that would carry the list through Plex's servers).
     */
    private fun findServer(c: Context, token: String): Result {
        val list = JSONArray(String(Net.get("$PLEX_TV/resources?includeHttps=1&includeRelay=0", headers = headers(c, token))))
        Log.i("MyTV", "plex: ${list.length()} resources")
        val found = gdm().ifEmpty { scan() }
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            if ("server" !in r.optString("provides")) continue
            val conns = r.optJSONArray("connections")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().filter { !it.optBoolean("relay") }
            // The server's own part of every plex.direct name (its certificate is for *.<hash>.plex.direct).
            val hash = conns.firstNotNullOfOrNull { Regex("""\.([0-9a-f]{32})\.plex\.direct""").find(it.optString("uri"))?.groupValues?.get(1) }
            val port = conns.firstNotNullOfOrNull { it.optInt("port").takeIf { p -> p > 0 } } ?: 32400
            fun secure(ip: String, p: Int = port) = hash?.let { "https://${ip.replace('.', '-')}.$it.plex.direct:$p" }
            val (local, other) = conns.partition { it.optBoolean("local") }
            val candidates = (local.mapNotNull { k -> k.optString("uri").takeIf { "plex.direct" in it } ?: secure(k.optString("address"), k.optInt("port", port)) } +
                found.filter { it.second.isEmpty() || it.second == r.optString("clientIdentifier") }.mapNotNull { secure(it.first) } +
                other.mapNotNull { k -> k.optString("uri").takeIf { "plex.direct" in it } ?: secure(k.optString("address"), k.optInt("port", port)) })
                .filter { it.startsWith("https://") }.distinct()
            Log.i("MyTV", "plex: ${candidates.size} addresses to try (${local.size} home, ${found.size} found, ${other.size} other)")
            val access = r.optString("accessToken").ifEmpty { token }
            for (uri in candidates) {
                val ok = runCatching { Net.get("$uri/identity", 3000, 4000, headers(c, access)); true }
                    .onFailure { Log.w("MyTV", "plex: an address did not answer: ${it.javaClass.simpleName}") }.getOrDefault(false)
                if (!ok) continue
                prefs(c).edit().putString("server", uri).putString("token", access).putString("name", r.optString("name"))
                    .putString("machine", r.optString("clientIdentifier")).apply()
                Log.i("MyTV", "plex: linked to the server")
                return Result.LINKED
            }
        }
        return Result.NO_SERVER
    }

    /**
     * When nothing answers Plex's search: which devices in this TV's part of the network (its /24) have Plex's port open,
     * as ("address", "") pairs (the id is checked by the secure name: its certificate only fits the viewer's server).
     * Only while linking, a few seconds, 32 at a time.
     */
    private fun scan(): List<Pair<String, String>> = runCatching {
        val me = java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress } ?: return emptyList()
        val prefix = me.hostAddress!!.substringBeforeLast('.')
        val pool = java.util.concurrent.Executors.newFixedThreadPool(32)
        try {
            (1..254).map { n -> "$prefix.$n" }.map { ip ->
                pool.submit<String?> {
                    runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, 32400), 300) }; ip }.getOrNull()
                }
            }.mapNotNull { it.get() }.also { Log.i("MyTV", "plex: ${it.size} devices with Plex's port") }.map { it to "" }
        } finally { pool.shutdown() }
    }.onFailure { Log.w("MyTV", "plex: scan: $it") }.getOrDefault(emptyList())

    /**
     * Plex's own search on the home network (GDM): servers in this part of the network answer with their id. Only finds
     * servers that hear the search (not every server in Docker does). (address, server id) pairs; blocking, ~2 s.
     */
    private fun gdm(): List<Pair<String, String>> = runCatching {
        java.net.DatagramSocket().use { s ->
            s.broadcast = true; s.soTimeout = 400
            val msg = "M-SEARCH * HTTP/1.1\r\n\r\n".toByteArray()
            listOf("255.255.255.255", "239.0.0.250").forEach { runCatching { s.send(java.net.DatagramPacket(msg, msg.size, java.net.InetAddress.getByName(it), 32414)) } }
            val out = mutableListOf<Pair<String, String>>()
            val end = System.currentTimeMillis() + 2000
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < end) {
                val pk = java.net.DatagramPacket(buf, buf.size)
                if (runCatching { s.receive(pk) }.isFailure) continue
                val id = String(pk.data, 0, pk.length).lineSequence().firstOrNull { it.startsWith("Resource-Identifier:") }?.substringAfter(':')?.trim()
                if (id != null && pk.address is java.net.Inet4Address) out += pk.address.hostAddress!! to id
            }
            out.distinct()
        }
    }.onFailure { Log.w("MyTV", "plex: search: $it") }.getOrDefault(emptyList())

    /** Linked with plex.tv, but the server was not found then: look again (the sheet does, when opened). */
    fun retryServer(c: Context): Result = runCatching {
        val token = prefs(c).getString("account", null) ?: return Result.WAITING
        findServer(c, token)
    }.onFailure { Log.w("MyTV", "plex: server: $it") }.getOrDefault(Result.NO_SERVER)

    // ---------------------------------------------------------------- Plex Home users per MyTV profile

    /** A user of the viewer's Plex Home (their own libraries, watch progress and restrictions; a PIN when protected). */
    data class HomeUser(val uuid: String, val name: String, val protected: Boolean)

    private fun mytvProfile(c: Context) = c.getSharedPreferences("mytv", Context.MODE_PRIVATE).getString("profile", "") ?: ""

    /** The Plex Home's users (empty without Plex Home). Blocking. */
    fun homeUsers(c: Context): List<HomeUser> = runCatching {
        val account = prefs(c).getString("account", null) ?: return emptyList()
        val users = JSONObject(String(Net.get("$PLEX_TV/home/users", headers = headers(c, account)))).optJSONArray("users") ?: return emptyList()
        (0 until users.length()).map { users.getJSONObject(it) }.map { HomeUser(it.optString("uuid"), it.optString("title"), it.optBoolean("protected")) }
            .filter { it.uuid.isNotEmpty() }
    }.onFailure { Log.w("MyTV", "plex: users: $it") }.getOrDefault(emptyList())

    /**
     * Continue watching of MyTV profile [profile] comes from Plex user [u] from now on: plex.tv gives that user's own
     * token (with the PIN when the user has one), and with it the server's token for that user. False: wrong PIN or
     * Plex not reachable. Blocking.
     */
    fun useUser(c: Context, profile: String, u: HomeUser, pin: String?): Boolean = runCatching {
        val account = prefs(c).getString("account", null) ?: return false
        val q = pin?.let { "?pin=$it" } ?: ""
        val user = JSONObject(String(Net.post("$PLEX_TV/home/users/${u.uuid}/switch$q", ByteArray(0), headers(c, account))))
        val userToken = user.optString("authToken").ifEmpty { return false }
        val machine = prefs(c).getString("machine", null) ?: return false
        val list = JSONArray(String(Net.get("$PLEX_TV/resources?includeHttps=1&includeRelay=0", headers = headers(c, userToken))))
        val server = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optString("clientIdentifier") == machine } ?: return false
        val access = server.optString("accessToken").ifEmpty { userToken }
        prefs(c).edit().putString("user_$profile", JSONObject().put("name", u.name).put("token", access).toString()).apply()
        true
    }.onFailure { Log.w("MyTV", "plex: user: $it") }.getOrDefault(false)

    /** Back to the linked account for MyTV profile [profile]. */
    fun useAccount(c: Context, profile: String) = prefs(c).edit().remove("user_$profile").apply()

    /** The Plex user chosen for MyTV profile [profile], or null for the linked account. */
    fun userName(c: Context, profile: String): String? =
        prefs(c).getString("user_$profile", null)?.let { runCatching { JSONObject(it).optString("name") }.getOrNull() }

    /** The server's Continue watching (films and episodes), as cards. Blocking; empty when not linked or away. */
    fun continueWatching(c: Context): List<WatchItem> {
        val p = prefs(c)
        val base = p.getString("server", null) ?: return emptyList()
        // The Plex user chosen for the MyTV profile that is watching, else the linked account.
        val token = p.getString("user_${mytvProfile(c)}", null)?.let { runCatching { JSONObject(it).getString("token") }.getOrNull() }
            ?: p.getString("token", null) ?: return emptyList()
        val machine = p.getString("machine", "") ?: ""
        return runCatching {
            val o = JSONObject(String(Net.get("$base/hubs/continueWatching?count=12", 3000, 6000, headers(c, token))))
            val mc = o.optJSONObject("MediaContainer") ?: return emptyList()
            val rows = buildList {
                fun addAll(m: JSONArray?) { if (m != null) for (j in 0 until m.length()) add(m.getJSONObject(j)) }
                // The hub nests its items under Hub[]; some servers answer with them flat.
                mc.optJSONArray("Hub")?.let { hubs -> for (i in 0 until hubs.length()) addAll(hubs.optJSONObject(i)?.optJSONArray("Metadata")) }
                addAll(mc.optJSONArray("Metadata"))
            }
            rows.mapNotNull { m -> item(m, base, token, machine) }.distinctBy { it.title + it.subtitle }
        }.onFailure { Log.w("MyTV", "plex: continue watching: $it") }.getOrDefault(emptyList())
    }

    private fun item(m: JSONObject, base: String, token: String, machine: String): WatchItem? {
        val type = m.optString("type")
        if (type != "episode" && type != "movie") return null
        val episode = type == "episode"
        val key = m.optString("ratingKey").ifEmpty { return null }
        val title = (if (episode) m.optString("grandparentTitle") else m.optString("title")).ifBlank { m.optString("title") }.ifBlank { return null }
        val sub = if (episode) listOfNotNull(
            m.optInt("parentIndex").takeIf { it > 0 }?.let { s -> m.optInt("index").takeIf { it > 0 }?.let { e -> "S$s · E$e" } },
            m.optString("title").ifBlank { null },
        ).joinToString(" · ").ifBlank { null } else null
        val offset = m.optLong("viewOffset"); val duration = m.optLong("duration")
        // The wide picture (backdrop) first: the line's picture is 16:9. Scaled down by the server itself.
        val pic = listOf("grandparentArt", "art", "grandparentThumb", "thumb").firstNotNullOfOrNull { k -> m.optString(k).takeIf { it.startsWith("/") } }
        val image = pic?.let {
            "$base/photo/:/transcode?width=400&height=225&minSize=1&upscale=1&url=" + java.net.URLEncoder.encode(it, "UTF-8") + "&X-Plex-Token=$token"
        }
        return WatchItem(PACKAGE, title, sub, image, link(key, machine),
            if (offset > 0 && duration > 0) (offset.toFloat() / duration).coerceIn(0f, 1f) else null,
            m.optLong("lastViewedAt") * 1000)
    }

    /** Plex's own link to this film or episode, for its Android TV app; WatchNext.open falls back to the app. */
    private fun link(ratingKey: String, machine: String): String =
        android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("plex://server://$machine/com.plexapp.plugins.library/library/metadata/$ratingKey"))
            .setPackage(PACKAGE).toUri(android.content.Intent.URI_INTENT_SCHEME)
}
