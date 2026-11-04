package fr.montemps.data.backup

import fr.montemps.domain.SessionValidator
import fr.montemps.domain.model.Session
import fr.montemps.domain.model.TrackedActivity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

/**
 * Format de sauvegarde JSON versionné.
 *
 * Les instants sont en ISO-8601 UTC (« 2026-11-04T08:15:00Z »). `formatVersion` est incrémenté
 * à chaque changement de format ; la lecture accepte les versions 1 à [BackupCodec.CURRENT_VERSION].
 *
 * Historique : v1 = version initiale ; v2 = ajout de `preferences.longSessionReminder`
 * (absent d'un fichier v1 : valeur par défaut `true`).
 */
@Serializable
data class BackupFile(
    val format: String = BackupCodec.FORMAT_ID,
    val formatVersion: Int = BackupCodec.CURRENT_VERSION,
    val exportedAt: String,
    val appVersion: String? = null,
    val activities: List<BackupActivity>,
    val sessions: List<BackupSession>,
    val preferences: BackupPreferences? = null,
)

@Serializable
data class BackupActivity(
    val id: String,
    val name: String,
    /** Couleur « #RRGGBB ». */
    val color: String,
    val icon: String? = null,
    val sortOrder: Int,
    val archived: Boolean = false,
)

@Serializable
data class BackupSession(
    val id: Long,
    val activityId: String,
    val start: String,
    val end: String? = null,
    val zoneId: String,
    val note: String? = null,
    val source: String = "app",
    @SerialName("startElapsedRealtimeMs") val startElapsedMs: Long? = null,
    val startBootCount: Int? = null,
)

@Serializable
data class BackupPreferences(
    val favoriteIds: List<String> = emptyList(),
    val notificationEnabled: Boolean = true,
    val hideNameOnLockScreen: Boolean = false,
    val forgottenThresholdHours: Int = 10,
    val longSessionReminder: Boolean = true,
)

/** Contenu validé d'une sauvegarde, prêt à être écrit. */
data class BackupSnapshot(
    val activities: List<TrackedActivity>,
    val sessions: List<Session>,
    val preferences: BackupPreferences,
)

sealed interface DecodeResult {
    data class Valid(val snapshot: BackupSnapshot, val exportedAt: String) : DecodeResult
    data class Invalid(val message: String) : DecodeResult
}

object BackupCodec {
    const val FORMAT_ID = "mon-temps-backup"
    const val CURRENT_VERSION = 2

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: BackupSnapshot, exportedAt: Instant, appVersion: String?): String {
        val file = BackupFile(
            exportedAt = exportedAt.toString(),
            appVersion = appVersion,
            activities = snapshot.activities.map {
                BackupActivity(it.id, it.name, colorToHex(it.color), it.icon, it.sortOrder, it.archived)
            },
            sessions = snapshot.sessions.sortedBy { it.start }.map {
                BackupSession(
                    id = it.id,
                    activityId = it.activityId,
                    start = it.start.toString(),
                    end = it.end?.toString(),
                    zoneId = it.zoneId,
                    note = it.note,
                    source = it.source,
                    startElapsedMs = it.startElapsedRealtimeMs,
                    startBootCount = it.startBootCount,
                )
            },
            preferences = snapshot.preferences,
        )
        return json.encodeToString(BackupFile.serializer(), file)
    }

    /** Décode et valide intégralement, sans aucune écriture. [now] sert à refuser les dates futures. */
    fun decode(text: String, now: Instant): DecodeResult {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: Exception) {
            return DecodeResult.Invalid("Le fichier n'est pas une sauvegarde Mon Temps lisible.")
        }
        if (file.format != FORMAT_ID) return DecodeResult.Invalid("Ce fichier n'est pas une sauvegarde Mon Temps.")
        if (file.formatVersion < 1 || file.formatVersion > CURRENT_VERSION) {
            return DecodeResult.Invalid("Version de sauvegarde non prise en charge (${file.formatVersion}). Mettez l'application à jour.")
        }

        val activities = mutableListOf<TrackedActivity>()
        val ids = HashSet<String>()
        for (a in file.activities) {
            if (a.id.isBlank()) return DecodeResult.Invalid("Une activité n'a pas d'identifiant.")
            if (!ids.add(a.id)) return DecodeResult.Invalid("Identifiant d'activité en double : ${a.id}.")
            if (a.name.isBlank()) return DecodeResult.Invalid("L'activité ${a.id} n'a pas de nom.")
            val color = parseColor(a.color) ?: return DecodeResult.Invalid("Couleur invalide pour « ${a.name} ».")
            activities += TrackedActivity(a.id, a.name.trim(), color, a.icon, a.sortOrder, a.archived)
        }

        val sessions = mutableListOf<Session>()
        val sessionIds = HashSet<Long>()
        for (s in file.sessions) {
            if (s.id <= 0 || !sessionIds.add(s.id)) return DecodeResult.Invalid("Identifiant de session invalide ou en double : ${s.id}.")
            if (s.activityId !in ids) return DecodeResult.Invalid("Une session fait référence à une activité inconnue (${s.activityId}).")
            val start = parseInstant(s.start) ?: return DecodeResult.Invalid("Date de début invalide : ${s.start}.")
            val end = s.end?.let { parseInstant(it) ?: return DecodeResult.Invalid("Date de fin invalide : $it.") }
            if (start.isAfter(now) || (end != null && end.isAfter(now))) {
                return DecodeResult.Invalid("La sauvegarde contient une session dans le futur (${s.start}).")
            }
            if (runCatching { ZoneId.of(s.zoneId) }.isFailure) return DecodeResult.Invalid("Fuseau horaire invalide : ${s.zoneId}.")
            sessions += Session(
                id = s.id,
                activityId = s.activityId,
                start = start,
                end = end,
                zoneId = s.zoneId,
                note = s.note,
                startElapsedRealtimeMs = s.startElapsedMs,
                startBootCount = s.startBootCount,
                source = s.source,
            )
        }
        SessionValidator.findInconsistency(sessions)?.let { return DecodeResult.Invalid(it) }

        val prefs = (file.preferences ?: BackupPreferences()).let { p ->
            p.copy(
                favoriteIds = p.favoriteIds.filter { it in ids }.distinct().take(2),
                forgottenThresholdHours = p.forgottenThresholdHours.coerceIn(1, 72),
            )
        }
        return DecodeResult.Valid(BackupSnapshot(activities, sessions, prefs), file.exportedAt)
    }

    fun colorToHex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

    fun parseColor(hex: String): Int? {
        val clean = hex.removePrefix("#")
        if (clean.length != 6) return null
        return clean.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
    }

    private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
}
