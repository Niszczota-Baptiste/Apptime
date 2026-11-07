package fr.montemps.data.backup

import fr.montemps.data.prefs.AppPreferences
import fr.montemps.domain.SessionValidator
import fr.montemps.domain.model.DefaultActivities
import fr.montemps.domain.model.Goal
import fr.montemps.domain.model.GoalPeriod
import fr.montemps.domain.model.Session
import fr.montemps.domain.model.SubActivity
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
 * Historique :
 * - v1 : version initiale ;
 * - v2 : `preferences.longSessionReminder` (défaut `true`) ;
 * - v3 : `activities[].interruption`, `goalMinutes`, `goalPeriod` ; préférences du résumé du soir,
 *   du rappel d'inactivité et de l'affichage « Live Update ». Absents d'un fichier plus ancien :
 *   valeurs par défaut (Pause et Repas sont alors des interruptions, aucun objectif) ;
 * - v4 : `subActivities` (sous-catégories), `sessions[].subId`, préférences du coucher et du réveil.
 *   Fichier plus ancien : sous-catégories par défaut ajoutées aux activités par défaut présentes et
 *   activité Sommeil ajoutée, comme le fait la migration de la base.
 */
@Serializable
data class BackupFile(
    val format: String = BackupCodec.FORMAT_ID,
    val formatVersion: Int = BackupCodec.CURRENT_VERSION,
    val exportedAt: String,
    val appVersion: String? = null,
    val activities: List<BackupActivity>,
    val subActivities: List<BackupSubActivity> = emptyList(),
    val sessions: List<BackupSession>,
    val preferences: BackupPreferences? = null,
)

@Serializable
data class BackupSubActivity(
    val id: String,
    val activityId: String,
    val name: String,
    val sortOrder: Int,
    val archived: Boolean = false,
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
    val interruption: Boolean? = null,
    val goalMinutes: Int? = null,
    /** « day » ou « week ». */
    val goalPeriod: String? = null,
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
    val subId: String? = null,
)

@Serializable
data class BackupPreferences(
    val favoriteIds: List<String> = emptyList(),
    val notificationEnabled: Boolean = true,
    val hideNameOnLockScreen: Boolean = false,
    val forgottenThresholdHours: Int = 10,
    val longSessionReminder: Boolean = true,
    val promotedNotification: Boolean = true,
    val idleReminderEnabled: Boolean = false,
    val idleReminderHours: Int = 2,
    val idleWindowStartMinutes: Int = 8 * 60,
    val idleWindowEndMinutes: Int = 22 * 60,
    val eveningSummaryEnabled: Boolean = false,
    val eveningSummaryMinutes: Int = 21 * 60,
    val bedtimeEnabled: Boolean = false,
    val bedtimeMinutes: Int = 23 * 60,
    /** Jours ISO (1 = lundi … 7 = dimanche). */
    val bedtimeDays: List<Int> = ALL_DAYS,
    val wakeEnabled: Boolean = false,
    val wakeMinutes: Int = 7 * 60,
    val wakeDays: List<Int> = ALL_DAYS,
) {
    fun toAppPreferences(): AppPreferences = AppPreferences(
        favoriteIds = favoriteIds,
        notificationEnabled = notificationEnabled,
        hideNameOnLockScreen = hideNameOnLockScreen,
        promotedNotification = promotedNotification,
        forgottenThresholdHours = forgottenThresholdHours,
        longSessionReminder = longSessionReminder,
        idleReminderEnabled = idleReminderEnabled,
        idleReminderHours = idleReminderHours,
        idleWindowStartMinutes = idleWindowStartMinutes,
        idleWindowEndMinutes = idleWindowEndMinutes,
        eveningSummaryEnabled = eveningSummaryEnabled,
        eveningSummaryMinutes = eveningSummaryMinutes,
        bedtimeEnabled = bedtimeEnabled,
        bedtimeMinutes = bedtimeMinutes,
        bedtimeDays = bedtimeDays.toSet(),
        wakeEnabled = wakeEnabled,
        wakeMinutes = wakeMinutes,
        wakeDays = wakeDays.toSet(),
    )

    companion object {
        val ALL_DAYS = (1..7).toList()

        fun of(p: AppPreferences) = BackupPreferences(
            favoriteIds = p.favoriteIds,
            notificationEnabled = p.notificationEnabled,
            hideNameOnLockScreen = p.hideNameOnLockScreen,
            forgottenThresholdHours = p.forgottenThresholdHours,
            longSessionReminder = p.longSessionReminder,
            promotedNotification = p.promotedNotification,
            idleReminderEnabled = p.idleReminderEnabled,
            idleReminderHours = p.idleReminderHours,
            idleWindowStartMinutes = p.idleWindowStartMinutes,
            idleWindowEndMinutes = p.idleWindowEndMinutes,
            eveningSummaryEnabled = p.eveningSummaryEnabled,
            eveningSummaryMinutes = p.eveningSummaryMinutes,
            bedtimeEnabled = p.bedtimeEnabled,
            bedtimeMinutes = p.bedtimeMinutes,
            bedtimeDays = p.bedtimeDays.sorted(),
            wakeEnabled = p.wakeEnabled,
            wakeMinutes = p.wakeMinutes,
            wakeDays = p.wakeDays.sorted(),
        )
    }
}

