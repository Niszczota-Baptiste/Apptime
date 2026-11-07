package fr.montemps.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.montemps.AppContainer
import fr.montemps.domain.model.Session
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.report.Gap
import fr.montemps.domain.report.Gaps
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
    /** Nom de la sous-catégorie (« Pro »), si la session en a une. */
    val subName: String? = null,
    val totalDuration: Duration,
    /** Partie de la session comprise dans la journée affichée. */
    val durationInDay: Duration,
    val startedBefore: Boolean,
    val endsAfter: Boolean,
)

/** Ligne de l'historique : une session, ou une période non suivie à combler. */
sealed interface HistoryEntry {
    val start: Instant

    data class SessionEntry(val item: HistoryItem) : HistoryEntry {
        override val start: Instant get() = item.session.start
    }

    data class GapEntry(val gap: Gap) : HistoryEntry {
        override val start: Instant get() = gap.start
    }
}

/** Bloc de la frise : couleur ARGB et position dans la journée (fractions 0..1). */
data class TimelineBlock(val color: Int, val from: Float, val to: Float)

data class HistoryUiState(
    val date: LocalDate,
    val today: LocalDate,
    val zone: ZoneId,
    val now: Instant,
    val items: List<HistoryItem>,
    val trackedInDay: Duration,
    /** Sessions et trous (≥ 5 min), du plus récent au plus ancien. */
    val entries: List<HistoryEntry> = emptyList(),
    val timeline: List<TimelineBlock> = emptyList(),
    /** Position de l'heure actuelle dans la journée affichée, si c'est aujourd'hui. */
    val nowFraction: Float? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    private val selected = MutableStateFlow(c.time.now().atZone(c.time.zone()).toLocalDate())

    private val sessions = selected.flatMapLatest { date ->
        val (from, to) = ReportCalculator.dayBounds(date, c.time.zone())
        c.store.observeSessionsOverlapping(from, to)
    }

    val state: StateFlow<HistoryUiState?> = combine(
        selected, sessions, c.store.observeActivities(), c.store.observeSubActivities(), displayTicker(c.time),
    ) { date, list, activities, subs, now ->
        val zone = c.time.zone()
        val (from, to) = ReportCalculator.dayBounds(date, zone)
        val byId = activities.associateBy { it.id }
        val subNames = subs.associate { it.id to it.name }
        val items = list.sortedByDescending { it.start }.map { s ->
            HistoryItem(
                session = s,
                activity = byId[s.activityId],
                subName = s.subId?.let { subNames[it] },
                totalDuration = s.duration(now),
                durationInDay = ReportCalculator.clippedDuration(s, from, to, now),
                startedBefore = s.start.isBefore(from),
                endsAfter = (s.end ?: now).isAfter(to),
            )
        }
        val length = Duration.between(from, to).toMillis().toFloat()
        fun fraction(i: Instant) = (Duration.between(from, i).toMillis() / length).coerceIn(0f, 1f)
        val gaps = Gaps.find(list, from, to, now, minimum = Duration.ofMinutes(5))
        HistoryUiState(
            date = date,
            today = now.atZone(zone).toLocalDate(),
            zone = zone,
            now = now,
            items = items,
            trackedInDay = items.fold(Duration.ZERO) { acc, i -> acc + i.durationInDay },
            entries = (items.map { HistoryEntry.SessionEntry(it) } + gaps.map { HistoryEntry.GapEntry(it) })
                .sortedByDescending { it.start },
            timeline = list.mapNotNull { s ->
                val end = minOf(s.end ?: now, to)
                if (!end.isAfter(s.start)) null else TimelineBlock(byId[s.activityId]?.color ?: 0xFF9A9AA0.toInt(), fraction(s.start), fraction(end))
            },
            nowFraction = if (now.isAfter(from) && now.isBefore(to)) fraction(now) else null,
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
