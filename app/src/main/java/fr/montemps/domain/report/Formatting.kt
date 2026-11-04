package fr.montemps.domain.report

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Formatage français des durées, dates et heures (sans dépendance Android). */
object Fmt {
    val locale: Locale = Locale.FRANCE
    private val time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val dayLong: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", locale)
    private val dayMedium: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    private val dayShort: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", locale)

    /** « 01:23:45 » (les heures peuvent dépasser 24). */
    fun hms(d: Duration): String {
        val total = d.seconds.coerceAtLeast(0)
        return String.format(locale, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }

    /** « 6 h 05 », « 45 min », « < 1 min », « 0 min ». */
    fun short(d: Duration): String {
        val minutes = d.toMinutes().coerceAtLeast(0)
        return when {
            d.isZero || d.isNegative -> "0 min"
            minutes == 0L -> "< 1 min"
            minutes < 60 -> "$minutes min"
            else -> String.format(locale, "%d h %02d", minutes / 60, minutes % 60)
        }
    }

    /** Lecture vocale (accessibilité) : « 6 heures 5 minutes ». */
    fun spoken(d: Duration): String {
        val minutes = d.toMinutes().coerceAtLeast(0)
        val h = minutes / 60
        val m = minutes % 60
        val parts = buildList {
            if (h > 0) add("$h heure" + if (h > 1) "s" else "")
            if (m > 0 || h == 0L) add("$m minute" + if (m > 1) "s" else "")
        }
        return parts.joinToString(" ")
    }

    fun percent(share: Double): String = "${(share * 100).roundToInt()} %"

    fun time(instant: Instant, zone: ZoneId): String = time.format(instant.atZone(zone))

    fun dayLong(date: LocalDate): String = dayLong.format(date).replaceFirstChar { it.titlecase(locale) }
    fun dayMedium(date: LocalDate): String = dayMedium.format(date).replaceFirstChar { it.titlecase(locale) }
    fun dayShort(date: LocalDate): String = dayShort.format(date)

    /**
     * Heure de début relative au jour de référence : « 09:15 », « hier 23:10 »,
     * « 3 nov. 23:10 ».
     */
    fun startLabel(start: Instant, today: LocalDate, zone: ZoneId): String {
        val startDate = start.atZone(zone).toLocalDate()
        val t = time(start, zone)
        return when (startDate) {
            today -> t
            today.minusDays(1) -> "hier $t"
            else -> "${dayShort(startDate)} $t"
        }
    }
}
