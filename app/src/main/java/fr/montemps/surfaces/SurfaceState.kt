package fr.montemps.surfaces

import fr.montemps.data.prefs.PreferencesRepository
import fr.montemps.domain.ResumeRule
import fr.montemps.domain.TrackingStore
import fr.montemps.domain.model.Session
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.report.Fmt
import fr.montemps.domain.report.ReportCalculator
import fr.montemps.domain.time.TimeSource
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Instantané de l'état de suivi, partagé par les surfaces hors de l'app (widgets, tuile).
 * Calculé uniquement depuis la base et les préférences : jamais d'état propre à une surface.
 */
data class SurfaceState(
    val now: Instant,
    val zone: ZoneId,
    val active: Session?,
    val activeActivity: TrackedActivity?,
    /** Activités non archivées, dans l'ordre des raccourcis. */
    val shortcuts: List<TrackedActivity>,
    /** Favorites non archivées, sauf l'activité en cours (elle n'aurait aucun effet). */
    val favorites: List<TrackedActivity>,
    /** Dernière activité « de travail » (ni archivée ni interruption), pour reprendre depuis l'inactivité. */
    val lastActivity: TrackedActivity?,
    /** Pendant une interruption : l'activité à reprendre en un appui. */
    val resumeActivity: TrackedActivity? = null,
    /** Temps suivi aujourd'hui, session en cours comprise jusqu'à [now]. */
    val trackedToday: Duration,
    /** « 09:15 », « hier 23:10 »… ; null sans session en cours. */
    val startLabel: String?,
)

class SurfaceStateLoader(
    private val store: TrackingStore,
    private val prefs: PreferencesRepository,
    private val time: TimeSource,
) {
    suspend fun load(): SurfaceState {
        val now = time.now()
        val zone = time.zone()
        val today = now.atZone(zone).toLocalDate()
        val p = prefs.current()
        val activities = store.activities()
        val byId = activities.associateBy { it.id }
        val active = store.activeSession()
        val (from, to) = ReportCalculator.dayBounds(today, zone)
        val recent = store.recentEndedSessions(ResumeRule.LOOKBACK)
        return SurfaceState(
            now = now,
            zone = zone,
            active = active,
            activeActivity = active?.let { byId[it.activityId] },
            shortcuts = activities.filter { !it.archived }.sortedBy { it.sortOrder },
            favorites = p.favoriteIds.filter { it != active?.activityId }.mapNotNull { byId[it] }.filter { !it.archived },
            lastActivity = ResumeRule.lastWorkActivity(recent, byId, exclude = null),
            resumeActivity = ResumeRule.resumeTarget(active, byId, recent),
            trackedToday = ReportCalculator.report(store.sessionsOverlapping(from, to), from, to, now).tracked,
            startLabel = active?.let { Fmt.startLabel(it.start, today, zone) },
        )
    }
}
