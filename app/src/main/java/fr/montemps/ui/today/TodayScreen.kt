package fr.montemps.ui.today

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.montemps.domain.report.Fmt
import fr.montemps.domain.report.PeriodReport
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.ui.ScreenScaffold
import fr.montemps.ui.components.ActivityBadge
import fr.montemps.ui.components.ActivityTile
import fr.montemps.ui.components.BarEntry
import fr.montemps.ui.components.DateTimeField
import fr.montemps.ui.components.DurationBars
import fr.montemps.ui.components.SectionTitle
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@Composable
fun TodayScreen(vm: TodayViewModel, onManageActivities: () -> Unit, onOpenHistory: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    // État de l'autorisation de notification, réévalué à chaque retour sur l'écran.
    var canNotify by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        canNotify = vm.canPostNotifications()
        onPauseOrDispose { }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        canNotify = vm.canPostNotifications()
        vm.onPermissionResult()
    }
    val requestNotifications: () -> Unit = {
        val s = state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && s != null && !s.notificationPermissionAsked) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Déjà refusée (ou notifications coupées dans les réglages) : ouvrir les réglages de l'app.
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    ScreenScaffold(
        title = "Aujourd'hui",
        snackbar = snackbar,
        actions = {
            IconButton(onClick = onManageActivities) { Icon(Icons.Filled.Edit, contentDescription = "Gérer les activités") }
        },
    ) { padding ->
        val s = state
        if (s == null) {
            Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
            }
            return@ScreenScaffold
        }
        TodayContent(
            s = s,
            padding = padding,
            canNotify = canNotify,
            onTap = { id ->
                // Première utilisation sur Android 13+ : proposer l'autorisation une seule fois.
                if (!canNotify && !s.notificationPermissionAsked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                vm.onActivityTap(id)
            },
            onStop = vm::onStop,
            onRequestNotifications = requestNotifications,
            onReshowNotification = vm::reshowNotification,
            onManageActivities = onManageActivities,
            onOpenHistory = onOpenHistory,
        )
        s.forgotten?.let { prompt ->
            ForgottenSessionDialog(
                prompt = prompt,
                now = s.now,
                state = s,
                onContinue = { vm.continueForgotten(prompt.session.id) },
                onEndAt = { vm.endForgottenAt(prompt.session.id, it) },
            )
        }
    }
}

@Composable
private fun TodayContent(
    s: TodayUiState,
    padding: PaddingValues,
    canNotify: Boolean,
    onTap: (String) -> Unit,
    onStop: () -> Unit,
    onRequestNotifications: () -> Unit,
    onReshowNotification: () -> Unit,
    onManageActivities: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    // Avec un très grand texte, une seule colonne de raccourcis reste lisible.
    val columns = if (LocalDensity.current.fontScale >= 1.5f) 1 else 2
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                Fmt.dayLong(s.today),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (!canNotify) {
            item {
                InfoCard(
                    icon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                    text = "Notifications désactivées : le suivi fonctionne normalement, mais l'activité en cours ne s'affichera pas hors de l'application.",
                    action = "Autoriser",
                    onAction = onRequestNotifications,
                )
            }
        } else if (s.notificationDismissed) {
            item {
                InfoCard(
                    icon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                    text = "Vous avez masqué la notification de cette session.",
                    action = "Réafficher",
                    onAction = onReshowNotification,
                )
            }
        }
        s.clockShiftMinutes?.let { shift ->
            item {
                InfoCard(
                    icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
                    text = "L'heure du téléphone a changé d'environ ${kotlin.math.abs(shift)} min depuis le début de cette session. " +
                        "Les durées suivent l'heure affichée par le téléphone ; corrigez la session dans l'historique si besoin.",
                    action = "Historique",
                    onAction = onOpenHistory,
                )
            }
        }
        item { CurrentActivityCard(s, onStop) }
        item { SectionTitle("Changer d'activité") }
        if (s.shortcuts.isEmpty()) {
            item {
                InfoCard(icon = null, text = "Aucune activité disponible : elles sont toutes archivées.", action = "Gérer les activités", onAction = onManageActivities)
            }
        }
        items(s.shortcuts.chunked(columns), key = { row -> row.joinToString { it.id } }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { a ->
                    ActivityTile(a, isActive = s.active?.activityId == a.id, onClick = { onTap(a.id) }, modifier = Modifier.weight(1f))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item { TodaySummary(s.report, s.activitiesById) }
    }
}

@Composable
private fun CurrentActivityCard(s: TodayUiState, onStop: () -> Unit) {
    val active = s.active
    if (active == null) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Aucune activité en cours", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(4.dp))
                Text(
                    "Touchez une activité ci-dessous pour démarrer le suivi. Le temps non suivi n'est attribué à aucune activité.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val activity = s.activitiesById[active.activityId]
    val color = activity?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary
    val elapsed = active.duration(s.now)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.14f)),
        border = BorderStroke(2.dp, color),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("EN COURS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActivityBadge(activity, size = 52.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    activity?.name ?: active.activityId,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                Fmt.hms(elapsed),
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.semantics { contentDescription = "Durée : ${Fmt.spoken(elapsed)}" },
            )
            Text(
                "Depuis ${Fmt.startLabel(active.start, s.today, s.zone)}",
                style = MaterialTheme.typography.titleMedium,
            )
            active.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ),
            ) { Text("Arrêter le suivi", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun TodaySummary(report: PeriodReport, activities: Map<String, TrackedActivity>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bilan du jour", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (report.isEmpty) {
                Text(
                    "Rien n'a encore été suivi aujourd'hui. Choisissez une activité ci-dessus : le bilan se remplira automatiquement.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text("Total suivi : ${Fmt.short(report.tracked)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                coverageText(report),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Pourcentages calculés sur le temps suivi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            DurationBars(report.totals.map { BarEntry(activities[it.activityId], activities[it.activityId]?.name ?: it.activityId, it.duration, it.shareOfTracked) })
        }
    }
}

fun coverageText(report: PeriodReport): String = when {
    !report.hasStarted -> "Période à venir."
    report.isFullyTracked -> "Toute la période écoulée est suivie."
    else -> "Non suivi : ${Fmt.short(report.untracked)} sur ${Fmt.short(report.elapsed)} écoulées — bilan partiel."
}

@Composable
private fun InfoCard(icon: (@Composable () -> Unit)?, text: String, action: String, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (icon != null) {
                    icon()
                    Spacer(Modifier.width(12.dp))
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text(action) }
        }
    }
}

