// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import com.mytv.launcher.data.Cleanup
import com.mytv.launcher.data.Moon
import com.mytv.launcher.data.Wmo
import com.mytv.launcher.ui.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LogicTest {
    @Test fun unitsConvertOnlyWhenDataIsInOtherUnits() {
        assertEquals(10, Units(fahrenheit = false, h24 = true).temp(10.0))          // °C data shown as °C
        assertEquals(50, Units(fahrenheit = true, h24 = true, dataFahrenheit = false).temp(10.0))
        assertEquals(10, Units(fahrenheit = false, h24 = true, dataFahrenheit = true).temp(50.0))
        assertEquals(62, Units(fahrenheit = true, h24 = true, dataFahrenheit = false).wind(100.0))
    }

    @Test fun windDirections() {
        assertEquals(0, Wmo.windIndex(0)); assertEquals(0, Wmo.windIndex(350))
        assertEquals(2, Wmo.windIndex(90)); assertEquals(4, Wmo.windIndex(180)); assertEquals(7, Wmo.windIndex(315))
    }

    @Test fun moonPhaseMatchesReference() {
        // Checked against the ephem library: 12 Nov 2026 21:40 Amsterdam ≈ 11.6 % lit, waxing; full moon 24 Nov.
        val info = Moon.info(52.6, 4.72, LocalDateTime.of(2026, 11, 12, 21, 40), ZoneId.of("Europe/Amsterdam"))
        assertTrue(info.illumination in 0.10..0.14)
        assertTrue(info.phase < 0.5)
        assertEquals(24, info.nextFull.dayOfMonth)
    }

    @Test fun cleanupNeverTouchesEssentials() {
        val essential = setOf("org.droidtv.playtv", "org.droidtv.ipcontrol", "org.droidtv.usagelogger", "org.droidtv.demome",
            "com.google.android.apps.mediashell", "com.google.android.katniss", "org.droidtv.hbbtvopapp", "com.mytv.launcher",
            // Setup, homes, live TV, updates, the remote, accessibility, the Play Store (CleanYourTV's warnings).
            "com.google.android.tungsten.setupwraith", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
            "com.mitv.tvhome.atv", "com.tcl.channelplus", "com.google.android.tv", "com.tcl.ttvs", "com.tcl.partnercustomizer",
            "com.tcl.guard", "com.google.android.marvin.talkback", "com.android.vending", "com.google.android.gms",
            "com.google.android.tts", "com.android.printspooler", "com.google.android.backdrop")
        assertTrue(Cleanup.ITEMS.none { it.pkg in essential })
        assertEquals(Cleanup.ITEMS.size, Cleanup.ITEMS.map { it.pkg }.toSet().size)
    }

    @Test fun quietHoursCrossMidnight() {
        val s = com.mytv.launcher.QuietHours.Settings(true, 22, 7, 30, null)
        assertTrue(com.mytv.launcher.QuietHours.inWindow(s, java.time.LocalTime.of(23, 30)))
        assertTrue(com.mytv.launcher.QuietHours.inWindow(s, java.time.LocalTime.of(6, 59)))
        assertTrue(!com.mytv.launcher.QuietHours.inWindow(s, java.time.LocalTime.of(7, 0)))
        assertTrue(!com.mytv.launcher.QuietHours.inWindow(s, java.time.LocalTime.of(12, 0)))
    }
}
