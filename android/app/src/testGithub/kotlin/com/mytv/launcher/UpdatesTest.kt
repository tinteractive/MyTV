// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import com.mytv.launcher.data.Updates
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdatesTest {
    private fun manifest(apk: String = "mytv-2026.11.2.apk") = JSONObject("""
        {"version":"2026.11.2","code":2026110002,"apk":"$apk","size":123,"sha256":"${"a".repeat(64)}",
         "releases":[{"version":"2026.11.2","code":2026110002,"notes":[{"tag":"new","en":"B"}]},
                     {"version":"2026.11.1","code":2026110001,"notes":[{"tag":"fixed","en":"A"}]}]}""")

    @Test fun readsManifestAndKeepsOnlyNewerNotes() {
        val r = Updates.parseManifest("tinteractive/MyTV", manifest(), installed = 2026110001)
        assertEquals("https://github.com/tinteractive/MyTV/releases/download/v2026.11.2/mytv-2026.11.2.apk", r.apkUrl)
        assertEquals(2026110002L, r.code)
        assertEquals(1, JSONArray(r.notes).length())
    }

    @Test fun fixedApkNameIsTakenFromTheReleaseFolder() {
        val r = Updates.parseManifest("tinteractive/MyTV", manifest("MyTV-Launcher.apk"), installed = 2026110001)
        assert(r.apkUrl == "https://github.com/tinteractive/MyTV/releases/download/v2026.11.2/MyTV-Launcher.apk")
    }

    @Test fun sizeAndChecksumAreRequired() {
        val noSha = manifest().apply { remove("sha256") }
        val noSize = manifest().apply { remove("size") }
        val huge = manifest().put("size", 500L * 1024 * 1024)
        for (m in listOf(noSha, noSize, huge)) assertThrows(Exception::class.java) { Updates.parseManifest("tinteractive/MyTV", m, 0) }
    }

    @Test fun refusesPathsInFileName() {
        assertThrows(IllegalArgumentException::class.java) { Updates.parseManifest("tinteractive/MyTV", manifest("../../evil.apk"), 0) }
    }
}
