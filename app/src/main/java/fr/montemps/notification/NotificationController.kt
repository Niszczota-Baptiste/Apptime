package fr.montemps.notification

import fr.montemps.data.prefs.PreferencesRepository
import fr.montemps.domain.TrackingStore
import fr.montemps.domain.report.Fmt
import fr.montemps.domain.time.TimeSource
import fr.montemps.domain.model.TrackedActivity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Aligne la notification sur l'état réellement enregistré. Appelé après chaque écriture, au
 * démarrage du processus, au redémarrage du téléphone et lors des changements d'heure/fuseau.
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
        val favorites = p.favoriteIds
            .filter { it != active.activityId }
            .mapNotNull { store.activity(it) }
            .filter { !it.archived }
            .map { FavoriteAction(it.id, it.label(p.showIcons)) }
        notifier.show(
            TrackingNotificationContent(
                sessionId = active.id,
                activityName = activity.name,
                activityIcon = activity.icon?.takeIf { p.showIcons && it.isNotBlank() },
                activityColor = activity.color,
                start = active.start,
                startLabel = Fmt.startLabel(active.start, today, zone),
                favorites = favorites,
                hideNameOnLockScreen = p.hideNameOnLockScreen,
            ),
        )
    }

    private fun TrackedActivity.label(showIcons: Boolean): String =
        if (showIcons) listOfNotNull(icon?.takeIf { it.isNotBlank() }, name).joinToString(" ") else name

    suspend fun onUserDismissed(sessionId: Long) {
        val active = store.activeSession()
        if (active?.id == sessionId) prefs.setDismissedNotificationSession(sessionId)
    }

    suspend fun reshow() {
        prefs.setDismissedNotificationSession(null)
        sync()
    }
}