/** Contenu validé d'une sauvegarde, prêt à être écrit. */
data class BackupSnapshot(
    val activities: List<TrackedActivity>,
    val sessions: List<Session>,
    val preferences: BackupPreferences,
    val subActivities: List<SubActivity> = emptyList(),
)

sealed interface DecodeResult {
    data class Valid(val snapshot: BackupSnapshot, val exportedAt: String) : DecodeResult
    data class Invalid(val message: String) : DecodeResult
}

object BackupCodec {
    const val FORMAT_ID = "mon-temps-backup"
    const val CURRENT_VERSION = 4

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
                BackupActivity(
                    id = it.id,
                    name = it.name,
                    color = colorToHex(it.color),
                    icon = it.icon,
                    sortOrder = it.sortOrder,
                    archived = it.archived,
                    interruption = it.interruption,
                    goalMinutes = it.goal?.minutes,
                    goalPeriod = it.goal?.period?.id,
                )
            },
            subActivities = snapshot.subActivities.sortedWith(compareBy({ it.activityId }, { it.sortOrder })).map {
                BackupSubActivity(it.id, it.activityId, it.name, it.sortOrder, it.archived)
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
                    subId = it.subId,
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
            val goal = when {
                a.goalMinutes == null && a.goalPeriod == null -> null
                a.goalMinutes == null || a.goalMinutes <= 0 || GoalPeriod.of(a.goalPeriod) == null ->
                    return DecodeResult.Invalid("Objectif invalide pour « ${a.name} ».")
                else -> Goal(a.goalMinutes, GoalPeriod.of(a.goalPeriod)!!)
            }
            activities += TrackedActivity(
                id = a.id,
                name = a.name.trim(),
                color = color,
                icon = a.icon,
                sortOrder = a.sortOrder,
                archived = a.archived,
                interruption = a.interruption ?: (a.id in DefaultActivities.interruptionIds),
                goal = goal,
            )
        }

        // Fichier antérieur aux sous-catégories : mêmes ajouts que la migration de la base.
        if (file.formatVersion < 4 && activities.none { it.id == DefaultActivities.SLEEP || it.name.equals("Sommeil", ignoreCase = true) }) {
            val sleep = DefaultActivities.all.first { it.id == DefaultActivities.SLEEP }
            activities += sleep.copy(sortOrder = (activities.maxOfOrNull { it.sortOrder } ?: -1) + 1)
            ids += sleep.id
        }
        val subs = mutableListOf<SubActivity>()
        val subIds = HashSet<String>()
        for (sub in file.subActivities) {
            if (sub.id.isBlank() || !subIds.add(sub.id)) return DecodeResult.Invalid("Identifiant de sous-catégorie invalide ou en double : ${sub.id}.")
            if (sub.activityId !in ids) return DecodeResult.Invalid("Une sous-catégorie fait référence à une activité inconnue (${sub.activityId}).")
            if (sub.name.isBlank()) return DecodeResult.Invalid("La sous-catégorie ${sub.id} n'a pas de nom.")
            subs += SubActivity(sub.id, sub.activityId, sub.name.trim(), sub.sortOrder, sub.archived)
        }
        if (file.formatVersion < 4) {
            DefaultActivities.defaultSubs.filter { it.activityId in ids && it.id !in subIds }.forEach {
                subs += it
                subIds += it.id
            }
        }
        val subsById = subs.associateBy { it.id }

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
            if (s.subId != null && subsById[s.subId]?.activityId != s.activityId) {
                return DecodeResult.Invalid("Une session fait référence à une sous-catégorie inconnue (${s.subId}).")
            }
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
                subId = s.subId,
            )
        }
        SessionValidator.findInconsistency(sessions)?.let { return DecodeResult.Invalid(it) }

        val prefs = (file.preferences ?: BackupPreferences()).let { p ->
            val windowValid = p.idleWindowStartMinutes in 0..1439 && p.idleWindowEndMinutes in 1..1440 &&
                p.idleWindowStartMinutes < p.idleWindowEndMinutes
            p.copy(
                favoriteIds = p.favoriteIds.filter { it in ids }.distinct().take(2),
                forgottenThresholdHours = p.forgottenThresholdHours.coerceIn(1, 72),
                idleReminderHours = p.idleReminderHours.coerceIn(1, 12),
                idleWindowStartMinutes = if (windowValid) p.idleWindowStartMinutes else 8 * 60,
                idleWindowEndMinutes = if (windowValid) p.idleWindowEndMinutes else 22 * 60,
                eveningSummaryMinutes = p.eveningSummaryMinutes.coerceIn(0, 1439),
                bedtimeMinutes = p.bedtimeMinutes.coerceIn(0, 1439),
                bedtimeDays = p.bedtimeDays.filter { it in 1..7 }.distinct().sorted(),
                wakeMinutes = p.wakeMinutes.coerceIn(0, 1439),
                wakeDays = p.wakeDays.filter { it in 1..7 }.distinct().sorted(),
            )
        }
        return DecodeResult.Valid(BackupSnapshot(activities, sessions, prefs, subs), file.exportedAt)
    }

    fun colorToHex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

    fun parseColor(hex: String): Int? {
        val clean = hex.removePrefix("#")
        if (clean.length != 6) return null
        return clean.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
    }

    private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
}
