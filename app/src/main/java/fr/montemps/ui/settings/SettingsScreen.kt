package fr.montemps.ui.settings

import android.content.Intent
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.montemps.BuildConfig
import fr.montemps.data.prefs.AppPreferences
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.ui.ScreenScaffold
import fr.montemps.ui.components.ActivityMarker
import fr.montemps.ui.components.PageHeader
import fr.montemps.ui.components.SectionHeader
import fr.montemps.ui.label
import java.time.LocalDate

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
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    val today = LocalDate.now()
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { vm.exportCsv(resolver, it) }
    }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.exportJson(resolver, it) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.prepareRestore(resolver, it) }
    }

    ScreenScaffold(snackbar = snackbar) { padding ->
        val s = state ?: return@ScreenScaffold
        val byId = s.activities.associateBy { it.id }
        val showIcons = s.prefs.showIcons
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
                    value = id?.let { byId[it]?.label(showIcons) } ?: "Aucune",
                    onClick = { favoriteSlot = slot },
                )
            }
            SwitchRow(
                "Masquer le nom sur l'écran verrouillé",
                "N'affiche que « Suivi en cours » et la durée.",
                s.prefs.hideNameOnLockScreen,
                vm::setHideName,
            )
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

            Group("Affichage")
            SwitchRow("Icônes des activités", "Émojis à la place des pastilles de couleur.", s.prefs.showIcons, vm::setShowIcons)

            Group("Suivi")
            SettingsRow(
                "Session peut-être oubliée",
                "Signalée à la réouverture, ou après un redémarrage.",
                value = "${s.prefs.forgottenThresholdHours} h",
                onClick = { chooseThreshold = true },
            )
            SettingsRow("Gérer les activités", "Créer, renommer, réordonner, archiver.", onClick = onManageActivities)

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
            title = "Signaler une session après…",
            options = AppPreferences.thresholdChoices,
            label = { "$it heures" },
            leading = { },
            onPick = { vm.setThreshold(it); chooseThreshold = false },
            onDismiss = { chooseThreshold = false },
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
