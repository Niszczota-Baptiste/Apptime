package fr.montemps.data

import androidx.room.withTransaction
import fr.montemps.data.db.MonTempsDatabase
import fr.montemps.data.db.toDomain
import fr.montemps.data.db.toEntity
import fr.montemps.domain.TrackingStore
import fr.montemps.domain.model.Session
import fr.montemps.domain.model.TrackedActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Implémentation Room de [TrackingStore] + flux observables pour l'interface. */
class RoomTrackingStore(private val db: MonTempsDatabase) : TrackingStore {
    private val activityDao = db.activityDao()
    private val sessionDao = db.sessionDao()

    override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }

    override suspend fun activeSession(): Session? = sessionDao.getActive()?.toDomain()
    override suspend fun session(id: Long): Session? = sessionDao.get(id)?.toDomain()
    override suspend fun sessionsOverlapping(from: Instant, to: Instant): List<Session> =
        sessionDao.getOverlapping(from.toEpochMilli(), to.toEpochMilli()).map { it.toDomain() }
    override suspend fun latestEnd(): Instant? = sessionDao.latestEnd()?.let(Instant::ofEpochMilli)
    override suspend fun lastEndedSession(): Session? = sessionDao.lastEnded()?.toDomain()
    override suspend fun insertSession(session: Session): Long = sessionDao.insert(session.toEntity().copy(id = 0))
    override suspend fun updateSession(session: Session) = sessionDao.update(session.toEntity())
    override suspend fun deleteSession(id: Long) = sessionDao.delete(id)

    override suspend fun activity(id: String): TrackedActivity? = activityDao.get(id)?.toDomain()
    override suspend fun activities(): List<TrackedActivity> = activityDao.getAll().map { it.toDomain() }
    override suspend fun upsertActivity(activity: TrackedActivity) = activityDao.upsert(activity.toEntity())
    override suspend fun countSessionsFor(activityId: String): Int = sessionDao.countFor(activityId)

    override suspend fun replaceAll(activities: List<TrackedActivity>, sessions: List<Session>) {
        sessionDao.deleteAll()
        activityDao.deleteAll()
        activityDao.insertAll(activities.map { it.toEntity() })
        // Les sessions terminées d'abord, puis l'éventuelle session active (trigger « une seule active »).
        sessionDao.insertAll(sessions.sortedWith(compareBy<Session> { it.isActive }.thenBy { it.start }).map { it.toEntity() })
    }

    suspend fun allSessions(): List<Session> = sessionDao.getAll().map { it.toDomain() }

    fun observeActivities(): Flow<List<TrackedActivity>> =
        activityDao.observeAll().map { list -> list.map { it.toDomain() } }.distinctUntilChanged()

    fun observeActiveSession(): Flow<Session?> =
        sessionDao.observeActive().map { it?.toDomain() }.distinctUntilChanged()

    fun observeSessionsOverlapping(from: Instant, to: Instant): Flow<List<Session>> =
        sessionDao.observeOverlapping(from.toEpochMilli(), to.toEpochMilli())
            .map { list -> list.map { it.toDomain() } }
            .distinctUntilChanged()
}
