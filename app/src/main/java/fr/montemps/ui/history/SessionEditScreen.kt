package fr.montemps.ui.history

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.montemps.ui.ScreenScaffold
import fr.montemps.ui.components.ActivityBadge
import fr.montemps.ui.components.DateTimeField

@Composable
fun SessionEditScreen(vm: SessionEditViewModel, onDone: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var chooseActivity by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(s.done) { if (s.done) onDone() }

    ScreenScaffold(
        title = if (s.isNew) "Nouvelle session" else "Modifier la session",
        onBack = onDone,
        actions = {
            if (!s.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer") }
        },
    ) { padding ->
        if (s.loading) return@ScreenScaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val activity = s.activities.firstOrNull { it.id == s.activityId }
            Text("Activité", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { chooseActivity = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                if (activity != null) {
                    ActivityBadge(activity, size = 32.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(activity.name, modifier = Modifier.weight(1f))
                } else {
                    Text("Choisir une activité", modifier = Modifier.weight(1f))
                }
            }
            DateTimeField("Début", s.start, vm::setStart, enabled = !s.isActive)
            if (s.isActive) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text(
                        "Session en cours : seuls l'activité et la note sont modifiables. Pour corriger le début, " +
                            "arrêtez le suivi puis modifiez la session terminée dans l'historique.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                DateTimeField("Fin", s.end, vm::setEnd)
            }
            OutlinedTextField(
                value = s.note,
                onValueChange = vm::setNote,
                label = { Text("Note (facultative)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            s.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Button(onClick = vm::save, enabled = !s.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Enregistrer")
            }
        }
    }

    if (chooseActivity) {
        AlertDialog(
            onDismissRequest = { chooseActivity = false },
            confirmButton = { TextButton(onClick = { chooseActivity = false }) { Text("Annuler") } },
            title = { Text("Activité") },
            text = {
                LazyColumn {
                    items(s.activities.filter { !it.archived || it.id == s.original?.activityId }, key = { it.id }) { a ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable { vm.setActivity(a.id); chooseActivity = false }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ActivityBadge(a, size = 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(a.name + if (a.archived) " (archivée)" else "", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer cette session ?") },
            text = {
                Text(
                    if (s.isActive) "Le suivi en cours sera annulé sans être enregistré. Cette action est définitive."
                    else "La session sera retirée de l'historique et des bilans. Cette action est définitive.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}
