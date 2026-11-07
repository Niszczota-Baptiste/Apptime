package fr.montemps.domain.report

import fr.montemps.domain.model.Session
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class ActivityShare(
    val activityId: String,
    val duration: Duration,
    /** Part de cette activité dans le temps *suivi* de la période (0..1). */
    val shareOfTracked: Double,
    /**
     * Répartition par sous-catégorie (null = sans précision), de la plus longue à la plus courte.
     * Vide si aucune session de l'activité n'a de sous-catégorie sur la période.
     */
    val bySub: List<SubShare> = emptyList(),
)

data class SubShare(val subId: String?, val duration: Duration)

/**
 * Bilan d'une période [from, to).
 *
 * - [periodLength] : durée réelle de la période (une journée de changement d'heure dure 23 h ou 25 h).
 * - [elapsed] : partie de la période déjà écoulée à l'instant du calcul.
 * - [tracked] : temps effectivement suivi ; le reste de [elapsed] est du temps *non suivi*,
 *   jamais attribué à une activité.
 */
data class PeriodReport(
    val from: Instant,
    val to: Instant,
    val totals: List<ActivityShare>,
    val tracked: Duration,
    val periodLength: Duration,
    val elapsed: Duration,
    val includesActiveSession: Boolean,
) {
    val untracked: Duration get() = (elapsed - tracked).let { if (it.isNegative) Duration.ZERO else it }
    val isEmpty: Boolean get() = tracked.isZero
    val hasStarted: Boolean get() = !elapsed.isZero
    /** Vrai si toute la partie écoulée de la période est couverte par des sessions. */
    val isFullyTracked: Boolean get() = hasStarted && untracked < Duration.ofMinutes(1)
}

data class DayReport(val date: LocalDate, val report: PeriodReport)

data class WeekReport(val weekStart: LocalDate, val days: List<DayReport>, val total: PeriodReport)

/**
 * Calculs de bilans, purs et déterministes.
 *
 * Convention : les jours et semaines sont découpés selon le fuseau horaire passé en paramètre
 * (dans l'app : le fuseau *courant* du téléphone). Semaine ISO, du lundi 00:00 au lundi suivant
 * 00:00. Une session traversant minuit est répartie entre les jours concernés sans être découpée
 * dans la base. La session active compte jusqu'à `now`.
 */
object ReportCalculator {

    fun dayBounds(date: LocalDate, zone: ZoneId): Pair<Instant, Instant> =
        date.atStartOfDay(zone).toInstant() to date.plusDays(1).atStartOfDay(zone).toInstant()

    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun weekBounds(anyDayInWeek: LocalDate, zone: ZoneId): Pair<Instant, Instant> {
        val start = weekStart(anyDayInWeek)
        return start.atStartOfDay(zone).toInstant() to start.plusWeeks(1).atStartOfDay(zone).toInstant()
    }

    /** Portion de [session] comprise dans [from, to), la session active s'arrêtant à [now]. */
    fun clippedDuration(session: Session, from: Instant, to: Instant, now: Instant): Duration {
        val effectiveEnd = session.end ?: now
        val start = maxOf(session.start, from)
        val end = minOf(effectiveEnd, to)
        return if (end.isAfter(start)) Duration.between(start, end) else Duration.ZERO
    }

    fun report(sessions: List<Session>, from: Instant, to: Instant, now: Instant): PeriodReport {
        val byActivity = LinkedHashMap<String, Duration>()
        val bySub = HashMap<String, LinkedHashMap<String?, Duration>>()
        var includesActive = false
        for (s in sessions) {
            val d = clippedDuration(s, from, to, now)
            if (d.isZero) continue
            if (s.isActive) includesActive = true
            byActivity[s.activityId] = (byActivity[s.activityId] ?: Duration.ZERO) + d
            val subs = bySub.getOrPut(s.activityId) { LinkedHashMap() }
            subs[s.subId] = (subs[s.subId] ?: Duration.ZERO) + d
        }
        val tracked = byActivity.values.fold(Duration.ZERO, Duration::plus)
        val totals = byActivity.entries
            .map { (id, d) ->
                val subs = bySub[id].orEmpty()
                ActivityShare(
                    activityId = id,
                    duration = d,
                    shareOfTracked = if (tracked.isZero) 0.0 else d.toMillis().toDouble() / tracked.toMillis(),
                    bySub = if (subs.keys.all { it == null }) emptyList() else subs.map { (sub, sd) -> SubShare(sub, sd) }
                        .sortedWith(compareByDescending<SubShare> { it.duration }.thenBy { it.subId == null }),
                )
            }
            .sortedWith(compareByDescending<ActivityShare> { it.duration }.thenBy { it.activityId })
        val periodLength = Duration.between(from, to)
        val elapsed = when {
            !now.isAfter(from) -> Duration.ZERO
            now.isBefore(to) -> Duration.between(from, now)
            else -> periodLength
        }
        return PeriodReport(from, to, totals, tracked, periodLength, elapsed, includesActive)
    }

    fun dayReport(sessions: List<Session>, date: LocalDate, zone: ZoneId, now: Instant): PeriodReport {
        val (from, to) = dayBounds(date, zone)
        return report(sessions, from, to, now)
    }

    fun weekReport(sessions: List<Session>, anyDayInWeek: LocalDate, zone: ZoneId, now: Instant): WeekReport {
        val start = weekStart(anyDayInWeek)
        val days = (0L until 7L).map { offset ->
            val date = start.plusDays(offset)
            DayReport(date, dayReport(sessions, date, zone, now))
        }
        val (from, to) = weekBounds(start, zone)
        return WeekReport(start, days, report(sessions, from, to, now))
    }
}
