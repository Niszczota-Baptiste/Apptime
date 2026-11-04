package fr.montemps.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities ORDER BY archived ASC, sort_order ASC, name ASC")
    fun observeAll(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities ORDER BY archived ASC, sort_order ASC, name ASC")
    suspend fun getAll(): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun get(id: String): ActivityEntity?

    @Upsert
    suspend fun upsert(activity: ActivityEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(activities: List<ActivityEntity>)

    @Query("DELETE FROM activities")
    suspend fun deleteAll()
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE end_utc_ms IS NULL LIMIT 1")
    fun observeActive(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE end_utc_ms IS NULL LIMIT 1")
    suspend fun getActive(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    /** Sessions intersectant [from, to) ; une session active s'étend indéfiniment. */
    @Query(
        "SELECT * FROM sessions WHERE start_utc_ms < :toMs AND (end_utc_ms IS NULL OR end_utc_ms > :fromMs) " +
            "ORDER BY start_utc_ms ASC",
    )
    suspend fun getOverlapping(fromMs: Long, toMs: Long): List<SessionEntity>

    @Query(
        "SELECT * FROM sessions WHERE start_utc_ms < :toMs AND (end_utc_ms IS NULL OR end_utc_ms > :fromMs) " +
            "ORDER BY start_utc_ms ASC",
    )
    fun observeOverlapping(fromMs: Long, toMs: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE end_utc_ms IS NOT NULL ORDER BY end_utc_ms DESC LIMIT 1")
    suspend fun lastEnded(): SessionEntity?

    @Query("SELECT MAX(end_utc_ms) FROM sessions")
    suspend fun latestEnd(): Long?

    @Query("SELECT COUNT(*) FROM sessions WHERE activity_id = :activityId")
    suspend fun countFor(activityId: String): Int

    @Query("SELECT * FROM sessions ORDER BY start_utc_ms ASC")
    suspend fun getAll(): List<SessionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: SessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(sessions: List<SessionEntity>)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}
