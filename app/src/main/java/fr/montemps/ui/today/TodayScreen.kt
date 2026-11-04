package fr.montemps.ui.today

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.report.Fmt
import fr.montemps.domain.report.PeriodReport
import fr.montemps.ui.ScreenScaffold
import fr.montemps.ui.components.ActivityMarker
import fr.montemps.ui.components.ActivityTile
import fr.montemps.ui.components.DateTimeField
import fr.montemps.ui.components.DistributionBar
import fr.montemps.ui.components.InfoBanner
import fr.montemps.ui.components.PageHeader
import fr.montemps.ui.components.PillButton
import fr.montemps.ui.components.SectionHeader
import fr.montemps.ui.components.ShareRow
import fr.montemps.ui.components.StopGlyph
import fr.montemps.ui.components.distributionSegments
import fr.montemps.ui.components.tabular
import fr.montemps.ui.coverageText
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@Composable
fun TodayScreen(vm: TodayViewModel, onManageActivities: () -> Unit, onOpenHistory: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
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

    ScreenScaffold(snackbar = snackbar) { padding ->
        // Pas d'indicateur de chargement : la base répond en quelques millisecondes.
        val s = state ?: return@ScreenScaffold
        TodayContent(
            s = s,
            padding = padding,
            canNotify = canNotify,
            onTap = { id ->
                // Première utilisation sur Android 13+ : proposer l'autorisation une seule fois.
                if (!canNotify && !s.notificationPermissionAsked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (s.active?.activityId != id) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
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
            start = 20.dp,
            end = 20.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 32.dp,
        ),
    ) {
        item { PageHeader(Fmt.dayTitle(s.today)) }
        if (!canNotify) {
            item {
                InfoBanner(
                    text = "Notifications désactivées : le suivi fonctionne, mais rien ne s'affiche hors de l'app.",
                    action = "Autoriser",
                    onAction = onRequestNotifications,
                    icon = Icons.Outlined.Notifications,
                )
            }
        } else if (s.notificationDismissed) {
            item {
                InfoBanner(
                    text = "Notification masquée pour cette session.",
                    action = "Réafficher",
                    onAction = onReshowNotification,
                    icon = Icons.Outlined.Notifications,
                )
            }
        }
        s.clockShiftMinutes?.let { shift ->
            item {
                Spacer(Modifier.height(8.dp))
                InfoBanner(
                    text = "L'heure du téléphone a changé d'environ ${kotlin.math.abs(shift)} min pendant cette session. " +
                        "Vérifiez-la dans l'historique si besoin.",
                    action = "Historique",
                    onAction = onOpenHistory,
                    icon = Icons.Outlined.Warning,
                )
            }
        }
        item { Hero(s, onStop) }
        item { SectionHeader("Activités") }
        if (s.shortcuts.isEmpty()) {
            item {
                InfoBanner(text = "Toutes les activités sont archivées.", action = "Gérer", onAction = onManageActivities)
            }
        }
        items(s.shortcuts.chunked(columns), key = { row -> row.joinToString { it.id } }) { row ->
            Row(
                Modifier.height(IntrinsicSize.Min).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { a ->
                    ActivityTile(
                        activity = a,
                        isActive = s.active?.activityId == a.id,
                        onClick = { onTap(a.id) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item {
            SectionHeader("Aujourd'hui", trailing = if (s.report.isEmpty) null else Fmt.short(s.report.tracked))
        }
        item { DaySummary(s.report, s.activitiesById) }
    }
}

/**
 * Zone principale : activité en cours et chronomètre. La hauteur est la même avec ou sans
 * activité, pour que les raccourcis ne se déplacent jamais sous le doigt.
 */
@Composable
private fun Hero(s: TodayUiState, onStop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val active = s.active
    val timerStyle = MaterialTheme.typography.displayLarge.tabular().copy(textAlign = TextAlign.Center, fontWeight = FontWeight.Light)
    val autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 76.sp, stepSize = 2.sp)
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (active == null) {
            Text(
                "Aucune activité en cours",
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            BasicText(
                "00:00:00",
                style = timerStyle.copy(color = scheme.onSurface.copy(alpha = 0.14f)),
                maxLines = 1,
                autoSize = autoSize,
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
            )
            Text("Touchez une activité pour démarrer.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Spacer(Modifier.height(52.dp)) // emplacement du bouton « Arrêter »
        } else {
            val activity = s.activitiesById[active.activityId]
            val elapsed = active.duration(s.now)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
                ActivityMarker(activity)
                Spacer(Modifier.width(8.dp))
                Text(activity?.name ?: active.activityId, style = MaterialTheme.typography.titleLarge)
            }
            BasicText(
                Fmt.hms(elapsed),
                style = timerStyle.copy(color = scheme.onSurface),
                maxLines = 1,
                autoSize = autoSize,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Durée : ${Fmt.spoken(elapsed)}" },
            )
            Text(
                "En cours depuis ${Fmt.startLabel(active.start, s.today, s.zone)}",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            active.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(20.dp))
            PillButton("Arrêter le suivi", onClick = onStop, primary = false, leading = { StopGlyph() })
        }
    }
}

@Composable
private fun DaySummary(report: PeriodReport, activities: Map<String, TrackedActivity>) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (report.isEmpty) {
        Text("Rien n'a encore été suivi aujourd'hui.", style = MaterialTheme.typography.bodyMedium, color = muted)
        return
    }
    Column {
        DistributionBar(distributionSegments(report, activities))
        Spacer(Modifier.height(8.dp))
        Text(coverageText(report), style = MaterialTheme.typography.bodySmall, color = muted)
        Spacer(Modifier.height(12.dp))
        report.totals.forEach { t ->
            val a = activities[t.activityId]
            ShareRow(a, a?.name ?: t.activityId, t.duration, t.shareOfTracked)
        }
        Spacer(Modifier.height(4.dp))
        Text("Pourcentages calculés sur le temps suivi.", style = MaterialTheme.typography.labelSmall, color = muted)
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
        title = { Text(if (correcting) "Heure de fin réelle" else "Toujours en cours ?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!correcting) {
                    Text(
                        "« $name » est en cours depuis ${Fmt.startLabel(prompt.session.start, state.today, zone)} " +
                            "(${Fmt.short(prompt.session.duration(now))})." +
                            if (prompt.rebootedSinceStart) " Le téléphone a redémarré depuis." else "",
                    )
                    Text(
                        "Si vous l'avez oubliée, indiquez quand elle s'est terminée. Rien n'est modifié sans votre choix.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Début : ${Fmt.dayMedium(prompt.session.start.atZone(zone).toLocalDate())} à ${Fmt.time(prompt.session.start, zone)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
