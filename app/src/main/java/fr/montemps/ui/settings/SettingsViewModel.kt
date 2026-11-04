package fr.montemps.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.montemps.AppContainer
import fr.montemps.data.backup.BackupSnapshot
import fr.montemps.data.backup.DecodeResult
import fr.montemps.data.backup.DocumentIo
import fr.montemps.data.prefs.AppPreferences
import fr.montemps.domain.model.TrackedActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PendingRestore(val snapshot: BackupSnapshot, val exportedAt: String)

data class SettingsUiState(val prefs: AppPreferences, val activities: List<TrackedActivity>)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<SettingsUiState?> = combine(c.preferences.preferences, c.store.observeActivities()) { p, a ->
        SettingsUiState(p, a)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()

    val pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val busy = MutableStateFlow(false)

    private fun update(block: suspend () -> Unit) = viewModelScope.launch {
        block()
        c.notifications.sync()
    }

    fun setFavorite(slot: Int, activityId: String?) = update {
        val current = c.preferences.current().favoriteIds.toMutableList()
        while (current.size < AppPreferences.MAX_FAVORITES) current.add("")
        current[slot] = activityId.orEmpty()
        c.preferences.setFavorites(current.filter { it.isNotBlank() })
    }

    fun setNotificationEnabled(v: Boolean) = update { c.preferences.setNotificationEnabled(v) }
    fun setHideName(v: Boolean) = update { c.preferences.setHideNameOnLockScreen(v) }
    fun setThreshold(h: Int) = update { c.preferences.setForgottenThresholdHours(h) }
    fun reshowNotification() = viewModelScope.launch { c.notifications.reshow() }

    fun exportCsv(resolver: ContentResolver, uri: Uri) = io("Export CSV enregistré.") {
        DocumentIo.write(resolver, uri, c.backup.exportCsv())
    }

    fun exportJson(resolver: ContentResolver, uri: Uri) = io("Sauvegarde enregistrée.") {
        DocumentIo.write(resolver, uri, c.backup.exportJson())
    }

    /** Lit et valide le fichier ; rien n'est écrit tant que l'utilisateur n'a pas confirmé. */
    fun prepareRestore(resolver: ContentResolver, uri: Uri) = io(null) {
        when (val r = c.backup.decode(DocumentIo.read(resolver, uri))) {
            is DecodeResult.Invalid -> messages.send("Restauration impossible : ${r.message} Vos données n'ont pas été modifiées.")
            is DecodeResult.Valid -> pendingRestore.value = PendingRestore(r.snapshot, r.exportedAt)
        }
    }

    fun confirmRestore() {
        val pending = pendingRestore.value ?: return
        pendingRestore.value = null
        io("Données restaurées.") { c.backup.restore(pending.snapshot) }
    }

    fun cancelRestore() {
        pendingRestore.value = null
    }

    private fun io(success: String?, block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        try {
            block()
            success?.let { messages.send(it) }
        } catch (e: Exception) {
            messages.send("Opération échouée : ${e.message ?: e.javaClass.simpleName}. Aucune donnée n'a été modifiée.")
        } finally {
            busy.value = false
        }
    }
}
