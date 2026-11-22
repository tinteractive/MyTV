// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mytv.launcher.BuildConfig
import com.mytv.launcher.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** What update.json announces: the newest version, where its APK is, its size and SHA-256, and the notes. */
internal data class Release(val version: String, val code: Long, val apkUrl: String, val size: Long, val sha256: String?, val notes: String)

/**
 * MyTV's self-update from the public GitHub releases of [BuildConfig.UPDATE_REPO].
 *
 * A check reads only update.json (a few KB, attached to the newest release): version, APK file name, size, SHA-256
 * and the release notes of every version. The APK itself is downloaded only when the viewer chooses Install, and is
 * deleted again afterwards. Off unless asked: "Check now", or the optional weekly check. An update is installed only after the
 * viewer chose Install, and only after these checks on the downloaded file:
 *  1. every request (including each redirect) goes over https to GitHub's own hosts;
 *  2. size and SHA-256 equal what the release metadata says;
 *  3. it is this package, with a higher versionCode, signed with the same certificate.
 *
 * How it is installed, the first way that works ("one button"):
 *  - MyTV already approved on the TV's own adb (network debugging on): copied to the TV and installed there as adb's
 *    shell user ([UpdateRules]); MyTV opens again by itself. The TV is never asked for approval for this.
 *  - Android 12 and later: Android's installer without its question, once MyTV installed itself before.
 *  - Otherwise Android's installer asks the viewer to confirm.
 */
object Updates {
    /** No MyTV release comes near this; a larger "update" is refused before it can fill the storage. */
    private const val MAX_APK_BYTES = 64L * 1024 * 1024

    private const val APK_NAME = "update.apk"
    private const val PREFS = "updates"
    private const val WEEK = 7L * 24 * 3600 * 1000
    private const val RETRY_AFTER = 3600L * 1000
    private const val ACTION_RESULT = "com.mytv.launcher.INSTALL_RESULT"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var awaitingConfirm = false
    /** This try installs without Android's question (through adb, or Android 12+ without asking). */
    private var quiet = false

    var ui by mutableStateOf(UpdateUi())
        private set

    fun current(): String = BuildConfig.VERSION_NAME
    private fun repo(): String? = BuildConfig.UPDATE_REPO.takeIf { Regex("[\\w.-]+/[\\w.-]+").matches(it) }
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- state

    private fun publish(c: Context, phase: UpdateUi.Phase = ui.phase, percent: Int = ui.percent, problem: UpdateProblem = ui.problem) {
        val p = prefs(c)
        val latest = p.getString("version", "") ?: ""
        val code = p.getLong("code", 0L)
        // Newer than this build, not skipped by the viewer, and not refused before (other signing key).
        val open = code > BuildConfig.VERSION_CODE && code != p.getLong("skipped", -1L) && code != p.getLong("rejectedCode", -1L)
        ui = UpdateUi(
            supported = repo() != null,
            latest = latest,
            available = open && !p.getString("url", "").isNullOrEmpty(),
            notes = p.getString("notes", "[]") ?: "[]",
            checkedAt = p.getLong("checkedAt", 0L),
            auto = p.getBoolean("auto", false),
            phase = phase, percent = percent, problem = problem,
            quiet = quiet,
            justUpdated = p.getString("pending", "").takeIf { it == BuildConfig.VERSION_NAME && !p.getBoolean("announced", false) } ?: "",
        )
    }

    fun init(c: Context) {
        // A finished or abandoned download is never kept on the TV.
        File(c.cacheDir, "updates").listFiles()?.forEach { it.delete() }
        val p = prefs(c)
        // A try that did not end with this version running: forget it.
        if (p.contains("pending") && p.getString("pending", "") != BuildConfig.VERSION_NAME) p.edit().remove("pending").remove("reopenAt").apply()
        publish(c)
    }

    /** "Updated to …" was on screen: say it only once. */
    fun announced(c: Context) {
        prefs(c).edit().putBoolean("announced", true).apply()
        publish(c)
    }

