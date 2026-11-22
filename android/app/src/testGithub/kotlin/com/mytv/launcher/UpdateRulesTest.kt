// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import com.mytv.launcher.data.UpdateProblem
import com.mytv.launcher.data.UpdateRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {
    @Test fun pmOutputStillRunning() {
        assertNull(UpdateRules.installResult(""))
        assertNull(UpdateRules.installResult("Performing Streamed Install\n"))
    }

    @Test fun pmOutputSuccess() {
        assertEquals(UpdateProblem.NONE, UpdateRules.installResult("Performing Streamed Install\nSuccess\n"))
    }

    @Test fun pmOutputFailures() {
        assertEquals(UpdateProblem.NO_SPACE, UpdateRules.installResult("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"))
        assertEquals(UpdateProblem.FOREIGN_KEY, UpdateRules.installResult("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]"))
        assertEquals(UpdateProblem.NOT_NEWER, UpdateRules.installResult("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"))
        assertEquals(UpdateProblem.FAILED, UpdateRules.installResult("Error: something else"))
    }

    @Test fun copyFailures() {
        assertEquals(UpdateProblem.NO_SPACE, UpdateRules.failure("push failed: No space left on device"))
        assertEquals(UpdateProblem.FAILED, UpdateRules.failure("push failed: closed"))
    }

    @Test fun scriptRunsApartAndReopensOnlyWhenAsked() {
        val on = UpdateRules.installScript("com.tinteractive.mytv", "com.mytv.launcher.MainActivity", reopen = true)
        assertTrue(on.contains("setsid) nohup sh -c 'pm install -r ${UpdateRules.TMP_APK} > ${UpdateRules.TMP_LOG} 2>&1"))
        assertTrue(on.endsWith("& sleep 2"))
        assertTrue(on.contains("grep -q Success ${UpdateRules.TMP_LOG} && am start -n com.tinteractive.mytv/com.mytv.launcher.MainActivity"))
        val off = UpdateRules.installScript("com.tinteractive.mytv", "com.mytv.launcher.MainActivity", reopen = false)
        assertFalse(off.contains("am start"))
    }

    @Test(expected = IllegalArgumentException::class) fun scriptRefusesOddNames() {
        UpdateRules.installScript("com.x; reboot", "A", reopen = true)
    }

    @Test fun space() {
        assertEquals(25_000_000L, UpdateRules.spaceNeeded(10_000_000L))
    }
}
