package fr.montemps.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.montemps.AppContainer
import fr.montemps.data.prefs.AppPreferences
import fr.montemps.domain.BackdateLimit
import fr.montemps.domain.ResumeRule
import fr.montemps.domain.TransitionResult
import fr.montemps.domain.UndoToken
import fr.montemps.domain.model.GoalPeriod
import fr.montemps.domain.model.Session
import fr.montemps.domain.model.SubActivity
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.model.displayName
import fr.montemps.domain.report.Fmt
import fr.montemps.domain.report.GoalProgress
import fr.montemps.domain.report.Goals
import fr.montemps.domain.report.PeriodReport
import fr.montemps.domain.report.ReportCalculator
import fr.montemps.domain.undoToken
import fr.montemps.ui.displayTicker
import fr.montemps.ui.message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

data class ForgottenPrompt(val session: Session, val activity: TrackedActivity?, val rebootedSinceStart: Boolean)

sealed interface TodayEvent {
    data class Message(val text: String) : TodayEvent

    /** Changement effectué, annulable quelques secondes (« Annuler »). */
    data class Undoable(val text: String, val token: UndoToken) : TodayEvent
}

data class TodayUiState(
    val now: Instant,
    val zone: ZoneId,
    val today: LocalDate,
    val shortcuts: List<TrackedActivity>,
    val activitiesById: Map<String, TrackedActivity>,
    val active: Session?,
    val report: PeriodReport,
    val forgotten: ForgottenPrompt?,
    /** Écart (minutes) entre horloge calendaire et horloge monotone depuis le début de la session. */
    val clockShiftMinutes: Long?,
    val notificationDismissed: Boolean,
    val notificationPermissionAsked: Boolean,
    /** Pendant une interruption : activité à reprendre en un appui (et sa sous-catégorie). */
    val resume: TrackedActivity? = null,
    val resumeSub: SubActivity? = null,
    val dailyGoals: List<GoalProgress> = emptyList(),
    /** Sous-catégories proposées (non retirées), par activité, dans l'ordre. */
    val subsByActivity: Map<String, List<SubActivity>> = emptyMap(),
    /** Toutes les sous-catégories (noms des sessions, y compris retirées). */
    val subsById: Map<String, SubActivity> = emptyMap(),
) {
    /** « Travail · Pro » pour une session. */
    fun label(session: Session): String =
        displayName(activitiesById[session.activityId]?.name ?: session.activityId, session.subId?.let { subsById[it]?.name })
}

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val c: AppContainer) : ViewModel() {

    private val messages = Channel<TodayEvent>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()

    private val ticker = displayTicker(c.time)
    private val todaySessions = ticker
        .map { it.atZone(c.time.zone()).toLocalDate() to c.time.zone() }
        .distinctUntilChanged()
        .flatMapLatest { (date, zone) ->
            val (from, to) = ReportCalculator.dayBounds(date, zone)
            c.store.observeSessionsOverlapping(from, to)
        }

    private data class Base(
        val activities: List<TrackedActivity>,
        val active: Session?,
        val recent: List<Session>,
        val subs: List<SubActivity>,
    )

    private val base = combine(
        c.store.observeActivities(),
        c.store.observeActiveSession(),
        c.store.observeRecentEnded(ResumeRule.LOOKBACK),
        c.store.observeSubActivities(),
    ) { activities, active, recent, subs -> Base(activities, active, recent, subs) }

    val state: StateFlow<TodayUiState?> = combine(
        ticker,
        base,
        todaySessions,
        c.preferences.preferences,
    ) { now, b, sessions, prefs ->
        val zone = c.time.zone()
        val today = now.atZone(zone).toLocalDate()
        val byId = b.activities.associateBy { it.id }
        val subsById = b.subs.associateBy { it.id }
        val report = ReportCalculator.dayReport(sessions, today, zone, now)
        val resumeSession = ResumeRule.resumeSession(b.active, byId, b.recent)
        TodayUiState(
            now = now,
            zone = zone,
            today = today,
            shortcuts = b.activities.filter { !it.archived },
            activitiesById = byId,
            active = b.active,
            report = report,
            forgotten = forgottenPrompt(b.active, byId, prefs, now),
            clockShiftMinutes = clockShift(b.active, now),
            notificationDismissed = b.active != null && prefs.dismissedNotificationSessionId == b.active.id,
            notificationPermissionAsked = prefs.notificationPermissionAsked,
            resume = resumeSession?.let { byId[it.activityId] },
            resumeSub = ResumeRule.usableSub(resumeSession, subsById),
            dailyGoals = Goals.progress(b.activities, report, GoalPeriod.DAY),
            subsByActivity = b.subs.filter { !it.archived }.groupBy { it.activityId }.mapValues { (_, l) -> l.sortedBy { it.sortOrder } },
            subsById = subsById,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun forgottenPrompt(active: Session?, byId: Map<String, TrackedActivity>, prefs: AppPreferences, now: Instant): ForgottenPrompt? {
        if (active == null || prefs.acknowledgedSessionId == active.id) return null
        val rebooted = active.startBootCount != null && c.time.bootCount() != null && active.startBootCount != c.time.bootCount()
        val long = active.duration(now) >= Duration.ofHours(prefs.forgottenThresholdHours.toLong())
        return if (long || rebooted) ForgottenPrompt(active, byId[active.activityId], rebooted) else null
    }

    /** Détecte une modification de l'heure système pendant le même démarrage du téléphone. */
    private fun clockShift(active: Session?, now: Instant): Long? {
        val startElapsed = active?.startElapsedRealtimeMs ?: return null
        if (active.startBootCount == null || active.startBootCount != c.time.bootCount()) return null
        val wall = Duration.between(active.start, now).toMillis()
        val mono = c.time.elapsedRealtimeMs() - startElapsed
        val diffMin = (wall - mono) / 60_000
        return if (abs(diffMin) >= 2) diffMin else null
    }

    fun onActivityTap(id: String) = start(id, null)

    /** Démarre [id] avec la sous-catégorie [subId] (null = sans précision) ; précise la session en cours si besoin. */
    fun start(id: String, subId: String?) = viewModelScope.launch { report(c.tracker.switchTo(id, subId = subId)) }

    /** Démarrage rétroactif (« il y a 10 min »). */
    fun startAt(id: String, at: Instant, subId: String? = null) =
        viewModelScope.launch { report(c.tracker.switchToAt(id, at, subId = subId), backdated = true) }

    /** « Reprendre » : l'activité interrompue, avec sa sous-catégorie. */
    fun resume() = viewModelScope.launch { report(c.tracker.resume()) }

    suspend fun backdateLimit(): BackdateLimit = c.tracker.backdateLimit()

    fun onStop() = viewModelScope.launch {
        val active = c.tracker.currentSession() ?: return@launch
        report(c.tracker.stop(expectedSessionId = active.id))
    }

    fun undo(token: UndoToken) = viewModelScope.launch {
        if (!c.tracker.undo(token)) messages.send(TodayEvent.Message("Annulation impossible : le suivi a changé entre-temps."))
    }

    private suspend fun report(result: TransitionResult, backdated: Boolean = false) {
        when (result) {
            is TransitionResult.Rejected -> messages.send(TodayEvent.Message(result.reason.message()))
            is TransitionResult.Started -> {
                val name = sessionLabel(result.session)
                val text = if (backdated) "En cours : $name, depuis ${Fmt.time(result.session.start, c.time.zone())}" else "En cours : $name"
                result.undoToken()?.let { messages.send(TodayEvent.Undoable(text, it)) }
            }
            is TransitionResult.Refined -> messages.send(TodayEvent.Message("Précisé : ${sessionLabel(result.session)}"))
            is TransitionResult.Stopped -> result.undoToken()?.let { messages.send(TodayEvent.Undoable("Suivi arrêté", it)) }
            is TransitionResult.NoChange -> Unit
        }
    }

    private suspend fun sessionLabel(session: Session): String = displayName(
        c.store.activity(session.activityId)?.name ?: session.activityId,
        session.subId?.let { c.store.subActivity(it)?.name },
    )

    fun continueForgotten(sessionId: Long) = viewModelScope.launch {
        c.reminders.acknowledge(sessionId)
        c.refreshOutputs()
    }

    fun endForgottenAt(sessionId: Long, end: Instant) = viewModelScope.launch {
        when (val r = c.tracker.stopAt(sessionId, end)) {
            is TransitionResult.Rejected -> messages.send(TodayEvent.Message(r.reason.message()))
            is TransitionResult.Stopped -> messages.send(TodayEvent.Message("Session terminée à l'heure indiquée."))
            else -> Unit
        }
    }

    fun reshowNotification() = viewModelScope.launch { c.notifications.reshow() }

    fun onPermissionResult() = viewModelScope.launch {
        c.preferences.setNotificationPermissionAsked()
        c.refreshOutputs()
    }

    fun canPostNotifications(): Boolean = c.notifier.canPost()
}
