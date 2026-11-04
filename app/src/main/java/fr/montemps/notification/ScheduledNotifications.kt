package fr.montemps.notification

import android.content.Context
import fr.montemps.data.prefs.AppPreferences
import fr.montemps.data.prefs.PreferencesRepository
import fr.montemps.domain.IdleCheck
import fr.montemps.domain.IdleRule
import fr.montemps.domain.ResumeRule
import fr.montemps.domain.TrackingStore
import fr.montemps.domain.model.GoalPeriod
import fr.montemps.domain.report.DaySummaryText
import fr.montemps.domain.report.Fmt
import fr.montemps.domain.report.Goals
import fr.montemps.domain.report.ReportCalculator
import fr.montemps.domain.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Rappel « rien n'est suivi » (optionnel). Une seule notification par période d'inactivité, et
 * seulement dans la plage horaire choisie. Ouvrir l'app fait repartir la période (l'utilisateur a
 * vu que rien n'était suivi). Démarrer une activité retire la notification.
 */
class IdleReminderController(
    private val context: Context,
    private val store: TrackingStore,
    private val prefs: PreferencesRepository,
    private val notifier: TrackingNotifier,
    private val time: TimeSource,
    private val alarms: AlarmScheduler,
) {
    private val mutex = Mutex()

    suspend fun reschedule() = mutex.withLock { schedule(prefs.current()) }

    suspend fun fire() = mutex.withLock {
        val p = prefs.current()
        if (!p.idleReminderEnabled || store.activeSession() != null) return@withLock
        val check = next(p) ?: return@withLock
        val now = time.now()
        if (check.triggerAt.isAfter(now)) {
            schedule(p) // alarme en avance : reprogrammer
            return@withLock
        }
        val byId = store.activities().associateBy { it.id }
        val last = ResumeRule.lastWorkActivity(store.recentEndedSessions(ResumeRule.LOOKBACK), byId, exclude = null)
        val actions = (listOfNotNull(last) + p.favoriteIds.mapNotNull { byId[it] })
            .filter { !it.archived }
            .distinctBy { it.id }
            .take(3)
            .map { FavoriteAction(it.id, it.name) }
        val zone = time.zone()
        notifier.showIdle(IdleContent(Fmt.startLabel(check.idleStart, now.atZone(zone).toLocalDate(), zone), actions))
        prefs.setIdleShownStart(check.idleStart.toEpochMilli())
        schedule(prefs.current())
    }

    /** L'app est au premier plan : la période d'inactivité repart de maintenant. */
    suspend fun onAppOpened() = mutex.withLock {
        prefs.setIdleSeenAt(time.now().toEpochMilli())
        notifier.cancelIdle()
        schedule(prefs.current())
    }

    private suspend fun schedule(p: AppPreferences) {
        val intent = TrackingIntents.idleReminder(context)
        if (!p.idleReminderEnabled || store.activeSession() != null) {
            alarms.cancel(intent)
            notifier.cancelIdle()
            return
        }
        val check = next(p)
        if (check == null) alarms.cancel(intent) else alarms.set(maxOf(check.triggerAt, time.now()), intent)
    }

    private suspend fun next(p: AppPreferences): IdleCheck? = IdleRule.next(
        lastEnd = store.latestEnd(),
        seenAt = p.idleSeenAtMs?.let(Instant::ofEpochMilli),
        shownStart = p.idleShownStartMs?.let(Instant::ofEpochMilli),
        now = time.now(),
        zone = time.zone(),
        delay = Duration.ofHours(p.idleReminderHours.toLong()),
        windowStartMinutes = p.idleWindowStartMinutes,
        windowEndMinutes = p.idleWindowEndMinutes,
    )
}

/**
 * Résumé du soir (optionnel) : une notification silencieuse par jour, à l'heure choisie, si
 * quelque chose a été suivi. Un appui ouvre les bilans.
 */
class EveningSummaryController(
    private val context: Context,
    private val store: TrackingStore,
    private val prefs: PreferencesRepository,
    private val notifier: TrackingNotifier,
    private val time: TimeSource,
    private val alarms: AlarmScheduler,
) {
    private val mutex = Mutex()

    suspend fun reschedule() = mutex.withLock { schedule(prefs.current()) }

    suspend fun fire() = mutex.withLock {
        val p = prefs.current()
        if (!p.eveningSummaryEnabled) return@withLock
        val now = time.now()
        val zone = time.zone()
        val today = now.atZone(zone).toLocalDate()
        if (p.summaryShownEpochDay == today.toEpochDay() || now.isBefore(at(today.toEpochDay(), p.eveningSummaryMinutes, zone))) {
            schedule(p)
            return@withLock
        }
        val (from, to) = ReportCalculator.dayBounds(today, zone)
        val report = ReportCalculator.report(store.sessionsOverlapping(from, to), from, to, now)
        if (!report.isEmpty) {
            val activities = store.activities()
            val (title, text) = DaySummaryText.build(
                report,
                activities.associate { it.id to it.name },
                Goals.progress(activities, report, GoalPeriod.DAY),
            )
            notifier.showSummary(SummaryContent(title, text))
        }
        prefs.setSummaryShownDay(today.toEpochDay())
        schedule(prefs.current())
    }

    private fun schedule(p: AppPreferences) {
        val intent = TrackingIntents.eveningSummary(context)
        if (!p.eveningSummaryEnabled) {
            alarms.cancel(intent)
            return
        }
        alarms.set(nextOccurrence(time.now(), time.zone(), p.eveningSummaryMinutes, p.summaryShownEpochDay), intent)
    }

    companion object {
        /** Prochaine heure de résumé strictement future, en sautant un jour déjà résumé. */
        fun nextOccurrence(now: Instant, zone: ZoneId, minutes: Int, shownEpochDay: Long?): Instant {
            var day = now.atZone(zone).toLocalDate().toEpochDay()
            repeat(3) {
                val t = at(day, minutes, zone)
                if (t.isAfter(now) && day != shownEpochDay) return t
                day++
            }
            return at(day, minutes, zone)
        }

        private fun at(epochDay: Long, minutes: Int, zone: ZoneId): Instant =
            java.time.LocalDate.ofEpochDay(epochDay).atTime(LocalTime.of(minutes / 60, minutes % 60)).atZone(zone).toInstant()
    }
}
