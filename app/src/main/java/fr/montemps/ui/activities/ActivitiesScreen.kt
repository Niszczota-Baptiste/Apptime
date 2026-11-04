package fr.montemps.ui.activities

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import fr.montemps.AppContainer
import fr.montemps.domain.CatalogResult
import fr.montemps.domain.model.DefaultActivities
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.ui.ScreenScaffold
import fr.montemps.ui.components.ActivityBadge
import fr.montemps.ui.components.SectionTitle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ActivitiesViewModel(private val c: AppContainer) : ViewModel() {
    val activities = c.store.observeActivities().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()

    private fun run(block: suspend () -> CatalogResult, onOk: () -> Unit = {}) = viewModelScope.launch {
        when (val r = block()) {
            is CatalogResult.Error -> messages.send(r.message)
            is CatalogResult.Ok -> onOk()
        }
    }

    fun save(id: String?, name: String, color: Int, icon: String?, onOk: () -> Unit) =
        run({ if (id == null) c.catalog.create(name, color, icon) else c.catalog.edit(id, name, color, icon) }, onOk)

    fun move(id: String, delta: Int) = run({ c.catalog.move(id, delta) })
    fun setArchived(id: String, archived: Boolean) = run({ c.catalog.setArchived(id, archived) }) {
        viewModelScope.launch { messages.send(if (archived) "Activité archivée : son historique est conservé." else "Activité restaurée.") }
    }
}

private data class EditTarget(val activity: TrackedActivity?)

@Composable
fun ActivitiesScreen(vm: ActivitiesViewModel, onBack: () -> Unit) {
    val list by vm.activities.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    ScreenScaffold(
        title = "Activités",
        onBack = onBack,
        snackbar = snackbar,
        fab = {
            ExtendedFloatingActionButton(
                onClick = { editing = EditTarget(null) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Nouvelle activité") },
            )
        },
    ) { padding ->
        val all = list ?: return@ScreenScaffold
        val visible = all.filter { !it.archived }
        val archived = all.filter { it.archived }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp)) {
            item {
                Text(
                    "L'ordre ci-dessous est celui des raccourcis. Renommer une activité renomme aussi son historique. " +
                        "Archiver la retire des raccourcis sans effacer ses sessions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(visible, key = { it.id }) { a ->
                val index = visible.indexOf(a)
                ActivityRow(a, onClick = { editing = EditTarget(a) }) {
                    IconButton(onClick = { vm.move(a.id, -1) }, enabled = index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Monter ${a.name}")
                    }
                    IconButton(onClick = { vm.move(a.id, 1) }, enabled = index < visible.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Descendre ${a.name}")
                    }
                }
                HorizontalDivider()
            }
            if (archived.isNotEmpty()) {
                item { SectionTitle("Archivées", Modifier.padding(horizontal = 16.dp)) }
                items(archived, key = { it.id }) { a ->
                    ActivityRow(a, onClick = { editing = EditTarget(a) }) {
                        TextButton(onClick = { vm.setArchived(a.id, false) }) { Text("Restaurer") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    editing?.let { target ->
        ActivityEditDialog(
            initial = target.activity,
            onDismiss = { editing = null },
            onSave = { name, color, icon -> vm.save(target.activity?.id, name, color, icon) { editing = null } },
            onArchive = target.activity?.takeIf { !it.archived }?.let { a -> { vm.setArchived(a.id, true); editing = null } },
        )
    }
}

@Composable
private fun ActivityRow(a: TrackedActivity, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, onClickLabel = "Modifier")
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivityBadge(a)
        Spacer(Modifier.width(12.dp))
        Text(a.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityEditDialog(
    initial: TrackedActivity?,
    onDismiss: () -> Unit,
    onSave: (String, Int, String?) -> Unit,
    onArchive: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var color by remember { mutableStateOf(initial?.color ?: DefaultActivities.palette.first()) }
    var icon by remember { mutableStateOf(initial?.icon) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nouvelle activité" else "Modifier l'activité") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Couleur", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DefaultActivities.palette.forEach { c ->
                        val isSelected = c == color
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(BorderStroke(if (isSelected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface), CircleShape)
                                .clickable { color = c }
                                .semantics {
                                    contentDescription = "Couleur ${DefaultActivities.palette.indexOf(c) + 1}"
                                    selected = isSelected
                                },
                            contentAlignment = Alignment.Center,
                        ) { if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White) }
                    }
                }
                Text("Icône", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DefaultActivities.iconChoices.forEach { e ->
                        val isSelected = e == icon
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) Color(color).copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { icon = if (isSelected) null else e }
                                .semantics { selected = isSelected },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, style = MaterialTheme.typography.titleMedium) }
                    }
                }
                if (onArchive != null) {
                    TextButton(onClick = onArchive) { Text("Archiver cette activité") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, color, icon) }, enabled = name.isNotBlank()) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
