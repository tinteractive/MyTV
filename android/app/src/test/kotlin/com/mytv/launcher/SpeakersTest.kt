// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import com.mytv.launcher.data.Speakers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URL

class SpeakersTest {
    private val d = Speakers.Dev("10.0.0.9", "Kamer", "x", 11000)

    @Test fun bluos() {
        val xml = "<status etag=\"1\"><artist>Adele</artist><image>/Artwork?service=Tidal&amp;id=1</image><state>play</state><title1>Hello</title1></status>"
        val n = Speakers.parseBluOS(d, xml)!!
        assertEquals("Hello", n.title); assertEquals("Adele", n.artist); assertEquals(true, n.playing)
        assertEquals("http://10.0.0.9:11000/Artwork?service=Tidal&id=1", n.art)
        assertNull(Speakers.parseBluOS(d, "<status><state>stop</state><title1>x</title1></status>"))
    }

    @Test fun bose() {
        val xml = "<nowPlaying deviceID=\"A\" source=\"SPOTIFY\"><track>Yellow</track><artist>Coldplay</artist><art artImageStatus=\"IMAGE_PRESENT\">http://i.scdn.co/x.jpg</art><playStatus>PAUSE_STATE</playStatus></nowPlaying>"
        val n = Speakers.parseBose(d.copy(port = 8090), xml)!!
        assertEquals("Yellow", n.title); assertEquals(false, n.playing); assertEquals("http://i.scdn.co/x.jpg", n.art)
        assertNull(Speakers.parseBose(d, "<nowPlaying source=\"STANDBY\"><ContentItem/></nowPlaying>"))
    }

    @Test fun musicCast() {
        val n = Speakers.parseMusicCast(d.copy(port = 80), """{"response_code":0,"playback":"play","artist":"Queen","track":"Bicycle","albumart_url":"/YamahaRemoteControl/AlbumART/AlbumART.jpg"}""")!!
        assertEquals("Bicycle", n.title); assertEquals("http://10.0.0.9/YamahaRemoteControl/AlbumART/AlbumART.jpg", n.art)
        assertNull(Speakers.parseMusicCast(d, """{"playback":"stop","track":""}"""))
    }

    @Test fun heos() {
        val n = Speakers.parseHeos(d, 7, "pid=7&state=pause", JSONObject("""{"type":"song","song":"Roxanne","artist":"The Police","image_url":"https://x/y.jpg"}"""))!!
        assertEquals("Roxanne", n.title); assertEquals(false, n.playing); assertEquals("heos|7", n.extra)
    }

    @Test fun deviceKinds() {
        val u = URL("http://10.0.0.5:8080/desc.xml")
        assertNull(Speakers.describe(u, "<root><device><manufacturer>Sonos, Inc.</manufacturer></device></root>"))
        assertEquals("heos", Speakers.describe(u, "<root><device><manufacturer>Denon</manufacturer><friendlyName>Woonkamer</friendlyName></device></root>")!!.kind)
        val x = "<root><device><manufacturer>Onkyo</manufacturer><friendlyName>Receiver</friendlyName><serviceList><service>" +
            "<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>upnp/control/avt</controlURL></service></serviceList></device></root>"
        val dev = Speakers.describe(u, x)!!
        assertEquals("upnp", dev.kind); assertEquals("/upnp/control/avt", dev.control); assertEquals(8080, dev.port)
    }
}
