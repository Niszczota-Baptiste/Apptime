@file:OptIn(ExperimentalMaterial3Api::class)

package fr.montemps.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.montemps.domain.report.Fmt
import fr.montemps.domain.report.PeriodReport
import fr.montemps.domain.report.WeekReport
import fr.montemps.ui.ScreenScaffold
import fr.montemps.ui.components.BarEntry
import fr.montemps.ui.components.DurationBars
import fr.montemps.ui.components.StackedBar
import fr.montemps.ui.today.coverageText
import fr.montemps.domain.model.TrackedActivity

@Composable
fun ReportsScreen(vm: ReportsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Bilans") { padding ->
        val s = state ?: return@ScreenScaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ReportMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = s.mode == m,
                        onClick = { vm.setMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, ReportMode.entries.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (m == ReportMode.DAY) "Jour" else "Semaine") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.shift(-1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = if (s.mode == ReportMode.DAY) "Jour précédent" else "Semaine précédente")
                }
                TextButton(onClick = vm::goToday, modifier = Modifier.weight(1f)) {
                    Text(periodTitle(s), style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = { vm.shift(1) }, enabled = s.canGoForward) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = if (s.mode == ReportMode.DAY) "Jour suivant" else "Semaine suivante")
                }
            }
            Summary(s.main, s.activities)
            s.week?.let { WeekDays(it, s.activities) }
            Text(
                "Jours découpés à minuit selon le fuseau horaire actuel du téléphone ; semaines du lundi au dimanche. " +
                    "Les sessions à cheval sur minuit sont réparties entre les jours concernés.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun periodTitle(s: ReportsUiState): String = when (s.mode) {
    ReportMode.DAY -> when (s.anchor) {
        s.today -> "Aujourd'hui · ${Fmt.dayMedium(s.anchor)}"
        s.today.minusDays(1) -> "Hier · ${Fmt.dayMedium(s.anchor)}"
        else -> Fmt.dayLong(s.anchor)
    }
    ReportMode.WEEK -> {
        val start = s.week!!.weekStart
        "Semaine du ${Fmt.dayShort(start)} au ${Fmt.dayShort(start.plusDays(6))}"
    }
}

@Composable
private fun Summary(report: PeriodReport, activities: Map<String, TrackedActivity>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Temps suivi : ${Fmt.short(report.tracked)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Text(coverageText(report), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (report.periodLength.toHours() != 24L && report.periodLength.toHours() != 168L) {
                Text(
                    "Changement d'heure : cette période dure ${Fmt.short(report.periodLength)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (report.includesActiveSession) {
                Text("Inclut la session en cours jusqu'à maintenant.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (report.isEmpty) {
                Text("Aucune activité suivie sur cette période.", style = MaterialTheme.typography.bodyLarge)
            } else {
                Text("Répartition (% du temps suivi)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                DurationBars(report.totals.map { BarEntry(activities[it.activityId], activities[it.activityId]?.name ?: it.activityId, it.duration, it.shareOfTracked) })
            }
        }
    }
}

@Composable
private fun WeekDays(week: WeekReport, activities: Map<String, TrackedActivity>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Par jour", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("Barre : part de chaque journée écoulée ; le gris correspond au temps non suivi.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            week.days.forEach { d ->
                val r = d.report
                val base = r.elapsed.toMillis().toDouble()
                Column(Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "${Fmt.dayMedium(d.date)} : ${Fmt.spoken(r.tracked)} suivies"
                }) {
                    Row {
                        Text(Fmt.dayMedium(d.date), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(if (r.hasStarted) Fmt.short(r.tracked) else "—", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    }
                    if (r.hasStarted) {
                        StackedBar(r.totals.map { t ->
                            (activities[t.activityId]?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary) to
                                if (base > 0) t.duration.toMillis() / base else 0.0
                        })
                    }
                }
            }
        }
    }
}
