package fr.montemps.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.domain.model.DefaultActivities.BREAK
import fr.montemps.domain.model.DefaultActivities.MEAL
import fr.montemps.domain.model.DefaultActivities.WORK
import fr.montemps.domain.report.ReportCalculator
import fr.montemps.testutil.FakeTimeSource
import fr.montemps.testutil.plusHours
import fr.montemps.testutil.plusMinutes
import fr.montemps.testutil.testContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class SessionEditorTest {
    private val time = FakeTimeSource(now = Instant.parse("2026-11-04T18:00:00Z"))
    private val c = testContainer(time)
    private val morning = Instant.parse("2026-11-04T07:00:00Z")

    private fun invalid(r: EditResult) = (r as EditResult.Invalid).error

    @Test
    fun addForgottenPastSession() = runBlocking {
        val r = c.editor.addPastSession(WORK, morning, morning.plusHours(2), "  réunion ") as EditResult.Saved
        assertEquals("réunion", r.session.note)
        assertEquals(Duration.ofHours(2), r.session.duration(time.now))
    }

    @Test
    fun rejectsInvalidRanges() = runBlocking {
        assertEquals(SessionError.EndNotAfterStart, invalid(c.editor.addPastSession(WORK, morning, morning, null)))
        assertEquals(SessionError.EndNotAfterStart, invalid(c.editor.addPastSession(WORK, morning, morning.minusSeconds(60), null)))
        assertEquals(SessionError.EndInFuture, invalid(c.editor.addPastSession(WORK, time.now.minusSeconds(60), time.now.plusSeconds(60), null)))
        assertEquals(SessionError.UnknownActivity, invalid(c.editor.addPastSession("inconnue", morning, morning.plusHours(1), null)))
    }

    @Test
    fun rejectsOverlapWithCompletedAndActiveSessions() = runBlocking {
        c.editor.addPastSession(WORK, morning, morning.plusHours(2), null)
        val e = invalid(c.editor.addPastSession(BREAK, morning.plusHours(1), morning.plusHours(3), null))
        assertTrue(e is SessionError.Overlap)
        // Adjacent : autorisé.
        assertTrue(c.editor.addPastSession(BREAK, morning.plusHours(2), morning.plusHours(3), null) is EditResult.Saved)

        // Session active démarrée à 16:00 : aucune session passée ne peut empiéter dessus.
        time.now = Instant.parse("2026-11-04T16:00:00Z")
        c.tracker.switchTo(MEAL)
        time.now = Instant.parse("2026-11-04T18:00:00Z")
        val e2 = invalid(c.editor.addPastSession(WORK, Instant.parse("2026-11-04T15:30:00Z"), Instant.parse("2026-11-04T16:30:00Z"), null))
        assertTrue(e2 is SessionError.Overlap && e2.other.isActive)
        assertTrue(c.editor.addPastSession(WORK, Instant.parse("2026-11-04T15:00:00Z"), Instant.parse("2026-11-04T16:00:00Z"), null) is EditResult.Saved)
    }

    @Test
    fun updateCreatingOverlapIsRefusedAndLeavesDataUnchanged() = runBlocking {
        val a = (c.editor.addPastSession(WORK, morning, morning.plusHours(1), null) as EditResult.Saved).session
        val b = (c.editor.addPastSession(BREAK, morning.plusHours(2), morning.plusHours(3), null) as EditResult.Saved).session
        val r = c.editor.updateSession(b.id, BREAK, morning.plusMinutes(30), b.end, null)
        assertTrue(invalid(r) is SessionError.Overlap)
        assertEquals(b, c.store.session(b.id))
        assertEquals(a, c.store.session(a.id))
    }

    @Test
    fun activeSessionStartIsLockedButActivityAndNoteEditable() = runBlocking {
        val s = (c.tracker.switchTo(WORK) as TransitionResult.Started).session
        assertEquals(SessionError.ActiveStartLocked, invalid(c.editor.updateSession(s.id, WORK, s.start.minusSeconds(600), null, null)))
        val r = c.editor.updateSession(s.id, BREAK, s.start, null, "corrigé") as EditResult.Saved
        assertEquals(BREAK, r.session.activityId)
        assertTrue(r.session.isActive)
    }

    @Test
    fun multiDaySessionCanBeRecordedAndIsSplitInReports() = runBlocking {
        time.now = Instant.parse("2026-11-10T12:00:00Z")
        val start = Instant.parse("2026-11-05T21:00:00Z") // 22:00 à Paris
        val end = Instant.parse("2026-11-07T05:00:00Z") // 06:00 à Paris
        assertTrue(c.editor.addPastSession(WORK, start, end, null) is EditResult.Saved)
        val sessions = c.store.allSessions()
        val zone = time.zone
        assertEquals(Duration.ofHours(2), ReportCalculator.dayReport(sessions, LocalDate.of(2026, 11, 5), zone, time.now).tracked)
        assertEquals(Duration.ofHours(24), ReportCalculator.dayReport(sessions, LocalDate.of(2026, 11, 6), zone, time.now).tracked)
        assertEquals(Duration.ofHours(6), ReportCalculator.dayReport(sessions, LocalDate.of(2026, 11, 7), zone, time.now).tracked)
    }

    @Test
    fun reportsRefreshAfterCorrection() = runBlocking {
        val (from, to) = ReportCalculator.dayBounds(LocalDate.of(2026, 11, 4), time.zone)
        val flow = c.store.observeSessionsOverlapping(from, to)
        val s = (c.editor.addPastSession(WORK, morning, morning.plusHours(1), null) as EditResult.Saved).session
        assertEquals(Duration.ofHours(1), ReportCalculator.report(flow.first(), from, to, time.now).tracked)

        c.editor.updateSession(s.id, MEAL, morning, morning.plusHours(3), null)
        val after = ReportCalculator.report(flow.first(), from, to, time.now)
        assertEquals(Duration.ofHours(3), after.tracked)
        assertEquals(listOf(MEAL), after.totals.map { it.activityId })

        c.editor.deleteSession(s.id)
        assertTrue(ReportCalculator.report(flow.first(), from, to, time.now).isEmpty)
    }

    @Test
    fun renamingKeepsHistoryAttachedToSameId() = runBlocking {
        c.editor.addPastSession(WORK, morning, morning.plusHours(1), null)
        val activity = c.store.activity(WORK)!!
        c.catalog.edit(WORK, "Boulot", activity.color, activity.icon)
        assertEquals("Boulot", c.store.activity(WORK)!!.name)
        assertEquals(WORK, c.store.allSessions().single().activityId)
        // Archivage : historique préservé.
        c.catalog.setArchived(WORK, true)
        assertEquals(1, c.store.allSessions().size)
    }
}
