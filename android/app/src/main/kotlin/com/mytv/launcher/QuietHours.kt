// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.time.LocalTime

/**
 * Quiet hours: during the chosen evening/night hours the volume never goes above a maximum, whatever is
 * playing and whichever remote is used. Runs in MyTV's always-on Home-button service.
 *
 * Where the sound goes decides how: the TV's own speakers through Android's volume; a Sonos soundbar on HDMI
 * (Arc, Beam, Ray, …) through its own volume on the home network. A soundbar MyTV cannot read (another brand on
 * HDMI, optical) cannot be limited, so the setting is not offered then. Bluetooth (headphones) is never limited.
 */
object QuietHours {
    private const val PREFS = "mytv"
    private var thread: HandlerThread? = null
    private var h: Handler? = null

    data class Settings(val on: Boolean, val from: Int, val to: Int, val maxPercent: Int, val sonos: String?)

    fun settings(c: Context): Settings = runCatching {
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Settings(sp.getBoolean("quietOn", false), sp.getInt("quietFrom", 22).coerceIn(0, 23), sp.getInt("quietTo", 7).coerceIn(0, 23),
            sp.getInt("quietMax", 30).coerceIn(1, 100), sp.getString("quietSonos", null))
    }.getOrDefault(Settings(false, 22, 7, 30, null))   // a stored value of the wrong type: quiet hours off, not a crash

    fun save(c: Context, s: Settings) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("quietOn", s.on).putInt("quietFrom", s.from)
            .putInt("quietTo", s.to).putInt("quietMax", s.maxPercent).putString("quietSonos", s.sonos).apply()
        start(c)
    }

    /** Hours: from 22 to 7 crosses midnight. */
    fun inWindow(s: Settings, now: LocalTime = LocalTime.now()): Boolean {
        val hNow = now.hour
        return if (s.from <= s.to) hNow in s.from until s.to else hNow >= s.from || hNow < s.to
    }

    /** Sound goes to a soundbar over HDMI (ARC/eARC): Android's own volume is then not the real volume. */
    fun soundOverHdmi(c: Context): Boolean = runCatching {
        (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_HDMI_ARC || it.type == 29 /* TYPE_HDMI_EARC, API 31 */ }
    }.getOrDefault(false)

    private fun bluetoothOut(c: Context): Boolean = runCatching {
        (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    }.getOrDefault(false)

    /** Called by the Home-button service when it connects, and after a change: checks every 3 s, only while on. */
    fun start(c: Context) {
        val app = c.applicationContext
        if (thread == null) thread = HandlerThread("mytv-quiet").apply { start() }.also { h = Handler(it.looper) }
        val handler = h ?: return
        handler.removeCallbacksAndMessages(null)
        val tick = object : Runnable {
            override fun run() {
                val s = settings(app)
                if (!s.on) return
                // Nothing to limit while the TV is off: no request to the soundbar every few seconds all night.
                val watching = inWindow(s) && runCatching { (app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive }.getOrDefault(true)
                if (watching) runCatching { enforce(app, s) }.onFailure { Log.w("MyTV", "quiet hours: $it") }
                handler.postDelayed(this, if (watching) 3_000L else 60_000L)
            }
        }
        handler.post(tick)
    }

    private fun enforce(c: Context, s: Settings) {
        if (bluetoothOut(c)) return                          // headphones: never limited
        val sonos = s.sonos
        if (soundOverHdmi(c)) {
            // Only ever a device on the home network (the address is a stored setting).
            val ip = sonos?.substringBefore('|')?.takeIf { com.mytv.launcher.data.Net.isLocal(it) } ?: return
            val vol = com.mytv.launcher.data.Sonos.volume(ip) ?: return
            if (vol > s.maxPercent) { com.mytv.launcher.data.Sonos.setVolume(ip, s.maxPercent); Log.i("MyTV", "quiet hours: Sonos $vol → ${s.maxPercent}") }
            return
        }
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val limit = Math.round(max * s.maxPercent / 100f)
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (now > limit) { am.setStreamVolume(AudioManager.STREAM_MUSIC, limit, AudioManager.FLAG_SHOW_UI); Log.i("MyTV", "quiet hours: TV $now → $limit") }
    }
}
