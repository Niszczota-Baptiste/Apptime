package fr.montemps.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import fr.montemps.data.prefs.PreferencesRepository
import fr.montemps.domain.TrackingStore
import fr.montemps.domain.report.Fmt
import fr.montemps.domain.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/**
 * Alarmes inexactes de l'app (aucune permission « alarmes exactes », pas de réveil forcé de
 * l'appareil) : l'alarme peut être retardée de quelques minutes, ce qui suffit ici.
 */
class AlarmScheduler(context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun set(triggerAt: Instant, intent: PendingIntent) = alarms.set(AlarmManager.RTC, triggerAt.toEpochMilli(), intent)

    fun cancel(intent: PendingIntent) = alarms.cancel(intent)
}

/**
 * Rappel de session longue : si une session dure plus que le seuil réglé (10 h par défaut), une
 * notification demande si elle est toujours en cours. Une seule fois par session ; rien n'est
 * modifié sans action de l'utilisateur (« Continuer » ou correction de l'heure de fin dans l'app).
 */
class ReminderController(
    private val context: Context,
    private val store: TrackingStore,
    private val prefs: PreferencesRepository,
    private val notifier: TrackingNotifier,
    private val time: TimeSource,
    private val alarms: AlarmScheduler,
) {
    private val mutex = Mutex()

    /** (Re)programme le rappel de la session active ; appelé après chaque changement et au démarrage. */
    suspend fun reschedule() = mutex.withLock {
        val active = store.activeSession()
        val p = prefs.current()
        if (active == null || !p.longSessionReminder || p.acknowledgedSessionId == active.id) {
            alarms.cancel(TrackingIntents.reminder(context, 0))
            notifier.cancelReminder()
            return@withLock
        }
        val due = active.start.plus(Duration.ofHours(p.forgottenThresholdHours.toLong()))
        if (time.now().isBefore(due)) {
            // Nouvelle session (ou seuil modifié) : un éventuel ancien rappel n'a plus lieu d'être.
            notifier.cancelReminder()
            alarms.set(due, TrackingIntents.reminder(context, active.id))
        }
        // Échéance déjà passée : pas de nouvelle alarme. L'app propose la correction à l'ouverture.
    }

    /** Déclenché par l'alarme : publie le rappel si la session visée est toujours en cours et longue. */
    suspend fun fire(sessionId: Long) = mutex.withLock {
        val active = store.activeSession() ?: return@withLock
        val p = prefs.current()
        if (active.id != sessionId || !p.longSessionReminder || p.acknowledgedSessionId == sessionId) return@withLock
        val now = time.now()
        val threshold = Duration.ofHours(p.forgottenThresholdHours.toLong())
        // Alarme en avance (heure système modifiée…) : reschedule() la reprogrammera.
        if (active.duration(now) < threshold) return@withLock
        val zone = time.zone()
        val name = store.activity(active.activityId)?.name ?: active.activityId
        notifier.showReminder(
            ReminderContent(
                sessionId = active.id,
                activityName = name,
                sinceLabel = Fmt.startLabel(active.start, now.atZone(zone).toLocalDate(), zone),
                durationLabel = Fmt.short(active.duration(now)),
                hideNameOnLockScreen = p.hideNameOnLockScreen,
            ),
        )
    }

    /** « Continuer » : la session est volontairement longue ; plus de rappel ni de question pour elle. */
    suspend fun acknowledge(sessionId: Long) {
        prefs.acknowledgeSession(sessionId)
        notifier.cancelReminder()
    }
}
