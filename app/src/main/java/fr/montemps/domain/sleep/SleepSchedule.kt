package fr.montemps.domain.sleep

import fr.montemps.domain.model.DefaultActivities
import fr.montemps.domain.model.TrackedActivity
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Coucher et réveil automatiques : à l'heure choisie (jours choisis), Sommeil démarre ou s'arrête.
 *
 * Les alarmes sont inexactes (aucune permission spéciale) : elles peuvent arriver en retard. Le
 * changement est donc enregistré à l'heure prévue, pas à l'heure d'arrivée de l'alarme — tant que
 * ce retard reste raisonnable ([BEDTIME_GRACE], [WAKE_GRACE]) et que rien de plus récent n'a été
 * fait entre-temps (le suivi manuel l'emporte toujours).
 */
object SleepSchedule {
    /** Au-delà, un coucher non traité est abandonné (téléphone éteint toute la soirée…). */
    val BEDTIME_GRACE: Duration = Duration.ofHours(2)

    /** Au-delà, un réveil non traité est abandonné (la session « oubliée » sera signalée). */
    val WAKE_GRACE: Duration = Duration.ofHours(12)

    /**
     * Première échéance (heure locale [minutes], jour ISO dans [days]) strictement postérieure à
     * [after] ; null si aucun jour n'est choisi. Les jours de changement d'heure suivent java.time
     * (heure inexistante décalée, heure ambiguë : premier décalage).
     */
    fun next(after: Instant, zone: ZoneId, minutes: Int, days: Set<Int>): Instant? {
        if (days.isEmpty()) return null
        val time = LocalTime.of((minutes / 60).coerceIn(0, 23), (minutes % 60).coerceIn(0, 59))
        val start = after.atZone(zone).toLocalDate().minusDays(1)
        for (i in 0L..8L) {
            val date = start.plusDays(i)
            if (date.dayOfWeek.value !in days) continue
            val at = date.atTime(time).atZone(zone).toInstant()
            if (at.isAfter(after)) return at
        }
        return null
    }

    /**
     * Échéance à traiter : la plus ancienne non traitée dans le délai de grâce (éventuellement
     * passée : l'alarme partira tout de suite), sinon la prochaine.
     */
    fun due(now: Instant, handled: Instant?, grace: Duration, zone: ZoneId, minutes: Int, days: Set<Int>): Instant? {
        val after = listOfNotNull(now.minus(grace), handled).max()
        return next(after, zone, minutes, days)
    }

    const val NAME = "Sommeil"

    /** Activité « Sommeil » non archivée : l'activité par défaut, sinon une activité portant ce nom. */
    fun sleepActivity(activities: List<TrackedActivity>): TrackedActivity? =
        activities.firstOrNull { it.id == DefaultActivities.SLEEP && !it.archived }
            ?: activities.firstOrNull { !it.archived && it.name.equals(NAME, ignoreCase = true) }

    /** « Tous les jours », « Du lundi au vendredi », « Le week-end », « lun., mer., ven. ». */
    fun daysLabel(days: Set<Int>): String = when {
        days.size == 7 -> "Tous les jours"
        days == (1..5).toSet() -> "Du lundi au vendredi"
        days == setOf(6, 7) -> "Le week-end"
        days.isEmpty() -> "Aucun jour"
        else -> days.sorted().joinToString(", ") { SHORT_DAYS[it - 1] }
    }

    val DAY_NAMES = listOf("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche")
    private val SHORT_DAYS = listOf("lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim.")
}
