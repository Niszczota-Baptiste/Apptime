package fr.montemps.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.domain.TransitionResult.Reason
import fr.montemps.domain.model.DefaultActivities.BREAK
import fr.montemps.domain.model.DefaultActivities.MEAL
import fr.montemps.domain.model.DefaultActivities.WORK
import fr.montemps.testutil.FakeTimeSource
import fr.montemps.testutil.testContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class UndoAndBackdateTest {
    private val time = FakeTimeSource()
    private val c = testContainer(time)

    @Test
    fun undoingASwitchReopensThePreviousSession() = runBlocking {
        val work = (c.tracker.switchTo(WORK) as TransitionResult.Started).session
        time.advance(Duration.ofMinutes(40))
        val r = c.tracker.switchTo(BREAK) as TransitionResult.Started // appui accidentel
        time.advance(Duration.ofSeconds(3))
        assertTrue(c.tracker.undo(r.undoToken()!!))
        val active = c.tracker.currentSession()!!
        assertEquals(work.id, active.id)
        assertNull(active.end)
        assertEquals(listOf(work.id), c.store.allSessions().map { it.id })
        // Un second « Annuler » est refusé (rien à annuler).
        assertFalse(c.tracker.undo(r.undoToken()!!))
    }

    @Test
    fun undoingAStartOrAStop() = runBlocking {
        val started = c.tracker.switchTo(WORK) as TransitionResult.Started
        time.advance(Duration.ofSeconds(2))
        assertTrue(c.tracker.undo(started.undoToken()!!))
        assertNull(c.tracker.currentSession())
        assertTrue(c.store.allSessions().isEmpty())

        c.tracker.switchTo(MEAL)
        time.advance(Duration.ofMinutes(30))
        val stopped = c.tracker.stop() as TransitionResult.Stopped
        time.advance(Duration.ofSeconds(2))
        assertTrue(c.tracker.undo(stopped.undoToken()!!))
        assertEquals(MEAL, c.tracker.currentSession()?.activityId)
    }

    @Test
    fun undoIsRefusedOnceSomethingElseChanged() = runBlocking {
        c.tracker.switchTo(WORK)
        time.advance(Duration.ofMinutes(10))
        val toBreak = c.tracker.switchTo(BREAK) as TransitionResult.Started
        time.advance(Duration.ofMinutes(1))
        c.tracker.switchTo(MEAL) // nouvelle transition entre-temps
        assertFalse(c.tracker.undo(toBreak.undoToken()!!))
        assertEquals(MEAL, c.tracker.currentSession()?.activityId)
        assertEquals(3, c.store.allSessions().size)
    }

    @Test
    fun startingEarlierSplitsTheRunningSessionWithoutOverlap() = runBlocking {
        val work = (c.tracker.switchTo(WORK) as TransitionResult.Started).session
        time.advance(Duration.ofMinutes(30))
        val at = time.now.minus(Duration.ofMinutes(10))
        val r = c.tracker.switchToAt(BREAK, at) as TransitionResult.Started
        assertEquals(at, r.previous!!.end)
        assertEquals(at, r.session.start)
        assertEquals(work.start, r.previous!!.start)
        // Horloge monotone ramenée au début réel : pas de fausse alerte « heure modifiée ».
        assertEquals(time.elapsed - Duration.ofMinutes(10).toMillis(), r.session.startElapsedRealtimeMs)
        // Annulable comme un changement normal.
        assertTrue(c.tracker.undo(r.undoToken()!!))
        assertEquals(work.id, c.tracker.currentSession()!!.id)
    }

    @Test
    fun startingEarlierIsBoundedByTheRunningAndPreviousSessions() = runBlocking {
        val work = (c.tracker.switchTo(WORK) as TransitionResult.Started).session
        time.advance(Duration.ofMinutes(5))
        assertEquals(BackdateLimit(work.start, exclusive = true), c.tracker.backdateLimit())
        assertEquals(TransitionResult.Rejected(Reason.START_TOO_EARLY), c.tracker.switchToAt(BREAK, work.start))
        assertEquals(TransitionResult.Rejected(Reason.START_TOO_EARLY), c.tracker.switchToAt(BREAK, work.start.minusSeconds(60)))
        assertEquals(TransitionResult.Rejected(Reason.START_IN_FUTURE), c.tracker.switchToAt(BREAK, time.now.plusSeconds(60)))
        assertTrue(c.tracker.switchToAt(WORK, time.now.minusSeconds(60)) is TransitionResult.NoChange)

        time.advance(Duration.ofMinutes(5))
        val stopped = c.tracker.stop() as TransitionResult.Stopped
        time.advance(Duration.ofMinutes(20))
        // Sans suivi : pas avant la fin de la dernière session (incluse).
        assertEquals(BackdateLimit(stopped.session.end, exclusive = false), c.tracker.backdateLimit())
        assertEquals(TransitionResult.Rejected(Reason.START_TOO_EARLY), c.tracker.switchToAt(MEAL, stopped.session.end!!.minusSeconds(1)))
        val ok = c.tracker.switchToAt(MEAL, stopped.session.end!!) as TransitionResult.Started
        assertNull(ok.previous)
        assertEquals(stopped.session.end, ok.session.start)
    }
}
