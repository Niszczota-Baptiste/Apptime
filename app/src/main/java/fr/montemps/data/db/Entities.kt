package fr.montemps.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.montemps.domain.model.Goal
import fr.montemps.domain.model.GoalPeriod
import fr.montemps.domain.model.Session
import fr.montemps.domain.model.SubActivity
import fr.montemps.domain.model.TrackedActivity
import java.time.Instant

@Entity(tableName = "activities")
data class ActivityEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: Int,
    val icon: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val archived: Boolean,
    /** Ajouté en version 2 de la base. */
    @ColumnInfo(name = "interruption", defaultValue = "0") val interruption: Boolean = false,
    /** Objectif en minutes (version 2) ; NULL = aucun objectif. */
    @ColumnInfo(name = "goal_minutes") val goalMinutes: Int? = null,
    /** « day » ou « week » (version 2). */
    @ColumnInfo(name = "goal_period") val goalPeriod: String? = null,
)

/** Sous-catégories (version 3 de la base). */
@Entity(
    tableName = "subactivities",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("activity_id")],
)
data class SubActivityEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "activity_id") val activityId: String,
    val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val archived: Boolean,
)

@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("activity_id"), Index("start_utc_ms"), Index("end_utc_ms")],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "activity_id") val activityId: String,
    /** Début, millisecondes depuis l'époque Unix (UTC). */
    @ColumnInfo(name = "start_utc_ms") val startUtcMs: Long,
    /** Fin (UTC) ; NULL = session en cours. */
    @ColumnInfo(name = "end_utc_ms") val endUtcMs: Long?,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    val note: String?,
    @ColumnInfo(name = "start_elapsed_ms") val startElapsedMs: Long?,
    @ColumnInfo(name = "start_boot_count") val startBootCount: Int?,
    val source: String,
    /** Sous-catégorie (version 3) ; NULL = sans précision. Cohérence vérifiée par trigger. */
    @ColumnInfo(name = "sub_id") val subId: String? = null,
)

fun ActivityEntity.toDomain() = TrackedActivity(
    id = id,
    name = name,
    color = color,
    icon = icon,
    sortOrder = sortOrder,
    archived = archived,
    interruption = interruption,
    goal = goalOf(goalMinutes, goalPeriod),
)

fun TrackedActivity.toEntity() = ActivityEntity(
    id = id,
    name = name,
    color = color,
    icon = icon,
    sortOrder = sortOrder,
    archived = archived,
    interruption = interruption,
    goalMinutes = goal?.minutes,
    goalPeriod = goal?.period?.id,
)

private fun goalOf(minutes: Int?, period: String?): Goal? {
    val p = GoalPeriod.of(period) ?: return null
    return if (minutes != null && minutes > 0) Goal(minutes, p) else null
}

fun SessionEntity.toDomain() = Session(
    id = id,
    activityId = activityId,
    start = Instant.ofEpochMilli(startUtcMs),
    end = endUtcMs?.let(Instant::ofEpochMilli),
    zoneId = zoneId,
    note = note,
    startElapsedRealtimeMs = startElapsedMs,
    startBootCount = startBootCount,
    source = source,
    subId = subId,
)

fun Session.toEntity() = SessionEntity(
    id = id,
    activityId = activityId,
    startUtcMs = start.toEpochMilli(),
    endUtcMs = end?.toEpochMilli(),
    zoneId = zoneId,
    note = note,
    startElapsedMs = startElapsedRealtimeMs,
    startBootCount = startBootCount,
    source = source,
    subId = subId,
)

fun SubActivityEntity.toDomain() = SubActivity(id, activityId, name, sortOrder, archived)

fun SubActivity.toEntity() = SubActivityEntity(id, activityId, name, sortOrder, archived)
