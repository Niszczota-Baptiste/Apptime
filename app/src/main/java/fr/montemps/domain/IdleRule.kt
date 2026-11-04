package fr.montemps.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Période d'inactivité et instant du rappel « rien n'est suivi » (peut être déjà passé : rappel dû). */
data class IdleCheck(val idleStart: Instant, val triggerAt: Instant)

/**
 * Rappel « rien n'est suivi ».
 *
 * La période d'inactivité commence au plus tard de : fin de la dernière session, début de la plage
 * horaire du jour, dernière ouverture de l'app (l'utilisateur a vu que rien n'était suivi). Le
 * rappel tombe [delay] plus tard s'il reste dans la plage du même jour, sinon le lendemain. Une
 * période déjà signalée n'est jamais rappelée deux fois.
 */
object IdleRule {
    fun next(
        lastEnd: Instant?,
        seenAt: Instant?,
        shownStart: Instant?,
        now: Instant,
        zone: ZoneId,
        delay: Duration,
        windowStartMinutes: Int,
        windowEndMinutes: Int,
    ): IdleCheck? {
        var day = now.atZone(zone).toLocalDate()
        repeat(3) {
            val windowStart = at(day, windowStartMinutes, zone)
            val windowEnd = at(day, windowEndMinutes, zone)
            val idleStart = listOfNotNull(lastEnd, seenAt, windowStart).max()
            val trigger = idleStart.plus(delay)
            if (trigger.isBefore(windowEnd) && idleStart != shownStart) return IdleCheck(idleStart, trigger)
            day = day.plusDays(1)
        }
        return null
    }

    private fun at(day: LocalDate, minutes: Int, zone: ZoneId): Instant =
        if (minutes >= 24 * 60) {
            day.plusDays(1).atStartOfDay(zone).toInstant()
        } else {
            day.atTime(LocalTime.of(minutes / 60, minutes % 60)).atZone(zone).toInstant()
        }
}
