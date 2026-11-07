package fr.montemps.domain

import fr.montemps.domain.model.Session
import java.time.Instant

/** Erreurs de validation d'une session saisie ou corrigée. */
sealed interface SessionError {
    data object EndNotAfterStart : SessionError
    data object EndInFuture : SessionError
    data object StartInFuture : SessionError
    data object UnknownActivity : SessionError
    /** Sous-catégorie inconnue, retirée ou d'une autre activité. */
    data object InvalidSub : SessionError
    data object NotFound : SessionError
    /** Une session active ne peut pas voir son début modifié dans cette version. */
    data object ActiveStartLocked : SessionError
    data object MissingEnd : SessionError
    data class Overlap(val other: Session) : SessionError
}

/**
 * Règles de validation pures (sans E/S). Les intervalles sont semi-ouverts [début, fin) : une
 * session peut commencer exactement à la fin d'une autre. Une session active occupe
 * [début, +∞) : rien ne peut être ajouté après son début.
 */
object SessionValidator {

    fun validateCompleted(start: Instant, end: Instant?, now: Instant): SessionError? {
        if (end == null) return SessionError.MissingEnd
        if (start.isAfter(now)) return SessionError.StartInFuture
        if (!end.isAfter(start)) return SessionError.EndNotAfterStart
        if (end.isAfter(now)) return SessionError.EndInFuture
        return null
    }

    /** Première session de [others] qui chevauche [start, end) (end == null : intervalle ouvert). */
    fun findOverlap(start: Instant, end: Instant?, others: List<Session>, excludeId: Long?): Session? =
        others.firstOrNull { other ->
            other.id != excludeId && overlaps(start, end, other.start, other.end)
        }

    fun overlaps(aStart: Instant, aEnd: Instant?, bStart: Instant, bEnd: Instant?): Boolean {
        val aEndsAfterBStarts = aEnd == null || aEnd.isAfter(bStart)
        val bEndsAfterAStarts = bEnd == null || bEnd.isAfter(aStart)
        return aEndsAfterBStarts && bEndsAfterAStarts
    }

    /** Vérifie qu'une liste complète de sessions (ex. sauvegarde) est cohérente. */
    fun findInconsistency(sessions: List<Session>): String? {
        val sorted = sessions.sortedBy { it.start }
        if (sorted.count { it.isActive } > 1) return "Plusieurs sessions sont marquées en cours."
        for (s in sorted) {
            if (s.end != null && !s.end.isAfter(s.start)) return "Une session se termine avant de commencer (${s.start})."
        }
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (overlaps(a.start, a.end, b.start, b.end)) {
                return "Deux sessions se chevauchent (${a.start} et ${b.start})."
            }
        }
        return null
    }
}
