package fr.montemps.garmin

import fr.montemps.domain.model.DefaultActivities
import fr.montemps.domain.model.Session
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.watch.WatchCommand
import fr.montemps.surfaces.SurfaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Messages échangés avec l'app montre (format décrit dans docs/GARMIN.md). */
class WatchProtocolTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-11-05T14:00:00Z") // 15:00 à Paris
    private fun activity(id: String): TrackedActivity = DefaultActivities.all.first { it.id == id }
    private val work = activity(DefaultActivities.WORK)
    private val pause = activity(DefaultActivities.BREAK)
    private val sport = activity(DefaultActivities.SPORT)

    private fun session(a: TrackedActivity, start: Instant) =
        Session(id = 1, activityId = a.id, start = start, end = null, zoneId = zone.id)

    private fun state(
        active: TrackedActivity?,
        start: Instant = Instant.parse("2026-11-05T12:30:00Z"),
        resume: TrackedActivity? = null,
        last: TrackedActivity? = null,
        at: Instant = now,
        tracked: Duration = Duration.ofHours(3),
    ) = SurfaceState(
        now = at,
        zone = zone,
        active = active?.let { session(it, start) },
        activeActivity = active,
        shortcuts = listOf(work, pause, sport),
        favorites = listOf(pause),
        lastActivity = last,
        resumeActivity = resume,
        trackedToday = tracked,
        startLabel = null,
    )

    private fun parse(message: Any?) = WatchProtocol.parse(message, now)

    @Test
    fun parsesCommandsFromTheWatch() {
        assertEquals(WatchCommand.Status, parse(mapOf("v" to 1, "c" to "status")))
        assertEquals(WatchCommand.Switch("work"), parse(mapOf("v" to 1, "c" to "switch", "id" to "work", "at" to now.epochSecond.toInt())))
        assertEquals(WatchCommand.Stop(), parse(mapOf("c" to "stop")))
        // Horloge de la montre un peu en avance : c'est « maintenant ».
        assertEquals(WatchCommand.Resume(), parse(mapOf("c" to "resume", "at" to now.epochSecond + 5)))
        assertNull("activité manquante", parse(mapOf("c" to "switch")))
        assertNull("activité vide", parse(mapOf("c" to "switch", "id" to " ")))
        assertNull("commande inconnue", parse(mapOf("c" to "danse")))
        assertNull(parse("texte"))
        assertNull(parse(null))
    }

    @Test
    fun delayedCommandsKeepTheirTimeUntilTooOld() {
        val tenMinutesAgo = now.minus(Duration.ofMinutes(10))
        // Commande restée dans la file hors ligne de la montre : appliquée à son heure d'origine.
        assertEquals(WatchCommand.Switch("sport", tenMinutesAgo), parse(mapOf("c" to "switch", "id" to "sport", "at" to tenMinutesAgo.epochSecond)))
        assertEquals(WatchCommand.Stop(tenMinutesAgo), parse(mapOf("c" to "stop", "at" to tenMinutesAgo.epochSecond.toDouble())))
        // Quelques secondes de transmission : « maintenant ».
        assertEquals(WatchCommand.Stop(), parse(mapOf("c" to "stop", "at" to now.minusSeconds(20).epochSecond)))
        // Au-delà de deux jours : ignorée.
        assertEquals(WatchCommand.Expired, parse(mapOf("c" to "switch", "id" to "work", "at" to now.minus(Duration.ofDays(3)).epochSecond)))
    }

    @Test
    fun encodesTheActiveSessionForTheWatch() {
        val start = Instant.parse("2026-11-05T12:30:00Z")
        val m = WatchProtocol.encode(state(work, start))
        assertEquals(1, m["v"])
        assertEquals("state", m["t"])
        assertEquals(now.epochSecond.toInt(), m["now"])
        assertEquals("jour local du téléphone", 20261105, m["d"])
        val a = m["a"] as Map<*, *>
        assertEquals("work", a["id"])
        assertEquals("Travail", a["n"])
        assertEquals("couleur sans canal alpha", work.color and 0xFFFFFF, a["c"])
        assertEquals(start.epochSecond.toInt(), a["s"])
        assertEquals(false, a["i"])
        assertEquals(3 * 3600, m["td"])
        assertNull(m["r"])
        assertNull("« reprendre » sans suivi seulement", m["l"])
        assertEquals(listOf("work", "break", "sport"), (m["acts"] as List<*>).map { (it as Map<*, *>)["id"] })
        assertEquals(listOf("break"), m["fav"])
        assertNull(m["msg"])
    }

    @Test
    fun idleStateOffersTheLastActivityAndABreakOffersToResume() {
        val idle = WatchProtocol.encode(state(null, last = work))
        assertNull(idle["a"])
        assertEquals("work", (idle["l"] as Map<*, *>)["id"])

        val onBreak = WatchProtocol.encode(state(pause, resume = work), message = "Activité archivée")
        assertEquals(true, (onBreak["a"] as Map<*, *>)["i"])
        assertEquals("work", (onBreak["r"] as Map<*, *>)["id"])
        assertNull(onBreak["l"])
        assertEquals("Activité archivée", onBreak["msg"])
    }

    @Test
    fun onlyRealChangesAreResent() {
        val base = state(work)
        // Le temps qui passe ne justifie aucun message : la montre avance son chronomètre seule.
        assertEquals(
            WatchProtocol.fingerprint(base),
            WatchProtocol.fingerprint(state(work, at = now.plus(Duration.ofMinutes(5)), tracked = Duration.ofMinutes(185))),
        )
        assertNotEquals(WatchProtocol.fingerprint(base), WatchProtocol.fingerprint(state(sport)))
        assertNotEquals(WatchProtocol.fingerprint(base), WatchProtocol.fingerprint(state(null, last = work)))
        assertNotEquals(WatchProtocol.fingerprint(base), WatchProtocol.fingerprint(base.copy(shortcuts = listOf(work.copy(name = "Bureau"), pause))))
        assertNotEquals("nouveau jour", WatchProtocol.fingerprint(base), WatchProtocol.fingerprint(state(work, at = now.plus(Duration.ofDays(1)))))
    }
}
