// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import com.mytv.launcher.data.LinkCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Linking two TVs: both sides end with the same key only with the same code; settings travel sealed. */
class TvLinkTest {
    private val a = LinkCrypto.keyPair()
    private val b = LinkCrypto.keyPair()

    @Test fun bothTvsMakeTheSameKey() {
        val onA = LinkCrypto.shared(a, LinkCrypto.pub(b), "1234", "tvA-12345", "tvB-12345")
        val onB = LinkCrypto.shared(b, LinkCrypto.pub(a), "1234", "tvB-12345", "tvA-12345")   // ids in the other order
        assertEquals(32, onA!!.size)
        assertArrayEquals(onA, onB)
    }

    @Test fun anotherCodeGivesAnotherKey() {
        val right = LinkCrypto.shared(a, LinkCrypto.pub(b), "1234", "tvA-12345", "tvB-12345")
        val wrong = LinkCrypto.shared(a, LinkCrypto.pub(b), "1235", "tvA-12345", "tvB-12345")
        assertFalse(right!!.contentEquals(wrong))
    }

    @Test fun proofNeedsTheCodeAndTheSentKey() {
        val pub = LinkCrypto.pub(a)
        val p = LinkCrypto.proof("1234", "ask", "tvA-12345", pub)
        assertTrue(LinkCrypto.same(p, LinkCrypto.proof("1234", "ask", "tvA-12345", pub)))
        assertFalse(LinkCrypto.same(p, LinkCrypto.proof("4321", "ask", "tvA-12345", pub)))
        assertFalse(LinkCrypto.same(p, LinkCrypto.proof("1234", "ask", "tvA-12345", LinkCrypto.pub(b))))   // key swapped in between
        assertFalse(LinkCrypto.same(p, LinkCrypto.proof("1234", "answer", "tvA-12345", pub)))           // an answer is no question
    }

    @Test fun badPublicKeyGivesNoKey() {
        assertNull(LinkCrypto.shared(a, "bm90IGEga2V5", "1234", "tvA-12345", "tvB-12345"))
    }

    @Test fun sealedSettingsOpenOnlyWithTheKeyAndRequest() {
        val key = LinkCrypto.shared(a, LinkCrypto.pub(b), "1234", "tvA-12345", "tvB-12345")!!
        val other = LinkCrypto.shared(a, LinkCrypto.pub(b), "9999", "tvA-12345", "tvB-12345")!!
        val text = """{"app":"MyTV","settings":{}}"""
        val sealed = LinkCrypto.seal(key, text.toByteArray(), "settings|n1")
        assertFalse(sealed.contains("MyTV"))
        assertEquals(text, String(LinkCrypto.open(key, sealed, "settings|n1")!!))
        assertNull(LinkCrypto.open(other, sealed, "settings|n1"))
        assertNull(LinkCrypto.open(key, sealed, "settings|n2"))           // an old answer for another request
        val bytes = LinkCrypto.unb64(sealed).also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertNull(LinkCrypto.open(key, LinkCrypto.b64(bytes), "settings|n1"))   // changed on the way
        assertNull(LinkCrypto.open(key, "short", "settings|n1"))
    }

    @Test fun requestMacDependsOnEverythingAsked() {
        val key = LinkCrypto.shared(a, LinkCrypto.pub(b), "1234", "tvA-12345", "tvB-12345")!!
        val n1 = "nonce-0000000001"
        val mac = LinkCrypto.requestMac(key, "photo", "tvA-12345", n1, "photo-1.jpg")
        assertEquals(mac, LinkCrypto.requestMac(key, "photo", "tvA-12345", n1, "photo-1.jpg"))
        assertNotEquals(mac, LinkCrypto.requestMac(key, "photo", "tvA-12345", "nonce-0000000002", "photo-1.jpg"))
        assertNotEquals(mac, LinkCrypto.requestMac(key, "photo", "tvA-12345", n1, "photo-2.jpg"))
        assertNotEquals(mac, LinkCrypto.requestMac(key, "get", "tvA-12345", n1, "photo-1.jpg"))
    }

    @Test fun fingerprintIgnoresTheOrderOfSetsAndKeys() {
        val one = org.json.JSONObject("""{"b":{"set":["x","y"]},"a":{"s":"nl"}}""")
        val two = org.json.JSONObject("""{"a":{"s":"nl"},"b":{"set":["y","x"]}}""")
        assertEquals(com.mytv.launcher.data.TvLink.canonical(one), com.mytv.launcher.data.TvLink.canonical(two))
        val changed = org.json.JSONObject("""{"a":{"s":"en"},"b":{"set":["y","x"]}}""")
        assertNotEquals(com.mytv.launcher.data.TvLink.canonical(one), com.mytv.launcher.data.TvLink.canonical(changed))
    }
}
