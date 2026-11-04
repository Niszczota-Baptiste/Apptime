package fr.montemps.data.backup

import fr.montemps.domain.model.Session
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.report.Fmt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Export CSV des sessions pour tableur.
 *
 * Choix : séparateur « ; » et BOM UTF-8, ce qu'attendent Excel et LibreOffice en français.
 * Les heures locales sont exprimées dans le fuseau de chaque session ; les colonnes UTC
 * permettent des calculs sans ambiguïté.
 */
object CsvExporter {
    private const val SEP = ";"
    private val localFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    val header = listOf(
        "id", "activite_id", "activite", "debut_local", "fin_local", "fuseau",
        "debut_utc", "fin_utc", "duree_secondes", "duree", "en_cours", "note",
    )

    fun export(sessions: List<Session>, activities: List<TrackedActivity>, now: Instant): String {
        val names = activities.associate { it.id to it.name }
        val sb = StringBuilder("\uFEFF")
        sb.append(header.joinToString(SEP)).append("\r\n")
        for (s in sessions.sortedBy { it.start }) {
            val zone = runCatching { ZoneId.of(s.zoneId) }.getOrDefault(ZoneId.of("UTC"))
            val duration = s.duration(now)
            val row = listOf(
                s.id.toString(),
                s.activityId,
                names[s.activityId] ?: s.activityId,
                localFormat.format(s.start.atZone(zone)),
                s.end?.let { localFormat.format(it.atZone(zone)) } ?: "",
                s.zoneId,
                s.start.toString(),
                s.end?.toString() ?: "",
                duration.seconds.toString(),
                Fmt.hms(duration),
                if (s.isActive) "oui" else "non",
                s.note ?: "",
            )
            sb.append(row.joinToString(SEP) { escape(it) }).append("\r\n")
        }
        return sb.toString()
    }

    fun escape(value: String): String =
        if (value.any { it == ';' || it == '"' || it == '\n' || it == '\r' || it == ',' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
