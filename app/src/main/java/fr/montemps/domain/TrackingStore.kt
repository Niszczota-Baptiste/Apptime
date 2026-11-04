package fr.montemps.domain

import fr.montemps.domain.model.Session
import fr.montemps.domain.model.TrackedActivity
import java.time.Instant

/**
 * Accès persistant utilisé par la logique métier. Implémenté par Room ([fr.montemps.data.RoomTrackingStore])
 * et remplaçable dans les tests. Toutes les écritures composées doivent passer par [transaction].
 */
interface TrackingStore {
    suspend fun <T> transaction(block: suspend () -> T): T

    suspend fun activeSession(): Session?
    suspend fun session(id: Long): Session?
    /** Sessions dont l'intervalle intersecte [from, to) (les sessions actives s'étendent à l'infini). */
    suspend fun sessionsOverlapping(from: Instant, to: Instant): List<Session>
    /** Fin la plus tardive parmi les sessions terminées. */
    suspend fun latestEnd(): Instant?
    /** Dernières sessions terminées, de la plus récente à la plus ancienne (règle de reprise). */
    suspend fun recentEndedSessions(limit: Int): List<Session>
    suspend fun insertSession(session: Session): Long
    suspend fun updateSession(session: Session)
    suspend fun deleteSession(id: Long)

    suspend fun activity(id: String): TrackedActivity?
    suspend fun activities(): List<TrackedActivity>
    suspend fun upsertActivity(activity: TrackedActivity)
    suspend fun countSessionsFor(activityId: String): Int

    /** Remplace toutes les données (restauration). Doit être appelée dans [transaction]. */
    suspend fun replaceAll(activities: List<TrackedActivity>, sessions: List<Session>)
}

/** Appelé après chaque écriture validée (mise à jour de la notification, etc.). */
fun interface ChangeListener {
    suspend fun onDataChanged()
}
