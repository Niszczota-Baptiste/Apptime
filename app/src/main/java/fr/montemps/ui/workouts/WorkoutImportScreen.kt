package fr.montemps.ui.workouts

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.montemps.data.health.HealthConnectWorkouts
import fr.montemps.domain.report.Fmt
import fr.montemps.domain.workouts.ProposalStatus
import fr.montemps.domain.workouts.WorkoutProposal
import fr.montemps.ui.ScreenScaffold
import fr.montemps.ui.components.ActivityMarker
import fr.montemps.ui.components.FieldLabel
import fr.montemps.ui.components.NoteBox
import fr.montemps.ui.components.PillButton
import fr.montemps.ui.components.SelectorField
import fr.montemps.ui.components.tabular
import java.time.ZoneId

@Composable
fun WorkoutImportScreen(vm: WorkoutImportViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var chooseActivity by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        vm.onPermissionResult(it)
    }

    ScreenScaffold(title = "Séances sportives", onBack = onBack, snackbar = snackbar) { padding ->
        val body = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 8.dp)
        when (val s = state) {
            WorkoutImportUi.Loading -> Column(body) { Explanation("Lecture des séances…") }
            is WorkoutImportUi.Unavailable -> Column(body, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Explanation(
                    if (s.needsUpdate) {
                        "Health Connect doit être mis à jour pour lire les séances de la montre."
                    } else {
                        "Health Connect n'est pas disponible sur ce téléphone."
                    },
                )
                if (s.needsUpdate) {
                    PillButton("Mettre à jour Health Connect", onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, "market://details?id=${HealthConnectWorkouts.HEALTH_CONNECT_PACKAGE}".toUri())
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (_: ActivityNotFoundException) {
                            // Pas de magasin d'applications : rien à proposer de plus.
                        }
                    }, modifier = Modifier.fillMaxWidth())
                }
            }
            WorkoutImportUi.NeedsPermission -> Column(body, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Explanation(
                    "Garmin Connect copie les séances de la montre dans Health Connect. Mon Temps peut les y lire " +
                        "(type, début, fin, titre) pour vous proposer de les ajouter à l'historique.",
                )
                NoteBox("Lecture seule et uniquement depuis cet écran : rien n'est écrit dans Health Connect, rien ne quitte le téléphone.")
                PillButton(
                    "Autoriser la lecture des séances",
                    onClick = { permissionLauncher.launch(HealthConnectWorkouts.PERMISSIONS) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is WorkoutImportUi.Failed -> Column(body, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Explanation("Lecture impossible : ${s.message}")
                PillButton("Réessayer", onClick = { vm.load() }, modifier = Modifier.fillMaxWidth())
            }
            is WorkoutImportUi.Ready -> ReadyContent(s, Modifier.fillMaxSize().padding(padding), vm, onChooseActivity = { chooseActivity = true })
        }
    }

    val ready = state as? WorkoutImportUi.Ready
    if (chooseActivity && ready != null) {
        AlertDialog(
            onDismissRequest = { chooseActivity = false },
            confirmButton = { TextButton(onClick = { chooseActivity = false }) { Text("Annuler") } },
            title = { Text("Ajouter comme") },
            text = {
                LazyColumn {
                    items(ready.activities, key = { it.id }) { a ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(selected = a.id == ready.targetId, role = Role.RadioButton) {
                                    vm.setTarget(a.id)
                                    chooseActivity = false
                                }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ActivityMarker(a)
                            Spacer(Modifier.width(12.dp))
                            Text(a.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ReadyContent(s: WorkoutImportUi.Ready, modifier: Modifier, vm: WorkoutImportViewModel, onChooseActivity: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val zone = s.zone
    val count = s.selectable.count { it.workout.id in s.selected }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Explanation(
                    "Séances des 30 derniers jours trouvées dans Health Connect. Rien n'est ajouté sans votre choix, " +
                        "et une séance ne remplace jamais une session existante.",
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldLabel("Ajouter comme")
                    SelectorField(onClick = onChooseActivity) {
                        val target = s.target
                        if (target != null) {
                            ActivityMarker(target)
                            Spacer(Modifier.width(12.dp))
                            Text(target.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        } else {
                            Text("Choisir une activité", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        }
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = scheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (s.proposals.isEmpty()) {
            item {
                Text(
                    "Aucune séance sur les 30 derniers jours. Vérifiez dans Garmin Connect que la synchronisation avec Health Connect est activée.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
        }
        items(s.proposals, key = { it.workout.id }) { p ->
            ProposalRow(p, zone, checked = p.workout.id in s.selected, onToggle = { vm.toggle(p.workout.id) })
            HorizontalDivider(Modifier.padding(start = 64.dp, end = 20.dp), thickness = 0.5.dp, color = scheme.outlineVariant)
        }
        item {
            PillButton(
                if (count <= 1) "Importer la séance" else "Importer $count séances",
                onClick = { vm.import() },
                enabled = count > 0 && s.target != null && !s.importing,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun ProposalRow(p: WorkoutProposal, zone: ZoneId, checked: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val w = p.workout
    val available = p.status == ProposalStatus.NEW
    val day = w.start.atZone(zone).toLocalDate()
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked && available, enabled = available, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked && available, onCheckedChange = null, enabled = available, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${w.type} · ${Fmt.short(w.duration)}",
                style = MaterialTheme.typography.bodyLarge,
                color = if (available) scheme.onSurface else scheme.onSurfaceVariant,
            )
            Text(
                "${Fmt.dayMedium(day)} · ${Fmt.time(w.start, zone)} – ${Fmt.time(w.end, zone)} · ${w.source}",
                style = MaterialTheme.typography.bodyMedium.tabular(),
                color = scheme.onSurfaceVariant,
            )
            when (p.status) {
                ProposalStatus.ALREADY_IMPORTED -> Status("Déjà dans l'historique" + (p.conflict?.let { " ($it)" } ?: ""))
                ProposalStatus.OVERLAPS -> Status("Chevauche « ${p.conflict ?: "une session"} »")
                ProposalStatus.NEW -> Unit
            }
        }
    }
}

@Composable
private fun Status(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Explanation(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
