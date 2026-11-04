package fr.montemps.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.montemps.BuildConfig
import fr.montemps.data.prefs.AppPreferences
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.ui.ScreenScaffold
import fr.montemps.domain.report.Fmt
import fr.montemps.ui.components.ActivityMarker
import fr.montemps.ui.components.TimePickerDialogFor
import fr.montemps.ui.components.PageHeader
import fr.montemps.ui.components.SectionHeader
import fr.montemps.surfaces.ActivitiesWidgetProvider
import fr.montemps.surfaces.HomeScreenRequests
import fr.montemps.surfaces.TrackingWidgetProvider
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

@Composable
fun SettingsScreen(vm: SettingsViewModel, onManageActivities: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pending by vm.pendingRestore.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resolver = context.contentResolver
    var favoriteSlot by remember { mutableStateOf<Int?>(null) }
    var chooseThreshold by remember { mutableStateOf(false) }
    var chooseIdleDelay by remember { mutableStateOf(false) }
    var windowStep by remember { mutableStateOf<Int?>(null) }
    var windowStart by remember { mutableStateOf(0) }
    var pickSummaryTime by remember { mutableStateOf(false) }
    var autoBackupMenu by remember { mutableStateOf(false) }
    val folderLabel by vm.autoBackupFolder.collectAsStateWithLifecycle()
    var canPromote by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        canPromote = vm.canPostPromoted()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    val today = LocalDate.now()
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { vm.exportCsv(resolver, it) }
    }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.exportJson(resolver, it) }
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.enableAutoBackup(resolver, it) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.prepareRestore(resolver, it) }
    }

    ScreenScaffold(snackbar = snackbar) { padding ->
        val s = state ?: return@ScreenScaffold
        val byId = s.activities.associateBy { it.id }
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 32.dp),
        ) {
            PageHeader("Réglages", Modifier.padding(horizontal = 20.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))

            Group("Notification")
            SwitchRow("Notification de suivi", "Activité en cours, chronomètre et actions rapides.", s.prefs.notificationEnabled, vm::setNotificationEnabled)
            repeat(AppPreferences.MAX_FAVORITES) { slot ->
                val id = s.prefs.favoriteIds.getOrNull(slot)
                SettingsRow(
                    title = "Action rapide ${slot + 1}",
                    value = id?.let { byId[it]?.name } ?: "Aucune",
                    onClick = { favoriteSlot = slot },
                )
            }
            SwitchRow(
                "Masquer le nom sur l'écran verrouillé",
                "N'affiche que « Suivi en cours » et la durée.",
                s.prefs.hideNameOnLockScreen,
                vm::setHideName,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                SwitchRow(
                    "Afficher en haut de l'écran",
                    "Live Update : chronomètre dans la barre d'état (Android 16).",
                    s.prefs.promotedNotification,
                    vm::setPromoted,
                )
                if (s.prefs.promotedNotification && !canPromote) {
                    SettingsRow("Live Updates désactivés dans Android", "Toucher pour les autoriser.", onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    })
                }
            }
            SettingsRow("Réafficher la notification", "Si vous l'avez balayée.", onClick = vm::reshowNotification)
            SettingsRow(
                "Notifications Android",
                "Autorisation, écran verrouillé, contenu sensible.",
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
            Help(
                "Une favorite déjà en cours n'est pas proposée. L'affichage sur l'écran verrouillé dépend des réglages " +
                    "d'Android et d'HyperOS ; le rendu exact varie selon l'appareil.",
            )

            Group("Écran d'accueil")
            SettingsRow("Widget « Suivi en cours »", "Activité, chronomètre, Arrêter et favorites.", onClick = {
                if (!HomeScreenRequests.pinWidget(context, TrackingWidgetProvider::class.java)) vm.message(HomeScreenRequests.MANUAL_WIDGET)
            })
            SettingsRow("Widget « Activités »", "Changer d'activité d'un appui.", onClick = {
                if (!HomeScreenRequests.pinWidget(context, ActivitiesWidgetProvider::class.java)) vm.message(HomeScreenRequests.MANUAL_WIDGET)
            })
            SettingsRow("Tuile du centre de contrôle", "Arrêter ou reprendre le suivi.", onClick = {
                HomeScreenRequests.addTile(context) { vm.message(it) }
            })
            Help("Également possible à la main : appui long sur l'écran d'accueil → Widgets → Mon Temps.")

            Group("Suivi")
            SettingsRow("Gérer les activités", "Créer, renommer, réordonner, archiver, objectifs.", onClick = onManageActivities)

            Group("Rappels")
            SettingsRow(
                "Session longue",
                "Au-delà, la session est signalée comme peut-être oubliée.",
                value = "${s.prefs.forgottenThresholdHours} h",
                onClick = { chooseThreshold = true },
            )
            SwitchRow(
                "Rappel de session longue",
                "Une notification, une seule fois par session.",
                s.prefs.longSessionReminder,
                vm::setReminder,
            )
            SwitchRow(
                "Rappel « rien n'est suivi »",
                "Après ${s.prefs.idleReminderHours} h sans suivi, dans la plage horaire choisie.",
                s.prefs.idleReminderEnabled,
                vm::setIdle,
            )
            if (s.prefs.idleReminderEnabled) {
                SettingsRow("Délai", value = "${s.prefs.idleReminderHours} h", onClick = { chooseIdleDelay = true })
                SettingsRow(
                    "Plage horaire",
                    value = "${clock(s.prefs.idleWindowStartMinutes)} – ${clock(s.prefs.idleWindowEndMinutes)}",
                    onClick = { windowStart = s.prefs.idleWindowStartMinutes; windowStep = 0 },
                )
            }
            SwitchRow(
                "Résumé du soir",
                "Une notification silencieuse : total et objectifs du jour.",
                s.prefs.eveningSummaryEnabled,
                vm::setSummary,
            )
            if (s.prefs.eveningSummaryEnabled) {
                SettingsRow("Heure du résumé", value = clock(s.prefs.eveningSummaryMinutes), onClick = { pickSummaryTime = true })
            }

            Group("Données")
            SettingsRow("Exporter les sessions", "Fichier CSV pour tableur.", enabled = !busy, onClick = {
                csvLauncher.launch("mon-temps-sessions-$today.csv")
            })
            SettingsRow("Créer une sauvegarde", "Fichier JSON complet.", enabled = !busy, onClick = {
                jsonLauncher.launch("mon-temps-sauvegarde-$today.json")
            })
            SettingsRow("Restaurer une sauvegarde", "Remplace toutes les données actuelles.", enabled = !busy, onClick = {
                restoreLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
            })
            SettingsRow(
                "Sauvegarde automatique",
                autoBackupSubtitle(s.prefs, folderLabel),
                value = if (s.prefs.autoBackupTreeUri == null) "Désactivée" else null,
                enabled = !busy,
                onClick = { if (s.prefs.autoBackupTreeUri == null) treeLauncher.launch(null) else autoBackupMenu = true },
            )
            Help("Fichiers enregistrés où vous le choisissez. Aucun accès général au stockage, aucune connexion réseau.")

            Text(
                "Mon Temps ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
        }
    }

    favoriteSlot?.let { slot ->
        val activities = state?.activities?.filter { !it.archived }.orEmpty()
        PickDialog(
            title = "Action rapide ${slot + 1}",
            options = listOf<TrackedActivity?>(null) + activities,
            label = { it?.name ?: "Aucune" },
            leading = { it?.let { a -> ActivityMarker(a) } },
            onPick = { vm.setFavorite(slot, it?.id); favoriteSlot = null },
            onDismiss = { favoriteSlot = null },
        )
    }
    if (chooseThreshold) {
        PickDialog(
            title = "Session longue après…",
            options = AppPreferences.thresholdChoices,
            label = { "$it heures" },
            leading = { },
            onPick = { vm.setThreshold(it); chooseThreshold = false },
            onDismiss = { chooseThreshold = false },
        )
    }
    if (chooseIdleDelay) {
        PickDialog(
            title = "Rappel après…",
            options = AppPreferences.idleDelayChoices,
            label = { "$it h sans suivi" },
            leading = { },
            onPick = { vm.setIdleHours(it); chooseIdleDelay = false },
            onDismiss = { chooseIdleDelay = false },
        )
    }
    when (windowStep) {
        0 -> TimePickerDialogFor(minutesToTime(state?.prefs?.idleWindowStartMinutes ?: 480), onDismiss = { windowStep = null }) {
            windowStart = it.hour * 60 + it.minute
            windowStep = 1
        }
        1 -> TimePickerDialogFor(minutesToTime(state?.prefs?.idleWindowEndMinutes ?: 1320), onDismiss = { windowStep = null }) {
            val end = (it.hour * 60 + it.minute).let { m -> if (m == 0) 1440 else m }
            if (end > windowStart) vm.setIdleWindow(windowStart, end) else vm.message("La fin de la plage doit être après son début.")
            windowStep = null
        }
    }
    if (pickSummaryTime) {
        TimePickerDialogFor(minutesToTime(state?.prefs?.eveningSummaryMinutes ?: 1260), onDismiss = { pickSummaryTime = false }) {
            vm.setSummaryMinutes(it.hour * 60 + it.minute)
            pickSummaryTime = false
        }
    }
    if (autoBackupMenu) {
        AlertDialog(
            onDismissRequest = { autoBackupMenu = false },
            title = { Text("Sauvegarde automatique") },
            text = {
                Column {
                    listOf(
                        "Sauvegarder maintenant" to { vm.backupNow() },
                        "Changer de dossier" to { treeLauncher.launch(null) },
                        "Désactiver" to { vm.disableAutoBackup(resolver) },
                    ).forEach { (label, action) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { autoBackupMenu = false; action() }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) { Text(label, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { autoBackupMenu = false }) { Text("Fermer") } },
        )
    }
    pending?.let { p ->
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text("Remplacer toutes les données ?") },
            text = {
                Text(
                    "Sauvegarde du ${p.exportedAt.take(10)} : ${p.snapshot.activities.size} activités et " +
                        "${p.snapshot.sessions.size} sessions.\n\nToutes les activités, sessions et préférences actuelles " +
                        "seront remplacées. Pensez à sauvegarder d'abord les données actuelles.",
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmRestore) { Text("Remplacer") } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Annuler") } },
        )
    }
}

