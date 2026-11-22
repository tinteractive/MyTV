// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Exists so Android lets MyTV read the media sessions of other apps ("notification access"); reads no notifications.
 * While Android keeps it connected, MyTV's own "continue watching" history follows the sessions ([History]).
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        History.start(this)
        // Android binds this listener again after a crash or force stop: a second way back for the Home-button helper.
        SystemAccess.repairHomeWatch(this)
    }
    override fun onListenerDisconnected() { History.stop(this) }
}

/**
 * What apps on the TV itself play (Spotify, YouTube, Plex, Netflix…), through Android's media sessions — the same
 * source the stock launcher uses for its "now playing" row. Needs notification access for MyTV (set once; with
 * the extra system right MyTV can switch it on itself).
 */
object MediaApps {
    private const val LISTENERS = "enabled_notification_listeners"

    fun component(c: Context) = ComponentName(c, MediaListener::class.java)

    fun allowed(c: Context): Boolean =
        Settings.Secure.getString(c.contentResolver, LISTENERS).orEmpty().split(':').any { it == component(c).flattenToString() }

    /** With WRITE_SECURE_SETTINGS: grant MyTV's own notification access. */
    fun enable(c: Context): Boolean = runCatching {
        val cur = Settings.Secure.getString(c.contentResolver, LISTENERS).orEmpty().split(':').filter { it.isNotBlank() }
        Settings.Secure.putString(c.contentResolver, LISTENERS, (cur + component(c).flattenToString()).distinct().joinToString(":"))
        true
    }.getOrElse { Log.w("MyTV", "notification access: $it"); false }

    fun openSettings(c: Context) {
        Net.startFirst(c, Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"), SystemAccess.androidTvSettings())
    }

    private fun controllers(c: Context): List<MediaController> = runCatching {
        (c.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).getActiveSessions(component(c))
    }.getOrDefault(emptyList())

    /** Apps on the TV that tell Android what they play (a title), for the settings; Plex, for one, does not. */
    fun reportingApps(c: Context): List<String> {
        if (!allowed(c)) return emptyList()
        val pm = c.packageManager
        return controllers(c).filter { it.packageName != c.packageName && !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() }
            .mapNotNull { mc -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(mc.packageName, 0)).toString() }.getOrNull() }.distinct()
    }

    private val covers = java.util.concurrent.ConcurrentHashMap<String, androidx.compose.ui.graphics.ImageBitmap>()

    fun nowPlaying(c: Context): List<NowPlaying> {
        if (!allowed(c)) return emptyList()
        val pm = c.packageManager
        return controllers(c).mapNotNull { mc ->
            if (mc.packageName == c.packageName) return@mapNotNull null
            val st = mc.playbackState?.state
            // Skipping to another song or connecting is a moment between two songs: still playing (else the card
            // would vanish for a second at every "next").
            val playing = st in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
                PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM)
            if (!playing && st != PlaybackState.STATE_PAUSED) return@mapNotNull null
            val md = mc.metadata ?: return@mapNotNull null
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return@mapNotNull null
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            val bmp = listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                .firstNotNullOfOrNull { k -> md.getBitmap(k) }
            // One scaled cover per song: a new bitmap every poll made the card redraw every 4 seconds.
            val coverKey = mc.packageName + "|" + title
            val image = bmp?.let { b -> covers.getOrPut(coverKey) { if (covers.size > 8) covers.clear(); Net.scaled(b, 128) } }
            val uri = listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                .firstNotNullOfOrNull { k -> md.getString(k)?.takeIf { it.startsWith("http") } }
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(mc.packageName, 0)).toString() }.getOrDefault(mc.packageName)
            // When it last really played, as MyTV's background watcher saw it. Not the session's own time: apps such
            // as Spotify restore their session "paused" after a cold start, stamped with the start-up time.
            val changedAt = History.lastPlaying(mc.packageName)
            NowPlaying(label, "", title, artist, uri, playing, source = "app", key = "app:${mc.packageName}", image = image, pkg = mc.packageName, changedAt = changedAt)
        }
    }

    fun command(c: Context, pkg: String, action: String): Boolean {
        val t = controllers(c).firstOrNull { it.packageName == pkg }?.transportControls ?: return false
        when (action) { "Play" -> t.play(); "Pause" -> t.pause(); else -> t.skipToNext() }
        return true
    }
}
