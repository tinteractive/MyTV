// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.staticCompositionLocalOf
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Languages MyTV ships, as language tags, in the order of the language choice (values/ is English and the fallback for
 * every other system language). Generated resource folders: tools/strings.py.
 */
val LANGUAGES = listOf(
    "id", "cs", "da", "de", "en", "es", "fr", "hr", "it", "hu", "nl", "nb", "pl", "pt-BR", "pt", "ro", "sk", "fi", "sv",
    "vi", "tr", "el", "bg", "ru", "sr", "uk", "he", "ar", "fa", "hi", "th", "ko", "zh-CN", "zh-TW",
)
/** Each language named in itself, for the language choice. */
val LANGUAGE_NAMES = mapOf(
    "id" to "Bahasa Indonesia", "cs" to "Čeština", "da" to "Dansk", "de" to "Deutsch", "en" to "English", "es" to "Español",
    "fr" to "Français", "hr" to "Hrvatski", "it" to "Italiano", "hu" to "Magyar", "nl" to "Nederlands", "nb" to "Norsk bokmål",
    "pl" to "Polski", "pt-BR" to "Português (Brasil)", "pt" to "Português (Portugal)", "ro" to "Română", "sk" to "Slovenčina",
    "fi" to "Suomi", "sv" to "Svenska", "vi" to "Tiếng Việt", "tr" to "Türkçe", "el" to "Ελληνικά", "bg" to "Български",
    "ru" to "Русский", "sr" to "Српски", "uk" to "Українська", "he" to "עברית", "ar" to "العربية", "fa" to "فارسی",
    "hi" to "हिन्दी", "th" to "ไทย", "ko" to "한국어", "zh-CN" to "中文（简体）", "zh-TW" to "中文（繁體）",
)

/** The TV's own language/region (not MyTV's override). */
fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0]

/**
 * The MyTV language for a locale, or null when MyTV does not have it. Android still reports some languages with their
 * old codes (in = Indonesian, iw = Hebrew, no = Norwegian); Portuguese and Chinese depend on the region or script
 * (Chinese: Traditional for Taiwan, Hong Kong and Macau, else Simplified).
 */
fun languageFor(l: Locale): String? {
    val lang = when (l.language) { "in" -> "id"; "iw" -> "he"; "no", "nn" -> "nb"; else -> l.language }
    return when (lang) {
        "pt" -> if (l.country == "BR") "pt-BR" else "pt"
        "zh" -> if (l.script == "Hant" || (l.script.isEmpty() && l.country in setOf("TW", "HK", "MO"))) "zh-TW" else "zh-CN"
        else -> lang.takeIf { it in LANGUAGES }
    }
}

/** The name of the TV's own language, for "Automatic (…)". */
fun systemLanguageName(): String = systemLocale().let { s -> languageFor(s)?.let { LANGUAGE_NAMES[it] } ?: s.getDisplayLanguage(s) }

/** The language MyTV uses: the chosen one, else the system language when supported, else English. */
fun effectiveLanguage(chosen: String?): String =
    chosen?.takeIf { it in LANGUAGES } ?: languageFor(systemLocale()) ?: "en"

/** The locale for MyTV's language: the TV's own when it is that language (keeps e.g. nl-BE), else the plain language. */
private fun myTvLocale(lang: String): Locale = systemLocale().let { sys -> if (languageFor(sys) == lang) sys else Locale.forLanguageTag(lang) }

/** A context whose resources and default locale follow MyTV's language (Android 11 has no per-app language). */
/** Sets the process default locale (dates, weekdays) to MyTV's language. */
fun applyMyTvLocale(c: Context) {
    Locale.setDefault(myTvLocale(effectiveLanguage(c.getSharedPreferences("mytv", Context.MODE_PRIVATE).getString(com.mytv.launcher.data.Prefs(c).p("language"), null))))
}

/** [base] in the language chosen in MyTV (or the TV's, or English): used by every screen MyTV shows. */
fun withMyTvLanguage(base: Context): Context =
    localizedContext(base, base.getSharedPreferences("mytv", Context.MODE_PRIVATE).getString(com.mytv.launcher.data.Prefs(base).p("language"), null))

fun localizedContext(base: Context, chosen: String?): Context {
    val locale = myTvLocale(effectiveLanguage(chosen))
    Locale.setDefault(locale)
    val cfg = Configuration(base.resources.configuration)
    cfg.setLocale(locale)
    return base.createConfigurationContext(cfg)
}

/** Current formatting locale (set by localizedContext). */
val LOC: Locale get() = Locale.getDefault()

/** Units and clock style chosen under Display ("auto" follows the TV). */
data class Units(val fahrenheit: Boolean, val h24: Boolean, val dataFahrenheit: Boolean = fahrenheit) {
    // Open-Meteo already delivers the chosen units; these only convert while a report in the other units is
    // still shown (right after switching, until the refresh arrives).
    fun temp(v: Double): Int = Math.round(when {
        fahrenheit == dataFahrenheit -> v; fahrenheit -> v * 9 / 5 + 32; else -> (v - 32) * 5 / 9 }).toInt()
    fun wind(v: Double): Int = Math.round(when {
        fahrenheit == dataFahrenheit -> v; fahrenheit -> v * 0.621371; else -> v / 0.621371 }).toInt()
    fun precip(v: Double): Double = when { fahrenheit == dataFahrenheit -> v; fahrenheit -> v / 25.4; else -> v * 25.4 }

    /** "16:52" or "4:52 PM". */
    fun time(t: LocalDateTime): String = t.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", LOC))
    /** Hour label for the hourly row: "16" or "4 PM". */
    fun hour(t: LocalDateTime): String = t.format(DateTimeFormatter.ofPattern(if (h24) "HH" else "h a", LOC))
    /** Clock digits without AM/PM ("16:52" / "4:52") and the AM/PM marker (or null). */
    fun clock(t: LocalDateTime): Pair<String, String?> =
        if (h24) t.format(DateTimeFormatter.ofPattern("HH:mm", LOC)) to null
        else t.format(DateTimeFormatter.ofPattern("h:mm", LOC)) to t.format(DateTimeFormatter.ofPattern("a", LOC))

    companion object {
        /** Countries that use Fahrenheit. */
        private val FAHRENHEIT = setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")
        fun autoFahrenheit() = systemLocale().country in FAHRENHEIT
        fun autoH24(ctx: Context) = android.text.format.DateFormat.is24HourFormat(ctx)

        /** The viewer's choice ("c"/"f"/auto, "24"/"12"/auto); [dataFahrenheit] says in which unit the weather data is. */
        fun of(ctx: Context, tempUnit: String, timeFormat: String, dataFahrenheit: Boolean? = null): Units {
            val f = wantsFahrenheit(tempUnit)
            return Units(f, when (timeFormat) { "24" -> true; "12" -> false; else -> autoH24(ctx) }, dataFahrenheit ?: f)
        }
        fun wantsFahrenheit(tempUnit: String) = when (tempUnit) { "f" -> true; "c" -> false; else -> autoFahrenheit() }
    }
}

val LocalUnits = staticCompositionLocalOf { Units(fahrenheit = false, h24 = true) }

/** Locale-appropriate date pattern from a skeleton, e.g. "EEEEdMMMM" -> "EEEE d MMMM" (nl) / "EEEE, MMMM d" (en). */
fun datePattern(skeleton: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(LOC, skeleton), LOC)
