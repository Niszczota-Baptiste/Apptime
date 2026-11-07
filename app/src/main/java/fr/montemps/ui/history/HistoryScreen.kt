package fr.montemps.ui.history

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.montemps.domain.report.Fmt
import fr.montemps.domain.report.Gap
import fr.montemps.ui.ScreenScaffold
import fr.montemps.ui.components.ActivityMarker
import fr.montemps.ui.components.AddFab
import fr.montemps.ui.components.DatePickerDialogFor
import fr.montemps.ui.components.DayTimeline
import fr.montemps.ui.components.PageHeader
import fr.montemps.ui.components.tabular
import java.time.Instant
import java.time.LocalDate

@Composable
fun HistoryScreen(
    vm: HistoryViewModel,
    onEdit: (Long) -> Unit,
    onAdd: (LocalDate) -> Unit,
    onFillGap: (LocalDate, Instant, Instant) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var pickDate by remember { mutableStateOf(false) }
    ScreenScaffold(fab = { AddFab("Ajouter", onClick = { onAdd(vm.selectedDate()) }) }) { padding ->
        val s = state ?: return@ScreenScaffold
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 104.dp,
            ),
        ) {
            item { PageHeader("Historique") }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.shift(-1) }) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Jour précédent")
                    }
                    Text(
                        when (s.date) {
                            s.today -> "Aujourd'hui · ${Fmt.dayMedium(s.date)}"
                            s.today.minusDays(1) -> "Hier · ${Fmt.dayMedium(s.date)}"
                            else -> Fmt.dayLong(s.date)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClickLabel = "Choisir une date") { pickDate = true }
                            .padding(vertical = 12.dp),
                    )
                    IconButton(onClick = { vm.shift(1) }, enabled = s.date < s.today) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Jour suivant")
                    }
                }
            }
            item {
                DayTimeline(
                    blocks = s.timeline.map { Color(it.color) to (it.from..it.to) },
                    nowFraction = s.nowFraction,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp).semantics(mergeDescendants = true) {
                        contentDescription = "Frise de la journée : ${Fmt.spoken(s.trackedInDay)} suivies"
                    },
                )
            }
            item {
                Text(
                    if (s.items.isEmpty()) "Aucune session ce jour." else "Temps suivi : ${Fmt.short(s.trackedInDay)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
            }
            if (s.items.isEmpty()) {
                item {
                    Text(
                        "Une période oubliée ? Ajoutez-la avec le bouton « Ajouter ».",
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    )
                }
            }
            items(s.entries, key = { e ->
                when (e) {
                    is HistoryEntry.SessionEntry -> "s-" + e.item.session.id
                    is HistoryEntry.GapEntry -> "g-" + e.gap.start.toEpochMilli()
                }
            }) { e ->
                when (e) {
                    is HistoryEntry.SessionEntry -> HistoryRow(e.item, s) { onEdit(e.item.session.id) }
                    is HistoryEntry.GapEntry -> GapRow(e.gap, s) { onFillGap(s.date, e.gap.start, e.gap.end) }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (pickDate) {
            DatePickerDialogFor(s.date, onDismiss = { pickDate = false }) {
                vm.select(if (it > s.today) s.today else it)
                pickDate = false
            }
        }
    }
}

/** Période non suivie : un appui ouvre la saisie d'une session préremplie avec ces heures. */
@Composable
private fun GapRow(gap: Gap, s: HistoryUiState, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val zone = s.zone
    val endsNow = !gap.end.isBefore(s.now.minusSeconds(1))
    val endText = if (endsNow) "maintenant" else Fmt.time(gap.end, zone)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, onClickLabel = "Attribuer une activité")
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Non suivi de ${Fmt.time(gap.start, zone)} à $endText, ${Fmt.spoken(gap.duration)}. Toucher pour attribuer une activité."
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).border(1.5.dp, muted, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Non suivi", style = MaterialTheme.typography.bodyLarge, color = muted)
            Text("${Fmt.time(gap.start, zone)} – $endText · Attribuer", style = MaterialTheme.typography.bodyMedium.tabular(), color = muted)
        }
        Spacer(Modifier.width(12.dp))
        Text(Fmt.short(gap.duration), style = MaterialTheme.typography.bodyLarge.tabular(), color = muted)
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, s: HistoryUiState, onClick: () -> Unit) {
    val session = item.session
    val zone = s.zone
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val name = fr.montemps.domain.model.displayName(item.activity?.name ?: session.activityId, item.subName)
    val startText = if (item.startedBefore) Fmt.startLabel(session.start, s.date, zone) else Fmt.time(session.start, zone)
    val endText = session.end?.let {
        if (item.endsAfter) "${Fmt.dayShort(it.atZone(zone).toLocalDate())} ${Fmt.time(it, zone)}" else Fmt.time(it, zone)
    } ?: "en cours"
    val detail = buildString {
        append("$startText – $endText")
        if (item.startedBefore || item.endsAfter) append(" · ${Fmt.short(item.durationInDay)} ce jour")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, onClickLabel = "Modifier")
            .heightIn(min = 64.dp)
            .padding(vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$name, de $startText à $endText, ${Fmt.spoken(item.totalDuration)}" +
                    (session.note?.let { ", note : $it" } ?: "")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivityMarker(item.activity)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name + if (item.activity?.archived == true) " · archivée" else "",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(detail, style = MaterialTheme.typography.bodyMedium.tabular(), color = muted)
            session.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 2) }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (session.isActive) Fmt.hms(item.totalDuration) else Fmt.short(item.totalDuration),
            style = MaterialTheme.typography.bodyLarge.tabular(),
            fontWeight = if (session.isActive) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
