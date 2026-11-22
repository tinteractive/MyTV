// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import com.mytv.launcher.data.History
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryTest {
    private fun result(id: String, title: String, owner: String, length: String) =
        "\"videoRenderer\":{\"videoId\":\"$id\",\"thumbnail\":{},\"title\":{\"runs\":[{\"text\":\"$title\"}]}," +
            "\"ownerText\":{\"runs\":[{\"text\":\"$owner\",\"x\":1}]},\"lengthText\":{\"accessibility\":{},\"simpleText\":\"$length\"}}"

    private val page = "<html>" +
        result("aaaaaaaaaaa", "Something else", "Other channel", "3:10") +
        result("Ueas1HTVAM8", "Kerk in brand gestoken… wat treffen we binnen aan? \\u0026 meer", "Brandweer Lunteren", "29:02") +
        result("bbbbbbbbbbb", "Co2 Alarm", "Brandweer Lunteren", "1:24:20")

    @Test fun picksTheMatchingVideoNotTheFirst() {
        assertEquals("Ueas1HTVAM8", History.pickYouTubeId(page, "Kerk in brand gestoken… wat treffen we binnen aan? & meer", "Brandweer Lunteren", 1_741_000))
    }

    @Test fun channelAndLengthTogetherAreEnough() {
        assertEquals("Ueas1HTVAM8", History.pickYouTubeId(page, "A slightly different title", "Brandweer Lunteren", 1_742_500))
    }

    @Test fun noGoodMatchMeansNoVideo() {
        assertNull(History.pickYouTubeId(page, "Unknown video", "Brandweer Lunteren", 60_000))
    }
}
