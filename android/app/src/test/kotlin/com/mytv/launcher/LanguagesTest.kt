// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import com.mytv.launcher.ui.LANGUAGES
import com.mytv.launcher.ui.LANGUAGE_NAMES
import com.mytv.launcher.ui.languageFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** The language choice, the TV's language and the resource folders agree. */
class LanguagesTest {
    private val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }

    /** Same mapping as QUALIFIER in tools/strings.py. */
    private fun folder(tag: String) = when (tag) {
        "en" -> "values"; "id" -> "values-in"; "he" -> "values-iw"; "pt-BR" -> "values-pt-rBR"; "zh-CN" -> "values-zh-rCN"; "zh-TW" -> "values-zh-rTW"
        else -> "values-$tag"
    }

    @Test fun everyLanguageHasANameAndTexts() {
        assertEquals(LANGUAGES.toSet(), LANGUAGE_NAMES.keys)
        for (l in LANGUAGES) assertTrue("texts for $l", File(res, folder(l) + "/strings.xml").isFile)
    }

    @Test fun everyTextFolderIsOffered() {
        val folders = res.listFiles().orEmpty().filter { File(it, "strings.xml").isFile }.map { it.name }.toSet()
        assertEquals(folders, LANGUAGES.map(::folder).toSet())
    }

    /** The APK keeps only the languages in localeFilters: a language missing there silently shows English. */
    @Test fun everyLanguageIsKeptInTheApk() {
        val gradle = listOf("build.gradle.kts", "app/build.gradle.kts").map(::File).first { it.isFile }.readText()
        val block = gradle.substringAfter("localeFilters += listOf(").substringBefore(")")
        val kept = Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toSet()
        assertEquals(LANGUAGES.map { folder(it).removePrefix("values").removePrefix("-").ifEmpty { "en" } }.toSet(), kept)
    }

    @Test fun theTvsLanguageIsRecognised() {
        assertEquals("nl", languageFor(Locale.forLanguageTag("nl-BE")))
        assertEquals("pt-BR", languageFor(Locale.forLanguageTag("pt-BR")))
        assertEquals("pt", languageFor(Locale.forLanguageTag("pt-PT")))
        assertEquals("zh-TW", languageFor(Locale.forLanguageTag("zh-TW")))
        assertEquals("zh-TW", languageFor(Locale.forLanguageTag("zh-Hant-HK")))
        assertEquals("zh-CN", languageFor(Locale.forLanguageTag("zh-CN")))
        assertEquals("zh-CN", languageFor(Locale.forLanguageTag("zh-Hans-SG")))
        assertEquals("zh-CN", languageFor(Locale.forLanguageTag("zh")))
        assertNull(languageFor(Locale.forLanguageTag("ja-JP")))
        // Android reports Indonesian, Hebrew and Norwegian with their old codes.
        for ((tag, want) in listOf("in" to "id", "id" to "id", "iw" to "he", "he" to "he", "no" to "nb", "nb" to "nb")) {
            @Suppress("DEPRECATION")
            assertEquals(tag, want, languageFor(Locale(tag)))
        }
    }
}
