// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import com.mytv.launcher.R

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Moon phase, illumination and rise/set times, computed locally (works offline).
 * Port of the moon functions of SunCalc by Vladimir Agafonkin (BSD-2-Clause), https://github.com/mourner/suncalc
 */
data class MoonInfo(
    val phase: Double,              // 0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter
    val illumination: Double,       // 0..1
    val rise: LocalDateTime?,
    val set: LocalDateTime?,
    val nextFull: LocalDate,
    val nextNew: LocalDate,
) {
    val nameRes: Int get() = when {
        phase < 0.034 || phase > 0.966 -> R.string.moon_new
        phase < 0.216 -> R.string.moon_waxing_crescent
        phase < 0.284 -> R.string.moon_first_quarter
        phase < 0.466 -> R.string.moon_waxing_gibbous
        phase < 0.534 -> R.string.moon_full
        phase < 0.716 -> R.string.moon_waning_gibbous
        phase < 0.784 -> R.string.moon_last_quarter
        else -> R.string.moon_waning_crescent
    }
}

object Moon {
    private const val RAD = PI / 180
    private const val DAY_MS = 86_400_000.0
    private const val J1970 = 2440588.0
    private const val J2000 = 2451545.0
    private const val E = RAD * 23.4397

    private fun toDays(ms: Long) = ms / DAY_MS - 0.5 + J1970 - J2000
    private fun ra(l: Double, b: Double) = atan2(sin(l) * cos(E) - tan(b) * sin(E), cos(l))
    private fun dec(l: Double, b: Double) = asin(sin(b) * cos(E) + cos(b) * sin(E) * sin(l))
    private fun altitude(h: Double, phi: Double, d: Double) = asin(sin(phi) * sin(d) + cos(phi) * cos(d) * cos(h))
    private fun sidereal(d: Double, lw: Double) = RAD * (280.16 + 360.9856235 * d) - lw
    private fun refraction(h0: Double): Double { val h = if (h0 < 0) 0.0 else h0; return 0.0002967 / tan(h + 0.00312536 / (h + 0.08901179)) }

    private class Coords(val ra: Double, val dec: Double, val dist: Double)

    private fun sunCoords(d: Double): Coords {
        val m = RAD * (357.5291 + 0.98560028 * d)
        val c = RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        val l = m + c + RAD * 102.9372 + PI
        return Coords(ra(l, 0.0), dec(l, 0.0), 0.0)
    }

    private fun moonCoords(d: Double): Coords {
        val l0 = RAD * (218.316 + 13.176396 * d)
        val m = RAD * (134.963 + 13.064993 * d)
        val f = RAD * (93.272 + 13.229350 * d)
        val l = l0 + RAD * 6.289 * sin(m)
        val b = RAD * 5.128 * sin(f)
        return Coords(ra(l, b), dec(l, b), 385001 - 20905 * cos(m))
    }

    private fun moonAltitude(ms: Long, lat: Double, lng: Double): Double {
        val lw = RAD * -lng; val phi = RAD * lat; val d = toDays(ms)
        val c = moonCoords(d)
        val h = sidereal(d, lw) - c.ra
        val a = altitude(h, phi, c.dec)
        return a + refraction(a)
    }

    /** Returns (phase 0..1, illuminated fraction 0..1). */
    fun illumination(ms: Long): Pair<Double, Double> {
        val d = toDays(ms)
        val s = sunCoords(d); val m = moonCoords(d)
        val sdist = 149598000.0
        val phi = acos(sin(s.dec) * sin(m.dec) + cos(s.dec) * cos(m.dec) * cos(s.ra - m.ra))
        val inc = atan2(sdist * sin(phi), m.dist - sdist * cos(phi))
        val angle = atan2(cos(s.dec) * sin(s.ra - m.ra), sin(s.dec) * cos(m.dec) - cos(s.dec) * sin(m.dec) * cos(s.ra - m.ra))
        val fraction = (1 + cos(inc)) / 2
        val phase = 0.5 + 0.5 * inc * (if (angle < 0) -1 else 1) / PI
        return phase to fraction
    }

