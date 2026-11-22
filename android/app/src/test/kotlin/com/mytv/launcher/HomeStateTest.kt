// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import android.content.Context
import com.mytv.launcher.data.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The home screen's own rules, on a simulated Android 11 (like the TVs MyTV is made for): the overlay stack and where
 * focus goes back to, the "who is watching?" question that a child cannot skip, the PIN and the profile rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HomeStateTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        context.getSharedPreferences("mytv", Context.MODE_PRIVATE).edit().clear().commit()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After fun tearDown() = scope.cancel()

    private fun newState() = HomeState(context, scope)

    /** A parent profile ("") that is active, a child profile, a PIN, and "who is watching?" on. */
    private fun withFamily(state: HomeState = newState()): HomeState {
        state.enableProfiles("Parent", "Kid")
        state.setPin("1234")
        state.changeAskWho(true)
        return state
    }

    private fun storedPin() = context.getSharedPreferences("mytv", Context.MODE_PRIVATE).getString("pinHash", null)

    // ---------------------------------------------------------------- overlay stack and focus

    @Test fun backReturnsFocusToWhatOpenedEachSheet() {
        val s = newState()
        s.open(Overlay.Settings, "btn:settings")
        s.open(Overlay.Bluetooth, "set:bt")
        s.close()
        assertEquals(Overlay.Settings, s.top)
        assertEquals("set:bt", s.pendingFocus)
        s.close()
        assertNull(s.top)
        assertEquals("btn:settings", s.pendingFocus)
    }

    @Test fun aSheetThatOpenedByItselfReturnsToTheDock() {
        val s = newState()
        s.open(Overlay.Update, null)
        s.close()
        assertEquals(s.dockFocus(), s.pendingFocus)
    }

    @Test fun closeAllEmptiesTheStackAndFocusesTheGivenKey() {
        val s = newState()
        s.open(Overlay.Settings, "btn:settings")
        s.open(Overlay.PinSetup, "set:pin")
        s.closeAll("app:x")
        assertTrue(s.overlays.isEmpty())
        assertEquals("app:x", s.pendingFocus)
    }

    // ---------------------------------------------------------------- "who is watching?"

    @Test fun whoIsWatchingCannotBeSkippedWhileAPinProtectsTheParent() {
        val s = withFamily()
        s.open(Overlay.Profiles(ask = true), null)
        assertTrue(s.whoLocked)
        s.close()                      // Back
        s.closeAll(null)               // Home
        assertEquals(Overlay.Profiles(ask = true), s.top)
        s.closeAll(null, answered = true)
        assertNull(s.top)
    }

    @Test fun whoIsWatchingCanBeClosedWithoutAPin() {
        val s = newState()
        s.enableProfiles("Parent", "Kid")
        s.open(Overlay.Profiles(ask = true), null)
        assertFalse(s.whoLocked)
        s.close()
        assertNull(s.top)
    }

    @Test fun theProfilePickerItselfIsNeverLocked() {
        val s = withFamily()
        s.open(Overlay.Profiles(ask = false), "btn:profile")
        assertFalse(s.whoLocked)
    }

    @Test fun aParentProfileNeedsThePinFromAChildOrFromTheQuestion() {
        val s = withFamily()
        val parent = s.profiles.first { !it.child }
        val kid = s.profiles.first { it.child }
        assertFalse(s.needsPin(kid))
        s.open(Overlay.Profiles(ask = true), null)
        assertTrue(s.needsPin(parent))
        s.closeAll(null, answered = true)
        assertFalse(s.needsPin(parent))       // the parent is active and nothing asks: no PIN to switch
        s.switchProfile(kid.id)
        assertTrue(s.isChild)
        assertTrue(s.needsPin(parent))
        assertFalse(s.needsPin(kid))
    }

    // ---------------------------------------------------------------- PIN

    @Test fun pinIsStoredSaltedAndChecked() {
        val s = newState()
        s.setPin("1234")
        val stored = storedPin()!!
        assertTrue(stored.startsWith("v2:"))
        assertFalse("1234" in stored)
        assertTrue(s.checkPin("1234"))
        assertFalse(s.checkPin("4321"))
    }

    @Test fun fiveWrongPinsBlockEvenTheRightOneAndARestartDoesNotHelp() {
        val s = newState()
        s.setPin("1234")
        repeat(5) { assertFalse(s.checkPin("0000")) }
        assertTrue(s.pinWait() > 0)
        assertFalse(s.checkPin("1234"))
        // Switching the TV off and on (a new home screen) keeps the count and the wait.
        val again = newState()
        assertTrue(again.pinWait() > 0)
        assertFalse(again.checkPin("1234"))
    }

    @Test fun theOldUnsaltedHashIsAcceptedOnceAndReplaced() {
        val old = java.security.MessageDigest.getInstance("SHA-256").digest("mytv:2468".toByteArray()).joinToString("") { "%02x".format(it) }
        context.getSharedPreferences("mytv", Context.MODE_PRIVATE).edit().putString("pinHash", old).commit()
        val s = newState()
        assertTrue(s.hasPin)
        assertTrue(s.checkPin("2468"))
        assertTrue(storedPin()!!.startsWith("v2:"))
        assertTrue(s.checkPin("2468"))
    }

    // ---------------------------------------------------------------- profiles

    @Test fun theLastParentCannotBecomeAChild() {
        val s = newState()
        s.enableProfiles("Parent", "Kid")
        assertFalse(s.setChild("", true))
        assertFalse(s.profiles.first { it.id == "" }.child)
    }

    @Test fun removingTheSecondToLastProfileSwitchesProfilesOffAndForgetsThePin() {
        val s = withFamily()
        s.deleteProfile(s.profiles.first { it.child }.id)
        assertTrue(s.profiles.isEmpty())
        assertFalse(s.hasPin)
        assertFalse(s.askWho)
        assertEquals("", s.profileId)
    }

    @Test fun deletingTheActiveChildGoesBackToTheFirstProfile() {
        val s = withFamily()
        val guest = s.addProfile("Guest", "Parent")
        val kid = s.profiles.first { it.child }
        s.switchProfile(kid.id)
        s.deleteProfile(kid.id)
        assertEquals("", s.profileId)
        assertEquals(listOf("", guest), s.profiles.map(Profile::id))
    }
}
