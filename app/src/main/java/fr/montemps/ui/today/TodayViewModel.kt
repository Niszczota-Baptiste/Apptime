package fr.montemps.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.montemps.AppContainer
import fr.montemps.data.prefs.AppPreferences
import fr.montemps.domain.TransitionResult
import fr.montemps.domain.model.Session
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.report.PeriodReport
import fr.montemps.domain.report.ReportCalculator
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
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val c: AppContainer) : ViewModel() {

    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()

    private val ticker = displayTicker(c.time)
    private val todaySessions = ticker
        .map { it.atZone(c.time.zone()).toLocalDate() to c.time.zone() }
        .distinctUntilChanged()
        .flatMapLatest { (date, zone) ->
            val (from, to) = ReportCalculator.dayBounds(date, zone)
            c.store.observeSessionsOverlapping(from, to)
        }

    val state: StateFlow<TodayUiState?> = combine(
        ticker,
        c.store.observeActivities(),
        c.store.observeActiveSession(),
        todaySessions,
        c.preferences.preferences,
    ) { now, activities, active, sessions, prefs ->
        val zone = c.time.zone()
        val today = now.atZone(zone).toLocalDate()
        val byId = activities.associateBy { it.id }
        TodayUiState(
            now = now,
            zone = zone,
            today = today,
            shortcuts = activities.filter { !it.archived },
            activitiesById = byId,
            active = active,
            report = ReportCalculator.dayReport(sessions, today, zone, now),
            forgotten = forgottenPrompt(active, byId, prefs, now),
            clockShiftMinutes = clockShift(active, now),
            notificationDismissed = active != null && prefs.dismissedNotificationSessionId == active.id,
            notificationPermissionAsked = prefs.notificationPermissionAsked,
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

    fun onActivityTap(id: String) = viewModelScope.launch {
        val r = c.tracker.switchTo(id)
        if (r is TransitionResult.Rejected) messages.send(r.reason.message())
    }

    fun onStop() = viewModelScope.launch {
        val active = c.tracker.currentSession() ?: return@launch
        val r = c.tracker.stop(expectedSessionId = active.id)
        if (r is TransitionResult.Rejected) messages.send(r.reason.message())
    }

    fun continueForgotten(sessionId: Long) = viewModelScope.launch { c.preferences.acknowledgeSession(sessionId) }

    fun endForgottenAt(sessionId: Long, end: Instant) = viewModelScope.launch {
        when (val r = c.tracker.stopAt(sessionId, end)) {
            is TransitionResult.Rejected -> messages.send(r.reason.message())
            is TransitionResult.Stopped -> messages.send("Session terminée à l'heure indiquée.")
            else -> Unit
        }
    }

    fun reshowNotification() = viewModelScope.launch { c.notifications.reshow() }

    fun onPermissionResult() = viewModelScope.launch {
        c.preferences.setNotificationPermissionAsked()
        c.notifications.sync()
    }

    fun canPostNotifications(): Boolean = c.notifier.canPost()
}
