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
): AppContainer {
    val file = File(context().cacheDir, "prefs-${UUID.randomUUID()}.preferences_pb")
    val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { file }
    return if (backupFolderFor == null) {
        AppContainer(context(), time, database, dataStore)
    } else {
        AppContainer(context(), time, database, dataStore, backupFolderFor)
    }
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
