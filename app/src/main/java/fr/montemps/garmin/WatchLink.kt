package fr.montemps.garmin

import kotlinx.coroutines.flow.StateFlow

/** État de la liaison avec la montre, affiché dans les réglages. */
sealed interface WatchLinkStatus {
    data object Off : WatchLinkStatus
    data object Starting : WatchLinkStatus
    data object GarminConnectMissing : WatchLinkStatus
    data object GarminConnectOutdated : WatchLinkStatus
    data object NoWatch : WatchLinkStatus
    data class Disconnected(val watch: String) : WatchLinkStatus
    data class AppMissing(val watch: String) : WatchLinkStatus
    data class Ready(val watch: String) : WatchLinkStatus
    data class Failed(val reason: String) : WatchLinkStatus

    val label: String
        get() = when (this) {
            Off -> "Désactivée"
            Starting -> "Connexion à Garmin Connect…"
            GarminConnectMissing -> "Garmin Connect n'est pas installé sur ce téléphone."
            GarminConnectOutdated -> "Garmin Connect doit être mis à jour."
            NoWatch -> "Aucune montre associée dans Garmin Connect."
            is Disconnected -> "$watch : non connectée (Bluetooth, portée)."
            is AppMissing -> "$watch : l'app Mon Temps n'est pas installée sur la montre."
            is Ready -> "$watch : connectée."
            is Failed -> "Liaison impossible : $reason"
        }
}

/**
 * Transport vers la montre. L'implémentation réelle ([ConnectIqLink]) passe par l'application
 * Garmin Connect ; les tests utilisent un faux.
 */
interface WatchLink {
    val status: StateFlow<WatchLinkStatus>

    /**
     * Démarre la liaison. [onMessage] reçoit chaque message de l'app montre ; [onWatchConnected]
     * est appelé quand une montre se (re)connecte (l'état doit alors lui être renvoyé).
     */
    fun start(onMessage: (Any?) -> Unit, onWatchConnected: () -> Unit)

    fun stop()

    /** Envoie [message] aux montres connectées ; true si au moins une l'a reçu. */
    suspend fun send(message: Map<String, Any?>): Boolean

    /** Recalcule [status] (montre connectée, app installée…). */
    fun refreshStatus()
}