    /**
     * MY_PACKAGE_REPLACED: an update through Android's installer that the viewer started while MyTV was on screen
     * opens MyTV again (the adb way does that itself). Only within a few minutes of the start.
     */
    fun afterUpdate(c: Context) {
        val p = prefs(c)
        val at = p.getLong("reopenAt", 0L)
        p.edit().remove("reopenAt").apply()
        if (at == 0L || System.currentTimeMillis() - at > 10 * 60_000L || p.getString("pending", "") != BuildConfig.VERSION_NAME) return
        runCatching {
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w("MyTV", "reopen after update: $it") }
    }

    /** "Skip": this version is not offered again; the next one will be. */
    fun skip(c: Context) {
        prefs(c).edit().putLong("skipped", prefs(c).getLong("code", 0L)).apply()
        publish(c)
    }

    /** Whether the update screen should open by itself (once per version, after an automatic check). */
    fun shouldPrompt(c: Context): Boolean {
        val p = prefs(c); val code = p.getLong("code", 0L)
        if (!ui.available || p.getLong("prompted", -1L) == code) return false
        p.edit().putLong("prompted", code).apply()
        return true
    }

    fun setAuto(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("auto", on).apply()
        publish(c)
        if (on) weeklyCheck(c)
    }

    /** Called when MyTV comes to the front. */
    fun onResume(c: Context) {
        val app = c.applicationContext
        if (ui.problem == UpdateProblem.NEEDS_PERMISSION && app.packageManager.canRequestPackageInstalls()) publish(app, problem = UpdateProblem.NONE)
        // Some TVs close the installer's question without reporting back: treat that as cancelled.
        if (ui.phase == UpdateUi.Phase.INSTALLING && awaitingConfirm) {
            scope.launch {
                kotlinx.coroutines.delay(4000)
                if (ui.phase == UpdateUi.Phase.INSTALLING && awaitingConfirm) done(app, UpdateProblem.CANCELLED)
            }
        }
        weeklyCheck(app)
    }

    private fun weeklyCheck(c: Context) {
        val p = prefs(c); val now = System.currentTimeMillis()
        if (!p.getBoolean("auto", false) || ui.phase != UpdateUi.Phase.IDLE) return
        if (now - p.getLong("checkedAt", 0L) < WEEK || now - p.getLong("triedAt", 0L) < RETRY_AFTER) return
        check(c, userAsked = false)
    }

    // ---------------------------------------------------------------- check

    fun check(c: Context, userAsked: Boolean) {
        val app = c.applicationContext
        val repo = repo() ?: return
        if (ui.phase != UpdateUi.Phase.IDLE) return
        publish(app, phase = UpdateUi.Phase.CHECKING, problem = if (userAsked) UpdateProblem.NONE else ui.problem)
        scope.launch {
            prefs(app).edit().putLong("triedAt", System.currentTimeMillis()).apply()
            val release = runCatching { withContext(Dispatchers.IO) { latestRelease(repo) } }
                .onFailure { Log.w("MyTV", "update check failed: $it") }.getOrNull()
            if (release != null) {
                prefs(app).edit().putLong("checkedAt", System.currentTimeMillis())
                    .putString("version", release.version).putLong("code", release.code).putString("url", release.apkUrl)
                    .putLong("size", release.size).putString("sha256", release.sha256).putString("notes", release.notes).apply()
            }
            publish(app, phase = UpdateUi.Phase.IDLE, problem = if (release == null && userAsked) UpdateProblem.OFFLINE else UpdateProblem.NONE)
        }
    }

    private fun latestRelease(repo: String): Release {
        val conn = githubGet("https://github.com/$repo/releases/latest/download/update.json")
        val json = try { JSONObject(String(conn.inputStream.use { Net.readCapped(it, 256 * 1024) })) } finally { conn.disconnect() }
        return parseManifest(repo, json, BuildConfig.VERSION_CODE.toLong())
    }

