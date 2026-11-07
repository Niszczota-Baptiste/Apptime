package fr.montemps.domain

import fr.montemps.domain.model.Session
import fr.montemps.domain.model.SubActivity
import fr.montemps.domain.model.TrackedActivity

/**
 * « Reprendre » : pendant une interruption (Pause, Repas…), proposer en un appui l'activité qui
 * précédait — avec sa sous-catégorie (« Travail · Pro »). On remonte les sessions terminées les
 * plus récentes jusqu'à la première activité non archivée qui n'est pas elle-même une interruption.
 * Sans suivi en cours, la même règle donne la dernière activité « de travail ».
 */
object ResumeRule {
    const val LOOKBACK = 20

    fun lastWorkSession(
        recentEnded: List<Session>,
        activities: Map<String, TrackedActivity>,
        exclude: String?,
    ): Session? = recentEnded.firstOrNull { s ->
        val a = activities[s.activityId]
        a != null && !a.archived && !a.interruption && a.id != exclude
    }

    fun lastWorkActivity(
        recentEnded: List<Session>,
        activities: Map<String, TrackedActivity>,
        exclude: String?,
    ): TrackedActivity? = lastWorkSession(recentEnded, activities, exclude)?.let { activities[it.activityId] }

    /** Session interrompue à reprendre, ou null si l'activité en cours n'est pas une interruption. */
    fun resumeSession(
        active: Session?,
        activities: Map<String, TrackedActivity>,
        recentEnded: List<Session>,
    ): Session? {
        val current = active?.let { activities[it.activityId] } ?: return null
        if (!current.interruption) return null
        return lastWorkSession(recentEnded, activities, exclude = current.id)
    }

    fun resumeTarget(
        active: Session?,
        activities: Map<String, TrackedActivity>,
        recentEnded: List<Session>,
    ): TrackedActivity? = resumeSession(active, activities, recentEnded)?.let { activities[it.activityId] }

    /** Sous-catégorie de [session] si elle est toujours proposée (non retirée), sinon null. */
    fun usableSub(session: Session?, subs: Map<String, SubActivity>): SubActivity? =
        session?.subId?.let { subs[it] }?.takeIf { !it.archived && it.activityId == session.activityId }
}
