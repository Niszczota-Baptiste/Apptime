package fr.montemps.notification

import fr.montemps.data.prefs.PreferencesRepository
import fr.montemps.domain.ResumeRule
import fr.montemps.domain.TrackingStore
import fr.montemps.domain.report.Fmt
import fr.montemps.domain.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Aligne la notification de suivi sur l'état réellement enregistré. Appelé (via
 * `AppContainer.refreshOutputs`) après chaque écriture, au démarrage du processus, au redémarrage
 * du téléphone, à minuit et lors des changements d'heure ou de fuseau.
 *
 * Règles :
 * - aucune session active, réglage désactivé ou permission absente → pas de notification ;
 * - si l'utilisateur a balayé la notification de la session en cours, on ne la republie pas
 *   (elle réapparaît au prochain changement d'activité ou via « Réafficher » dans les réglages) ;
 * - une favorite qui est déjà l'activité active n'est pas proposée (l'action ne ferait rien).
 */
class NotificationController(
    private val store: TrackingStore,
    private val prefs: PreferencesRepository,
    private val notifier: TrackingNotifier,
    private val time: TimeSource,
) {
    private val mutex = Mutex()

    suspend fun sync() = mutex.withLock {
        val active = store.activeSession()
        val p = prefs.current()
        if (active == null || !p.notificationEnabled || p.dismissedNotificationSessionId == active.id) {
            notifier.cancel()
            return@withLock
        }
        val activity = store.activity(active.activityId)
        if (activity == null) {
            notifier.cancel()
            return@withLock
        }
        val zone = time.zone()
        val today = time.now().atZone(zone).toLocalDate()
        val byId = store.activities().associateBy { it.id }
        // Pendant une interruption (Pause, Repas…) : « Reprendre Travail » en première action.
        val resume = ResumeRule.resumeTarget(active, byId, store.recentEndedSessions(ResumeRule.LOOKBACK))
        // Android n'affiche que 3 actions : [Reprendre] + favorites + Arrêter.
        val favorites = p.favoriteIds
            .filter { it != active.activityId && it != resume?.id }
            .mapNotNull { byId[it] }
            .filter { !it.archived }
            .map { FavoriteAction(it.id, it.name) }
            .take(if (resume != null) 1 else 2)
        notifier.show(
            TrackingNotificationContent(
                sessionId = active.id,
                activityName = activity.name,
                activityColor = activity.color,
                start = active.start,
                startLabel = Fmt.startLabel(active.start, today, zone),
                favorites = favorites,
                hideNameOnLockScreen = p.hideNameOnLockScreen,
                resume = resume?.let { FavoriteAction(it.id, "Reprendre ${it.name}") },
                promoted = p.promotedNotification,
            ),
        )
    }

    suspend fun onUserDismissed(sessionId: Long) {
        val active = store.activeSession()
        if (active?.id == sessionId) prefs.setDismissedNotificationSession(sessionId)
    }

    suspend fun reshow() {
        prefs.setDismissedNotificationSession(null)
        sync()
    }
}
