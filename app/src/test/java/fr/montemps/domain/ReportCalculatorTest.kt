package fr.montemps.domain

import fr.montemps.domain.model.Session
import fr.montemps.domain.report.Fmt
import fr.montemps.domain.report.ReportCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ReportCalculatorTest {
    private val paris = ZoneId.of("Europe/Paris")

    private fun at(text: String, zone: ZoneId = paris): Instant = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone).toInstant()
    private fun session(id: Long, activity: String, start: Instant, end: Instant?) =
        Session(id = id, activityId = activity, start = start, end = end, zoneId = paris.id)

    @Test
    fun sessionAcrossMidnightIsSplitBetweenDays() {
        val s = session(1, "work", at("2026-11-03T22:30"), at("2026-11-04T01:15"))
        val day1 = ReportCalculator.dayReport(listOf(s), LocalDate.of(2026, 11, 3), paris, at("2026-11-05T12:00"))
        val day2 = ReportCalculator.dayReport(listOf(s), LocalDate.of(2026, 11, 4), paris, at("2026-11-05T12:00"))
        assertEquals(Duration.ofMinutes(90), day1.tracked)
        assertEquals(Duration.ofMinutes(75), day2.tracked)
        assertEquals(s.duration(Instant.MAX), day1.tracked + day2.tracked)
    }

    @Test
    fun sessionSpanningSeveralDaysFillsMiddleDaysCompletely() {
        val s = session(1, "other", at("2026-11-01T20:00"), at("2026-11-04T06:00"))
        val now = at("2026-11-10T00:00")
        val week = ReportCalculator.weekReport(listOf(s), LocalDate.of(2026, 11, 2), paris, now)
        // Semaine du lundi 2 novembre : le 1er (dimanche) appartient à la semaine précédente.
        assertEquals(LocalDate.of(2026, 11, 2), week.weekStart)
        assertEquals(Duration.ofHours(24), week.days[0].report.tracked) // lundi 2
        assertEquals(Duration.ofHours(24), week.days[1].report.tracked) // mardi 3
        assertEquals(Duration.ofHours(6), week.days[2].report.tracked) // mercredi 4
        assertEquals(Duration.ofHours(54), week.total.tracked)
        assertTrue(week.days[0].report.isFullyTracked)
        val sunday = ReportCalculator.dayReport(listOf(s), LocalDate.of(2026, 11, 1), paris, now)
        assertEquals(Duration.ofHours(4), sunday.tracked)
    }

    @Test
    fun springForwardDayLasts23Hours() {
        // 29 mars 2026 : passage à l'heure d'été en France (02:00 → 03:00).
        val day = LocalDate.of(2026, 3, 29)
        val s = session(1, "work", at("2026-03-29T01:30"), at("2026-03-29T03:30"))
        val r = ReportCalculator.dayReport(listOf(s), day, paris, at("2026-04-01T00:00"))
        assertEquals(Duration.ofHours(23), r.periodLength)
        // 01:30 → 03:30 heure locale = 1 h réelle.
        assertEquals(Duration.ofHours(1), r.tracked)
    }

    @Test
    fun fallBackDayLasts25Hours() {
        // 25 octobre 2026 : retour à l'heure d'hiver (03:00 → 02:00).
        val day = LocalDate.of(2026, 10, 25)
        val start = at("2026-10-25T01:30")
        val end = start.plus(Duration.ofHours(3)) // 01:30 CEST → 03:30 CET = 3 h réelles
        val r = ReportCalculator.dayReport(listOf(session(1, "work", start, end)), day, paris, at("2026-10-27T00:00"))
        assertEquals(Duration.ofHours(25), r.periodLength)
        assertEquals(Duration.ofHours(3), r.tracked)
    }

    @Test
    fun timeZoneChangeRebucketsSameInstants() {
        // Session de 23:00 à 01:00 à Paris = 22:00 → 00:00 UTC (hiver).
        val s = session(1, "work", at("2026-11-03T23:00"), at("2026-11-04T01:00"))
        val now = at("2026-11-06T00:00")
        val parisDay = ReportCalculator.dayReport(listOf(s), LocalDate.of(2026, 11, 3), paris, now)
        val seoul = ZoneId.of("Asia/Seoul")
        val seoulDay = ReportCalculator.dayReport(listOf(s), LocalDate.of(2026, 11, 4), seoul, now)
        assertEquals(Duration.ofHours(1), parisDay.tracked)
        // À Séoul (UTC+9) toute la session tombe le 4 novembre (07:00 → 09:00).
        assertEquals(Duration.ofHours(2), seoulDay.tracked)
        // La durée totale ne dépend jamais du fuseau.
        assertEquals(Duration.ofHours(2), s.duration(now))
    }

    @Test
    fun activeSessionCountsUpToNowAndDayIsPartial() {
        val now = at("2026-11-04T10:00")
        val sessions = listOf(
            session(1, "work", at("2026-11-04T08:00"), at("2026-11-04T09:00")),
            session(2, "break", at("2026-11-04T09:00"), null),
        )
        val r = ReportCalculator.dayReport(sessions, LocalDate.of(2026, 11, 4), paris, now)
        assertEquals(Duration.ofHours(2), r.tracked)
        assertEquals(Duration.ofHours(10), r.elapsed)
        assertEquals(Duration.ofHours(8), r.untracked)
        assertTrue(r.includesActiveSession)
        assertFalse(r.isFullyTracked)
        assertEquals(0.5, r.totals.first { it.activityId == "work" }.shareOfTracked, 1e-9)
        assertEquals(1.0, r.totals.sumOf { it.shareOfTracked }, 1e-9)
    }

    @Test
    fun emptyAndFutureDays() {
        val now = at("2026-11-04T10:00")
        val r = ReportCalculator.dayReport(emptyList(), LocalDate.of(2026, 11, 5), paris, now)
        assertTrue(r.isEmpty)
        assertFalse(r.hasStarted)
        assertEquals(Duration.ZERO, r.elapsed)
    }

    @Test
    fun formatting() {
        assertEquals("01:23:45", Fmt.hms(Duration.ofSeconds(5025)))
        assertEquals("26:00:00", Fmt.hms(Duration.ofHours(26)))
        assertEquals("6 h 05", Fmt.short(Duration.ofMinutes(365)))
        assertEquals("45 min", Fmt.short(Duration.ofMinutes(45)))
        assertEquals("< 1 min", Fmt.short(Duration.ofSeconds(20)))
        assertEquals("0 min", Fmt.short(Duration.ZERO))
        assertEquals("hier 23:10", Fmt.startLabel(at("2026-11-03T23:10"), LocalDate.of(2026, 11, 4), paris))
        assertEquals("09:15", Fmt.startLabel(at("2026-11-04T09:15"), LocalDate.of(2026, 11, 4), paris))
    }
}
