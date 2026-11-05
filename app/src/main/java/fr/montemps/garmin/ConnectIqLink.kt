package fr.montemps.garmin

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Liaison réelle avec la montre via le SDK Connect IQ pour Android : l'application Garmin Connect
 * sert de relais Bluetooth. Aucune permission n'est ajoutée à l'app.
 *
 * Sécurité : les messages de la montre ne sont acceptés que par la voie « service lié » — Garmin
 * Connect se lie à `IQGarminBindingService`, et le SDK vérifie la signature de l'appelant avant
 * de transmettre. Aucun écouteur par montre n'est enregistré, si bien que les diffusions
 * (que n'importe quelle app pourrait imiter) restent sans effet.
 */
class ConnectIqLink(
    private val context: Context,
    private val appId: String = WatchProtocol.APP_ID,
) : WatchLink {
    private val _status = MutableStateFlow<WatchLinkStatus>(WatchLinkStatus.Off)
    override val status: StateFlow<WatchLinkStatus> = _status.asStateFlow()

    private val ready = MutableStateFlow(false)
    private val main = Handler(Looper.getMainLooper())
    private var connectIq: ConnectIQ? = null
    private var onWatchConnected: () -> Unit = {}

    override fun start(onMessage: (Any?) -> Unit, onWatchConnected: () -> Unit) {
        // Le SDK crée des Handler sur le fil courant : toujours l'initialiser sur le fil principal.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { start(onMessage, onWatchConnected) }
            return
        }
        if (connectIq != null) return
        this.onWatchConnected = onWatchConnected
        _status.value = WatchLinkStatus.Starting
        try {
            val ciq = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)
            connectIq = ciq
            ciq.initialize(context, false, object : ConnectIQ.ConnectIQListener {
                override fun onSdkReady() {
                    ready.value = true
                    // Demande à Garmin Connect de livrer les messages par le service lié (vérifié).
                    runCatching { ciq.registerAppToUseBinderService(appId) }
                    watchDevices(ciq)
                    refreshStatus()
                    onWatchConnected()
                }

                override fun onInitializeError(error: ConnectIQ.IQSdkErrorStatus) {
                    ready.value = false
                    _status.value = when (error) {
                        ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED -> WatchLinkStatus.GarminConnectMissing
                        ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED -> WatchLinkStatus.GarminConnectOutdated
                        else -> WatchLinkStatus.Failed("service Garmin indisponible.")
                    }
                }

                override fun onSdkShutDown() {
                    ready.value = false
                    _status.value = WatchLinkStatus.Off
                }
            })
            // Écouteur global, enregistré tout de suite (avant toute livraison par le service lié,
            // y compris quand Garmin Connect démarre l'app pour lui remettre un message).
            ciq.registerForAppEvents(
                ConnectIQ.IQApplicationEventListener { _, app, messages, status ->
                    if (status == ConnectIQ.IQMessageStatus.SUCCESS && sameApp(app?.applicationId)) {
                        messages?.forEach(onMessage)
                    }
                },
            )
        } catch (e: Exception) {
            _status.value = WatchLinkStatus.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    override fun stop() {
        val ciq = connectIq ?: return
        connectIq = null
        ready.value = false
        runCatching { ciq.unregisterAppFromUsingBinderService(appId, context) }
        runCatching { ciq.unregisterAllForEvents() }
        runCatching { ciq.shutdown(context) }
        _status.value = WatchLinkStatus.Off
    }

    override suspend fun send(message: Map<String, Any?>): Boolean {
        val ciq = connectIq ?: return false
        if (withTimeoutOrNull(READY_TIMEOUT_MS) { ready.first { it } } == null) return false
        val devices = runCatching { ciq.connectedDevices }.getOrNull().orEmpty()
        var delivered = false
        for (device in devices) {
            if (sendTo(ciq, device, message)) delivered = true
        }
        return delivered
    }

    private suspend fun sendTo(ciq: ConnectIQ, device: IQDevice, message: Map<String, Any?>): Boolean =
        withTimeoutOrNull(SEND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    ciq.sendMessage(device, IQApp(appId), message) { _, _, status ->
                        if (cont.isActive) cont.resume(status == ConnectIQ.IQMessageStatus.SUCCESS)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false

    override fun refreshStatus() {
        val ciq = connectIq ?: return
        if (!ready.value) return
        val known = runCatching { ciq.knownDevices }.getOrNull()
        if (known == null) {
            _status.value = WatchLinkStatus.Failed("Garmin Connect ne répond pas.")
            return
        }
        if (known.isEmpty()) {
            _status.value = WatchLinkStatus.NoWatch
            return
        }
        val connected = known.firstOrNull { runCatching { ciq.getDeviceStatus(it) }.getOrNull() == IQDevice.IQDeviceStatus.CONNECTED }
        if (connected == null) {
            _status.value = WatchLinkStatus.Disconnected(nameOf(known.first()))
            return
        }
        val name = nameOf(connected)
        runCatching {
            ciq.getApplicationInfo(appId, connected, object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp) {
                    _status.value = WatchLinkStatus.Ready(name)
                }

                override fun onApplicationNotInstalled(applicationId: String) {
                    _status.value = WatchLinkStatus.AppMissing(name)
                }
            })
        }.onFailure { _status.value = WatchLinkStatus.Ready(name) }
    }

    /** Suivi des connexions : une montre qui revient doit recevoir l'état courant. */
    private fun watchDevices(ciq: ConnectIQ) {
        val devices = runCatching { ciq.knownDevices }.getOrNull().orEmpty()
        devices.forEach { device ->
            runCatching {
                ciq.registerForDeviceEvents(device) { _, status ->
                    refreshStatus()
                    if (status == IQDevice.IQDeviceStatus.CONNECTED) onWatchConnected()
                }
            }
        }
    }

    private fun sameApp(id: String?): Boolean = id != null && normalize(id) == normalize(appId)

    private fun normalize(id: String) = id.replace("-", "").lowercase()

    private fun nameOf(device: IQDevice): String = device.friendlyName?.takeIf { it.isNotBlank() } ?: "Montre"

    private companion object {
        const val READY_TIMEOUT_MS = 5_000L
        const val SEND_TIMEOUT_MS = 20_000L
    }
}
