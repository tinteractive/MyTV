// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import com.mytv.launcher.ui.OkKey
import com.mytv.launcher.ui.OkPress
import com.mytv.launcher.ui.OkPress.Down
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OK on a tile: a short press opens, a hold opens the menu, however the remote reports the hold. */
class OkPressTest {
    @Test fun shortPressClicks() {
        val p = OkPress(OkKey())
        assertEquals(Down.NEW, p.down(0, 0, false, canHold = true))
        assertTrue(p.up())
    }

    @Test fun markedRepeatsBecomeAHoldAfter600Ms() {
        val p = OkPress(OkKey())
        p.down(0, 0, false, true)
        assertEquals(Down.NONE, p.down(400, 1, false, true))      // first repeat: too early
        assertEquals(Down.HOLD, p.down(650, 6, false, true))
        assertEquals(Down.NONE, p.down(700, 7, false, true))      // only once
        assertFalse(p.up())                                       // a hold never also clicks
    }

    @Test fun unmarkedSecondKeyDownIsAHold() {
        // Some TVs repeat a held key without the repeat mark (seen on a TCL).
        val p = OkPress(OkKey())
        p.down(0, 0, false, true)
        assertEquals(Down.HOLD, p.down(450, 0, false, true))
        assertFalse(p.up())
    }

    @Test fun noRepeatsAtAllHoldsThroughTheTimer() {
        val p = OkPress(OkKey())
        p.down(0, 0, false, true)
        assertTrue(p.hold())                                      // the caller's 600 ms timer
        assertFalse(p.hold())
        assertFalse(p.up())
    }

    @Test fun aPressWhoseKeyUpWentElsewhereDoesNotMakeTheNextOneAHold() {
        // A hold opened a menu, the key-up went to the menu: the next press, seconds later, is a new one.
        val p = OkPress(OkKey())
        p.down(0, 0, false, true); p.hold()
        assertEquals(Down.NEW, p.down(5_000, 0, false, true))
        assertTrue(p.up())
    }

    @Test fun withoutAMenuHoldingStillClicksOnRelease() {
        val p = OkPress(OkKey())
        p.down(0, 0, false, canHold = false)
        assertEquals(Down.NONE, p.down(450, 0, false, false))
        assertEquals(Down.NONE, p.down(900, 9, true, false))
        assertTrue(p.up())
    }

    @Test fun theHeldKeyDoesNotPressTheMenuThatOpensUnderIt() {
        // Hold OK on an app: its menu opens and its first item gets the focus while OK is still down.
        val key = OkKey()
        val tile = OkPress(key); val firstItem = OkPress(key)
        tile.down(0, 0, false, true); assertTrue(tile.hold())
        assertEquals(Down.NONE, firstItem.down(650, 7, false, true))   // marked repeats
        assertEquals(Down.NONE, firstItem.down(700, 0, false, true))   // unmarked repeats (some TVs)
        assertFalse(firstItem.up())                                    // releasing does not choose "Move"
        // The next real press on the menu item works.
        assertEquals(Down.NEW, firstItem.down(2_000, 0, false, true))
        assertTrue(firstItem.up())
    }

    @Test fun releaseWithoutPressHereDoesNothing() {
        assertFalse(OkPress(OkKey()).up())
    }
}
