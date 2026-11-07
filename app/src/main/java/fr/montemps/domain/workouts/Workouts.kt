package fr.montemps.domain.workouts

import fr.montemps.domain.model.Session
import fr.montemps.domain.model.SubActivity
import fr.montemps.domain.model.TrackedActivity
import java.time.Duration
import java.time.Instant

/** Séance sportive lue dans une source externe (Health Connect, alimenté par Garmin Connect). */
data class Workout(
    /** Identifiant de l'enregistrement dans la source. */
    val id: String,
    val start: Instant,
    val end: Instant,
    /** Type en français (« Course à pied »). */
    val type: String,
    /** Titre saisi dans l'app d'origine, s'il y en a un. */
    val title: String?,
    /** App d'origine lisible (« Garmin Connect »). */
    val source: String,
) {
    val duration: Duration get() = Duration.between(start, end)
}

enum class WorkoutAvailability { AVAILABLE, NEEDS_UPDATE, UNAVAILABLE }

/** Port de lecture des séances (Health Connect en production, faux dans les tests). */
interface WorkoutSource {
    fun availability(): WorkoutAvailability

    /** Autorisation de lecture accordée ? */
    suspend fun hasPermission(): Boolean

    suspend fun workouts(from: Instant, to: Instant): List<Workout>
}

enum class ProposalStatus {
    /** Créneau libre : peut être ajoutée à l'historique. */
    NEW,

    /** Une session occupe déjà exactement ce créneau (import précédent, saisie manuelle). */
    ALREADY_IMPORTED,

    /** Chevauche une session existante (ou une autre séance proposée) : jamais d'écrasement. */
    OVERLAPS,
}

data class WorkoutProposal(
    val workout: Workout,
    val status: ProposalStatus,
    /** Ce qui occupe déjà le créneau (nom d'activité ou type de séance). */
    val conflict: String? = null,
)

/**
 * Propositions d'import : rien n'est ajouté automatiquement, l'utilisateur choisit. Une séance ne
 * remplace jamais une session existante (chevauchement = refus, comme pour une saisie manuelle).
 */
object WorkoutImport {
    /** Séances plus courtes ignorées (déclenchements par erreur). */
    val MIN_DURATION: Duration = Duration.ofMinutes(1)

    /** Tolérance pour reconnaître une séance déjà importée (arrondis des sources). */
    val SAME_SLOT_TOLERANCE: Duration = Duration.ofMinutes(1)

    fun propose(
        workouts: List<Workout>,
        sessions: List<Session>,
        activities: Map<String, TrackedActivity>,
        now: Instant,
    ): List<WorkoutProposal> {
        val accepted = mutableListOf<Workout>()
        return workouts
            .filter { !it.end.isAfter(now) && it.duration >= MIN_DURATION }
            .distinctBy { it.id }
            .sortedBy { it.start }
            .map { w ->
                val overlapping = sessions.filter { s -> s.start.isBefore(w.end) && (s.end ?: now).isAfter(w.start) }
                val same = overlapping.firstOrNull { s ->
                    s.end != null && close(s.start, w.start) && close(s.end, w.end)
                }
                val otherWorkout = accepted.firstOrNull { it.start.isBefore(w.end) && it.end.isAfter(w.start) }
                when {
                    same != null -> WorkoutProposal(w, ProposalStatus.ALREADY_IMPORTED, activities[same.activityId]?.name)
                    overlapping.isNotEmpty() -> WorkoutProposal(w, ProposalStatus.OVERLAPS, activities[overlapping.first().activityId]?.name)
                    otherWorkout != null -> WorkoutProposal(w, ProposalStatus.OVERLAPS, otherWorkout.type)
                    else -> WorkoutProposal(w, ProposalStatus.NEW).also { accepted += w }
                }
            }
            .sortedByDescending { it.workout.start }
    }

    /** Note enregistrée sur la session importée : « Course à pied · Sortie longue (Garmin Connect) ». */
    fun note(w: Workout): String {
        val title = w.title?.trim()?.takeIf { it.isNotEmpty() && !it.equals(w.type, ignoreCase = true) }
        return listOfNotNull(w.type, title).joinToString(" · ") + " (${w.source})"
    }

    private fun close(a: Instant, b: Instant) = Duration.between(a, b).abs() <= SAME_SLOT_TOLERANCE

    /** Mots du type de séance → nom de sous-catégorie (Sport par défaut : Course, Muscu, Foot). */
    private val subHints = listOf(
        listOf("course", "running") to listOf("course", "running"),
        listOf("musculation", "renforcement", "poids du corps") to listOf("muscu", "musculation"),
        listOf("football") to listOf("foot", "football"),
        listOf("marche", "randonnée") to listOf("marche", "à pied"),
        listOf("vélo") to listOf("vélo"),
        listOf("natation") to listOf("natation"),
    )

    /**
     * Sous-catégorie à proposer pour une séance importée dans une activité qui en a : celle dont le
     * nom correspond au type (« Course à pied » → Course). Aucune correspondance : sans précision.
     */
    fun suggestSub(type: String, subs: List<SubActivity>): String? {
        val t = type.lowercase()
        val wanted = subHints.firstOrNull { (keys, _) -> keys.any { it in t } }?.second ?: return null
        return subs.firstOrNull { !it.archived && it.name.lowercase() in wanted }?.id
    }
}
