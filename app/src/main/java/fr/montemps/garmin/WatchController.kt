package fr.montemps.garmin

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import fr.montemps.domain.time.TimeSource
import fr.montemps.domain.watch.WatchCommand
import fr.montemps.notification.TrackingIntents
import fr.montemps.surfaces.SurfaceStateLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Montre Garmin : envoie l'état de suivi à l'app Connect IQ et relaie ses commandes.
 *
 * - Activée seulement à la demande (Réglages → Montre Garmin). L'état « activé » est celui du
 *   service exporté du SDK (`IQGarminBindingService`) : désactivé, Garmin Connect ne peut ni s'y
 *   lier ni démarrer l'app ; il se lit sans attente au démarrage du processus.
 * - Les commandes de la montre passent, comme celles de la notification, par
 *   [TrackingIntents] → `TrackingActionReceiver`.
 * - L'état n'est envoyé que lorsqu'il change (ou à la demande de la montre) : la montre fait
 *   avancer le chronomètre elle-même.
 */
class WatchController(
    private val context: Context,
    private val link: WatchLink,
    private val loader: SurfaceStateLoader,
    private val time: TimeSource,
    private val scope: CoroutineScope,
) {
    private val component = ComponentName(context, BINDING_SERVICE)
    private val mutex = Mutex()

    @Volatile private var lastSent: String? = null
    @Volatile private var pendingMessage: String? = null
    @Volatile private var forceNext = false
    @Volatile private var lastJob: Job? = null

    val status: StateFlow<WatchLinkStatus> get() = link.status

    fun isEnabled(): Boolean =
        context.packageManager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        if (enabled) {
            start()
        } else {
            link.stop()
            lastSent = null
        }
    }

    /** Démarrage du processus (Application.onCreate) : avant toute livraison de message. */
    fun start() {
        if (!isEnabled()) return
        link.start(onMessage = ::onMessage, onWatchConnected = { deliver(WatchCommand.Status) })
    }

    private fun onMessage(payload: Any?) {
        WatchProtocol.parse(payload, time.now())?.let(::deliver)
    }

    private fun deliver(command: WatchCommand) {
        context.sendBroadcast(TrackingIntents.watchCommand(context, command))
    }

    /** Après une commande de la montre : renvoyer l'état, même inchangé, avec [message] éventuel. */
    fun reply(message: String?) {
        pendingMessage = message
        forceNext = true
    }

    /** Envoi en arrière-plan (ne bloque jamais l'app : la montre peut être lente ou absente). */
    fun requestSync(force: Boolean = false) {
        if (!isEnabled()) return
        if (force) forceNext = true
        lastJob = scope.launch { sync() }
    }

    /** Attend la fin du dernier envoi (récepteur : garder le processus en vie jusque-là). */
    suspend fun awaitSync() {
        lastJob?.join()
    }

    private suspend fun sync() = mutex.withLock {
        val state = loader.load()
        val fingerprint = WatchProtocol.fingerprint(state)
        if (!forceNext && fingerprint == lastSent) return@withLock
        val message = pendingMessage
        forceNext = false
        pendingMessage = null
        lastSent = if (link.send(WatchProtocol.encode(state, message))) fingerprint else null
        link.refreshStatus()
    }

    companion object {
        /** Service du SDK Connect IQ auquel Garmin Connect se lie pour livrer les messages. */
        const val BINDING_SERVICE = "com.garmin.android.connectiq.IQGarminBindingService"
        const val GARMIN_CONNECT_PACKAGE = "com.garmin.android.apps.connectmobile"
    }
}
