package fr.montemps.domain

import fr.montemps.domain.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SessionValidatorTest {
    private val t0 = Instant.parse("2026-11-04T08:00:00Z")
    private fun t(min: Long) = t0.plusSeconds(min * 60)
    private fun s(id: Long, a: Long, b: Long?) = Session(id, "work", t(a), b?.let { t(it) }, "UTC")

    @Test
    fun adjacentSessionsDoNotOverlap() {
        assertFalse(SessionValidator.overlaps(t(0), t(10), t(10), t(20)))
        assertTrue(SessionValidator.overlaps(t(0), t(11), t(10), t(20)))
    }

    @Test
    fun activeSessionBlocksEverythingAfterItsStart() {
        val active = s(1, 60, null)
        assertNotNull(SessionValidator.findOverlap(t(100), t(110), listOf(active), excludeId = null))
        assertNotNull(SessionValidator.findOverlap(t(50), t(61), listOf(active), excludeId = null))
        assertNull(SessionValidator.findOverlap(t(50), t(60), listOf(active), excludeId = null))
    }

    @Test
    fun excludedSessionIsIgnored() {
        assertNull(SessionValidator.findOverlap(t(0), t(30), listOf(s(5, 0, 20)), excludeId = 5))
    }

    @Test
    fun completedValidation() {
        val now = t(100)
        assertEquals(SessionError.EndNotAfterStart, SessionValidator.validateCompleted(t(10), t(10), now))
        assertEquals(SessionError.EndInFuture, SessionValidator.validateCompleted(t(10), t(101), now))
        assertEquals(SessionError.StartInFuture, SessionValidator.validateCompleted(t(101), t(102), now))
        assertNull(SessionValidator.validateCompleted(t(10), t(100), now))
    }

    @Test
    fun inconsistencyDetection() {
        assertNull(SessionValidator.findInconsistency(listOf(s(1, 0, 10), s(2, 10, 20), s(3, 30, null))))
        assertNotNull(SessionValidator.findInconsistency(listOf(s(1, 0, 15), s(2, 10, 20))))
        assertNotNull(SessionValidator.findInconsistency(listOf(s(1, 0, null), s(2, 10, null))))
        assertNotNull(SessionValidator.findInconsistency(listOf(s(1, 10, 5))))
    }
}