private fun clock(minutes: Int): String =
    if (minutes >= 1440) "24:00" else String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)

private fun minutesToTime(minutes: Int): LocalTime = if (minutes >= 1440) LocalTime.MIDNIGHT else LocalTime.of(minutes / 60, minutes % 60)

private fun autoBackupSubtitle(p: AppPreferences, folder: String?): String = when {
    p.autoBackupTreeUri == null -> "Une copie JSON par semaine dans un dossier choisi (8 conservées)."
    p.autoBackupLastError != null -> "${p.autoBackupLastError} · dossier « ${folder ?: "…"} »"
    p.autoBackupLastMs != null -> {
        val zone = ZoneId.systemDefault()
        val last = Instant.ofEpochMilli(p.autoBackupLastMs)
        "Chaque semaine dans « ${folder ?: "…"} » · dernière : ${Fmt.dayShort(last.atZone(zone).toLocalDate())} ${Fmt.time(last, zone)}"
    }
    else -> "Chaque semaine dans « ${folder ?: "…"} »"
}

@Composable
private fun Group(title: String) = SectionHeader(title, Modifier.padding(horizontal = 20.dp))

@Composable
private fun Help(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
        }
        value?.let {
            Spacer(Modifier.width(12.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = muted.copy(alpha = 0.6f))
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun <T> PickDialog(
    title: String,
    options: List<T>,
    label: (T) -> String,
    leading: @Composable (T) -> Unit,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        text = {
            LazyColumn {
                items(options) { o ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(o) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        leading(o)
                        Spacer(Modifier.width(14.dp))
                        Text(label(o), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
    )
}
