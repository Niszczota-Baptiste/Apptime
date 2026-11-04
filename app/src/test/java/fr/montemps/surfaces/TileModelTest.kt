package fr.montemps.surfaces

import fr.montemps.domain.model.Session
import fr.montemps.domain.model.TrackedActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class TileModelTest {
    private val work = TrackedActivity("work", "Travail", 0, null, 0, false)
    private val now = Instant.parse("2026-11-04T14:42:00Z")

    private fun state(active: Session? = null, last: TrackedActivity? = null) = SurfaceState(
        now = now,
        zone = ZoneId.of("Europe/Paris"),
        active = active,
        activeActivity = active?.let { work },
        shortcuts = listOf(work),
        favorites = emptyList(),
        lastActivity = last,
        trackedToday = Duration.ZERO,
        startLabel = active?.let { "15:20" },
    )

    @Test
    fun activeSessionTileStopsThatSession() {
        val m = TileModel.from(state(active = Session(id = 7, activityId = "work", start = now.minusSeconds(60), end = null, zoneId = "Europe/Paris")))
        assertTrue(m.active)
        assertEquals("Travail", m.label)
        assertEquals("Depuis 15:20", m.subtitle)
        assertEquals(TileAction.Stop(7), m.action)
    }

    @Test
    fun idleTileResumesLastActivity() {
        val m = TileModel.from(state(last = work))
        assertFalse(m.active)
        assertEquals("Reprendre Travail", m.subtitle)
        assertEquals(TileAction.Resume("work"), m.action)
    }

    @Test
    fun idleTileWithoutHistoryOpensApp() {
        val m = TileModel.from(state())
        assertEquals("Mon Temps", m.label)
        assertEquals(TileAction.OpenApp, m.action)
    }
}
