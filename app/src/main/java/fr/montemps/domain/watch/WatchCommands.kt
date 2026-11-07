package fr.montemps.domain.watch

import fr.montemps.domain.TimeTracker
import fr.montemps.domain.TrackingStore
import fr.montemps.domain.TransitionResult
import fr.montemps.domain.model.CommandSource
import java.time.Instant

/**
 * Commande envoyée par la montre (voir docs/GARMIN.md). [at] : instant où l'utilisateur l'a
 * donnée, si elle a attendu dans la file hors ligne de la montre ; null = maintenant.
 */
sealed interface WatchCommand {
    /** La montre demande l'état actuel (ouverture de l'app, reconnexion). */
    data object Status : WatchCommand
    data class Switch(val activityId: String, val at: Instant? = null, val subId: String? = null) : WatchCommand
    data class Stop(val at: Instant? = null) : WatchCommand
    /** Reprendre l'activité interrompue (ou la dernière activité, si rien n'est suivi). */
    data class Resume(val at: Instant? = null) : WatchCommand
    /** Commande restée trop longtemps dans la file de la montre : ignorée. */
    data object Expired : WatchCommand
}

/**
 * Exécute les commandes de la montre avec les mêmes règles que l'app ([TimeTracker] : une seule
 * session active, jamais de chevauchement). Une commande différée est appliquée à son heure
 * d'origine si c'est encore possible, sinon refusée : le téléphone fait foi.
 *
 * Renvoie un court message à afficher sur la montre en cas de refus, sinon null.
 */
class WatchCommandHandler(
    private val tracker: TimeTracker,
    private val store: TrackingStore,
) {
    suspend fun handle(command: WatchCommand): String? = when (command) {
        WatchCommand.Status -> null
        WatchCommand.Expired -> "Commande trop ancienne, ignorée"
        is WatchCommand.Switch -> switch(command.activityId, command.at, command.subId)
        is WatchCommand.Stop -> stop(command.at)
        is WatchCommand.Resume -> resume(command.at)
    }

    private suspend fun switch(activityId: String, at: Instant?, subId: String?): String? {
        val result = if (at == null) {
            tracker.switchTo(activityId, CommandSource.Watch, subId)
        } else {
            tracker.switchToAt(activityId, at, CommandSource.Watch, subId)
        }
        return (result as? TransitionResult.Rejected)?.let { refusal(it.reason, delayed = at != null) }
    }

    private suspend fun stop(at: Instant?): String? {
        val active = store.activeSession() ?: return null
        val result = if (at == null) tracker.stop(CommandSource.Watch, active.id) else tracker.stopAt(active.id, at)
        return (result as? TransitionResult.Rejected)?.let { refusal(it.reason, delayed = at != null) }
    }

    /** Même règle que partout : l'activité interrompue (ou la dernière), avec sa sous-catégorie. */
    private suspend fun resume(at: Instant?): String? {
        if (tracker.resumePoint() == null) return "Rien à reprendre"
        val result = tracker.resume(CommandSource.Watch, at)
        return (result as? TransitionResult.Rejected)?.let { refusal(it.reason, delayed = at != null) }
    }

    companion object {
        /** Messages courts (écran de montre) ; [delayed] : commande venue de la file hors ligne. */
        fun refusal(reason: TransitionResult.Reason, delayed: Boolean): String = when (reason) {
            TransitionResult.Reason.UNKNOWN_ACTIVITY -> "Activité inconnue"
            TransitionResult.Reason.ARCHIVED_ACTIVITY -> "Activité archivée"
            TransitionResult.Reason.INVALID_SUB -> "Sous-catégorie indisponible"
            TransitionResult.Reason.CLOCK_BEFORE_LAST_RECORD -> "Heure du téléphone incohérente"
            TransitionResult.Reason.NOT_ACTIVE -> "Aucun suivi en cours"
            TransitionResult.Reason.START_IN_FUTURE -> "Heure dans le futur"
            TransitionResult.Reason.START_TOO_EARLY, TransitionResult.Reason.INVALID_END_TIME ->
                if (delayed) "Ignorée : changement plus récent sur le téléphone" else "Heure refusée"
        }
    }
}
