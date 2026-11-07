package fr.montemps.domain

import fr.montemps.domain.model.DefaultActivities
import fr.montemps.domain.model.Session
import fr.montemps.domain.workouts.ProposalStatus
import fr.montemps.domain.workouts.Workout
import fr.montemps.domain.workouts.WorkoutImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Propositions d'import des séances (Health Connect) : jamais d'écrasement, jamais de doublon. */
class WorkoutImportTest {
    private val now = Instant.parse("2026-11-05T18:00:00Z")
    private val activities = DefaultActivities.all.associateBy { it.id }

    private fun workout(id: String, start: String, end: String, type: String = "Course à pied", source: String = "Garmin Connect") =
        Workout(id, Instant.parse(start), Instant.parse(end), type, null, source)

    private fun session(activity: String, start: String, end: String?) =
        Session(activityId = activity, start = Instant.parse(start), end = end?.let(Instant::parse), zoneId = "Europe/Paris")

    private fun propose(workouts: List<Workout>, sessions: List<Session> = emptyList()) =
        WorkoutImport.propose(workouts, sessions, activities, now)

    @Test
    fun freeSlotsAreProposedNewestFirst() {
        val p = propose(
            listOf(
                workout("a", "2026-11-03T06:00:00Z", "2026-11-03T06:45:00Z"),
                workout("b", "2026-11-04T17:00:00Z", "2026-11-04T18:00:00Z", type = "Vélo"),
            ),
        )
        assertEquals(listOf("b", "a"), p.map { it.workout.id })
        assertEquals(listOf(ProposalStatus.NEW, ProposalStatus.NEW), p.map { it.status })
    }

    @Test
    fun sameSlotIsRecognisedAsAlreadyImported() {
        // Arrondis de la source : une minute d'écart reste « le même créneau ».
        val p = propose(
            listOf(workout("a", "2026-11-03T06:00:00Z", "2026-11-03T06:45:00Z")),
            listOf(session(DefaultActivities.SPORT, "2026-11-03T06:00:30Z", "2026-11-03T06:44:40Z")),
        ).single()
        assertEquals(ProposalStatus.ALREADY_IMPORTED, p.status)
        assertEquals("Sport", p.conflict)
    }

    @Test
    fun overlapWithAnExistingOrActiveSessionIsNeverImportable() {
        val p = propose(
            listOf(
                workout("a", "2026-11-05T11:50:00Z", "2026-11-05T12:20:00Z"),
                workout("b", "2026-11-05T16:30:00Z", "2026-11-05T17:10:00Z"),
            ),
            listOf(
                session(DefaultActivities.WORK, "2026-11-05T08:00:00Z", "2026-11-05T12:00:00Z"),
                // Session en cours : elle occupe le temps jusqu'à maintenant.
                session(DefaultActivities.MUSIC, "2026-11-05T17:00:00Z", null),
            ),
        ).associateBy { it.workout.id }
        assertEquals(ProposalStatus.OVERLAPS, p.getValue("a").status)
        assertEquals("Travail", p.getValue("a").conflict)
        assertEquals(ProposalStatus.OVERLAPS, p.getValue("b").status)
        assertEquals("Musique", p.getValue("b").conflict)
    }

    @Test
    fun theSameWorkoutFromTwoAppsIsProposedOnce() {
        val p = propose(
            listOf(
                workout("garmin", "2026-11-04T17:00:00Z", "2026-11-04T18:00:00Z"),
                workout("autre", "2026-11-04T17:01:00Z", "2026-11-04T17:59:00Z", source = "Strava"),
            ),
        ).associateBy { it.workout.id }
        assertEquals(ProposalStatus.NEW, p.getValue("garmin").status)
        assertEquals(ProposalStatus.OVERLAPS, p.getValue("autre").status)
        assertEquals("Course à pied", p.getValue("autre").conflict)
    }

    @Test
    fun ongoingAndTooShortWorkoutsAreIgnored() {
        val p = propose(
            listOf(
                workout("en-cours", "2026-11-05T17:30:00Z", "2026-11-05T18:30:00Z"),
                workout("bref", "2026-11-05T10:00:00Z", "2026-11-05T10:00:30Z"),
                workout("ok", "2026-11-05T10:00:00Z", "2026-11-05T10:20:00Z"),
            ),
        )
        assertEquals(listOf("ok"), p.map { it.workout.id })
    }

    @Test
    fun noteNamesTypeTitleAndSource() {
        val w = workout("a", "2026-11-03T06:00:00Z", "2026-11-03T06:45:00Z")
        assertEquals("Course à pied (Garmin Connect)", WorkoutImport.note(w))
        assertEquals("Course à pied · Sortie longue (Garmin Connect)", WorkoutImport.note(w.copy(title = " Sortie longue ")))
        assertEquals("titre identique au type : pas de répétition", "Course à pied (Garmin Connect)", WorkoutImport.note(w.copy(title = "course à pied")))
    }

    @Test
    fun subCategoryIsSuggestedFromTheWorkoutType() {
        val sport = DefaultActivities.defaultSubs.filter { it.activityId == DefaultActivities.SPORT }
        assertEquals("sport_running", WorkoutImport.suggestSub("Course à pied", sport))
        assertEquals("sport_gym", WorkoutImport.suggestSub("Renforcement musculaire", sport))
        assertEquals("sport_football", WorkoutImport.suggestSub("Football", sport))
        assertNull("aucune correspondance : sans précision", WorkoutImport.suggestSub("Vélo", sport))
        val retired = sport.map { if (it.id == "sport_running") it.copy(archived = true) else it }
        assertNull("sous-catégorie retirée : sans précision", WorkoutImport.suggestSub("Course à pied", retired))
        val walk = DefaultActivities.defaultSubs.filter { it.activityId == DefaultActivities.DRIVING }
        assertEquals("randonnée importée en Déplacement", "driving_walk", WorkoutImport.suggestSub("Randonnée", walk))
        assertNull("activité sans sous-catégorie", WorkoutImport.suggestSub("Course à pied", emptyList()))
    }
}
