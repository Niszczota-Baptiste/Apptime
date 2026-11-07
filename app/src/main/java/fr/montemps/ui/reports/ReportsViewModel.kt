package fr.montemps.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.montemps.AppContainer
import fr.montemps.domain.model.GoalPeriod
import fr.montemps.domain.model.SubActivity
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.report.DailyGoalWeek
import fr.montemps.domain.report.GoalProgress
import fr.montemps.domain.report.Goals
import fr.montemps.domain.report.PeriodComparison
import fr.montemps.domain.report.PeriodReport
import fr.montemps.domain.report.ReportCalculator
import fr.montemps.domain.report.Streak
import fr.montemps.domain.report.Trends
import fr.montemps.domain.report.WeekReport
import fr.montemps.ui.displayTicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

enum class ReportMode { DAY, WEEK }

data class ReportsUiState(
    val mode: ReportMode,
    val anchor: LocalDate,
    val today: LocalDate,
    val day: PeriodReport?,
    val week: WeekReport?,
    val activities: Map<String, TrackedActivity>,
    /** Sous-catégories (noms des lignes de détail). */
    val subs: Map<String, SubActivity> = emptyMap(),
    /** Objectifs de la période affichée (quotidiens en mode Jour, hebdomadaires en mode Semaine). */
    val goals: List<GoalProgress> = emptyList(),
    /** Mode Semaine : objectifs quotidiens atteints n jours sur les jours écoulés. */
    val dailyGoalsWeek: List<DailyGoalWeek> = emptyList(),
    /** Mode Semaine : comparaison avec la semaine précédente (au même moment pour la semaine en cours). */
    val comparison: PeriodComparison? = null,
    val dailyAverage: Duration? = null,
    /** Séries en cours (affichées quand la période contient aujourd'hui). */
    val streaks: List<Streak> = emptyList(),
) {
    val main: PeriodReport get() = day ?: week!!.total
    val canGoForward: Boolean get() = when (mode) {
        ReportMode.DAY -> anchor < today
        ReportMode.WEEK -> ReportCalculator.weekStart(anchor) < ReportCalculator.weekStart(today)
    }
    val includesToday: Boolean get() = when (mode) {
        ReportMode.DAY -> anchor == today
        ReportMode.WEEK -> ReportCalculator.weekStart(anchor) == ReportCalculator.weekStart(today)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModel(private val c: AppContainer) : ViewModel() {
    private val mode = MutableStateFlow(ReportMode.DAY)
    private val anchor = MutableStateFlow(c.time.now().atZone(c.time.zone()).toLocalDate())

    /** Sessions de la période affichée et de la période précédente (pour la comparaison). */
    private val sessions = combine(mode, anchor) { m, a -> m to a }.flatMapLatest { (m, a) ->
        val zone = c.time.zone()
        val (from, to) = if (m == ReportMode.DAY) {
            ReportCalculator.dayBounds(a, zone)
        } else {
            ReportCalculator.weekBounds(a.minusWeeks(1), zone).first to ReportCalculator.weekBounds(a, zone).second
        }
        c.store.observeSessionsOverlapping(from, to)
    }

    /** Séries : sur un an, recalculées seulement quand les données changent (pas à chaque seconde). */
    private val streaks = combine(
        c.store.observeSessionsOverlapping(
            c.time.now().minus(Duration.ofDays(400)),
            Instant.parse("9999-12-31T00:00:00Z"),
        ),
        c.store.observeActivities(),
    ) { list, activities ->
        val zone = c.time.zone()
        val now = c.time.now()
        Trends.streaks(list, activities, now.atZone(zone).toLocalDate(), zone, now)
    }.flowOn(Dispatchers.Default).onStart { emit(emptyList()) }

    val state: StateFlow<ReportsUiState?> = combine(
        combine(mode, anchor) { m, a -> m to a },
        sessions,
        combine(c.store.observeActivities(), c.store.observeSubActivities()) { a, s -> a to s },
        streaks,
        displayTicker(c.time, 1_000),
    ) { (m, a), list, (activities, subList), streakList, now ->
        val zone = c.time.zone()
        val today = now.atZone(zone).toLocalDate()
        val byId = activities.associateBy { it.id }
        val subs = subList.associateBy { it.id }
        if (m == ReportMode.DAY) {
            val day = ReportCalculator.dayReport(list, a, zone, now)
            ReportsUiState(
                mode = m,
                anchor = a,
                today = today,
                day = day,
                week = null,
                activities = byId,
                subs = subs,
                goals = Goals.progress(activities, day, GoalPeriod.DAY),
                streaks = streakList,
            )
        } else {
            val week = ReportCalculator.weekReport(list, a, zone, now)
            // Semaine précédente arrêtée au même moment : comparaison à durée écoulée égale.
            val previous = Trends.previousWeek(list, a, zone, now)
            ReportsUiState(
                mode = m,
                anchor = a,
                today = today,
                day = null,
                week = week,
                activities = byId,
                subs = subs,
                goals = Goals.progress(activities, week.total, GoalPeriod.WEEK),
                dailyGoalsWeek = Goals.dailyGoalsOverWeek(activities, week),
                comparison = Trends.compare(week.total, previous),
                dailyAverage = Trends.dailyAverage(week),
                streaks = streakList,
            )
        }
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
