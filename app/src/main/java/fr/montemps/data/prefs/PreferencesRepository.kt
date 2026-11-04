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
    /** Au-delà de cette durée, une session active est signalée comme peut-être oubliée. */
    val forgottenThresholdHours: Int = 10,
    /** Session pour laquelle l'utilisateur a déjà répondu « Continuer ». */
    val acknowledgedSessionId: Long? = null,
    /** Session dont l'utilisateur a balayé la notification : on ne la republie pas d'office. */
    val dismissedNotificationSessionId: Long? = null,
    val notificationPermissionAsked: Boolean = false,
) {
    companion object {
        const val MAX_FAVORITES = 2
        val thresholdChoices = listOf(4, 6, 8, 10, 12, 16, 24)
    }
}

class PreferencesRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val favorites = stringPreferencesKey("favorites")
        val notificationEnabled = booleanPreferencesKey("notification_enabled")
        val hideName = booleanPreferencesKey("hide_name_lock_screen")
        val threshold = intPreferencesKey("forgotten_threshold_hours")
        val ackSession = longPreferencesKey("ack_session_id")
        val dismissedSession = longPreferencesKey("dismissed_notification_session_id")
        val permissionAsked = booleanPreferencesKey("notification_permission_asked")
    }

    val preferences: Flow<AppPreferences> = dataStore.data.map { p ->
        AppPreferences(
            favoriteIds = p[Keys.favorites]?.split(',')?.filter { it.isNotBlank() }?.take(AppPreferences.MAX_FAVORITES)
                ?: DefaultActivities.defaultFavorites,
            notificationEnabled = p[Keys.notificationEnabled] ?: true,
            hideNameOnLockScreen = p[Keys.hideName] ?: false,
            forgottenThresholdHours = p[Keys.threshold] ?: 10,
            acknowledgedSessionId = p[Keys.ackSession],
            dismissedNotificationSessionId = p[Keys.dismissedSession],
            notificationPermissionAsked = p[Keys.permissionAsked] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun current(): AppPreferences = preferences.first()

    suspend fun setFavorites(ids: List<String>) = dataStore.edit {
        // Les identifiants d'activité ne contiennent pas de virgule (mots-clés ou UUID).
        it[Keys.favorites] = ids.distinct().take(AppPreferences.MAX_FAVORITES).joinToString(",")
    }

    suspend fun setNotificationEnabled(enabled: Boolean) = dataStore.edit { it[Keys.notificationEnabled] = enabled }
    suspend fun setHideNameOnLockScreen(hide: Boolean) = dataStore.edit { it[Keys.hideName] = hide }
    suspend fun setForgottenThresholdHours(hours: Int) = dataStore.edit { it[Keys.threshold] = hours }
    suspend fun acknowledgeSession(id: Long) = dataStore.edit { it[Keys.ackSession] = id }
    suspend fun setNotificationPermissionAsked() = dataStore.edit { it[Keys.permissionAsked] = true }

    suspend fun setDismissedNotificationSession(id: Long?) = dataStore.edit {
        if (id == null) it.remove(Keys.dismissedSession) else it[Keys.dismissedSession] = id
    }

    /** Préférences restaurées depuis une sauvegarde (les états transitoires sont remis à zéro). */
    suspend fun restore(favorites: List<String>, notificationEnabled: Boolean, hideName: Boolean, thresholdHours: Int) =
        dataStore.edit {
            it[Keys.favorites] = favorites.distinct().take(AppPreferences.MAX_FAVORITES).joinToString(",")
            it[Keys.notificationEnabled] = notificationEnabled
            it[Keys.hideName] = hideName
            it[Keys.threshold] = thresholdHours
            it.remove(Keys.ackSession)
            it.remove(Keys.dismissedSession)
        }
}
