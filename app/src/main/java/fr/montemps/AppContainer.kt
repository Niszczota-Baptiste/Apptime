package fr.montemps

import android.content.Context
import androidx.core.net.toUri
import android.os.SystemClock
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import fr.montemps.data.RoomTrackingStore
import fr.montemps.data.backup.AutoBackupController
import fr.montemps.data.backup.BackupFolder
import fr.montemps.data.backup.BackupService
import fr.montemps.data.backup.SafBackupFolder
import fr.montemps.data.db.MonTempsDatabase
import fr.montemps.data.prefs.PreferencesRepository
import fr.montemps.domain.ActivityCatalog
import fr.montemps.domain.ChangeListener
import fr.montemps.domain.SessionEditor
import fr.montemps.domain.TimeTracker
import fr.montemps.domain.WriteLock
import fr.montemps.domain.time.TimeSource
import fr.montemps.domain.watch.WatchCommandHandler
import fr.montemps.domain.workouts.WorkoutSource
import fr.montemps.data.health.HealthConnectWorkouts
import fr.montemps.garmin.ConnectIqLink
import fr.montemps.garmin.WatchController
import fr.montemps.garmin.WatchLink
import fr.montemps.notification.AlarmScheduler
import fr.montemps.notification.EveningSummaryController
import fr.montemps.notification.IdleReminderController
import fr.montemps.notification.NotificationController
import fr.montemps.notification.ReminderController
import fr.montemps.notification.TrackingIntents
import fr.montemps.notification.TrackingNotifier
import fr.montemps.surfaces.SurfaceStateLoader
import fr.montemps.surfaces.SurfacesController
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
    /** Dossier de sauvegarde automatique à partir de son URI (remplaçable dans les tests). */
    backupFolderFor: (String) -> BackupFolder = { SafBackupFolder(context, it.toUri()) },
    /** Liaison avec la montre Garmin (remplaçable dans les tests). */
    watchLink: WatchLink = ConnectIqLink(context.applicationContext),
    /** Séances sportives à importer (Health Connect ; remplaçable dans les tests). */
    val workouts: WorkoutSource = HealthConnectWorkouts(context.applicationContext),
) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = RoomTrackingStore(database)
    val preferences = PreferencesRepository(dataStore)
    val notifier = TrackingNotifier(context)
    val notifications = NotificationController(store, preferences, notifier, time)
    private val alarms = AlarmScheduler(context)
    val reminders = ReminderController(context, store, preferences, notifier, time, alarms)
    val idle = IdleReminderController(context, store, preferences, notifier, time, alarms)
    val summary = EveningSummaryController(context, store, preferences, notifier, time, alarms)
    val surfaces = SurfacesController(context, SurfaceStateLoader(store, preferences, time), alarms, time)
    val watch = WatchController(context.applicationContext, watchLink, SurfaceStateLoader(store, preferences, time), time, appScope)

    private val lock = WriteLock()
    private val onChange = ChangeListener { refreshOutputs() }

    /**
     * Réaligne tout ce qui est affiché hors de l'app (notification de suivi, rappel, widgets,
     * tuile, montre, alarmes) sur la base. Appelé après chaque écriture et lors des événements système.
     */
    suspend fun refreshOutputs() {
        notifications.sync()
        reminders.reschedule()
        idle.reschedule()
        summary.reschedule()
        surfaces.refresh()
        watch.requestSync() // en arrière-plan : la montre peut être lente ou absente
        autoBackup.reschedule() // en dernier : peut écrire un fichier si la sauvegarde est due
    }

    val tracker = TimeTracker(store, time, lock, onChange)
    val watchCommands = WatchCommandHandler(tracker, store)
    val editor = SessionEditor(store, time, lock, onChange)
    val catalog = ActivityCatalog(store, lock, onChange)
    val backup = BackupService(store, preferences, lock, time, onChange, BuildConfig.VERSION_NAME)
    val autoBackup = AutoBackupController(preferences, backup, alarms, time, backupFolderFor) { TrackingIntents.autoBackup(context) }
}
