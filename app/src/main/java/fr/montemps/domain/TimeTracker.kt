package fr.montemps.domain

import fr.montemps.domain.model.CommandSource
import fr.montemps.domain.model.Session
import fr.montemps.domain.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Interface métier stable pour piloter le suivi. L'interface utilisateur, les actions de
 * notification et — plus tard — les applications compagnons (Coréen, Sport, Création) passent
 * toutes par ces opérations.
 */
interface TrackingCommands {
    suspend fun currentSession(): Session?
    suspend fun switchTo(activityId: String, source: CommandSource = CommandSource.App): TransitionResult
    suspend fun stop(source: CommandSource = CommandSource.App, expectedSessionId: Long? = null): TransitionResult
}

sealed interface TransitionResult {
    /** Une nouvelle session a démarré (et la précédente, s'il y en avait une, s'est terminée au même instant). */
    data class Started(val session: Session, val previous: Session?) : TransitionResult
    data class Stopped(val session: Session) : TransitionResult
    /** Rien à faire : activité déjà active, aucun suivi à arrêter, ou commande périmée. */
    data class NoChange(val active: Session?) : TransitionResult
    data class Rejected(val reason: Reason) : TransitionResult

    enum class Reason {
        UNKNOWN_ACTIVITY,
        ARCHIVED_ACTIVITY,
        /** L'heure système est antérieure à la dernière transition enregistrée. */
        CLOCK_BEFORE_LAST_RECORD,
        INVALID_END_TIME,
        NOT_ACTIVE,
    }
}

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

    override suspend fun switchTo(activityId: String, source: CommandSource): TransitionResult {
        val result = lock.withLock {
            store.transaction {
                val active = store.activeSession()
                if (active?.activityId == activityId) return@transaction TransitionResult.NoChange(active)

                val activity = store.activity(activityId)
                    ?: return@transaction TransitionResult.Rejected(TransitionResult.Reason.UNKNOWN_ACTIVITY)
                if (activity.archived) return@transaction TransitionResult.Rejected(TransitionResult.Reason.ARCHIVED_ACTIVITY)

                val now = time.now()
                if (!isAfterLastRecord(now, active)) {
                    return@transaction TransitionResult.Rejected(TransitionResult.Reason.CLOCK_BEFORE_LAST_RECORD)
                }

                val ended = active?.copy(end = now)?.also { store.updateSession(it) }
                val newSession = Session(
                    activityId = activityId,
                    start = now,
                    end = null,
                    zoneId = time.zone().id,
                    startElapsedRealtimeMs = time.elapsedRealtimeMs(),
                    startBootCount = time.bootCount(),
                    source = source.id,
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
