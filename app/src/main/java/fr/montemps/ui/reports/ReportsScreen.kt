package fr.montemps.ui.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.montemps.domain.report.Fmt
import fr.montemps.domain.report.PeriodReport
import fr.montemps.domain.report.WeekReport
import fr.montemps.ui.ScreenScaffold
import fr.montemps.ui.components.ActivityMarker
import fr.montemps.ui.components.DistributionBar
import fr.montemps.ui.components.GoalRow
import fr.montemps.ui.components.signedShort
import fr.montemps.ui.components.PageHeader
import fr.montemps.ui.components.SectionHeader
import fr.montemps.ui.components.SegmentedToggle
import fr.montemps.ui.components.ShareRow
import fr.montemps.ui.components.distributionSegments
import fr.montemps.ui.components.tabular
import fr.montemps.ui.coverageText

@Composable
fun ReportsScreen(vm: ReportsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    ScreenScaffold { padding ->
        val s = state ?: return@ScreenScaffold
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val report = s.main
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        ) {
            PageHeader("Bilans")
            SegmentedToggle(
                options = ReportMode.entries,
                selected = s.mode,
                label = { if (it == ReportMode.DAY) "Jour" else "Semaine" },
                onSelect = vm::setMode,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.shift(-1) }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = if (s.mode == ReportMode.DAY) "Jour précédent" else "Semaine précédente",
                    )
                }
                Text(
                    periodTitle(s),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = "Revenir à aujourd'hui", onClick = vm::goToday)
                        .padding(vertical = 12.dp),
                )
                IconButton(onClick = { vm.shift(1) }, enabled = s.canGoForward) {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = if (s.mode == ReportMode.DAY) "Jour suivant" else "Semaine suivante",
                    )
                }
            }

            Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Fmt.short(report.tracked), style = MaterialTheme.typography.displayMedium.tabular())
                Text("de temps suivi", style = MaterialTheme.typography.bodyMedium, color = muted)
                Spacer(Modifier.height(6.dp))
                Text(coverageText(report), style = MaterialTheme.typography.bodySmall, color = muted, textAlign = TextAlign.Center)
                if (report.periodLength.toHours() != 24L && report.periodLength.toHours() != 168L) {
                    Text(
                        "Changement d'heure : cette période dure ${Fmt.short(report.periodLength)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        textAlign = TextAlign.Center,
                    )
                }
                if (report.includesActiveSession) {
                    Text("Session en cours incluse.", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                s.comparison?.let { cmp ->
                    if (!cmp.previous.isZero || !cmp.current.isZero) {
                        Text(
                            "${signedShort(cmp.delta)} par rapport à la semaine précédente" + if (s.includesToday) " (au même moment)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                s.dailyAverage?.takeIf { !it.isZero }?.let {
                    Text("Moyenne : ${Fmt.short(it)} par jour écoulé", style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }

            if (report.isEmpty) {
                Text(
                    "Aucune activité suivie sur cette période.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
            } else {
                SectionHeader("Répartition", trailing = "% du suivi")
                DistributionBar(distributionSegments(report, s.activities))
                Spacer(Modifier.height(14.dp))
                report.totals.forEach { t ->
                    val a = s.activities[t.activityId]
                    ShareRow(a, a?.name ?: t.activityId, t.duration, t.shareOfTracked, t.bySub, s.subs)
                }
            }
            if (s.goals.isNotEmpty() || s.dailyGoalsWeek.isNotEmpty()) {
                SectionHeader(if (s.mode == ReportMode.DAY) "Objectifs du jour" else "Objectifs")
                s.goals.forEach { GoalRow(it) }
                s.dailyGoalsWeek.forEach { g ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) { },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActivityMarker(g.activity)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${g.activity.name} · ${Fmt.short(g.goal.target)} par jour",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${g.reachedDays} j / ${g.countedDays}",
                            style = MaterialTheme.typography.bodyMedium.tabular(),
                            color = if (g.countedDays > 0 && g.reachedDays == g.countedDays) MaterialTheme.colorScheme.onSurface else muted,
                        )
                    }
                }
            }
            s.comparison?.byActivity?.takeIf { it.isNotEmpty() }?.let { deltas ->
                SectionHeader("Évolution", trailing = "vs semaine précédente")
                deltas.take(3).forEach { d ->
                    val a = s.activities[d.activityId]
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActivityMarker(a)
                        Spacer(Modifier.width(12.dp))
                        Text(a?.name ?: d.activityId, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(signedShort(d.delta), style = MaterialTheme.typography.bodyMedium.tabular(), color = muted)
                    }
                }
            }
            if (s.includesToday && s.streaks.isNotEmpty()) {
                SectionHeader("Séries en cours")
                s.streaks.take(5).forEach { st ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActivityMarker(st.activity)
                        Spacer(Modifier.width(12.dp))
                        Text(st.activity.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            "${st.days} jours" + if (st.includesToday) "" else " (jusqu'à hier)",
                            style = MaterialTheme.typography.bodyMedium.tabular(),
                            color = muted,
                        )
                    }
                }
                Text(
                    "Jours consécutifs suivis (objectif quotidien atteint, s'il existe).",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
            s.week?.let { WeekDays(it, s) }
            Text(
                "Journées découpées à minuit dans le fuseau actuel du téléphone ; semaines du lundi au dimanche. " +
                    "Une session à cheval sur minuit est répartie entre les jours concernés.",
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier.padding(top = 28.dp),
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
        "${Fmt.dayShort(start)} – ${Fmt.dayShort(start.plusDays(6))}"
    }
}

@Composable
private fun WeekDays(week: WeekReport, s: ReportsUiState) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SectionHeader("Par jour")
    week.days.forEach { d ->
        val r: PeriodReport = d.report
        val isToday = d.date == s.today
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 9.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "${Fmt.dayMedium(d.date)} : " + if (r.hasStarted) "${Fmt.spoken(r.tracked)} suivies" else "à venir"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                Fmt.weekdayShort(d.date),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                color = if (r.hasStarted) MaterialTheme.colorScheme.onSurface else muted,
                modifier = Modifier.width(64.dp),
            )
            Box(Modifier.weight(1f)) {
                if (r.hasStarted) DistributionBar(distributionSegments(r, s.activities), height = 6.dp)
            }
            Text(
                if (r.hasStarted) Fmt.short(r.tracked) else "—",
                style = MaterialTheme.typography.bodyMedium.tabular(),
                color = if (r.hasStarted) MaterialTheme.colorScheme.onSurface else muted,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 72.dp),
            )
        }
    }
}