    /** Moonrise/moonset on [day] at the location (local zone); null when the moon does not rise/set that day. */
    /**
     * Sunrise and sunset (SunCalc's getTimes, sun 0.833° below the horizon); null on a day without one (polar).
     * Used when the weather comes from MET Norway, which has no sun times.
     */
    fun sunTimes(day: LocalDate, lat: Double, lng: Double, zone: ZoneId): Pair<LocalDateTime?, LocalDateTime?> {
        val j0 = 0.0009
        val lw = RAD * -lng; val phi = RAD * lat
        val d = toDays(day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
        val n = Math.round(d - j0 - lw / (2 * PI)).toDouble()
        val ds = j0 + lw / (2 * PI) + n
        val m = RAD * (357.5291 + 0.98560028 * ds)
        val c = RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        val l = m + c + RAD * 102.9372 + PI
        val decl = dec(l, 0.0)
        val jNoon = J2000 + ds + 0.0053 * sin(m) - 0.0069 * sin(2 * l)
        val cosW = (sin(-0.833 * RAD) - sin(phi) * sin(decl)) / (cos(phi) * cos(decl))
        if (cosW < -1 || cosW > 1) return null to null
        val w = acos(cosW)
        val a = j0 + (w + lw) / (2 * PI) + n
        val jSet = J2000 + a + 0.0053 * sin(m) - 0.0069 * sin(2 * l)
        val jRise = jNoon - (jSet - jNoon)
        fun local(j: Double) = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(((j + 0.5 - J1970) * DAY_MS).toLong()), zone)
        return local(jRise) to local(jSet)
    }

    fun times(day: LocalDate, lat: Double, lng: Double, zone: ZoneId): Pair<LocalDateTime?, LocalDateTime?> {
        val t0 = day.atStartOfDay(zone).toInstant().toEpochMilli()
        fun at(hours: Double) = t0 + (hours * 3_600_000).toLong()
        val hc = 0.133 * RAD
        var h0 = moonAltitude(t0, lat, lng) - hc
        var rise: Double? = null; var set: Double? = null
        var i = 1
        while (i <= 24) {
            val h1 = moonAltitude(at(i.toDouble()), lat, lng) - hc
            val h2 = moonAltitude(at(i + 1.0), lat, lng) - hc
            val a = (h0 + h2) / 2 - h1
            val b = (h2 - h0) / 2
            val xe = -b / (2 * a)
            val ye = (a * xe + b) * xe + h1
            val disc = b * b - 4 * a * h1
            var roots = 0; var x1 = 0.0; var x2 = 0.0
            if (disc >= 0) {
                val dx = sqrt(disc) / (abs(a) * 2)
                x1 = xe - dx; x2 = xe + dx
                if (abs(x1) <= 1) roots++
                if (abs(x2) <= 1) roots++
                if (x1 < -1) x1 = x2
            }
            if (roots == 1) { if (h0 < 0) rise = i + x1 else set = i + x1 }
            else if (roots == 2) { rise = i + (if (ye < 0) x2 else x1); set = i + (if (ye < 0) x1 else x2) }
            if (rise != null && set != null) break
            h0 = h2
            i += 2
        }
        fun toLocal(h: Double?) = h?.let { LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(at(it)), zone) }
        return toLocal(rise) to toLocal(set)
    }

    fun info(lat: Double, lng: Double, now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): MoonInfo {
        val ms = now.atZone(zone).toInstant().toEpochMilli()
        val (phase, frac) = illumination(ms)
        val (rise, set) = times(now.toLocalDate(), lat, lng, zone)
        // Next full / new moon: scan ahead hour by hour for the phase crossing 0.5 / wrapping past 1.0.
        var full: LocalDate? = null; var new: LocalDate? = null
        var prev = phase
        var h = 1
        while ((full == null || new == null) && h <= 31 * 24) {
            val t = ms + h * 3_600_000L
            val p = illumination(t).first
            val date = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), zone).toLocalDate()
            if (full == null && prev < 0.5 && p >= 0.5) full = date
            if (new == null && p < prev - 0.5) new = date
            prev = p; h++
        }
        return MoonInfo(phase, frac, rise, set, full ?: now.toLocalDate(), new ?: now.toLocalDate())
    }
}
