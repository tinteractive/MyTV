// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import com.mytv.launcher.data.OnlinePhotos
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlinePhotosTest {
    private val sha = "a".repeat(64)
    private fun photo(id: String, file: String = "$id.webp", bytes: Int = 200_000, author: String = "Maker", source: String = "https://commons.wikimedia.org/wiki/File:X.jpg", hash: String = sha) =
        """{"id":"$id","name":"N","title":"T.jpg","file":"$file","bytes":$bytes,"sha256":"$hash","author":"$author","license":"CC BY-SA 4.0","source":"$source"}"""
    private fun list(vararg p: String, format: Int = 1) = """{"format":$format,"photos":[${p.joinToString(",")}]}"""

    @Test fun relativeFileIsNextToTheList() {
        val e = OnlinePhotos.parse(list(photo("polder")), "https://raw.githubusercontent.com/tinteractive/MyTV/main/photos/photos.json").single()
        assertEquals("https://raw.githubusercontent.com/tinteractive/MyTV/main/photos/polder.webp", e.url)
        assertEquals("T.jpg", e.title)
    }

    @Test fun refusesUnsafeOrIncompleteEntries() {
        val r = OnlinePhotos.parse(list(
            photo("ok"),
            photo("plain", file = "http://example.com/a.webp"),      // not https
            photo("../evil"),                                          // id used as file name
            photo("big", bytes = 5_000_000),                           // too large
            photo("nohash", hash = "xyz"),
            photo("nocredit", author = ""),                            // no credit, no photo
            photo("nosource", source = "javascript:alert(1)"),
            photo("ok"),                                               // duplicate id
        ))
        assertEquals(listOf("ok"), r.map { it.id })
    }

    @Test fun unknownFormatGivesNothing() {
        assertEquals(0, OnlinePhotos.parse(list(photo("a"), format = 2)).size)
    }
}
