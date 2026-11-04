package fr.montemps.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.montemps.domain.report.Fmt
import fr.montemps.ui.ScreenScaffold
import fr.montemps.ui.components.ActivityBadge
import fr.montemps.ui.components.DatePickerDialogFor
import java.time.LocalDate

@Composable
fun HistoryScreen(vm: HistoryViewModel, onEdit: (Long) -> Unit, onAdd: (LocalDate) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var pickDate by remember { mutableStateOf(false) }
    ScreenScaffold(
        title = "Historique",
        fab = {
            ExtendedFloatingActionButton(
                onClick = { onAdd(vm.selectedDate()) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Ajouter une session") },
            )
        },
    ) { padding ->
        val s = state ?: return@ScreenScaffold
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.shift(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Jour précédent")
                    }
                    TextButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(
                            when (s.date) {
                                s.today -> "Aujourd'hui · ${Fmt.dayMedium(s.date)}"
                                s.today.minusDays(1) -> "Hier · ${Fmt.dayMedium(s.date)}"
                                else -> Fmt.dayLong(s.date)
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    IconButton(onClick = { vm.shift(1) }, enabled = s.date < s.today) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Jour suivant")
                    }
                }
                Text(
                    "Temps suivi ce jour : ${Fmt.short(s.trackedInDay)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                HorizontalDivider()
            }
            if (s.items.isEmpty()) {
                item {
                    Text(
                        "Aucune session ce jour. Utilisez « Ajouter une session » pour saisir une période oubliée.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(s.items, key = { it.session.id }) { item ->
                HistoryRow(item, s) { onEdit(item.session.id) }
                HorizontalDivider()
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

@Composable
private fun HistoryRow(item: HistoryItem, s: HistoryUiState, onClick: () -> Unit) {
    val session = item.session
    val zone = s.zone
    val name = item.activity?.name ?: session.activityId
    val startText = if (item.startedBefore) Fmt.startLabel(session.start, s.date, zone) else Fmt.time(session.start, zone)
    val endText = session.end?.let {
        if (item.endsAfter) "${Fmt.dayShort(it.atZone(zone).toLocalDate())} ${Fmt.time(it, zone)}" else Fmt.time(it, zone)
    } ?: "En cours"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, onClickLabel = "Modifier")
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$name, de $startText à $endText, ${Fmt.spoken(item.totalDuration)}" +
                    (session.note?.let { ", note : $it" } ?: "")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivityBadge(item.activity)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name + (if (item.activity?.archived == true) " (archivée)" else ""), style = MaterialTheme.typography.titleMedium)
            Text("$startText → $endText", style = MaterialTheme.typography.bodyMedium, fontWeight = if (session.isActive) FontWeight.Bold else null)
            if (item.startedBefore || item.endsAfter) {
                Text(
                    "Dont ${Fmt.short(item.durationInDay)} ce jour",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            session.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (session.isActive) Fmt.hms(item.totalDuration) else Fmt.short(item.totalDuration),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
