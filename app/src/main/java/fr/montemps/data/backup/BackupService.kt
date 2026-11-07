package fr.montemps.data.backup

import android.content.ContentResolver
import android.net.Uri
import fr.montemps.data.RoomTrackingStore
import fr.montemps.data.prefs.PreferencesRepository
import fr.montemps.domain.ChangeListener
import fr.montemps.domain.WriteLock
import fr.montemps.domain.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Export CSV, sauvegarde JSON et restauration.
 *
 * La restauration se fait en deux temps : [decode] valide entièrement le fichier sans rien
 * écrire ; puis, après confirmation, [restore] remplace les données dans une seule transaction
 * Room. Un fichier invalide ne touche donc jamais aux données actuelles.
 */
class BackupService(
    private val store: RoomTrackingStore,
    private val prefs: PreferencesRepository,
    private val lock: WriteLock,
    private val time: TimeSource,
    private val listener: ChangeListener,
    private val appVersion: String?,
) {
    suspend fun snapshot(): BackupSnapshot {
        val p = prefs.current()
        return lock.withLock {
            store.transaction {
                BackupSnapshot(
                    activities = store.activities(),
                    sessions = store.allSessions(),
                    preferences = BackupPreferences.of(p),
                    subActivities = store.subActivities(),
                )
            }
        }
    }

    suspend fun exportJson(): String = BackupCodec.encode(snapshot(), time.now(), appVersion)

    suspend fun exportCsv(): String {
        val s = snapshot()
        return CsvExporter.export(s.sessions, s.activities, time.now(), s.subActivities)
    }

    fun decode(text: String): DecodeResult = BackupCodec.decode(text, time.now())

    suspend fun restore(snapshot: BackupSnapshot) {
        lock.withLock {
            store.transaction { store.replaceAll(snapshot.activities, snapshot.subActivities, snapshot.sessions) }
        }
        prefs.restore(snapshot.preferences.toAppPreferences(), time.now().toEpochMilli())
        listener.onDataChanged()
    }
}

/** Lecture/écriture via le Storage Access Framework (aucune permission de stockage requise). */
object DocumentIo {
    private const val MAX_BYTES = 20L * 1024 * 1024

    suspend fun write(resolver: ContentResolver, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("Impossible d'ouvrir le fichier en écriture.")
    }

    suspend fun read(resolver: ContentResolver, uri: Uri): String = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > MAX_BYTES) throw IOException("Fichier trop volumineux.")
            String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        } ?: throw IOException("Impossible d'ouvrir le fichier.")
    }
}
