package fr.montemps

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import fr.montemps.data.RoomTrackingStore
import fr.montemps.data.backup.BackupService
import fr.montemps.data.db.MonTempsDatabase
import fr.montemps.data.prefs.PreferencesRepository
import fr.montemps.domain.ActivityCatalog
import fr.montemps.domain.ChangeListener
import fr.montemps.domain.SessionEditor
import fr.montemps.domain.TimeTracker
import fr.montemps.domain.WriteLock
import fr.montemps.domain.time.TimeSource
import fr.montemps.notification.NotificationController
import fr.montemps.notification.TrackingNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Instant
import java.time.ZoneId

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Horloges réelles du téléphone. */
class AndroidTimeSource(private val context: Context) : TimeSource {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun bootCount(): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()
}

/**
 * Assemblage manuel des dépendances (le projet est assez petit pour se passer de Hilt).
 * Une seule instance par processus : le [WriteLock] partagé sérialise toutes les écritures.
 */
class AppContainer(
    context: Context,
    val time: TimeSource = AndroidTimeSource(context),
    database: MonTempsDatabase = MonTempsDatabase.build(context),
    dataStore: DataStore<Preferences> = context.dataStore,
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = RoomTrackingStore(database)
    val preferences = PreferencesRepository(dataStore)
    val notifier = TrackingNotifier(context)
    val notifications = NotificationController(store, preferences, notifier, time)

    private val lock = WriteLock()
    private val onChange = ChangeListener { notifications.sync() }

    val tracker = TimeTracker(store, time, lock, onChange)
    val editor = SessionEditor(store, time, lock, onChange)
    val catalog = ActivityCatalog(store, lock, onChange)
    val backup = BackupService(store, preferences, lock, time, onChange, BuildConfig.VERSION_NAME)
}
