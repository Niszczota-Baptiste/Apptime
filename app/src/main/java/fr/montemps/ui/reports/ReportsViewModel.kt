package fr.montemps.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.montemps.AppContainer
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.report.PeriodReport
import fr.montemps.domain.report.ReportCalculator
import fr.montemps.domain.report.WeekReport
import fr.montemps.ui.displayTicker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

enum class ReportMode { DAY, WEEK }

data class ReportsUiState(
    val mode: ReportMode,
    val anchor: LocalDate,
    val today: LocalDate,
    val day: PeriodReport?,
    val week: WeekReport?,
    val activities: Map<String, TrackedActivity>,
) {
    val main: PeriodReport get() = day ?: week!!.total
    val canGoForward: Boolean get() = when (mode) {
        ReportMode.DAY -> anchor < today
        ReportMode.WEEK -> ReportCalculator.weekStart(anchor) < ReportCalculator.weekStart(today)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModel(private val c: AppContainer) : ViewModel() {
    private val mode = MutableStateFlow(ReportMode.DAY)
    private val anchor = MutableStateFlow(c.time.now().atZone(c.time.zone()).toLocalDate())

    private val sessions = combine(mode, anchor) { m, a -> m to a }.flatMapLatest { (m, a) ->
        val zone = c.time.zone()
        val (from, to) = if (m == ReportMode.DAY) ReportCalculator.dayBounds(a, zone) else ReportCalculator.weekBounds(a, zone)
        c.store.observeSessionsOverlapping(from, to)
    }

    val state: StateFlow<ReportsUiState?> = combine(
        mode, anchor, sessions, c.store.observeActivities(), displayTicker(c.time, 1_000),
    ) { m, a, list, activities, now ->
        val zone = c.time.zone()
        ReportsUiState(
            mode = m,
            anchor = a,
            today = now.atZone(zone).toLocalDate(),
            day = if (m == ReportMode.DAY) ReportCalculator.dayReport(list, a, zone, now) else null,
            week = if (m == ReportMode.WEEK) ReportCalculator.weekReport(list, a, zone, now) else null,
            activities = activities.associateBy { it.id },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setMode(m: ReportMode) {
        mode.value = m
    }

    fun shift(direction: Int) {
        anchor.value = if (mode.value == ReportMode.DAY) anchor.value.plusDays(direction.toLong()) else anchor.value.plusWeeks(direction.toLong())
    }

    fun goToday() {
        anchor.value = c.time.now().atZone(c.time.zone()).toLocalDate()
    }
}
