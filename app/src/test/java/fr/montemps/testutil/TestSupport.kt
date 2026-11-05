package fr.montemps.testutil

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import fr.montemps.AppContainer
import fr.montemps.MonTempsApp
import kotlinx.coroutines.runBlocking
import fr.montemps.data.backup.BackupFolder
import fr.montemps.data.db.MonTempsDatabase
import fr.montemps.domain.time.TimeSource
import fr.montemps.domain.workouts.Workout
import fr.montemps.domain.workouts.WorkoutAvailability
import fr.montemps.domain.workouts.WorkoutSource
import fr.montemps.garmin.WatchLink
import fr.montemps.garmin.WatchLinkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Horloge contrôlable pour les tests. */
class FakeTimeSource(
    var now: Instant = Instant.parse("2026-11-04T08:00:00Z"),
    var zone: ZoneId = ZoneId.of("Europe/Paris"),
    var elapsed: Long = 1_000_000L,
    var boot: Int? = 7,
) : TimeSource {
    override fun now(): Instant = now
    override fun zone(): ZoneId = zone
    override fun elapsedRealtimeMs(): Long = elapsed
    override fun bootCount(): Int? = boot

    /** Fait avancer les deux horloges (calendaire et monotone) de [d]. */
    fun advance(d: Duration) {
        now += d
        elapsed += d.toMillis()
    }
}

fun context(): Context = ApplicationProvider.getApplicationContext()

/**
 * Installe le conteneur de test dans l'application. On attend d'abord la tâche de démarrage du
 * conteneur réel (sinon elle pourrait, en se terminant plus tard, annuler une notification
 * publiée par le test).
 */
fun useContainer(container: AppContainer) {
    val app = context() as MonTempsApp
    runBlocking { app.startup.join() }
    app.container = container
}

/** Conteneur complet branché sur une base en mémoire, un DataStore temporaire et une horloge factice. */
fun testContainer(
    time: FakeTimeSource = FakeTimeSource(),
    database: MonTempsDatabase = MonTempsDatabase.build(context(), inMemory = true),
    backupFolderFor: ((String) -> BackupFolder)? = null,
    watchLink: WatchLink = FakeWatchLink(),
    workouts: WorkoutSource = FakeWorkoutSource(),
): AppContainer {
    val file = File(context().cacheDir, "prefs-${UUID.randomUUID()}.preferences_pb")
    val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { file }
    return AppContainer(
        context(),
        time,
        database,
        dataStore,
        backupFolderFor = backupFolderFor ?: { MemoryBackupFolder() },
        watchLink = watchLink,
        workouts = workouts,
    )
}

/** Montre factice : enregistre les messages envoyés, permet de simuler ceux de la montre. */
class FakeWatchLink : WatchLink {
    override val status = MutableStateFlow<WatchLinkStatus>(WatchLinkStatus.Off)
    val sent = mutableListOf<Map<String, Any?>>()
    var connected = true
    var started = false
    private var onMessage: ((Any?) -> Unit)? = null

    override fun start(onMessage: (Any?) -> Unit, onWatchConnected: () -> Unit) {
        started = true
        this.onMessage = onMessage
        status.value = WatchLinkStatus.Ready("Forerunner 965")
    }

    override fun stop() {
        started = false
        onMessage = null
        status.value = WatchLinkStatus.Off
    }

    override suspend fun send(message: Map<String, Any?>): Boolean {
        if (!connected) return false
        sent += message
        return true
    }

    override fun refreshStatus() = Unit

    /** Message reçu de l'app montre (comme le transmettrait Garmin Connect). */
    fun receive(message: Any?) {
        onMessage?.invoke(message)
    }
}

/** Source de séances factice (Health Connect n'existe pas sous Robolectric). */
class FakeWorkoutSource(
    var availability: WorkoutAvailability = WorkoutAvailability.AVAILABLE,
    var granted: Boolean = true,
    val items: MutableList<Workout> = mutableListOf(),
) : WorkoutSource {
    override fun availability(): WorkoutAvailability = availability
    override suspend fun hasPermission(): Boolean = granted
    override suspend fun workouts(from: Instant, to: Instant): List<Workout> =
        items.filter { it.start.isBefore(to) && it.end.isAfter(from) }
}

/** Dossier de sauvegarde en mémoire (le vrai passe par le sélecteur de documents Android). */
class MemoryBackupFolder : BackupFolder {
    val files = linkedMapOf<String, String>()
    var failing = false

    override fun names(): List<String> = files.keys.toList()

    override fun write(name: String, text: String) {
        if (failing) throw java.io.IOException("dossier inaccessible")
        files[name] = text
    }

    override fun delete(name: String) {
        files.remove(name)
    }
}

fun Instant.plusMinutes(m: Long): Instant = plus(Duration.ofMinutes(m))
fun Instant.plusHours(h: Long): Instant = plus(Duration.ofHours(h))
