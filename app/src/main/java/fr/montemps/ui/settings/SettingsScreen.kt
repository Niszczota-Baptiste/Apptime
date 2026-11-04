package fr.montemps.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.montemps.BuildConfig
import fr.montemps.data.prefs.AppPreferences
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.ui.ScreenScaffold
import fr.montemps.ui.components.ActivityBadge
import fr.montemps.ui.components.SectionTitle
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

    ScreenScaffold(title = "Réglages", snackbar = snackbar) { padding ->
        val s = state ?: return@ScreenScaffold
        val byId = s.activities.associateBy { it.id }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            SectionTitle("Notification", Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                "Afficher la notification de suivi",
                "Activité en cours avec chronomètre et actions rapides.",
                s.prefs.notificationEnabled,
                vm::setNotificationEnabled,
            )
            repeat(AppPreferences.MAX_FAVORITES) { slot ->
                val id = s.prefs.favoriteIds.getOrNull(slot)
                ListItem(
                    headlineContent = { Text("Action rapide ${slot + 1}") },
                    supportingContent = { Text(id?.let { byId[it]?.label() } ?: "Aucune") },
                    modifier = Modifier.clickable { favoriteSlot = slot }.heightIn(min = 64.dp),
                )
            }
            Text(
                "Si une activité favorite est déjà en cours, son action est masquée (elle ne ferait rien). " +
                    "Android et HyperOS peuvent n'afficher qu'une partie des actions selon l'espace disponible.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SwitchRow(
                "Masquer le nom sur l'écran verrouillé",
                "Affiche seulement « Suivi en cours » et la durée lorsque le téléphone est verrouillé.",
                s.prefs.hideNameOnLockScreen,
                vm::setHideName,
            )
            ListItem(
                headlineContent = { Text("Réafficher la notification") },
                supportingContent = { Text("Si vous l'avez balayée pour la session en cours.") },
                modifier = Modifier.clickable { vm.reshowNotification() },
            )
            ListItem(
                headlineContent = { Text("Réglages de notification d'Android") },
                supportingContent = { Text("Autorisation, écran verrouillé, style d'affichage.") },
                modifier = Modifier.clickable {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
            Text(
                "Écran verrouillé : la notification n'y apparaît que si Android l'autorise. Vérifiez dans les réglages du " +
                    "téléphone (rubrique Notifications / Écran de verrouillage — l'intitulé varie selon HyperOS) que " +
                    "« Mon Temps » peut afficher ses notifications sur l'écran verrouillé, et si le contenu " +
                    "sensible doit être masqué. Le rendu exact dépend de l'appareil.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            SectionTitle("Suivi", Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text("Signaler une session peut-être oubliée") },
                supportingContent = { Text("Après ${s.prefs.forgottenThresholdHours} h, ou après un redémarrage du téléphone.") },
                modifier = Modifier.clickable { chooseThreshold = true },
            )
            ListItem(
                headlineContent = { Text("Gérer les activités") },
                supportingContent = { Text("Créer, renommer, réordonner, archiver.") },
                modifier = Modifier.clickable(onClick = onManageActivities),
            )
            HorizontalDivider()

            SectionTitle("Données", Modifier.padding(horizontal = 16.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { csvLauncher.launch("mon-temps-sessions-$today.csv") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("Exporter les sessions (CSV)")
                }
                OutlinedButton(onClick = { jsonLauncher.launch("mon-temps-sauvegarde-$today.json") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("Créer une sauvegarde (JSON)")
                }
                OutlinedButton(
                    onClick = { restoreLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Restaurer une sauvegarde…") }
                Text(
                    "Les fichiers sont enregistrés à l'endroit choisi (téléphone, carte, cloud…). L'application ne demande aucun " +
                        "accès général au stockage et fonctionne entièrement hors connexion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(Modifier.padding(top = 16.dp))
            Text(
                "Mon Temps ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    favoriteSlot?.let { slot ->
        val activities = state?.activities?.filter { !it.archived }.orEmpty()
        PickDialog(
            title = "Action rapide ${slot + 1}",
            options = listOf<TrackedActivity?>(null) + activities,
            label = { it?.label() ?: "Aucune" },
            leading = { it?.let { a -> ActivityBadge(a, size = 32.dp) } },
            onPick = { vm.setFavorite(slot, it?.id); favoriteSlot = null },
            onDismiss = { favoriteSlot = null },
        )
    }
    if (chooseThreshold) {
        PickDialog(
            title = "Session peut-être oubliée après…",
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
                        "seront remplacées. Pensez à créer d'abord une sauvegarde des données actuelles.",
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmRestore) { Text("Remplacer") } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Annuler") } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
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
                            .heightIn(min = 56.dp)
                            .clickable { onPick(o) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        leading(o)
                        Spacer(Modifier.width(12.dp))
                        Text(label(o), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        },
    )
}
