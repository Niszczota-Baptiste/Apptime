package fr.montemps.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.montemps.AppContainer
import fr.montemps.domain.model.Session
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.report.ReportCalculator
import fr.montemps.ui.displayTicker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HistoryItem(
    val session: Session,
    val activity: TrackedActivity?,
    val totalDuration: Duration,
    /** Partie de la session comprise dans la journée affichée. */
    val durationInDay: Duration,
    val startedBefore: Boolean,
    val endsAfter: Boolean,
)

data class HistoryUiState(
    val date: LocalDate,
    val today: LocalDate,
    val zone: ZoneId,
    val now: Instant,
    val items: List<HistoryItem>,
    val trackedInDay: Duration,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    private val selected = MutableStateFlow(c.time.now().atZone(c.time.zone()).toLocalDate())

    private val sessions = selected.flatMapLatest { date ->
        val (from, to) = ReportCalculator.dayBounds(date, c.time.zone())
        c.store.observeSessionsOverlapping(from, to)
    }

    val state: StateFlow<HistoryUiState?> = combine(
        selected, sessions, c.store.observeActivities(), displayTicker(c.time),
    ) { date, list, activities, now ->
        val zone = c.time.zone()
        val (from, to) = ReportCalculator.dayBounds(date, zone)
        val byId = activities.associateBy { it.id }
        val items = list.sortedByDescending { it.start }.map { s ->
            HistoryItem(
                session = s,
                activity = byId[s.activityId],
                totalDuration = s.duration(now),
                durationInDay = ReportCalculator.clippedDuration(s, from, to, now),
                startedBefore = s.start.isBefore(from),
                endsAfter = (s.end ?: now).isAfter(to),
            )
        }
        HistoryUiState(
            date = date,
            today = now.atZone(zone).toLocalDate(),
            zone = zone,
            now = now,
            items = items,
            trackedInDay = items.fold(Duration.ZERO) { acc, i -> acc + i.durationInDay },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectedDate(): LocalDate = selected.value

    fun select(date: LocalDate) {
        selected.value = date
    }

    fun shift(days: Long) {
        selected.value = selected.value.plusDays(days)
    }
}
