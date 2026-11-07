package fr.montemps.domain

import fr.montemps.domain.model.CommandSource
import fr.montemps.domain.model.Session
import fr.montemps.domain.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/**
 * Interface métier stable pour piloter le suivi. L'interface utilisateur, les actions de
 * notification et — plus tard — les applications compagnons (Coréen, Sport, Création) passent
 * toutes par ces opérations.
 */
interface TrackingCommands {
    suspend fun currentSession(): Session?

    /**
     * Démarre [activityId] (sous-catégorie [subId] facultative). Sur l'activité déjà en cours :
     * sans sous-catégorie, ou la même, rien ne change ; si la session en cours n'en avait pas, elle
     * est seulement précisée ([TransitionResult.Refined]) ; sinon (Pro → Perso), c'est un changement.
     */
    suspend fun switchTo(activityId: String, source: CommandSource = CommandSource.App, subId: String? = null): TransitionResult
    suspend fun stop(source: CommandSource = CommandSource.App, expectedSessionId: Long? = null): TransitionResult
}

sealed interface TransitionResult {
    /** Une nouvelle session a démarré (et la précédente, s'il y en avait une, s'est terminée au même instant). */
    data class Started(val session: Session, val previous: Session?) : TransitionResult
    data class Stopped(val session: Session) : TransitionResult
    /** La session en cours, sans sous-catégorie, a reçu [Session.subId] (aucune nouvelle session). */
    data class Refined(val session: Session) : TransitionResult
    /** Rien à faire : activité déjà active, aucun suivi à arrêter, ou commande périmée. */
    data class NoChange(val active: Session?) : TransitionResult
    data class Rejected(val reason: Reason) : TransitionResult

    enum class Reason {
        UNKNOWN_ACTIVITY,
        ARCHIVED_ACTIVITY,
        /** Sous-catégorie inconnue, retirée ou d'une autre activité. */
        INVALID_SUB,
        /** L'heure système est antérieure à la dernière transition enregistrée. */
        CLOCK_BEFORE_LAST_RECORD,
        INVALID_END_TIME,
        NOT_ACTIVE,
        /** Démarrage rétroactif avant la session en cours ou avant la fin de la précédente. */
        START_TOO_EARLY,
        START_IN_FUTURE,
    }
}

/** Jeton d'annulation d'une transition récente (voir [TimeTracker.undo]). */
data class UndoToken(val startedSessionId: Long?, val endedSessionId: Long?, val endedAt: Instant?)

fun TransitionResult.undoToken(): UndoToken? = when (this) {
    is TransitionResult.Started -> UndoToken(session.id, previous?.id, previous?.end)
    is TransitionResult.Stopped -> UndoToken(null, session.id, session.end)
    else -> null
}

/**
 * Limite basse d'un démarrage rétroactif : début de la session en cours (exclu), sinon fin de la
 * dernière session (incluse) ; null si aucune session.
 */
data class BackdateLimit(val instant: Instant?, val exclusive: Boolean) {
    fun allows(at: Instant): Boolean = when {
        instant == null -> true
        exclusive -> at.isAfter(instant)
        else -> !at.isBefore(instant)
    }
}

private val FAR_FUTURE: Instant = Instant.parse("9999-12-31T00:00:00Z")

/**
 * Verrou partagé par toutes les opérations d'écriture (transitions, corrections, activités,
 * restauration). Combiné aux transactions Room, il garantit qu'aucune séquence lecture-écriture
 * concurrente ne crée deux sessions actives ou des chevauchements.
 */
