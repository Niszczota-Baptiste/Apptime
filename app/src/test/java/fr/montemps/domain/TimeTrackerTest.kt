package fr.montemps.domain

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.data.db.MonTempsDatabase
import fr.montemps.data.db.SessionEntity
import fr.montemps.domain.TransitionResult.Reason
import fr.montemps.domain.model.CommandSource
import fr.montemps.domain.model.DefaultActivities.BREAK
import fr.montemps.domain.model.DefaultActivities.MEAL
import fr.montemps.domain.model.DefaultActivities.WORK
import fr.montemps.testutil.FakeTimeSource
import fr.montemps.testutil.context
import fr.montemps.testutil.testContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class TimeTrackerTest {
    private val time = FakeTimeSource()
    private val c = testContainer(time)

    @Test
    fun defaultActivitiesAreSeeded() = runBlocking {
        val names = c.store.activities().map { it.name }
        assertEquals(
            listOf("Travail", "Déplacement", "Pause", "Repas", "Coréen", "Sport", "Écriture", "Musique", "Temps libre", "Autre", "Sommeil"),
            names,
        )
    }

    @Test
    fun startThenStop() = runBlocking {
        val started = c.tracker.switchTo(WORK) as TransitionResult.Started
        assertNull(started.previous)
        assertEquals(WORK, c.tracker.currentSession()?.activityId)

        time.advance(Duration.ofMinutes(42))
        val stopped = c.tracker.stop() as TransitionResult.Stopped
        assertEquals(Duration.ofMinutes(42), stopped.session.duration(time.now))
        assertNull(c.tracker.currentSession())
        // Arrêter à nouveau ne fait rien : le temps reste non suivi.
        assertTrue(c.tracker.stop() is TransitionResult.NoChange)
        assertEquals(1, c.store.allSessions().size)
    }

    @Test
    fun switchingEndsPreviousAtExactlyTheSameInstant() = runBlocking {
        c.tracker.switchTo(WORK)
        time.advance(Duration.ofMinutes(30))
        val r = c.tracker.switchTo(BREAK) as TransitionResult.Started
        assertEquals(r.previous!!.end, r.session.start)
        time.advance(Duration.ofMinutes(10))
        c.tracker.switchTo(MEAL)

        val all = c.store.allSessions()
        assertEquals(3, all.size)
        for (i in 0 until all.size - 1) assertEquals("pas de trou ni chevauchement", all[i].end, all[i + 1].start)
        assertEquals(1, all.count { it.isActive })
    }

    @Test
    fun tappingActiveActivityAgainDoesNothing() = runBlocking {
        val first = c.tracker.switchTo(WORK) as TransitionResult.Started
        time.advance(Duration.ofSeconds(1))
        repeat(5) { assertTrue(c.tracker.switchTo(WORK) is TransitionResult.NoChange) }
        assertEquals(listOf(first.session.id), c.store.allSessions().map { it.id })
    }

    @Test
    fun pauseIsATrackedActivityNotAStop() = runBlocking {
        c.tracker.switchTo(BREAK)
        time.advance(Duration.ofMinutes(15))
        assertEquals(BREAK, c.tracker.currentSession()?.activityId)
        assertEquals(Duration.ofMinutes(15), c.tracker.currentSession()!!.duration(time.now))
    }

    @Test
    fun concurrentTransitionsNeverCreateSimultaneousSessions() = runBlocking {
        val targets = listOf(WORK, BREAK, MEAL)
        repeat(10) { round ->
            time.advance(Duration.ofSeconds(1))
            (0 until 30).map { i ->
                async(Dispatchers.Default) {
                    if (i % 7 == 0) c.tracker.stop() else c.tracker.switchTo(targets[(i + round) % 3])
                }
            }.awaitAll()
        }
        val all = c.store.allSessions().sortedBy { it.start }
        assertTrue(all.count { it.isActive } <= 1)
        for (i in 0 until all.size - 1) {
            assertTrue("chevauchement entre ${all[i]} et ${all[i + 1]}", !all[i].end!!.isAfter(all[i + 1].start))
        }
        all.filter { !it.isActive }.forEach { assertTrue(it.end!!.isAfter(it.start)) }
    }

    @Test
    fun repeatedNotificationStopWithStaleSessionIdIsIgnored() = runBlocking {
        val a = (c.tracker.switchTo(WORK) as TransitionResult.Started).session
        time.advance(Duration.ofMinutes(5))
        c.tracker.switchTo(BREAK)
        time.advance(Duration.ofMinutes(5))
        // Ancienne action « Arrêter » visant la session Travail : ne doit pas arrêter la Pause.
        assertTrue(c.tracker.stop(CommandSource.Notification, expectedSessionId = a.id) is TransitionResult.NoChange)
        assertEquals(BREAK, c.tracker.currentSession()?.activityId)
    }

    @Test
    fun archivedOrUnknownActivityIsRejected() = runBlocking {
        c.catalog.setArchived(MEAL, true)
        assertEquals(TransitionResult.Rejected(Reason.ARCHIVED_ACTIVITY), c.tracker.switchTo(MEAL))
        assertEquals(TransitionResult.Rejected(Reason.UNKNOWN_ACTIVITY), c.tracker.switchTo("nope"))
        assertNull(c.tracker.currentSession())
    }

    @Test
    fun clockSetBackBeforeLastRecordIsRejectedInsteadOfCreatingOverlap() = runBlocking {
        c.tracker.switchTo(WORK)
        time.advance(Duration.ofHours(1))
        c.tracker.stop()
        time.now = time.now.minus(Duration.ofHours(2)) // l'utilisateur recule l'heure système
        assertEquals(TransitionResult.Rejected(Reason.CLOCK_BEFORE_LAST_RECORD), c.tracker.switchTo(BREAK))
        assertNull(c.tracker.currentSession())
    }

    @Test
    fun forgottenSessionCanBeEndedAtAPastTime() = runBlocking {
        val s = (c.tracker.switchTo(WORK) as TransitionResult.Started).session
        time.advance(Duration.ofHours(14))
        val end = s.start.plus(Duration.ofHours(9))
        assertEquals(TransitionResult.Rejected(Reason.INVALID_END_TIME), c.tracker.stopAt(s.id, s.start))
        assertEquals(TransitionResult.Rejected(Reason.INVALID_END_TIME), c.tracker.stopAt(s.id, time.now.plusSeconds(60)))
        val r = c.tracker.stopAt(s.id, end) as TransitionResult.Stopped
        assertEquals(end, r.session.end)
        assertNull(c.tracker.currentSession())
    }

    @Test
    fun databaseRejectsSecondActiveSessionEvenIfLogicIsBypassed() = runBlocking {
        c.tracker.switchTo(WORK)
        val dao = MonTempsDatabase.build(context(), inMemory = true).also { db ->
            db.sessionDao().insert(SessionEntity(activityId = WORK, startUtcMs = 0, endUtcMs = null, zoneId = "UTC", note = null, startElapsedMs = null, startBootCount = null, source = "test"))
        }.sessionDao()
        try {
            dao.insert(SessionEntity(activityId = BREAK, startUtcMs = 10, endUtcMs = null, zoneId = "UTC", note = null, startElapsedMs = null, startBootCount = null, source = "test"))
            fail("Le trigger aurait dû refuser une seconde session active")
        } catch (e: SQLiteConstraintException) {
            assertTrue(e.message!!.contains("active_session_exists"))
        }
        try {
            dao.insert(SessionEntity(activityId = BREAK, startUtcMs = 10, endUtcMs = 10, zoneId = "UTC", note = null, startElapsedMs = null, startBootCount = null, source = "test"))
            fail("Le trigger aurait dû refuser une fin non postérieure au début")
        } catch (e: SQLiteConstraintException) {
            assertTrue(e.message!!.contains("invalid_range"))
        }
    }

    @Test
    fun activeSessionIsRebuiltAfterProcessRecreation() = runBlocking {
        val name = "recreate-${System.nanoTime()}.db"
        fun build() = androidx.room.Room.databaseBuilder(context(), MonTempsDatabase::class.java, name)
            .addCallback(MonTempsDatabase.Callbacks).build()

        val db1 = build()
        val first = testContainer(time, db1)
        val started = (first.tracker.switchTo(WORK) as TransitionResult.Started).session
        db1.close() // le processus est détruit : rien d'autre que la base ne survit

        time.advance(Duration.ofHours(3)) // y compris un éventuel redémarrage
        time.boot = 8
        time.elapsed = 5_000
        val second = testContainer(time, build())
        val restored = second.tracker.currentSession()!!
        assertEquals(started.id, restored.id)
        assertEquals(Instant.parse("2026-11-04T08:00:00Z"), restored.start)
        assertEquals(Duration.ofHours(3), restored.duration(time.now))
        // Le suivi continue normalement après reconstruction.
        val r = second.tracker.switchTo(BREAK) as TransitionResult.Started
        assertEquals(time.now, r.previous!!.end)
        File(context().getDatabasePath(name).path).delete()
        Unit
    }
}
