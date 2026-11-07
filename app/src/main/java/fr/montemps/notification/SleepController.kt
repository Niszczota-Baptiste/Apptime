package fr.montemps.notification

import android.app.PendingIntent
import android.content.Context
import fr.montemps.data.prefs.PreferencesRepository
import fr.montemps.domain.ActivityCatalog
import fr.montemps.domain.CatalogResult
import fr.montemps.domain.TimeTracker
import fr.montemps.domain.TrackingStore
import fr.montemps.domain.TransitionResult
import fr.montemps.domain.model.CommandSource
import fr.montemps.domain.model.DefaultActivities
import fr.montemps.domain.model.Session
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.report.Fmt
import fr.montemps.domain.sleep.SleepSchedule
import fr.montemps.domain.time.TimeSource
import fr.montemps.domain.undoToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/**
 * Coucher et réveil automatiques (Réglages → Sommeil, désactivés par défaut).
 *
 * - Coucher : à l'heure choisie, Sommeil démarre (l'activité en cours se termine à cette heure),
 *   notification « Sommeil démarré » avec « Annuler ».
 * - Réveil : si Sommeil est en cours, il s'arrête à l'heure choisie ; notification « Bonjour » avec
 *   la durée, deux favorites et « Je dors encore ».
 *
 * Les changements passent par [TimeTracker] (mêmes règles que partout) ; une alarme en retard
 * applique l'heure prévue, mais jamais par-dessus une action plus récente de l'utilisateur.
 * Chaque échéance n'est traitée qu'une fois.
 */
class SleepController(
    private val context: Context,
    private val store: TrackingStore,
    private val prefs: PreferencesRepository,
    private val tracker: TimeTracker,
    private val catalog: ActivityCatalog,
    private val notifier: TrackingNotifier,
    private val time: TimeSource,
    private val alarms: AlarmScheduler,
) {
    private val mutex = Mutex()

    suspend fun reschedule() = mutex.withLock {
        val p = prefs.current()
        val now = time.now()
        val zone = time.zone()
        schedule(
            p.bedtimeEnabled,
            SleepSchedule.due(now, p.bedtimeHandledMs?.let(Instant::ofEpochMilli), SleepSchedule.BEDTIME_GRACE, zone, p.bedtimeMinutes, p.bedtimeDays),
        ) { TrackingIntents.bedtime(context, it) }
        schedule(
            p.wakeEnabled,
            SleepSchedule.due(now, p.wakeHandledMs?.let(Instant::ofEpochMilli), SleepSchedule.WAKE_GRACE, zone, p.wakeMinutes, p.wakeDays),
        ) { TrackingIntents.wake(context, it) }
    }

    private fun schedule(enabled: Boolean, due: Instant?, intent: (Instant?) -> PendingIntent) {
        if (!enabled || due == null) {
            alarms.cancel(intent(null))
            return
        }
        // Échéance déjà passée mais encore dans le délai de grâce : l'alarme part tout de suite.
        alarms.set(maxOf(due, time.now()), intent(due))
    }

    /** Coucher prévu à [at]. */
    suspend fun fireBedtime(at: Instant) {
        val go = mutex.withLock {
            val p = prefs.current()
            if (!p.bedtimeEnabled || !claim(at, p.bedtimeHandledMs)) return@withLock false
            prefs.setBedtimeHandled(at.toEpochMilli())
            Duration.between(at, time.now()) <= SleepSchedule.BEDTIME_GRACE
        }
        if (!go) return
        val sleep = sleepActivity() ?: return
        val active = store.activeSession()
        if (active?.activityId == sleep.id) return
        // Hors du verrou : le changement déclenche lui-même la reprogrammation des alarmes.
        val result = if (isNow(at)) tracker.switchTo(sleep.id, CommandSource.Schedule) else tracker.switchToAt(sleep.id, at, CommandSource.Schedule)
        if (result is TransitionResult.Started) {
            notifier.cancelSleep(TrackingNotifier.WAKE_ID)
            notifier.showBedtime(
                SleepContent(
                    title = "Sommeil démarré",
                    text = "Depuis ${Fmt.time(result.session.start, time.zone())} · bonne nuit.",
                    undoLabel = "Annuler",
                    undo = result.undoToken(),
                ),
            )
        }
    }

    /** Réveil prévu à [at] : seulement si Sommeil est toujours en cours. */
    suspend fun fireWake(at: Instant) {
        val session: Session = mutex.withLock {
            val p = prefs.current()
            if (!p.wakeEnabled || !claim(at, p.wakeHandledMs)) return@withLock null
            prefs.setWakeHandled(at.toEpochMilli())
            if (Duration.between(at, time.now()) > SleepSchedule.WAKE_GRACE) return@withLock null
            val sleep = SleepSchedule.sleepActivity(store.activities()) ?: return@withLock null
            store.activeSession()?.takeIf { it.activityId == sleep.id && at.isAfter(it.start) }
        } ?: return
        val end = if (at.isAfter(time.now())) time.now() else at
        val result = tracker.stopAt(session.id, end)
        if (result !is TransitionResult.Stopped) return
        notifier.cancelSleep(TrackingNotifier.BEDTIME_ID)
        val zone = time.zone()
        val stopped = result.session
        val slept = Duration.between(stopped.start, stopped.end ?: end)
        val byId = store.activities().associateBy { it.id }
        val favorites = prefs.current().favoriteIds
            .mapNotNull { byId[it] }
            .filter { !it.archived && it.id != session.activityId }
            .take(2)
            .map { FavoriteAction(it.id, it.name) }
        notifier.showWake(
            SleepContent(
                title = "Bonjour",
                text = "Sommeil : ${Fmt.short(slept)} (${Fmt.time(stopped.start, zone)} – ${Fmt.time(stopped.end ?: end, zone)}).",
                undoLabel = "Je dors encore",
                undo = result.undoToken(),
                actions = favorites,
            ),
        )
    }

    /** Échéance pas encore traitée et pas en avance (une alarme ne part jamais avant l'heure). */
    private fun claim(at: Instant, handledMs: Long?): Boolean {
        if (handledMs != null && !at.isAfter(Instant.ofEpochMilli(handledMs))) return false
        return !at.isAfter(time.now().plusSeconds(60))
    }

    private fun isNow(at: Instant) = !at.isBefore(time.now().minusSeconds(60))

    /**
     * Sommeil, rétablie si elle a été archivée (même identifiant : l'historique reste groupé), ou
     * recréée si elle n'existe plus (sauvegarde ancienne restaurée…). Le coucher automatique est
     * activé : l'activité qu'il démarre doit exister.
     */
    private suspend fun sleepActivity(): TrackedActivity? {
        val activities = store.activities()
        SleepSchedule.sleepActivity(activities)?.let { return it }
        val archived = activities.firstOrNull { it.id == DefaultActivities.SLEEP }
            ?: activities.firstOrNull { it.name.equals(SleepSchedule.NAME, ignoreCase = true) }
        if (archived != null) return (catalog.setArchived(archived.id, false) as? CatalogResult.Ok)?.activity
        val default = DefaultActivities.all.first { it.id == DefaultActivities.SLEEP }
        return (catalog.create(default.name, default.color, null) as? CatalogResult.Ok)?.activity
    }
}