class WriteLock {
    private val mutex = Mutex()
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * Logique unique de démarrage, changement et arrêt du suivi.
 *
 * Invariants :
 * 1. au plus une session active ;
 * 2. A → B : A se termine et B commence au même instant, dans une même transaction ;
 * 3. réappuyer sur l'activité active ne crée rien ;
 * 4. arrêter laisse ensuite du temps non suivi (aucune attribution automatique).
 */
class TimeTracker(
    private val store: TrackingStore,
    private val time: TimeSource,
    private val lock: WriteLock,
    private val listener: ChangeListener = ChangeListener { },
) : TrackingCommands {

    override suspend fun currentSession(): Session? = store.activeSession()

    override suspend fun switchTo(activityId: String, source: CommandSource, subId: String?): TransitionResult =
        transition(activityId, subId, at = null, source = source)

    /**
     * Démarre [activityId] à l'instant passé [at] (« j'ai commencé il y a 10 min »). L'activité en
     * cours éventuelle se termine à [at]. Refusé si [at] est futur, antérieur au début de la session
     * en cours ou à la fin de la dernière session : jamais de chevauchement.
     */
    suspend fun switchToAt(
        activityId: String,
        at: Instant,
        source: CommandSource = CommandSource.App,
        subId: String? = null,
    ): TransitionResult = transition(activityId, subId, at = at, source = source)

    /** Ce que « Reprendre » démarrerait maintenant : activité et sous-catégorie encore valide. */
    data class ResumePoint(val activityId: String, val subId: String?)

    suspend fun resumePoint(): ResumePoint? {
        val activities = store.activities().associateBy { it.id }
        val recent = store.recentEndedSessions(ResumeRule.LOOKBACK)
        val active = store.activeSession()
        val session = if (active != null) {
            ResumeRule.resumeSession(active, activities, recent)
        } else {
            ResumeRule.lastWorkSession(recent, activities, exclude = null)
        } ?: return null
        val sub = ResumeRule.usableSub(session, store.subActivities().associateBy { it.id })
        return ResumePoint(session.activityId, sub?.id)
    }

    /**
     * « Reprendre » : pendant une interruption, l'activité interrompue ; sans suivi, la dernière
     * activité de travail — avec sa sous-catégorie. Utilisé par l'app, la notification, les
     * widgets, la tuile et la montre. Rien à reprendre : [TransitionResult.NoChange].
     */
    suspend fun resume(source: CommandSource = CommandSource.App, at: Instant? = null): TransitionResult {
        val point = resumePoint() ?: return TransitionResult.NoChange(store.activeSession())
        return transition(point.activityId, point.subId, at, source)
    }

    /** Limite basse d'un démarrage rétroactif (voir [BackdateLimit]). */
    suspend fun backdateLimit(): BackdateLimit {
        val active = store.activeSession()
        return if (active != null) BackdateLimit(active.start, exclusive = true) else BackdateLimit(store.latestEnd(), exclusive = false)
    }

    /**
     * Annule une transition récente (appui accidentel) : supprime la session démarrée et rouvre la
     * session terminée. Refusé (false) si quoi que ce soit a changé depuis : autre transition,
     * correction, session ajoutée…
     */
    suspend fun undo(token: UndoToken): Boolean {
        val done = lock.withLock {
            store.transaction {
                val active = store.activeSession()
                val started = token.startedSessionId?.let { store.session(it) }
                val ended = token.endedSessionId?.let { store.session(it) }
                val activeMatches = if (token.startedSessionId != null) {
                    started != null && active?.id == token.startedSessionId
                } else {
                    active == null
                }
                val endedMatches = token.endedSessionId == null || (ended != null && ended.end == token.endedAt)
                if (!activeMatches || !endedMatches || (started == null && ended == null)) return@transaction false
                // Rien d'autre ne doit exister après le début de la session à rouvrir.
                val from = ended?.start ?: started!!.start
                val others = store.sessionsOverlapping(from, FAR_FUTURE)
                    .filter { it.id != token.startedSessionId && it.id != token.endedSessionId }
                if (others.isNotEmpty()) return@transaction false
                started?.let { store.deleteSession(it.id) }
                ended?.let { store.updateSession(it.copy(end = null)) }
                true
            }
        }
        if (done) listener.onDataChanged()
        return done
    }

    private suspend fun transition(activityId: String, subId: String?, at: Instant?, source: CommandSource): TransitionResult {
        val result = lock.withLock {
            store.transaction {
                val active = store.activeSession()
                if (subId != null) {
                    val sub = store.subActivity(subId)
                    if (sub == null || sub.activityId != activityId || sub.archived) {
                        return@transaction TransitionResult.Rejected(TransitionResult.Reason.INVALID_SUB)
                    }
                }
                if (active?.activityId == activityId) {
                    // Même activité : rien à faire, sauf préciser la session ou changer de sous-catégorie.
                    if (subId == null || subId == active.subId) return@transaction TransitionResult.NoChange(active)
                    if (active.subId == null && at == null) {
                        val refined = active.copy(subId = subId)
                        store.updateSession(refined)
                        return@transaction TransitionResult.Refined(refined)
                    }
                }

                val activity = store.activity(activityId)
                    ?: return@transaction TransitionResult.Rejected(TransitionResult.Reason.UNKNOWN_ACTIVITY)
                if (activity.archived) return@transaction TransitionResult.Rejected(TransitionResult.Reason.ARCHIVED_ACTIVITY)

                val now = time.now()
                val start = at ?: now
                if (at == null) {
                    if (!isAfterLastRecord(now, active)) {
                        return@transaction TransitionResult.Rejected(TransitionResult.Reason.CLOCK_BEFORE_LAST_RECORD)
                    }
                } else {
                    if (at.isAfter(now)) return@transaction TransitionResult.Rejected(TransitionResult.Reason.START_IN_FUTURE)
                    val limit = if (active != null) BackdateLimit(active.start, true) else BackdateLimit(store.latestEnd(), false)
                    if (!limit.allows(at)) return@transaction TransitionResult.Rejected(TransitionResult.Reason.START_TOO_EARLY)
                }

                val ended = active?.copy(end = start)?.also { store.updateSession(it) }
                val newSession = Session(
                    activityId = activityId,
                    start = start,
                    end = null,
                    zoneId = time.zone().id,
                    // Horloge monotone ramenée au début réel (démarrage rétroactif).
                    startElapsedRealtimeMs = time.elapsedRealtimeMs() - Duration.between(start, now).toMillis(),
                    startBootCount = time.bootCount(),
                    source = source.id,
                    subId = subId,
                )
                val id = store.insertSession(newSession)
                TransitionResult.Started(newSession.copy(id = id), ended)
            }
        }
        if (result !is TransitionResult.NoChange && result !is TransitionResult.Rejected) listener.onDataChanged()
        return result
    }

    override suspend fun stop(source: CommandSource, expectedSessionId: Long?): TransitionResult {
        val now = time.now()
        return endActive(expectedSessionId, TransitionResult.Reason.CLOCK_BEFORE_LAST_RECORD) { active ->
            if (!now.isAfter(active.start)) null else now
        }
    }

    /**
     * Termine la session active à une heure passée choisie par l'utilisateur (session oubliée).
     * L'heure doit être postérieure au début et non future.
     */
    suspend fun stopAt(sessionId: Long, end: Instant): TransitionResult {
        val now = time.now()
        return endActive(sessionId, TransitionResult.Reason.INVALID_END_TIME) { active ->
            if (!end.isAfter(active.start) || end.isAfter(now)) null else end
        }
    }

    private suspend fun endActive(
        expectedSessionId: Long?,
        rejectReason: TransitionResult.Reason,
        chooseEnd: (Session) -> Instant?,
    ): TransitionResult {
        val result = lock.withLock {
            store.transaction {
                val active = store.activeSession() ?: return@transaction TransitionResult.NoChange(null)
                // Action de notification périmée : la session visée n'est plus active.
                if (expectedSessionId != null && active.id != expectedSessionId) {
                    return@transaction TransitionResult.NoChange(active)
                }
                val end = chooseEnd(active)
                    ?: return@transaction TransitionResult.Rejected(rejectReason)
                val ended = active.copy(end = end)
                store.updateSession(ended)
                TransitionResult.Stopped(ended)
            }
        }
        if (result is TransitionResult.Stopped) listener.onDataChanged()
        return result
    }

    private suspend fun isAfterLastRecord(now: Instant, active: Session?): Boolean {
        if (active != null && !now.isAfter(active.start)) return false
        val lastEnd = store.latestEnd()
        return lastEnd == null || !now.isBefore(lastEnd)
    }
}
