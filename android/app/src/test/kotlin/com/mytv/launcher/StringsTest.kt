// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every text exists in every language, with the same placeholders (a missing one shows English or crashes a format). */
class StringsTest {
    private val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
    /** values (English) first, then every values-<language> folder that has texts. */
    private val languages = listOf("values") + res.listFiles().orEmpty()
        .filter { it.name.startsWith("values-") && File(it, "strings.xml").isFile }.map { it.name }.sorted()

    private fun strings(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
    }

    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[sdf]|%(\\d+\\$)?\\.\\d+f|%0?\\d*d").findAll(s).map { it.value }.sorted().toList()

    @Test fun allLanguagesHaveTheSameKeys() {
        val english = strings("values").keys
        assertTrue(english.size > 300)
        assertTrue("languages found: $languages", languages.size >= 6)
        for (l in languages.drop(1)) assertEquals("keys of $l", english, strings(l).keys)
    }

    @Test fun placeholdersMatchEnglish() {
        val english = strings("values")
        for (l in languages.drop(1)) for ((k, v) in strings(l)) assertEquals("$l/$k", placeholders(english.getValue(k)), placeholders(v))
    }
}
