package fr.montemps.domain

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.domain.model.CommandSource
import fr.montemps.domain.model.DefaultActivities.BREAK
import fr.montemps.domain.model.DefaultActivities.DRIVING
import fr.montemps.domain.model.DefaultActivities.SLEEP
import fr.montemps.domain.model.DefaultActivities.SPORT
import fr.montemps.domain.model.DefaultActivities.WORK
import fr.montemps.domain.model.Session
import fr.montemps.domain.report.ReportCalculator
import fr.montemps.testutil.FakeTimeSource
import fr.montemps.testutil.testContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/** Sous-catégories : valeurs par défaut, transitions, reprise, validations, bilans. */
@RunWith(AndroidJUnit4::class)
class SubActivitiesTest {
    private val time = FakeTimeSource(now = Instant.parse("2026-11-05T08:00:00Z"))
    private val c = testContainer(time)
    private val minute = Duration.ofMinutes(1)

    @Test
    fun freshInstallHasDefaultSubCategoriesDeplacementAndSommeil() = runBlocking {
        val subs = c.store.subActivities().groupBy({ it.activityId }, { it.name })
        assertEquals(listOf("Perso", "Pro"), subs[WORK])
        assertEquals(listOf("YouTube", "Jeu", "Autre"), subs[BREAK])
        assertEquals(listOf("Muscu", "Foot", "Course", "Autre"), subs[SPORT])
        assertEquals(listOf("Écrire", "Lire"), subs["writing"])
        assertEquals(listOf("Composition", "Jouer"), subs["music"])
        assertEquals(listOf("Voiture", "À pied", "Transports", "Autre"), subs[DRIVING])
        assertEquals("Déplacement", c.store.activity(DRIVING)!!.name)
        assertEquals("Sommeil", c.store.activity(SLEEP)!!.name)
        assertNull("Repas sans sous-catégorie", subs["meal"])
    }

    @Test
    fun choosingASubStartsRefinesOrSwitches() = runBlocking {
        val started = c.tracker.switchTo(WORK, subId = "work_pro") as TransitionResult.Started
        assertEquals("work_pro", started.session.subId)
        time.advance(minute)
        // Même activité, même sous-catégorie, ou sans précision : rien ne change.
        assertTrue(c.tracker.switchTo(WORK, subId = "work_pro") is TransitionResult.NoChange)
        assertTrue(c.tracker.switchTo(WORK) is TransitionResult.NoChange)
        // Autre sous-catégorie : vrai changement, sans trou.
        time.advance(minute)
        val perso = c.tracker.switchTo(WORK, subId = "work_perso") as TransitionResult.Started
        assertEquals(perso.session.start, perso.previous!!.end)
        assertEquals("work_pro", perso.previous!!.subId)
    }

    @Test
    fun aSessionWithoutSubIsRefinedInPlace() = runBlocking {
        val started = c.tracker.switchTo(SPORT) as TransitionResult.Started
        time.advance(Duration.ofMinutes(20))
        val refined = c.tracker.switchTo(SPORT, subId = "sport_running") as TransitionResult.Refined
        assertEquals("même session", started.session.id, refined.session.id)
        assertEquals("même début", started.session.start, refined.session.start)
        assertEquals("sport_running", c.store.activeSession()!!.subId)
        assertEquals(1, c.store.sessionsOverlapping(Instant.EPOCH, time.now.plusSeconds(1)).size)
    }

    @Test
    fun invalidOrRetiredSubIsRejected() = runBlocking {
        assertEquals(TransitionResult.Reason.INVALID_SUB, (c.tracker.switchTo(WORK, subId = "sport_gym") as TransitionResult.Rejected).reason)
        assertEquals(TransitionResult.Reason.INVALID_SUB, (c.tracker.switchTo(WORK, subId = "nope") as TransitionResult.Rejected).reason)
        c.catalog.setSubArchived("work_pro", true)
        assertEquals(TransitionResult.Reason.INVALID_SUB, (c.tracker.switchTo(WORK, subId = "work_pro") as TransitionResult.Rejected).reason)
        assertNull(c.store.activeSession())
    }

