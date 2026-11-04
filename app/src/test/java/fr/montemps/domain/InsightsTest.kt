package fr.montemps.domain

import fr.montemps.domain.model.Goal
import fr.montemps.domain.model.GoalPeriod
import fr.montemps.domain.model.Session
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.report.DaySummaryText
import fr.montemps.domain.report.Gap
import fr.montemps.domain.report.Gaps
import fr.montemps.domain.report.Goals
import fr.montemps.domain.report.ReportCalculator
import fr.montemps.domain.report.Trends
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class InsightsTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(s: String): Instant = LocalDateTime.parse(s).atZone(paris).toInstant()
    private var nextId = 1L
    private fun s(activity: String, start: String, end: String?) = Session(nextId++, activity, at(start), end?.let { at(it) }, paris.id)

    private val work = TrackedActivity("work", "Travail", 0, null, 0, false)
    private val pause = TrackedActivity("break", "Pause", 0, null, 1, false, interruption = true)
    private val meal = TrackedActivity("meal", "Repas", 0, null, 2, false, interruption = true)
    private val korean = TrackedActivity("korean", "Coréen", 0, null, 3, false, goal = Goal(30, GoalPeriod.DAY))
    private val sport = TrackedActivity("sport", "Sport", 0, null, 4, false, goal = Goal(180, GoalPeriod.WEEK))
    private val byId = listOf(work, pause, meal, korean, sport).associateBy { it.id }

    // --- Reprise ---------------------------------------------------------------------------

    @Test
    fun resumeTargetIsTheLastNonInterruptionActivity() {
        // Travail → Pause → Repas (en cours) : on propose de reprendre Travail, pas Pause.
        val recent = listOf(s("break", "2026-11-04T12:00", "2026-11-04T12:10"), s("work", "2026-11-04T09:00", "2026-11-04T12:00"))
        val active = s("meal", "2026-11-04T12:10", null)
        assertEquals(work, ResumeRule.resumeTarget(active, byId, recent))
        // Pendant Travail (pas une interruption) : rien à proposer.
        assertNull(ResumeRule.resumeTarget(s("work", "2026-11-04T13:00", null), byId, recent))
        // Activité archivée : ignorée.
        val archived = byId + ("work" to work.copy(archived = true))
        assertNull(ResumeRule.resumeTarget(active, archived, recent))
    }

    // --- Trous -----------------------------------------------------------------------------

    @Test
    fun gapsAreUntrackedPeriodsUntilNowOnly() {
        val from = at("2026-11-04T00:00")
        val to = at("2026-11-05T00:00")
        val now = at("2026-11-04T15:00")
        val sessions = listOf(
            s("work", "2026-11-04T08:00", "2026-11-04T12:00"),
            s("meal", "2026-11-04T12:30", "2026-11-04T13:00"),
            s("work", "2026-11-04T13:00", "2026-11-04T13:02"), // 2 min puis rien jusqu'à maintenant
        )
        assertEquals(
            listOf(
                Gap(from, at("2026-11-04T08:00")),
                Gap(at("2026-11-04T12:00"), at("2026-11-04T12:30")),
                Gap(at("2026-11-04T13:02"), now),
            ),
            Gaps.find(sessions, from, to, now),
        )
        // Seuil : les trous de moins de 45 min sont ignorés.
        assertEquals(2, Gaps.find(sessions, from, to, now, minimum = Duration.ofMinutes(45)).size)
        // Une session en cours couvre jusqu'à maintenant.
        val withActive = sessions + s("sport", "2026-11-04T13:02", null)
        assertEquals(2, Gaps.find(withActive, from, to, now).size)
    }

    // --- Objectifs -------------------------------------------------------------------------

    @Test
    fun goalsProgressPerDayAndOverAWeek() {
        val now = at("2026-11-04T20:00") // mercredi
        val sessions = listOf(
            s("korean", "2026-11-02T07:00", "2026-11-02T07:40"), // lundi : atteint
            s("korean", "2026-11-03T07:00", "2026-11-03T07:20"), // mardi : non
            s("korean", "2026-11-04T07:00", "2026-11-04T07:30"), // mercredi : atteint (pile)
            s("sport", "2026-11-03T18:00", "2026-11-03T19:30"),
        )
        val day = ReportCalculator.dayReport(sessions, LocalDate.of(2026, 11, 4), paris, now)
        val daily = Goals.progress(byId.values.toList(), day, GoalPeriod.DAY).single()
        assertEquals("korean", daily.activity.id)
        assertTrue(daily.reached)
        assertEquals(1.0, daily.ratio, 1e-9)

        val week = ReportCalculator.weekReport(sessions, LocalDate.of(2026, 11, 4), paris, now)
        val weekly = Goals.progress(byId.values.toList(), week.total, GoalPeriod.WEEK).single()
        assertEquals(Duration.ofMinutes(90), weekly.done)
        assertFalse(weekly.reached)
        val overWeek = Goals.dailyGoalsOverWeek(byId.values.toList(), week).single()
        assertEquals(2, overWeek.reachedDays)
        assertEquals(3, overWeek.countedDays) // lundi, mardi, mercredi commencés
    }

    // --- Tendances et séries ---------------------------------------------------------------

    @Test
    fun weekComparisonAtTheSameMomentAndDailyAverage() {
        val now = at("2026-11-04T12:00") // mercredi midi
        val sessions = listOf(
            // Semaine précédente : 2 h lundi + 3 h jeudi (après « le même moment »).
            s("work", "2026-10-26T09:00", "2026-10-26T11:00"),
            s("work", "2026-10-29T09:00", "2026-10-29T12:00"),
            // Cette semaine : 4 h lundi + 1 h mercredi.
            s("work", "2026-11-02T09:00", "2026-11-02T13:00"),
            s("sport", "2026-11-04T08:00", "2026-11-04T09:00"),
        )
        val week = ReportCalculator.weekReport(sessions, LocalDate.of(2026, 11, 4), paris, now)
        val previous = Trends.previousWeek(sessions, LocalDate.of(2026, 11, 4), paris, now)
        val cmp = Trends.compare(week.total, previous)
        assertEquals(Duration.ofHours(5), cmp.current)
        assertEquals(Duration.ofHours(2), cmp.previous) // le jeudi précédent n'est pas encore « atteint »
        assertEquals(Duration.ofHours(3), cmp.delta)
        assertEquals(listOf("work", "sport"), cmp.byActivity.map { it.activityId })
        // Moyenne sur 3 jours commencés (lundi, mardi, mercredi).
        assertEquals(Duration.ofMinutes(100), Trends.dailyAverage(week))
    }

    @Test
    fun streaksCountConsecutiveDaysAndRespectDailyGoals() {
        val now = at("2026-11-04T08:00")
        val sessions = listOf(
            // Travail : 3 jours d'affilée jusqu'à hier (rien encore aujourd'hui).
            s("work", "2026-11-01T09:00", "2026-11-01T10:00"),
            s("work", "2026-11-02T09:00", "2026-11-02T10:00"),
            s("work", "2026-11-03T09:00", "2026-11-03T10:00"),
            // Coréen (objectif 30 min) : 40 min, 20 min (non atteint), 35 min, 30 min aujourd'hui.
            s("korean", "2026-11-01T07:00", "2026-11-01T07:40"),
            s("korean", "2026-11-02T07:00", "2026-11-02T07:20"),
            s("korean", "2026-11-03T07:00", "2026-11-03T07:35"),
            s("korean", "2026-11-04T07:00", "2026-11-04T07:30"),
        )
        val streaks = Trends.streaks(sessions, byId.values.toList(), LocalDate.of(2026, 11, 4), paris, now)
        val workStreak = streaks.first { it.activity.id == "work" }
        assertEquals(3, workStreak.days)
        assertFalse(workStreak.includesToday)
        val koreanStreak = streaks.first { it.activity.id == "korean" }
        assertEquals(2, koreanStreak.days) // 3 et 4 novembre ; le 2 n'atteint pas l'objectif
        assertTrue(koreanStreak.includesToday)
    }

    @Test
    fun eveningSummaryText() {
        val now = at("2026-11-04T21:00")
        val sessions = listOf(
            s("work", "2026-11-04T09:00", "2026-11-04T13:10"),
            s("korean", "2026-11-04T07:00", "2026-11-04T07:30"),
            s("break", "2026-11-04T13:10", "2026-11-04T13:30"),
            s("meal", "2026-11-04T13:30", "2026-11-04T13:35"),
        )
        val report = ReportCalculator.dayReport(sessions, LocalDate.of(2026, 11, 4), paris, now)
        val (title, text) = DaySummaryText.build(report, byId.mapValues { it.value.name }, Goals.progress(byId.values.toList(), report, GoalPeriod.DAY))
        assertEquals("Aujourd'hui : 5 h 05 suivies", title)
        assertEquals("Travail 4 h 10 · Coréen 30 min · Pause 20 min\nObjectifs du jour : 1/1 atteints", text)
    }

    // --- Rappel d'inactivité ---------------------------------------------------------------

    @Test
    fun idleReminderTimingWindowsAndOncePerPeriod() {
        val delay = Duration.ofHours(2)
        fun next(lastEnd: String?, now: String, seen: String? = null, shown: Instant? = null) = IdleRule.next(
            lastEnd?.let { at(it) }, seen?.let { at(it) }, shown, at(now), paris, delay, 8 * 60, 22 * 60,
        )
        // Fin de session à 14:10 → rappel à 16:10.
        assertEquals(IdleCheck(at("2026-11-04T14:10"), at("2026-11-04T16:10")), next("2026-11-04T14:10", "2026-11-04T15:00"))
        // Nuit : fin à 21:30 → 23:30 hors plage → lendemain 08:00 + 2 h.
        assertEquals(at("2026-11-05T10:00"), next("2026-11-04T21:30", "2026-11-04T21:45")!!.triggerAt)
        // App ouverte à 15:30 : la période repart.
        assertEquals(at("2026-11-04T17:30"), next("2026-11-04T14:10", "2026-11-04T15:40", seen = "2026-11-04T15:30")!!.triggerAt)
        // Période déjà signalée : prochaine le lendemain.
        val shown = next("2026-11-04T14:10", "2026-11-04T16:20", shown = at("2026-11-04T14:10"))!!
        assertEquals(at("2026-11-05T10:00"), shown.triggerAt)
        // Aucun historique : début de plage.
        assertEquals(at("2026-11-04T10:00"), next(null, "2026-11-04T07:00")!!.triggerAt)
    }
}