    /**
     * Pure, unit-tested: update.json -> what to offer. The APK must be a plain file name in this repository's own
     * release of that version; notes are kept only for versions newer than [installed].
     */
    internal fun parseManifest(repo: String, json: JSONObject, installed: Long): Release {
        val version = json.getString("version").trim()
        require(Regex("\\d{4}\\.\\d{1,2}\\.\\d{1,4}|\\d+\\.\\d+\\.\\d+").matches(version)) { "bad version" }
        val apk = json.getString("apk")
        require(Regex("[A-Za-z0-9._-]+\\.apk").matches(apk)) { "bad file name" }
        // Size and SHA-256 are required: without them a broken or swapped file would only be caught by the installer.
        val sha = json.getString("sha256").lowercase()
        require(sha.matches(Regex("[0-9a-f]{64}"))) { "bad sha256" }
        val size = json.getLong("size")
        require(size in 1..MAX_APK_BYTES) { "bad size" }
        val all = json.optJSONArray("releases") ?: org.json.JSONArray()
        val newer = org.json.JSONArray()
        for (i in 0 until all.length()) all.optJSONObject(i)?.takeIf { it.optLong("code") > installed }?.let { newer.put(it) }
        return Release(version, json.getLong("code"), "https://github.com/$repo/releases/download/v$version/$apk",
            size, sha, newer.toString())
    }

    // ---------------------------------------------------------------- install

