package fr.montemps.domain

import fr.montemps.domain.model.CommandSource
import fr.montemps.domain.model.Session
import fr.montemps.domain.time.TimeSource
import java.time.Instant

sealed interface EditResult {
    data class Saved(val session: Session) : EditResult
    data class Invalid(val error: SessionError) : EditResult
}

/**
 * Corrections de l'historique : ajout d'une session oubliée, modification, suppression.
 * Les validations et l'écriture se font dans une même transaction, sous le verrou d'écriture
 * partagé avec [TimeTracker], ce qui évite toute course avec un changement d'activité.
 */
class SessionEditor(
    private val store: TrackingStore,
    private val time: TimeSource,
    private val lock: WriteLock,
    private val listener: ChangeListener = ChangeListener { },
) {

    suspend fun addPastSession(activityId: String, start: Instant, end: Instant, note: String?): EditResult {
        val result = lock.withLock {
            store.transaction {
                validateActivity(activityId)?.let { return@transaction EditResult.Invalid(it) }
                val now = time.now()
                SessionValidator.validateCompleted(start, end, now)?.let { return@transaction EditResult.Invalid(it) }
                overlap(start, end, excludeId = null)?.let { return@transaction EditResult.Invalid(it) }
                val session = Session(
                    activityId = activityId,
                    start = start,
                    end = end,
                    zoneId = time.zone().id,
                    note = note.normalized(),
                    source = CommandSource.Manual.id,
                )
                EditResult.Saved(session.copy(id = store.insertSession(session)))
            }
        }
        if (result is EditResult.Saved) listener.onDataChanged()
        return result
    }

    /**
     * Modifie une session. Pour une session terminée : activité, début, fin, note. Pour la session
     * active : seulement l'activité et la note (le début est verrouillé dans cette version ; pour le
     * corriger, arrêter le suivi puis modifier la session terminée).
     */
    suspend fun updateSession(id: Long, activityId: String, start: Instant, end: Instant?, note: String?): EditResult {
        val result = lock.withLock {
            store.transaction {
                val existing = store.session(id) ?: return@transaction EditResult.Invalid(SessionError.NotFound)
                validateActivity(activityId, allowArchivedId = existing.activityId)?.let { return@transaction EditResult.Invalid(it) }
                val updated = if (existing.isActive) {
                    if (start != existing.start || end != null) return@transaction EditResult.Invalid(SessionError.ActiveStartLocked)
                    existing.copy(activityId = activityId, note = note.normalized())
                } else {
                    val newEnd = end ?: return@transaction EditResult.Invalid(SessionError.MissingEnd)
                    SessionValidator.validateCompleted(start, newEnd, time.now())?.let { return@transaction EditResult.Invalid(it) }
                    overlap(start, newEnd, excludeId = id)?.let { return@transaction EditResult.Invalid(it) }
                    existing.copy(activityId = activityId, start = start, end = end, note = note.normalized())
                }
                store.updateSession(updated)
                EditResult.Saved(updated)
            }
        }
        if (result is EditResult.Saved) listener.onDataChanged()
        return result
    }

    suspend fun deleteSession(id: Long): Session? {
        val deleted = lock.withLock {
            store.transaction {
                store.session(id)?.also { store.deleteSession(id) }
            }
        }
        if (deleted != null) listener.onDataChanged()
        return deleted
    }

    private suspend fun validateActivity(activityId: String, allowArchivedId: String? = null): SessionError? {
        val activity = store.activity(activityId) ?: return SessionError.UnknownActivity
        if (activity.archived && activity.id != allowArchivedId) return SessionError.UnknownActivity
        return null
    }

    private suspend fun overlap(start: Instant, end: Instant, excludeId: Long?): SessionError? {
        val candidates = store.sessionsOverlapping(start, end)
        return SessionValidator.findOverlap(start, end, candidates, excludeId)?.let { SessionError.Overlap(it) }
    }

    private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
