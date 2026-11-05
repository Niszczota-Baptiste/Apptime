package fr.montemps.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.domain.model.CommandSource
import fr.montemps.domain.model.DefaultActivities
import fr.montemps.domain.workouts.ProposalStatus
import fr.montemps.domain.workouts.Workout
import fr.montemps.domain.workouts.WorkoutAvailability
import fr.montemps.testutil.FakeTimeSource
import fr.montemps.testutil.FakeWorkoutSource
import fr.montemps.testutil.testContainer
import fr.montemps.testutil.useContainer
import fr.montemps.ui.workouts.WorkoutImportUi
import fr.montemps.ui.workouts.WorkoutImportViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import java.time.Instant

/** Écran « Séances sportives » : états de Health Connect et import des séances choisies. */
@RunWith(AndroidJUnit4::class)
class WorkoutImportViewModelTest {
    private val time = FakeTimeSource(now = Instant.parse("2026-11-05T18:00:00Z"))
    private val source = FakeWorkoutSource()
    private val c = testContainer(time, workouts = source)

    @Before
    fun setUp() {
        useContainer(c)
    }

    private fun workout(id: String, start: String, end: String, type: String) =
        Workout(id, Instant.parse(start), Instant.parse(end), type, null, "Garmin Connect")

    private fun <T> waitFor(what: String, value: () -> T?): T = runBlocking {
        repeat(150) {
            value()?.let { return@runBlocking it }
            ShadowLooper.idleMainLooper()
            delay(20)
        }
        fail("Condition non atteinte : $what")
        error("inatteignable")
    }

    @Test
    fun stateFollowsHealthConnectAvailabilityAndPermission() {
        source.availability = WorkoutAvailability.UNAVAILABLE
        assertEquals(WorkoutImportUi.Unavailable(needsUpdate = false), waitFor("indisponible") { WorkoutImportViewModel(c).state.value.takeUnless { it == WorkoutImportUi.Loading } })
        source.availability = WorkoutAvailability.NEEDS_UPDATE
        assertEquals(WorkoutImportUi.Unavailable(needsUpdate = true), waitFor("mise à jour") { WorkoutImportViewModel(c).state.value.takeUnless { it == WorkoutImportUi.Loading } })
        source.availability = WorkoutAvailability.AVAILABLE
        source.granted = false
        assertEquals(WorkoutImportUi.NeedsPermission, waitFor("autorisation") { WorkoutImportViewModel(c).state.value.takeUnless { it == WorkoutImportUi.Loading } })
    }

    @Test
    fun chosenWorkoutsAreAddedAsSportSessions() = runBlocking {
        source.items += workout("run", "2026-11-05T06:00:00Z", "2026-11-05T06:45:00Z", "Course à pied")
        source.items += workout("gym", "2026-11-05T11:50:00Z", "2026-11-05T12:30:00Z", "Musculation")
        c.editor.addPastSession(DefaultActivities.WORK, Instant.parse("2026-11-05T08:00:00Z"), Instant.parse("2026-11-05T12:00:00Z"), null)

        val vm = WorkoutImportViewModel(c)
        val ready = waitFor("propositions") { vm.state.value as? WorkoutImportUi.Ready }
        val byId = ready.proposals.associateBy { it.workout.id }
        assertEquals(ProposalStatus.NEW, byId.getValue("run").status)
        assertEquals(ProposalStatus.OVERLAPS, byId.getValue("gym").status)
        assertEquals("seules les séances libres sont cochées", setOf("run"), ready.selected)
        assertEquals("Sport par défaut", DefaultActivities.SPORT, ready.targetId)

        vm.import()
        waitFor("séance importée") {
            (vm.state.value as? WorkoutImportUi.Ready)?.proposals?.firstOrNull { it.workout.id == "run" && it.status == ProposalStatus.ALREADY_IMPORTED }
        }
        val imported = c.store.sessionsOverlapping(Instant.parse("2026-11-05T06:00:00Z"), Instant.parse("2026-11-05T06:45:00Z")).single()
        assertEquals(DefaultActivities.SPORT, imported.activityId)
        assertEquals(Instant.parse("2026-11-05T06:00:00Z"), imported.start)
        assertEquals(Instant.parse("2026-11-05T06:45:00Z"), imported.end)
        assertEquals("Course à pied (Garmin Connect)", imported.note)
        assertEquals(CommandSource.HealthImport.id, imported.source)
    }

    @Test
    fun chosenActivityIsUsedAndKeptAfterImport() = runBlocking {
        source.items += workout("walk", "2026-11-04T16:00:00Z", "2026-11-04T17:00:00Z", "Marche")
        val vm = WorkoutImportViewModel(c)
        waitFor("propositions") { vm.state.value as? WorkoutImportUi.Ready }
        vm.setTarget(DefaultActivities.FREE_TIME)
        vm.import()
        val after = waitFor("séance importée") {
            (vm.state.value as? WorkoutImportUi.Ready)?.takeIf { s -> s.proposals.single().status == ProposalStatus.ALREADY_IMPORTED }
        }
        assertEquals(DefaultActivities.FREE_TIME, after.targetId)
        assertEquals(DefaultActivities.FREE_TIME, c.store.recentEndedSessions(1).single().activityId)
    }
}
