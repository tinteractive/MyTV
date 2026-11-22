// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import com.mytv.launcher.data.Sonos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SonosTest {
    private fun pos(uri: String, meta: String) =
        "<u:GetPositionInfoResponse><Track>1</Track><TrackMetaData>${meta.replace("<", "&lt;").replace(">", "&gt;")}</TrackMetaData><TrackURI>$uri</TrackURI></u:GetPositionInfoResponse>"

    @Test fun music() {
        val meta = "<DIDL-Lite><item><dc:title>Clair de Lune</dc:title><dc:creator>Debussy</dc:creator><upnp:albumArtURI>/getaa?s=1&amp;u=x</upnp:albumArtURI></item></DIDL-Lite>"
        val n = Sonos.parseTrack("Keuken", "10.0.0.5", true, pos("x-sonos-spotify:spotify%3atrack%3a1", meta))!!
        assertEquals("Clair de Lune", n.title); assertEquals("Debussy", n.artist)
        assertEquals("http://10.0.0.5:1400/getaa?s=1&u=x", n.art)
    }

    @Test fun tvSoundAndGroupMembersAreNotMusic() {
        assertNull(Sonos.parseTrack("TV", "10.0.0.6", true, pos("x-sonos-htastream:RINCON_1:spdif", "<DIDL-Lite/>")))
        assertNull(Sonos.parseTrack("Keuken", "10.0.0.5", true, pos("x-rincon:RINCON_949F", "")))
    }

    @Test fun radioUsesStreamTitle() {
        val meta = "<DIDL-Lite><item><dc:title>x-sonosapi-stream</dc:title><r:streamContent>Queen - Bohemian Rhapsody</r:streamContent></item></DIDL-Lite>"
        assertEquals("Queen - Bohemian Rhapsody", Sonos.parseTrack("Keuken", "10.0.0.5", false, pos("x-sonosapi-stream:s1", meta))!!.title)
    }

    @Test fun groupsUseTheirLeader() {
        val topo = "<ZoneGroups><ZoneGroup Coordinator=\"RINCON_A\" ID=\"g\"><ZoneGroupMember UUID=\"RINCON_B\" Location=\"http://10.0.0.7:1400/xml\" ZoneName=\"Keuken\"/>" +
            "<ZoneGroupMember UUID=\"RINCON_A\" Location=\"http://10.0.0.8:1400/xml\" ZoneName=\"Woonkamer\"/></ZoneGroup></ZoneGroups>"
        assertEquals(listOf("Keuken + Woonkamer" to "10.0.0.8"), Sonos.parseGroups(topo))
    }

    @org.junit.Test fun dechunk() {
        val body = "5\r\nSonos\r\n4\r\n Arc\r\n0\r\n\r\n".toByteArray()
        org.junit.Assert.assertEquals("Sonos Arc", String(com.mytv.launcher.data.Sonos.dechunk(body)))
    }

    @Test fun onlyLiteralHomeAddressesAreLocal() {
        val net = com.mytv.launcher.data.Net
        org.junit.Assert.assertTrue(net.isLocal("192.168.1.23"))
        org.junit.Assert.assertTrue(net.isLocal("10.0.0.5"))
        org.junit.Assert.assertTrue(net.isLocal("172.16.3.4"))
        org.junit.Assert.assertFalse(net.isLocal("8.8.8.8"))
        org.junit.Assert.assertFalse(net.isLocal("127.0.0.1"))
        org.junit.Assert.assertFalse(net.isLocal("999.1.1.1"))
        org.junit.Assert.assertFalse(net.isLocal("192.168.1"))
        // Names are never looked up: one could point to the home network now and elsewhere at connect time.
        org.junit.Assert.assertFalse(net.isLocal("localhost"))
        org.junit.Assert.assertFalse(net.isLocal("router.lan"))
        org.junit.Assert.assertNull(net.literalIp("example.com"))
        org.junit.Assert.assertNotNull(net.literalIp("[fe80::1]"))
    }

    @Test fun requestPartsWithLineBreaksAreRefused() {
        org.junit.Assert.assertTrue(Sonos.safeRequestPart("/getaa?s=1&u=x"))
        org.junit.Assert.assertFalse(Sonos.safeRequestPart("/a HTTP/1.0\r\nX-Evil: 1"))
        org.junit.Assert.assertFalse(Sonos.safeRequestPart("/a\nb"))
    }
}
