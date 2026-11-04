package fr.montemps.domain.report

import fr.montemps.domain.model.Goal
import fr.montemps.domain.model.GoalPeriod
import fr.montemps.domain.model.Session
import fr.montemps.domain.model.TrackedActivity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Durée suivie pour une activité dans un bilan (zéro si absente). */
fun PeriodReport.durationOf(activityId: String): Duration =
    totals.firstOrNull { it.activityId == activityId }?.duration ?: Duration.ZERO

// ---------------------------------------------------------------------------------------------
// Périodes non suivies (« trous »)
// ---------------------------------------------------------------------------------------------

data class Gap(val start: Instant, val end: Instant) {
    val duration: Duration get() = Duration.between(start, end)
}

object Gaps {
    /**
     * Périodes non suivies de [from, min(to, now)) d'au moins [minimum]. La session en cours
     * compte jusqu'à [now] ; le futur n'est jamais un « trou ».
     */
    fun find(
        sessions: List<Session>,
        from: Instant,
        to: Instant,
        now: Instant,
        minimum: Duration = Duration.ofMinutes(1),
    ): List<Gap> {
        val limit = minOf(to, now)
        if (!limit.isAfter(from)) return emptyList()
        val intervals = sessions
            .map { it.start to (it.end ?: now) }
            .filter { (s, e) -> e.isAfter(from) && s.isBefore(limit) }
            .sortedBy { it.first }
        val gaps = mutableListOf<Gap>()
        var cursor = from
        for ((s, e) in intervals) {
            val start = maxOf(s, from)
            if (start.isAfter(cursor) && Duration.between(cursor, start) >= minimum) gaps += Gap(cursor, start)
            cursor = maxOf(cursor, minOf(e, limit))
        }
        if (limit.isAfter(cursor) && Duration.between(cursor, limit) >= minimum) gaps += Gap(cursor, limit)
        return gaps
    }
}

// ---------------------------------------------------------------------------------------------
// Objectifs
// ---------------------------------------------------------------------------------------------

data class GoalProgress(val activity: TrackedActivity, val goal: Goal, val done: Duration) {
    val ratio: Double get() = done.toMillis().toDouble() / goal.target.toMillis()
    val reached: Boolean get() = done >= goal.target
}

/** Objectif quotidien vu sur une semaine : jours atteints parmi les jours déjà commencés. */
data class DailyGoalWeek(val activity: TrackedActivity, val goal: Goal, val reachedDays: Int, val countedDays: Int)

object Goals {
    fun progress(activities: List<TrackedActivity>, report: PeriodReport, period: GoalPeriod): List<GoalProgress> =
        activities
            .filter { !it.archived && it.goal?.period == period }
            .sortedBy { it.sortOrder }
            .map { GoalProgress(it, it.goal!!, report.durationOf(it.id)) }

    fun dailyGoalsOverWeek(activities: List<TrackedActivity>, week: WeekReport): List<DailyGoalWeek> {
        val started = week.days.filter { it.report.hasStarted }
        return activities
            .filter { !it.archived && it.goal?.period == GoalPeriod.DAY }
            .sortedBy { it.sortOrder }
            .map { a ->
                val reached = started.count { it.report.durationOf(a.id) >= a.goal!!.target }
                DailyGoalWeek(a, a.goal!!, reached, started.size)
            }
    }
}

// ---------------------------------------------------------------------------------------------
// Tendances et séries
// ---------------------------------------------------------------------------------------------

data class ActivityDelta(val activityId: String, val current: Duration, val previous: Duration) {
    val delta: Duration get() = current - previous
}

data class PeriodComparison(val current: Duration, val previous: Duration, val byActivity: List<ActivityDelta>) {
    val delta: Duration get() = current - previous
}

/** Série en cours : jours consécutifs où l'activité a été suivie (objectif quotidien atteint s'il existe). */
data class Streak(val activity: TrackedActivity, val days: Int, val includesToday: Boolean)

