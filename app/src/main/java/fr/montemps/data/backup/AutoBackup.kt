package fr.montemps.data.backup

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import fr.montemps.data.prefs.AppPreferences
import fr.montemps.data.prefs.PreferencesRepository
import fr.montemps.domain.time.TimeSource
import fr.montemps.notification.AlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** Dossier de destination des sauvegardes automatiques (abstrait pour les tests). */
interface BackupFolder {
    fun names(): List<String>
    fun write(name: String, text: String)
    fun delete(name: String)
}

/**
 * Dossier choisi par l'utilisateur via le sélecteur Android (Storage Access Framework), avec une
 * permission persistante limitée à ce dossier : aucune permission de stockage générale.
 */
class SafBackupFolder(private val context: Context, private val treeUri: Uri) : BackupFolder {
    private val resolver get() = context.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val rootDocument: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)

    private fun children(): List<Pair<String, Uri>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return resolver.query(uri, projection, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(1) to DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)))
            }
        } ?: throw IOException("dossier inaccessible")
    }

    override fun names(): List<String> = children().map { it.first }

    override fun write(name: String, text: String) {
        val target = children().firstOrNull { it.first == name }?.second
            ?: DocumentsContract.createDocument(resolver, rootDocument, "application/json", name)
            ?: throw IOException("création du fichier impossible")
        resolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("écriture impossible")
    }

    override fun delete(name: String) {
        children().firstOrNull { it.first == name }?.let { DocumentsContract.deleteDocument(resolver, it.second) }
    }

    /** Nom du dossier tel qu'affiché par le sélecteur (ex. « Documents »). */
    fun displayName(): String? = runCatching {
        resolver.query(rootDocument, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}

/**
 * Sauvegarde automatique hebdomadaire (optionnelle) : une copie JSON complète tous les 7 jours dans
 * le dossier choisi ; les [KEEP] plus récentes sont conservées. En cas d'échec (dossier supprimé…),
 * nouvel essai au plus tôt le lendemain ; l'erreur est affichée dans les réglages.
 */
class AutoBackupController(
    private val prefs: PreferencesRepository,
    private val backup: BackupService,
    private val alarms: AlarmScheduler,
    private val time: TimeSource,
    private val folderFor: (String) -> BackupFolder,
    private val alarmIntent: () -> PendingIntent,
) {
    companion object {
        const val PREFIX = "mon-temps-auto-"
        const val KEEP = 8
        val PERIOD: Duration = Duration.ofDays(7)
        val RETRY: Duration = Duration.ofDays(1)

        fun nextDue(p: AppPreferences): Instant {
            val byPeriod = p.autoBackupLastMs?.let { Instant.ofEpochMilli(it).plus(PERIOD) } ?: Instant.EPOCH
            val attempt = p.autoBackupLastAttemptMs
            val byRetry = if (p.autoBackupLastError != null && attempt != null) Instant.ofEpochMilli(attempt).plus(RETRY) else Instant.EPOCH
            return maxOf(byPeriod, byRetry)
        }
    }

    private val mutex = Mutex()

    /** Fait la sauvegarde si elle est due, puis programme la suivante. */
    suspend fun reschedule() = mutex.withLock {
        val p = prefs.current()
        val uri = p.autoBackupTreeUri
        if (uri == null) {
            alarms.cancel(alarmIntent())
            return@withLock
        }
        if (!nextDue(p).isAfter(time.now())) run(uri)
        alarms.set(nextDue(prefs.current()), alarmIntent())
    }

    /** Sauvegarde immédiate ; renvoie le message d'erreur, ou null en cas de succès. */
    suspend fun backupNow(): String? = mutex.withLock {
        val uri = prefs.current().autoBackupTreeUri ?: return@withLock "Aucun dossier choisi."
        run(uri).also { alarms.set(nextDue(prefs.current()), alarmIntent()) }
    }

    private suspend fun run(uri: String): String? {
        val now = time.now()
        return try {
            val json = backup.exportJson()
            withContext(Dispatchers.IO) {
                val folder = folderFor(uri)
                folder.write("$PREFIX${now.atZone(time.zone()).toLocalDate()}.json", json)
                folder.names()
                    .filter { it.startsWith(PREFIX) && it.endsWith(".json") }
                    .sortedDescending()
                    .drop(KEEP)
                    .forEach { folder.delete(it) }
            }
            prefs.setAutoBackupResult(now.toEpochMilli(), now.toEpochMilli(), null)
            null
        } catch (e: Exception) {
            val message = "Échec : ${e.message ?: e.javaClass.simpleName}"
            prefs.setAutoBackupResult(now.toEpochMilli(), null, message)
            message
        }
    }
}
