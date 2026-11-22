// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import com.mytv.launcher.data.Backup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a settings file may put back: only known settings, with the type and range MyTV uses for them. */
class BackupTest {
    private fun v(json: String) = JSONObject(json)

    @Test fun acceptsKnownSettingsOfTheRightType() {
        assertEquals("nl", Backup.accept("language", v("""{"s":"nl"}""")))
        assertEquals(true, Backup.accept("nowPlaying", v("""{"b":true}""")))
        assertEquals(600, Backup.accept("rotateSec", v("""{"i":600}""")))
        assertEquals(setOf("a", "b"), Backup.accept("hidden", v("""{"set":["a","b"]}""")))
    }

    @Test fun refusesUnknownKeysAndWrongTypes() {
        assertNull(Backup.accept("somethingElse", v("""{"s":"x"}""")))
        assertNull(Backup.accept("pinHash", v("""{"s":"abc"}""")))                 // never from a file
        assertNull(Backup.accept("cleanedUp", v("""{"set":["com.android.vending"]}""")))   // belongs to the TV it was done on
        assertNull(Backup.accept("profile", v("""{"b":true}""")))                  // would crash the typed reader
        assertNull(Backup.accept("nowPlaying", v("""{"s":"true"}""")))
        assertNull(Backup.accept("rotateSec", v("""{"s":"x"}""")))
        assertNull(Backup.accept("hidden", v("""{"set":["a",1]}""")))
        assertNull(Backup.accept("language", v("""{"s":{"nested":1}}""")))
    }

    @Test fun remoteButtonChoicesTravelButTheirCodesDoNot() {
        assertEquals(false, Backup.accept("appsButton", v("""{"b":false}""")))
        assertEquals(true, Backup.accept("sourcesButton", v("""{"b":true}""")))
        assertEquals(15, Backup.accept("dreamMinutesChosen", v("""{"i":15}""")))
        assertNull(Backup.accept("dreamMinutesChosen", v("""{"i":5000}""")))
        assertNull(Backup.accept("appsKey", v("""{"i":183}""")))          // a key code of this TV's remote
        assertNull(Backup.accept("keycode_red", v("""{"i":183}""")))
    }

    @Test fun refusesValuesOutOfRange() {
        assertNull(Backup.accept("quietFrom", v("""{"i":24}""")))
        assertNull(Backup.accept("quietMax", v("""{"i":0}""")))
        assertNull(Backup.accept("rotateSec", v("""{"i":-5}""")))
        assertNull(Backup.accept("profiles", v("""{"s":"not json"}""")))
        assertNull(Backup.accept("appNames", v("""{"s":"[1,2]"}""")))
        assertNull(Backup.accept("language", v("""{"s":"${"x".repeat(200_001)}"}""")))
    }

    @Test fun perProfileKeysOnlyWhereTheyExist() {
        assertEquals("a\nb", Backup.accept("order@kid-1", v("""{"s":"a\nb"}""")))
        // Home-screen settings are per profile; settings for the whole TV are not.
        assertEquals("nl", Backup.accept("language@kid-1", v("""{"s":"nl"}""")))
        assertNull(Backup.accept("askWho@kid-1", v("""{"b":true}""")))
        assertNull(Backup.accept("order@../x", v("""{"s":"a"}""")))
    }

    @Test fun startUpAppMustLookLikeAComponent() {
        assertEquals("com.example.tv/.Main", Backup.accept("autostart", v("""{"s":"com.example.tv/.Main"}""")))
        assertNull(Backup.accept("autostart", v("""{"s":"not a component"}""")))
    }

    @Test fun quietHoursSoundbarMustBeOnTheHomeNetwork() {
        assertEquals("10.0.0.20|Living room|Arc", Backup.accept("quietSonos", v("""{"s":"10.0.0.20|Living room|Arc"}""")))
        assertNull(Backup.accept("quietSonos", v("""{"s":"8.8.8.8|Living room|Arc"}""")))
        assertNull(Backup.accept("quietSonos", v("""{"s":"example.com|Living room|Arc"}""")))
        assertNull(Backup.accept("quietSonos", v("""{"s":"127.0.0.1|x|y"}""")))
    }
}
