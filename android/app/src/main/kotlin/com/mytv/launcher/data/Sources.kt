// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.util.Log

/** One TV input: an HDMI port (with the CEC device name when one reports itself), AV, component or the tuner. */
data class Source(
    val id: String,
    val name: String,          // the name given in the TV's settings, or the CEC device name, or "HDMI 2"
    val port: String?,         // "HDMI 2" when [name] is a device name
    val type: Int,
    val state: Int,            // TvInputManager.INPUT_STATE_*
    val passthrough: Boolean,
)

/**
 * TV inputs from Android's TV input framework, switched the way the stock launcher does it: VIEW the input's
 * passthrough "channel", which the Philips live-TV app shows. Works with the stock launcher switched off too.
 */
object Sources {
    /** Pseudo source for the Philips live-TV app (channels). */
    const val LIVE_TV = "live-tv"

    /** Live-TV apps of several brands (Philips, Google Live Channels, Sony, MediaTek-based TVs, TCL, Hisense). */
    private val LIVE_APPS = listOf("org.droidtv.playtv", "com.google.android.tv", "com.sony.dtv.tvx", "com.mediatek.wwtv.tvcenter",
        "com.tcl.tvinput", "com.hisense.tv.livetv")

    private fun liveIntent(c: Context): Intent? = LIVE_APPS.firstNotNullOfOrNull { p ->
        c.packageManager.getLeanbackLaunchIntentForPackage(p) ?: c.packageManager.getLaunchIntentForPackage(p)
    }

    fun list(c: Context): List<Source> = runCatching {
        val tim = c.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
        val all = tim.tvInputList
        val byId = all.associateBy { it.id }
        // Philips stores the device type after '&' in the name ("Sonos&AUDIO_SYSTEM").
        fun clean(s: CharSequence?) = s?.toString()?.substringBefore('&')?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        fun label(i: TvInputInfo) = clean(i.loadCustomLabel(c)) ?: clean(i.loadLabel(c)) ?: i.id
        fun state(i: TvInputInfo) = runCatching { tim.getInputState(i.id) }.getOrDefault(TvInputManager.INPUT_STATE_DISCONNECTED)
        val analog = setOf(TvInputInfo.TYPE_COMPONENT, TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_SCART, TvInputInfo.TYPE_SVIDEO,
            TvInputInfo.TYPE_VGA, TvInputInfo.TYPE_DVI)
        val withChild = all.mapNotNull { it.parentId }.toSet()
        val inputs = all.filter { it.isPassthroughInput && it.id !in withChild }.mapNotNull { i ->
            val parent = i.parentId?.let { byId[it] }
            val type = if (parent != null) TvInputInfo.TYPE_HDMI else i.type
            val st = state(i)
            when {
                // Port shown under a device name: "HDMI 4" (also when the port itself was renamed, e.g. "Sonos").
                type == TvInputInfo.TYPE_HDMI -> {
                    val port = clean((parent ?: i).loadLabel(c))
                    Source(i.id, label(i), port?.takeIf { it != label(i) }, type, st, true)
                }
                // This TV reports "standby" for analog inputs with nothing attached: only show them when connected.
                type in analog && st == TvInputManager.INPUT_STATE_CONNECTED -> Source(i.id, label(i), null, type, st, true)
                else -> null
            }
        }.sortedWith(compareBy({ it.state != TvInputManager.INPUT_STATE_CONNECTED }, { it.port ?: it.name }))
        inputs + listOfNotNull(liveIntent(c)?.let { Source(LIVE_TV, "", null, TvInputInfo.TYPE_TUNER, TvInputManager.INPUT_STATE_CONNECTED, false) })
    }.onFailure { Log.w("MyTV", "inputs: $it") }.getOrDefault(emptyList())

    fun open(c: Context, s: Source): Boolean {
        if (s.id == LIVE_TV) return Net.startFirst(c, liveIntent(c))
        val uri = if (s.passthrough) TvContract.buildChannelUriForPassthroughInput(s.id) else TvContract.buildChannelsUriForInput(s.id)
        val typed = Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (s.passthrough) TvContract.Channels.CONTENT_ITEM_TYPE else TvContract.Channels.CONTENT_TYPE)
        // Live TV without a channel URI handler: the brand's TV app.
        return Net.startFirst(c, typed, Intent(Intent.ACTION_VIEW, uri), liveIntent(c))
    }
}
