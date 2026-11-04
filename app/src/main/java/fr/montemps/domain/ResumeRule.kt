package fr.montemps.domain

import fr.montemps.domain.model.Session
import fr.montemps.domain.model.TrackedActivity

/**
 * « Reprendre » : pendant une interruption (Pause, Repas…), proposer en un appui l'activité qui
 * précédait. On remonte les sessions terminées les plus récentes jusqu'à la première activité
 * non archivée qui n'est pas elle-même une interruption.
 */
object ResumeRule {
    const val LOOKBACK = 20

    fun lastWorkActivity(
        recentEnded: List<Session>,
        activities: Map<String, TrackedActivity>,
        exclude: String?,
    ): TrackedActivity? = recentEnded.asSequence()
        .mapNotNull { activities[it.activityId] }
        .firstOrNull { !it.archived && !it.interruption && it.id != exclude }

    fun resumeTarget(
        active: Session?,
        activities: Map<String, TrackedActivity>,
        recentEnded: List<Session>,
    ): TrackedActivity? {
        val current = active?.let { activities[it.activityId] } ?: return null
        if (!current.interruption) return null
        return lastWorkActivity(recentEnded, activities, exclude = current.id)
    }
}