    @Test
    fun resumeAfterABreakKeepsTheSubCategory() = runBlocking {
        c.tracker.switchTo(WORK, subId = "work_pro")
        time.advance(Duration.ofHours(1))
        c.tracker.switchTo(BREAK, subId = "break_youtube")
        time.advance(Duration.ofMinutes(10))
        val resumed = c.tracker.resume(CommandSource.Notification) as TransitionResult.Started
        assertEquals(WORK, resumed.session.activityId)
        assertEquals("work_pro", resumed.session.subId)

        // Sous-catégorie retirée entre-temps : reprise sans précision.
        time.advance(minute)
        c.tracker.switchTo(BREAK)
        c.catalog.setSubArchived("work_pro", true)
        time.advance(minute)
        val again = c.tracker.resume() as TransitionResult.Started
        assertEquals(WORK, again.session.activityId)
        assertNull(again.session.subId)
    }

    @Test
    fun editorAcceptsOnlyASubOfTheSessionActivity() = runBlocking {
        val start = Instant.parse("2026-11-04T09:00:00Z")
        val end = Instant.parse("2026-11-04T10:00:00Z")
        assertEquals(SessionError.InvalidSub, (c.editor.addPastSession(WORK, start, end, null, subId = "sport_gym") as EditResult.Invalid).error)
        val saved = (c.editor.addPastSession(WORK, start, end, null, subId = "work_pro") as EditResult.Saved).session
        assertEquals("work_pro", saved.subId)
        // Changer d'activité en gardant la sous-catégorie de l'ancienne : refusé.
        assertEquals(SessionError.InvalidSub, (c.editor.updateSession(saved.id, SPORT, start, end, null, "work_pro") as EditResult.Invalid).error)
        val moved = (c.editor.updateSession(saved.id, SPORT, start, end, null, "sport_football") as EditResult.Saved).session
        assertEquals("sport_football", moved.subId)
        // Une sous-catégorie retirée reste acceptée sur la session qui l'avait déjà.
        c.catalog.setSubArchived("sport_football", true)
        assertTrue(c.editor.updateSession(saved.id, SPORT, start, end, "note", "sport_football") is EditResult.Saved)
    }

    @Test
    fun catalogAddsRenamesAndRestoresSubCategories() = runBlocking {
        assertTrue(c.catalog.addSub(WORK, " pro ") is SubResult.Error)
        val added = (c.catalog.addSub(WORK, "Étude") as SubResult.Ok).sub
        assertEquals(2, added.sortOrder)
        assertEquals("Révisions", (c.catalog.renameSub(added.id, "Révisions") as SubResult.Ok).sub.name)
        c.catalog.setSubArchived("work_pro", true)
        val restored = (c.catalog.addSub(WORK, "Pro") as SubResult.Ok).sub
        assertEquals("un nom retiré est rétabli avec son identifiant", "work_pro", restored.id)
        assertTrue(c.catalog.addSub(WORK, "") is SubResult.Error)
    }

    @Test
    fun databaseRejectsASubOfAnotherActivityEvenIfLogicIsBypassed() = runBlocking {
        try {
            c.store.insertSession(
                Session(activityId = WORK, start = time.now, end = time.now.plusSeconds(60), zoneId = "UTC", subId = "sport_gym"),
            )
            fail("le trigger doit refuser la sous-catégorie d'une autre activité")
        } catch (e: SQLiteConstraintException) {
            assertTrue(e.message!!.contains("invalid_sub"))
        }
    }

    @Test
    fun reportsSplitAnActivityBySubCategory() = runBlocking {
        val base = Instant.parse("2026-11-04T08:00:00Z")
        c.editor.addPastSession(WORK, base, base.plus(Duration.ofHours(2)), null, subId = "work_pro")
        c.editor.addPastSession(WORK, base.plus(Duration.ofHours(2)), base.plus(Duration.ofHours(3)), null, subId = "work_perso")
        c.editor.addPastSession(WORK, base.plus(Duration.ofHours(3)), base.plus(Duration.ofMinutes(210)), null)
        c.editor.addPastSession(SPORT, base.plus(Duration.ofHours(4)), base.plus(Duration.ofHours(5)), null)
        val report = ReportCalculator.report(c.store.allSessions(), base, base.plus(Duration.ofHours(6)), time.now)
        val work = report.totals.first { it.activityId == WORK }
        assertEquals(Duration.ofMinutes(210), work.duration)
        assertEquals(listOf("work_pro" to 120L, "work_perso" to 60L, null to 30L), work.bySub.map { it.subId to it.duration.toMinutes() })
        assertTrue("aucun détail sans sous-catégorie", report.totals.first { it.activityId == SPORT }.bySub.isEmpty())
    }
}