    fun install(c: Context) {
        val app = c.applicationContext
        val p = prefs(app)
        val url = p.getString("url", "") ?: ""
        if (!ui.available || url.isEmpty() || ui.phase != UpdateUi.Phase.IDLE) return
        val size = p.getLong("size", 0L)
        publish(app, phase = UpdateUi.Phase.DOWNLOADING, percent = 0, problem = UpdateProblem.NONE)
        scope.launch {
            // The TV's own adb, only when MyTV is approved there already. The first command clears what an earlier try
            // left, and wakes the link.
            val viaAdb = withContext(Dispatchers.IO) {
                LocalAdb.reachable() && runCatching { LocalAdb.shell(UpdateRules.CLEAN, prompt = false) }
                    .onFailure { Log.i("MyTV", "update without adb: $it") }.isSuccess
            }
            if (!viaAdb && !app.packageManager.canRequestPackageInstalls()) {
                publish(app, phase = UpdateUi.Phase.IDLE, problem = UpdateProblem.NEEDS_PERMISSION)
                runCatching {
                    app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                return@launch
            }
            if (size > 0 && minOf(app.cacheDir.usableSpace, app.filesDir.usableSpace) < UpdateRules.spaceNeeded(size)) {
                publish(app, phase = UpdateUi.Phase.IDLE, problem = UpdateProblem.NO_SPACE); return@launch
            }
            val file = File(File(app.cacheDir, "updates").apply { mkdirs() }, APK_NAME)
            val problem = withContext(Dispatchers.IO) {
                try {
                    download(url, file, size) { pct -> scope.launch { publish(app, percent = pct) } }
                    checkApk(app, file, size, p.getString("sha256", null))
                } catch (e: java.io.IOException) {
                    if (e.message.orEmpty().contains("ENOSPC") || e.message.orEmpty().contains("No space", ignoreCase = true)) UpdateProblem.NO_SPACE
                    else UpdateProblem.OFFLINE
                } catch (e: Exception) { UpdateProblem.OFFLINE }
            }
            if (problem != UpdateProblem.NONE) { refused(app, problem); return@launch }
            // Kept before Android stops MyTV: the new version says "Updated to …" once.
            p.edit().putString("pending", p.getString("version", "")).putBoolean("announced", false).commit()
            if (viaAdb) {
                quiet = true
                publish(app, phase = UpdateUi.Phase.INSTALLING)
                val r = withContext(Dispatchers.IO) { installViaAdb(app, file) }
                // Success: Android is about to stop MyTV. Otherwise why not, or (null) the adb way did not work at all.
                if (r == UpdateProblem.NONE) return@launch
                quiet = false
                if (r != null) { refused(app, r); return@launch }
                if (!file.exists() || !app.packageManager.canRequestPackageInstalls()) { refused(app, UpdateProblem.FAILED); return@launch }
            }
            quiet = android.os.Build.VERSION.SDK_INT >= 31 && installerIsMe(app)
            if (MainActivity.inFront) p.edit().putLong("reopenAt", System.currentTimeMillis()).commit()
            publish(app, phase = UpdateUi.Phase.INSTALLING)
            runCatching { withContext(Dispatchers.IO) { handToInstaller(app, file) } }.onFailure { done(app, UpdateProblem.FAILED) }
        }
    }

    /** A try that stopped before Android took over: the download goes, and a file that can never work is not offered again. */
    private fun refused(app: Context, problem: UpdateProblem) {
        val p = prefs(app)
        p.edit().remove("pending").remove("reopenAt").apply()
        // Another key, or not newer than what is installed: never offer this one again. A broken download may be retried.
        if (problem == UpdateProblem.FOREIGN_KEY || problem == UpdateProblem.NOT_NEWER) p.edit().putLong("rejectedCode", p.getLong("code", 0L)).apply()
        // Not newer: nothing to say, it simply is no longer offered (publish reads rejectedCode).
        done(app, if (problem == UpdateProblem.NOT_NEWER) UpdateProblem.NONE else problem)
    }

    /**
     * Copies the checked file to the TV and installs it there as adb's shell user. NONE once pm said Success (Android
     * is about to stop MyTV), a problem when pm or the copy said why not, null when the adb way did not work at all
     * (the download is then still there for Android's installer).
     */
    private fun installViaAdb(app: Context, file: File): UpdateProblem? {
        try {
            LocalAdb.push(file, UpdateRules.TMP_APK)
        } catch (e: IOException) {
            Log.w("MyTV", "update copy: $e")
            runCatching { LocalAdb.shell(UpdateRules.CLEAN, prompt = false) }
            val msg = e.message.orEmpty()
            return if (msg.startsWith("push failed:") && UpdateRules.failure(msg) == UpdateProblem.NO_SPACE) UpdateProblem.NO_SPACE else null
        }
        file.delete()      // the TV has its own copy now: room for Android's staged one
        return try {
            // Read now, not when Install was pressed: the viewer may have gone back to a programme meanwhile.
            LocalAdb.shell(UpdateRules.installScript(app.packageName, MainActivity::class.java.name, MainActivity.inFront), prompt = false)
            val until = System.currentTimeMillis() + 5 * 60_000L
            while (System.currentTimeMillis() < until) {
                Thread.sleep(1000)
                val out = runCatching { LocalAdb.shell("cat ${UpdateRules.TMP_LOG} 2>/dev/null", prompt = false) }.getOrDefault("")
                val r = UpdateRules.installResult(out) ?: continue
                if (r != UpdateProblem.NONE) Log.w("MyTV", "pm install: ${out.trim().take(300)}")
                return r
            }
            runCatching { LocalAdb.shell(UpdateRules.CLEAN, prompt = false) }
            UpdateProblem.FAILED
        } catch (e: IOException) {
            Log.w("MyTV", "update via adb: $e")
            UpdateProblem.FAILED
        }
    }

    /** Whether MyTV installed itself last time (then Android 12+ may skip its question). */
    private fun installerIsMe(c: Context): Boolean = runCatching {
        android.os.Build.VERSION.SDK_INT >= 30 && c.packageManager.getInstallSourceInfo(c.packageName).installingPackageName == c.packageName
    }.getOrDefault(false)

    private fun done(c: Context, problem: UpdateProblem) {
        awaitingConfirm = false
        quiet = false
        File(File(c.cacheDir, "updates"), APK_NAME).delete()      // installed or not: the download is not kept
        publish(c, phase = UpdateUi.Phase.IDLE, percent = 0, problem = problem)
    }

    // ---------------------------------------------------------------- network

    private val GITHUB_HOSTS = setOf("github.com", "api.github.com")

    /** GET over https, following redirects one by one so that every hop stays on GitHub. */
    private fun githubGet(address: String, api: Boolean = false): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            val hostOk = url.host in GITHUB_HOSTS || url.host.endsWith(".githubusercontent.com")
            if (url.protocol != "https" || !hostOk) throw IOException("refused host ${url.host}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000; readTimeout = 30_000
                setRequestProperty("User-Agent", "MyTV/${current()}")
                if (api) setRequestProperty("Accept", "application/vnd.github+json")
            }
            when (conn.responseCode) {
                in 200..299 -> return conn
                in 300..399 -> { val next = conn.getHeaderField("Location"); conn.disconnect(); url = URL(url, next ?: throw IOException("empty redirect")) }
                else -> { val code = conn.responseCode; conn.disconnect(); throw IOException("HTTP $code") }
            }
        }
        throw IOException("redirect loop")
    }

    private fun download(url: String, target: File, expected: Long, progress: (Int) -> Unit) {
        val conn = githubGet(url)
        try {
            val total = if (expected > 0) expected else MAX_APK_BYTES   // never more than announced, never endless
            var received = 0L; var lastPct = -1
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        received += n
                        if (total > 0 && received > total) throw IOException("larger than announced")
                        out.write(buffer, 0, n)
                        val pct = if (total > 0) (received * 100 / total).toInt() else 0
                        if (pct >= lastPct + 5) { lastPct = pct; progress(pct) }
                    }
                }
            }
        } finally { conn.disconnect() }
    }

    // ---------------------------------------------------------------- verification

    private fun checkApk(c: Context, apk: File, size: Long, sha256: String?): UpdateProblem {
        if (size > 0 && apk.length() != size) return UpdateProblem.CORRUPT
        if (sha256 != null && hexSha256(apk) != sha256) return UpdateProblem.CORRUPT
        val pm = c.packageManager
        // Android 8 (API 26/27) only knows the older signature flag.
        @Suppress("DEPRECATION") val flags = PackageManager.GET_SIGNATURES or
            (if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
        val incoming = pm.getPackageArchiveInfo(apk.path, flags) ?: return UpdateProblem.CORRUPT
        if (incoming.packageName != c.packageName) return UpdateProblem.CORRUPT
        val installed = pm.getPackageInfo(c.packageName, flags)
        if (androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(incoming) <= androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(installed)) return UpdateProblem.NOT_NEWER
        val mine = certificates(installed); val theirs = certificates(incoming)
        // Some Android builds cannot read certificates from an uninstalled file; the installer checks again anyway.
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine != theirs) return UpdateProblem.FOREIGN_KEY
        return UpdateProblem.NONE
    }

    private fun certificates(info: android.content.pm.PackageInfo): Set<String> {
        val signers = (if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null) ?: @Suppress("DEPRECATION") info.signatures
        return signers?.map { hexSha256Bytes(it.toByteArray()) }?.toSet() ?: emptySet()
    }

    private fun hexSha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hexSha256Bytes(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- Android's installer

    private fun handToInstaller(c: Context, apk: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            // Android 12+: no question when MyTV updates itself and installed itself before (else Android still asks).
            if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out -> input.copyTo(out); session.fsync(out) }
            }
            val callback = PendingIntent.getBroadcast(c, sessionId, Intent(c, InstallResult::class.java).setAction(ACTION_RESULT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(callback.intentSender)
        }
    }

    /** Status from Android's installer (only reachable through our own PendingIntent; not exported). */
    class InstallResult : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != ACTION_RESULT) return
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    quiet = false; publish(c)
                    @Suppress("DEPRECATION") val ask = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (ask == null) { done(c, UpdateProblem.FAILED); return }
                    runCatching { c.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); awaitingConfirm = true }
                        .onFailure { done(c, UpdateProblem.FAILED) }
                }
                PackageInstaller.STATUS_SUCCESS -> done(c, UpdateProblem.NONE)
                PackageInstaller.STATUS_FAILURE_ABORTED -> done(c, UpdateProblem.CANCELLED)
                PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> done(c, UpdateProblem.FOREIGN_KEY)
                PackageInstaller.STATUS_FAILURE_STORAGE -> done(c, UpdateProblem.NO_SPACE)
                else -> done(c, UpdateProblem.FAILED)
            }
        }
    }
}