@Composable
private fun ForgottenSessionDialog(
    prompt: ForgottenPrompt,
    now: Instant,
    state: TodayUiState,
    onContinue: () -> Unit,
    onEndAt: (Instant) -> Unit,
) {
    var correcting by remember(prompt.session.id) { mutableStateOf(false) }
    val zone = state.zone
    val name = prompt.activity?.name ?: prompt.session.activityId
    // Proposition initiale : début + 1 h (ou maintenant si plus tôt), arrondie à la minute.
    val suggested = minOf(prompt.session.start.plusSeconds(3600), now).atZone(zone).truncatedTo(ChronoUnit.MINUTES).toLocalDateTime()
    var end by remember(prompt.session.id) { mutableStateOf<LocalDateTime>(suggested) }
    val endInstant = ZonedDateTime.of(end, zone).toInstant()
    val valid = endInstant.isAfter(prompt.session.start) && !endInstant.isAfter(now)

    AlertDialog(
        onDismissRequest = { },
        title = { Text(if (correcting) "Heure de fin réelle" else "Session toujours en cours ?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!correcting) {
                    Text(
                        "« $name » est en cours depuis ${Fmt.startLabel(prompt.session.start, state.today, zone)} " +
                            "(${Fmt.short(prompt.session.duration(now))})." +
                            if (prompt.rebootedSinceStart) " Le téléphone a redémarré depuis." else "",
                    )
                    Text("Si vous l'avez oubliée, indiquez quand elle s'est réellement terminée. Rien n'est modifié sans votre choix.")
                } else {
                    Text("Début : ${Fmt.dayMedium(prompt.session.start.atZone(zone).toLocalDate())} à ${Fmt.time(prompt.session.start, zone)}")
                    DateTimeField("Fin", end, { end = it })
                    if (!valid) {
                        Text("La fin doit être après le début et ne pas être dans le futur.", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (correcting) {
                TextButton(onClick = { onEndAt(endInstant) }, enabled = valid) { Text("Terminer à cette heure") }
            } else {
                TextButton(onClick = onContinue) { Text("Continuer") }
            }
        },
        dismissButton = {
            if (correcting) {
                TextButton(onClick = { correcting = false }) { Text("Retour") }
            } else {
                TextButton(onClick = { correcting = true }) { Text("Corriger l'heure de fin") }
            }
        },
    )
}
