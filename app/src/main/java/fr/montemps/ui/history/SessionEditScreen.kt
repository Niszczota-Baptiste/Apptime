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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.montemps.ui.ScreenScaffold
import fr.montemps.ui.components.ActivityMarker
import fr.montemps.ui.components.DateTimeField
import fr.montemps.ui.components.FieldLabel
import fr.montemps.ui.components.NoteBox
import fr.montemps.ui.components.PillButton
import fr.montemps.ui.components.SelectorField

@Composable
fun SessionEditScreen(vm: SessionEditViewModel, onDone: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var chooseActivity by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(s.done) { if (s.done) onDone() }
    val scheme = MaterialTheme.colorScheme

    ScreenScaffold(
        title = if (s.isNew) "Nouvelle session" else "Modifier la session",
        onBack = onDone,
        actions = {
            if (!s.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, contentDescription = "Supprimer") }
        },
    ) { padding ->
        if (s.loading) return@ScreenScaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            val activity = s.activities.firstOrNull { it.id == s.activityId }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Activité")
                SelectorField(onClick = { chooseActivity = true }) {
                    if (activity != null) {
                        ActivityMarker(activity)
                        Spacer(Modifier.width(12.dp))
                        Text(activity.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    } else {
                        Text("Choisir une activité", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = scheme.onSurfaceVariant)
                }
            }
            DateTimeField("Début", s.start, vm::setStart, enabled = !s.isActive)
            if (s.isActive) {
                NoteBox(
                    "Session en cours : seuls l'activité et la note sont modifiables. Pour corriger le début, " +
                        "arrêtez le suivi puis modifiez la session terminée.",
                )
            } else {
                DateTimeField("Fin", s.end, vm::setEnd)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Note")
                OutlinedTextField(
                    value = s.note,
                    onValueChange = vm::setNote,
                    placeholder = { Text("Facultative") },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = scheme.onSurface,
                        unfocusedBorderColor = scheme.outlineVariant,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
            s.error?.let {
                Text(
                    it,
                    color = scheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            PillButton("Enregistrer", onClick = vm::save, enabled = !s.saving, modifier = Modifier.fillMaxWidth())
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
                                .heightIn(min = 52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { vm.setActivity(a.id); chooseActivity = false }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ActivityMarker(a)
                            Spacer(Modifier.width(14.dp))
                            Text(a.name + if (a.archived) " · archivée" else "", style = MaterialTheme.typography.bodyLarge)
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

