package fr.montemps.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.montemps.domain.model.DefaultActivities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppPreferences(
    /** Jusqu'à deux activités proposées comme actions rapides dans la notification. */
    val favoriteIds: List<String> = DefaultActivities.defaultFavorites,
    val notificationEnabled: Boolean = true,
    /** Masque le nom de l'activité sur l'écran verrouillé (version publique de la notification). */
    val hideNameOnLockScreen: Boolean = false,
    /** Demande à Android 16+ d'afficher le suivi en « Live Update » (puce dans la barre d'état). */
    val promotedNotification: Boolean = true,
    /** Au-delà de cette durée, une session active est signalée comme peut-être oubliée. */
    val forgottenThresholdHours: Int = 10,
    /** Notification de rappel quand une session dépasse [forgottenThresholdHours]. */
    val longSessionReminder: Boolean = true,
    /** Rappel « rien n'est suivi » (désactivé par défaut). */
    val idleReminderEnabled: Boolean = false,
    val idleReminderHours: Int = 2,
    /** Plage horaire du rappel d'inactivité, en minutes depuis minuit. */
    val idleWindowStartMinutes: Int = 8 * 60,
    val idleWindowEndMinutes: Int = 22 * 60,
    /** Résumé du soir (désactivé par défaut), heure en minutes depuis minuit. */
    val eveningSummaryEnabled: Boolean = false,
    val eveningSummaryMinutes: Int = 21 * 60,

    // --- États transitoires (jamais sauvegardés) ---
    /** Session pour laquelle l'utilisateur a déjà répondu « Continuer ». */
    val acknowledgedSessionId: Long? = null,
    /** Session dont l'utilisateur a balayé la notification : on ne la republie pas d'office. */
    val dismissedNotificationSessionId: Long? = null,
    val notificationPermissionAsked: Boolean = false,
    /** Dernière ouverture de l'app (l'utilisateur sait que rien n'est suivi). */
    val idleSeenAtMs: Long? = null,
    /** Début de la période d'inactivité déjà signalée (un seul rappel par période). */
    val idleShownStartMs: Long? = null,
    /** Jour (epochDay) du dernier résumé du soir publié. */
    val summaryShownEpochDay: Long? = null,

    // --- Propres à l'appareil (jamais sauvegardés) ---
    /** Dossier choisi pour la sauvegarde automatique (URI d'arborescence SAF). */
    val autoBackupTreeUri: String? = null,
    val autoBackupLastMs: Long? = null,
    val autoBackupLastAttemptMs: Long? = null,
    val autoBackupLastError: String? = null,
) {
    companion object {
        const val MAX_FAVORITES = 2
        val thresholdChoices = listOf(4, 6, 8, 10, 12, 16, 24)
        val idleDelayChoices = listOf(1, 2, 3, 4)
    }
}

class PreferencesRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val favorites = stringPreferencesKey("favorites")
        val notificationEnabled = booleanPreferencesKey("notification_enabled")
        val hideName = booleanPreferencesKey("hide_name_lock_screen")
        val promoted = booleanPreferencesKey("promoted_notification")
        val threshold = intPreferencesKey("forgotten_threshold_hours")
        val reminder = booleanPreferencesKey("long_session_reminder")
        val idleEnabled = booleanPreferencesKey("idle_reminder")
        val idleHours = intPreferencesKey("idle_reminder_hours")
        val idleStart = intPreferencesKey("idle_window_start")
        val idleEnd = intPreferencesKey("idle_window_end")
        val summaryEnabled = booleanPreferencesKey("evening_summary")
        val summaryMinutes = intPreferencesKey("evening_summary_minutes")
        val ackSession = longPreferencesKey("ack_session_id")
        val dismissedSession = longPreferencesKey("dismissed_notification_session_id")
        val permissionAsked = booleanPreferencesKey("notification_permission_asked")
        val idleSeenAt = longPreferencesKey("idle_seen_at")
        val idleShownStart = longPreferencesKey("idle_shown_start")
        val summaryShownDay = longPreferencesKey("summary_shown_day")
        val backupTree = stringPreferencesKey("auto_backup_tree")
        val backupLast = longPreferencesKey("auto_backup_last")
        val backupAttempt = longPreferencesKey("auto_backup_attempt")
        val backupError = stringPreferencesKey("auto_backup_error")
    }

    val preferences: Flow<AppPreferences> = dataStore.data.map { p ->
        AppPreferences(
            favoriteIds = p[Keys.favorites]?.split(',')?.filter { it.isNotBlank() }?.take(AppPreferences.MAX_FAVORITES)
                ?: DefaultActivities.defaultFavorites,
            notificationEnabled = p[Keys.notificationEnabled] ?: true,
            hideNameOnLockScreen = p[Keys.hideName] ?: false,
            promotedNotification = p[Keys.promoted] ?: true,
            forgottenThresholdHours = p[Keys.threshold] ?: 10,
            longSessionReminder = p[Keys.reminder] ?: true,
            idleReminderEnabled = p[Keys.idleEnabled] ?: false,
            idleReminderHours = p[Keys.idleHours] ?: 2,
            idleWindowStartMinutes = p[Keys.idleStart] ?: (8 * 60),
            idleWindowEndMinutes = p[Keys.idleEnd] ?: (22 * 60),
            eveningSummaryEnabled = p[Keys.summaryEnabled] ?: false,
            eveningSummaryMinutes = p[Keys.summaryMinutes] ?: (21 * 60),
            acknowledgedSessionId = p[Keys.ackSession],
            dismissedNotificationSessionId = p[Keys.dismissedSession],
            notificationPermissionAsked = p[Keys.permissionAsked] ?: false,
            idleSeenAtMs = p[Keys.idleSeenAt],
            idleShownStartMs = p[Keys.idleShownStart],
            summaryShownEpochDay = p[Keys.summaryShownDay],
            autoBackupTreeUri = p[Keys.backupTree],
            autoBackupLastMs = p[Keys.backupLast],
            autoBackupLastAttemptMs = p[Keys.backupAttempt],
            autoBackupLastError = p[Keys.backupError],
        )
    }.distinctUntilChanged()

    suspend fun current(): AppPreferences = preferences.first()

    suspend fun setFavorites(ids: List<String>) = dataStore.edit {
        // Les identifiants d'activité ne contiennent pas de virgule (mots-clés ou UUID).
        it[Keys.favorites] = ids.distinct().take(AppPreferences.MAX_FAVORITES).joinToString(",")
    }

    suspend fun setNotificationEnabled(enabled: Boolean) = dataStore.edit { it[Keys.notificationEnabled] = enabled }
    suspend fun setHideNameOnLockScreen(hide: Boolean) = dataStore.edit { it[Keys.hideName] = hide }
    suspend fun setPromotedNotification(enabled: Boolean) = dataStore.edit { it[Keys.promoted] = enabled }
    suspend fun setForgottenThresholdHours(hours: Int) = dataStore.edit { it[Keys.threshold] = hours }
    suspend fun setLongSessionReminder(enabled: Boolean) = dataStore.edit { it[Keys.reminder] = enabled }
    suspend fun setIdleReminder(enabled: Boolean) = dataStore.edit { it[Keys.idleEnabled] = enabled }
    suspend fun setIdleReminderHours(hours: Int) = dataStore.edit { it[Keys.idleHours] = hours }
    suspend fun setIdleWindow(startMinutes: Int, endMinutes: Int) = dataStore.edit {
        it[Keys.idleStart] = startMinutes
        it[Keys.idleEnd] = endMinutes
    }
    suspend fun setEveningSummary(enabled: Boolean) = dataStore.edit { it[Keys.summaryEnabled] = enabled }
    suspend fun setEveningSummaryMinutes(minutes: Int) = dataStore.edit { it[Keys.summaryMinutes] = minutes }
    suspend fun acknowledgeSession(id: Long) = dataStore.edit { it[Keys.ackSession] = id }
    suspend fun setNotificationPermissionAsked() = dataStore.edit { it[Keys.permissionAsked] = true }
    suspend fun setIdleSeenAt(ms: Long) = dataStore.edit { it[Keys.idleSeenAt] = ms }
    suspend fun setIdleShownStart(ms: Long) = dataStore.edit { it[Keys.idleShownStart] = ms }
    suspend fun setSummaryShownDay(epochDay: Long) = dataStore.edit { it[Keys.summaryShownDay] = epochDay }

    suspend fun setDismissedNotificationSession(id: Long?) = dataStore.edit {
        if (id == null) it.remove(Keys.dismissedSession) else it[Keys.dismissedSession] = id
    }

    suspend fun setAutoBackupFolder(treeUri: String?) = dataStore.edit {
        if (treeUri == null) it.remove(Keys.backupTree) else it[Keys.backupTree] = treeUri
        it.remove(Keys.backupLast)
        it.remove(Keys.backupAttempt)
        it.remove(Keys.backupError)
    }

    suspend fun setAutoBackupResult(attemptMs: Long, successMs: Long?, error: String?) = dataStore.edit {
        it[Keys.backupAttempt] = attemptMs
        if (successMs != null) it[Keys.backupLast] = successMs
        if (error == null) it.remove(Keys.backupError) else it[Keys.backupError] = error
    }

    /**
     * Préférences restaurées depuis une sauvegarde : seules les préférences « sauvegardables » de [p]
     * sont reprises ; les états transitoires sont remis à zéro ; la sauvegarde automatique (propre à
     * l'appareil) n'est pas modifiée.
     */
    suspend fun restore(p: AppPreferences) = dataStore.edit {
        it[Keys.favorites] = p.favoriteIds.distinct().take(AppPreferences.MAX_FAVORITES).joinToString(",")
        it[Keys.notificationEnabled] = p.notificationEnabled
        it[Keys.hideName] = p.hideNameOnLockScreen
        it[Keys.promoted] = p.promotedNotification
        it[Keys.threshold] = p.forgottenThresholdHours
        it[Keys.reminder] = p.longSessionReminder
        it[Keys.idleEnabled] = p.idleReminderEnabled
        it[Keys.idleHours] = p.idleReminderHours
        it[Keys.idleStart] = p.idleWindowStartMinutes
        it[Keys.idleEnd] = p.idleWindowEndMinutes
        it[Keys.summaryEnabled] = p.eveningSummaryEnabled
        it[Keys.summaryMinutes] = p.eveningSummaryMinutes
        it.remove(Keys.ackSession)
        it.remove(Keys.dismissedSession)
        it.remove(Keys.idleSeenAt)
        it.remove(Keys.idleShownStart)
        it.remove(Keys.summaryShownDay)
    }
}