object Trends {
    /**
     * Compare deux bilans. Pour la semaine en cours, l'appelant fournit la semaine précédente
     * arrêtée au même moment (comparaison à durée écoulée égale).
     */
    fun compare(current: PeriodReport, previous: PeriodReport): PeriodComparison {
        val ids = (current.totals.map { it.activityId } + previous.totals.map { it.activityId }).distinct()
        val deltas = ids
            .map { ActivityDelta(it, current.durationOf(it), previous.durationOf(it)) }
            .filter { !it.delta.isZero }
            .sortedByDescending { it.delta.abs() }
        return PeriodComparison(current.tracked, previous.tracked, deltas)
    }

    /**
     * Semaine précédant celle de [anchor], arrêtée au même moment local que [now] (même jour de la
     * semaine, même heure, changement d'heure compris). Pour une semaine passée, la semaine
     * précédente est complète. Les sessions postérieures à ce moment ne sont pas comptées.
     */
    fun previousWeek(sessions: List<Session>, anchor: LocalDate, zone: ZoneId, now: Instant): PeriodReport {
        val (from, to) = ReportCalculator.weekBounds(anchor.minusWeeks(1), zone)
        val cutoff = now.atZone(zone).minusWeeks(1).toInstant()
        val end = minOf(to, maxOf(from, cutoff))
        return ReportCalculator.report(sessions, from, end, cutoff)
    }

    /** Moyenne par jour écoulé de la semaine (le jour en cours compte). */
    fun dailyAverage(week: WeekReport): Duration {
        val days = week.days.count { it.report.hasStarted }
        return if (days == 0) Duration.ZERO else week.total.tracked.dividedBy(days.toLong())
    }

    /**
     * Séries en cours d'au moins 2 jours. Un jour compte si l'activité y a été suivie au moins
     * 1 min (ou si son objectif quotidien est atteint). Aujourd'hui, encore incomplet, ne casse pas
     * la série : on compte alors à partir d'hier.
     */
    fun streaks(
        sessions: List<Session>,
        activities: List<TrackedActivity>,
        today: LocalDate,
        zone: ZoneId,
        now: Instant,
        maxDays: Int = 366,
    ): List<Streak> {
        val earliest = today.minusDays(maxDays.toLong())
        val perDay = HashMap<LocalDate, HashMap<String, Duration>>()
        for (s in sessions) {
            val end = s.end ?: now
            if (!end.isAfter(s.start)) continue
            var day = maxOf(s.start.atZone(zone).toLocalDate(), earliest)
            val lastDay = minOf(end.atZone(zone).toLocalDate(), today)
            while (!day.isAfter(lastDay)) {
                val (from, to) = ReportCalculator.dayBounds(day, zone)
                val d = ReportCalculator.clippedDuration(s, from, to, now)
                if (!d.isZero) perDay.getOrPut(day) { HashMap() }.merge(s.activityId, d) { a, b -> a.plus(b) }
                day = day.plusDays(1)
            }
        }
        return activities
            .filter { !it.archived }
            .mapNotNull { a ->
                val threshold = a.goal?.takeIf { it.period == GoalPeriod.DAY }?.target ?: Duration.ofMinutes(1)
                fun counts(d: LocalDate) = (perDay[d]?.get(a.id) ?: Duration.ZERO) >= threshold
                val includesToday = counts(today)
                var day = if (includesToday) today else today.minusDays(1)
                var count = 0
                while (count < maxDays && counts(day)) {
                    count++
                    day = day.minusDays(1)
                }
                if (count >= 2) Streak(a, count, includesToday) else null
            }
            .sortedWith(compareByDescending<Streak> { it.days }.thenBy { it.activity.sortOrder })
    }
}

// ---------------------------------------------------------------------------------------------
// Résumé du soir
// ---------------------------------------------------------------------------------------------

object DaySummaryText {
    /** Titre et texte du résumé d'une journée (texte : 3 activités principales, puis objectifs). */
    fun build(report: PeriodReport, names: Map<String, String>, goals: List<GoalProgress>): Pair<String, String> {
        val title = "Aujourd'hui : ${Fmt.short(report.tracked)} suivies"
        val top = report.totals.take(3).joinToString(" · ") { "${names[it.activityId] ?: it.activityId} ${Fmt.short(it.duration)}" }
        val goalLine = goals.takeIf { it.isNotEmpty() }?.let { g -> "Objectifs du jour : ${g.count { it.reached }}/${g.size} atteints" }
        return title to listOfNotNull(top, goalLine).joinToString("\n")
    }
}
