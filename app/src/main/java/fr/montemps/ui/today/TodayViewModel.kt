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
import fr.montemps.domain.model.TrackedActivity
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
    /** Pendant une interruption : activité à reprendre en un appui. */
    val resume: TrackedActivity? = null,
    val dailyGoals: List<GoalProgress> = emptyList(),
)

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

    private data class Base(val activities: List<TrackedActivity>, val active: Session?, val recent: List<Session>)

    private val base = combine(
        c.store.observeActivities(),
        c.store.observeActiveSession(),
        c.store.observeRecentEnded(ResumeRule.LOOKBACK),
    ) { activities, active, recent -> Base(activities, active, recent) }

    val state: StateFlow<TodayUiState?> = combine(
        ticker,
        base,
        todaySessions,
        c.preferences.preferences,
    ) { now, b, sessions, prefs ->
        val zone = c.time.zone()
        val today = now.atZone(zone).toLocalDate()
        val byId = b.activities.associateBy { it.id }
        val report = ReportCalculator.dayReport(sessions, today, zone, now)
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
            resume = ResumeRule.resumeTarget(b.active, byId, b.recent),
            dailyGoals = Goals.progress(b.activities, report, GoalPeriod.DAY),
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

    fun onActivityTap(id: String) = viewModelScope.launch { report(c.tracker.switchTo(id)) }

    /** Démarrage rétroactif (« il y a 10 min »). */
    fun startAt(id: String, at: Instant) = viewModelScope.launch { report(c.tracker.switchToAt(id, at), backdated = true) }

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
                val name = c.store.activity(result.session.activityId)?.name ?: result.session.activityId
                val text = if (backdated) "En cours : $name, depuis ${Fmt.time(result.session.start, c.time.zone())}" else "En cours : $name"
                result.undoToken()?.let { messages.send(TodayEvent.Undoable(text, it)) }
            }
            is TransitionResult.Stopped -> result.undoToken()?.let { messages.send(TodayEvent.Undoable("Suivi arrêté", it)) }
            is TransitionResult.NoChange -> Unit
        }
    }

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
