package fr.montemps.domain

import fr.montemps.domain.model.DefaultActivities
import fr.montemps.domain.model.DefaultActivities.SLEEP
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.sleep.SleepSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Échéances du coucher et du réveil (logique pure, sans Android). */
class SleepScheduleTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(s: String): Instant = LocalDateTime.parse(s).atZone(paris).toInstant()
    private val everyDay = (1..7).toSet()

    @Test
    fun nextOccurrenceFollowsTheChosenTimeAndDays() {
        // Mercredi 4 novembre 2026, 15:00.
        val now = at("2026-11-04T15:00")
        assertEquals(at("2026-11-04T23:00"), SleepSchedule.next(now, paris, 23 * 60, everyDay))
        assertEquals("heure passée aujourd'hui : demain", at("2026-11-05T07:00"), SleepSchedule.next(now, paris, 7 * 60, everyDay))
        assertEquals("week-end seulement : samedi", at("2026-11-07T07:00"), SleepSchedule.next(now, paris, 7 * 60, setOf(6, 7)))
        assertEquals("lundi seulement", at("2026-11-09T06:30"), SleepSchedule.next(now, paris, 6 * 60 + 30, setOf(1)))
        assertEquals(
            "exactement à l'heure : l'échéance suivante",
            at("2026-11-05T23:00"),
            SleepSchedule.next(at("2026-11-04T23:00"), paris, 23 * 60, everyDay),
        )
        assertNull("aucun jour choisi", SleepSchedule.next(now, paris, 23 * 60, emptySet()))
    }

    @Test
    fun daylightSavingDaysFollowJavaTime() {
        // 28 mars 2027 : 02:30 n'existe pas (passage à l'heure d'été) → 03:30 heure d'été.
        assertEquals(
            Instant.parse("2027-03-28T01:30:00Z"),
            SleepSchedule.next(Instant.parse("2027-03-27T12:00:00Z"), paris, 2 * 60 + 30, everyDay),
        )
        // 25 octobre 2026 : 02:30 existe deux fois → la première (heure d'été, UTC+2).
        assertEquals(
            Instant.parse("2026-10-25T00:30:00Z"),
            SleepSchedule.next(Instant.parse("2026-10-24T12:00:00Z"), paris, 2 * 60 + 30, everyDay),
        )
        // Le réveil de 07:00 reste à 07:00 locale de part et d'autre du changement d'heure.
        assertEquals(at("2026-10-25T07:00"), SleepSchedule.next(at("2026-10-24T08:00"), paris, 7 * 60, everyDay))
        assertEquals(at("2026-10-26T07:00"), SleepSchedule.next(at("2026-10-25T08:00"), paris, 7 * 60, everyDay))
    }

    @Test
    fun aLateOccurrenceIsKeptWithinGraceOnlyAndOnlyOnce() {
        val bedtime = 23 * 60
        val grace = SleepSchedule.BEDTIME_GRACE
        // 23:40, rien de traité : le coucher de 23:00 est encore à appliquer (alarme en retard).
        assertEquals(at("2026-11-04T23:00"), SleepSchedule.due(at("2026-11-04T23:40"), null, grace, paris, bedtime, everyDay))
        // Déjà traité : la prochaine échéance.
        assertEquals(
            at("2026-11-05T23:00"),
            SleepSchedule.due(at("2026-11-04T23:40"), at("2026-11-04T23:00"), grace, paris, bedtime, everyDay),
        )
        // Plus de 2 h de retard (téléphone éteint) : abandonné.
        assertEquals(at("2026-11-05T23:00"), SleepSchedule.due(at("2026-11-05T01:30"), null, grace, paris, bedtime, everyDay))
        // Réglage modifié à 23:30 (traité jusqu'à maintenant) : rien de rétroactif.
        assertEquals(
            at("2026-11-05T23:00"),
            SleepSchedule.due(at("2026-11-04T23:30"), at("2026-11-04T23:30"), grace, paris, bedtime, everyDay),
        )
        // Réveil : 12 h de grâce.
        assertEquals(
            at("2026-11-05T07:00"),
            SleepSchedule.due(at("2026-11-05T18:00"), null, SleepSchedule.WAKE_GRACE, paris, 7 * 60, everyDay),
        )
    }

    @Test
    fun daysAreSummarizedInPlainFrench() {
        assertEquals("Tous les jours", SleepSchedule.daysLabel(everyDay))
        assertEquals("Du lundi au vendredi", SleepSchedule.daysLabel((1..5).toSet()))
        assertEquals("Le week-end", SleepSchedule.daysLabel(setOf(6, 7)))
        assertEquals("lun., mer., ven.", SleepSchedule.daysLabel(setOf(5, 1, 3)))
        assertEquals("Aucun jour", SleepSchedule.daysLabel(emptySet()))
    }

    @Test
    fun sleepActivityIsTheDefaultOneOrAnActivityNamedSommeil() {
        val defaults = DefaultActivities.all
        assertEquals(SLEEP, SleepSchedule.sleepActivity(defaults)!!.id)
        val custom = defaults.map { if (it.id == SLEEP) it.copy(archived = true) else it } +
            TrackedActivity("perso_1", "sommeil", 0xFF000000.toInt(), null, 20, archived = false)
        assertEquals("perso_1", SleepSchedule.sleepActivity(custom)!!.id)
        assertNull(SleepSchedule.sleepActivity(defaults.filter { it.id != SLEEP }))
    }
}
