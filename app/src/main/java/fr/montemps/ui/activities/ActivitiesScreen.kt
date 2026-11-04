package fr.montemps.ui.activities

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import fr.montemps.AppContainer
import fr.montemps.domain.CatalogResult
import fr.montemps.domain.model.DefaultActivities
import fr.montemps.domain.model.Goal
import fr.montemps.domain.model.GoalPeriod
import fr.montemps.domain.report.Fmt
import fr.montemps.ui.components.SegmentedToggle
import fr.montemps.ui.components.tabular
import java.time.Duration
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.ui.ScreenScaffold
import fr.montemps.ui.components.ActivityMarker
import fr.montemps.ui.components.FieldLabel
import fr.montemps.ui.components.SectionHeader
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

    fun save(id: String?, values: ActivityValues, onOk: () -> Unit) = run({
        if (id == null) {
            c.catalog.create(values.name, values.color, values.icon, values.interruption, values.goal)
        } else {
            c.catalog.edit(id, values.name, values.color, values.icon, values.interruption, values.goal)
        }
    }, onOk)

    fun move(id: String, delta: Int) = run({ c.catalog.move(id, delta) })
    fun setArchived(id: String, archived: Boolean) = run({ c.catalog.setArchived(id, archived) }) {
        viewModelScope.launch { messages.send(if (archived) "Activité archivée : son historique est conservé." else "Activité restaurée.") }
    }
}

private data class EditTarget(val activity: TrackedActivity?)

/** Valeurs saisies dans la boîte d'édition d'une activité. */
data class ActivityValues(
    val name: String,
    val color: Int,
    val icon: String?,
    val interruption: Boolean,
    val goal: Goal?,
)

@Composable
fun ActivitiesScreen(vm: ActivitiesViewModel, onBack: () -> Unit) {
    val list by vm.activities.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    ScreenScaffold(
        title = "Activités",
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            TextButton(onClick = { editing = EditTarget(null) }) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Nouvelle")
            }
        },
    ) { padding ->
        val all = list ?: return@ScreenScaffold
        val visible = all.filter { !it.archived }
        val archived = all.filter { it.archived }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 8.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
        ) {
            item {
                Text(
                    "Dans l'ordre des raccourcis. Renommer s'applique aussi à l'historique ; " +
                        "archiver retire l'activité des raccourcis sans effacer ses sessions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    modifier = Modifier.padding(end = 12.dp, top = 4.dp, bottom = 12.dp),
                )
            }
            items(visible, key = { it.id }) { a ->
                val index = visible.indexOf(a)
                ActivityRow(a, onClick = { editing = EditTarget(a) }) {
                    val colors = IconButtonDefaults.iconButtonColors(contentColor = muted)
                    IconButton(onClick = { vm.move(a.id, -1) }, enabled = index > 0, colors = colors) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Monter ${a.name}")
                    }
                    IconButton(onClick = { vm.move(a.id, 1) }, enabled = index < visible.lastIndex, colors = colors) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Descendre ${a.name}")
                    }
                }
            }
            if (archived.isNotEmpty()) {
                item { SectionHeader("Archivées", Modifier.padding(end = 12.dp)) }
                items(archived, key = { it.id }) { a ->
                    ActivityRow(a, onClick = { editing = EditTarget(a) }) {
                        TextButton(onClick = { vm.setArchived(a.id, false) }) { Text("Restaurer") }
                    }
                }
            }
        }
    }

    editing?.let { target ->
        ActivityEditDialog(
            initial = target.activity,
            onDismiss = { editing = null },
            onSave = { values -> vm.save(target.activity?.id, values) { editing = null } },
            onArchive = target.activity?.takeIf { !it.archived }?.let { a -> { vm.setArchived(a.id, true); editing = null } },
        )
    }
}

@Composable
private fun ActivityRow(a: TrackedActivity, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick, onClickLabel = "Modifier")
            .heightIn(min = 56.dp)
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivityMarker(a)
        Spacer(Modifier.width(14.dp))
        Text(
            a.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (a.archived) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityEditDialog(
    initial: TrackedActivity?,
    onDismiss: () -> Unit,
    onSave: (ActivityValues) -> Unit,
    onArchive: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var color by remember { mutableStateOf(initial?.color ?: DefaultActivities.palette.first()) }
    var interruption by remember { mutableStateOf(initial?.interruption ?: false) }
    var goalPeriod by remember { mutableStateOf(initial?.goal?.period) }
    var goalMinutes by remember { mutableStateOf(initial?.goal?.minutes ?: 30) }
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nouvelle activité" else "Modifier l'activité") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Nom") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = scheme.onSurface,
                        unfocusedBorderColor = scheme.outlineVariant,
                        focusedLabelColor = scheme.onSurface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldLabel("Couleur")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DefaultActivities.palette.forEachIndexed { i, c ->
                        val isSelected = c == color
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .then(if (isSelected) Modifier.border(2.dp, scheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = c }
                                .semantics {
                                    contentDescription = "Couleur ${i + 1}"
                                    selected = isSelected
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(26.dp).clip(CircleShape).background(Color(c)))
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = interruption, role = Role.Switch, onValueChange = { interruption = it })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Interruption", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Pause, repas… : propose ensuite de reprendre l'activité précédente.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = interruption, onCheckedChange = null)
                }
                FieldLabel("Objectif")
                SegmentedToggle(
                    options = listOf(null, GoalPeriod.DAY, GoalPeriod.WEEK),
                    selected = goalPeriod,
                    label = {
                        when (it) {
                            null -> "Aucun"
                            GoalPeriod.DAY -> "Par jour"
                            GoalPeriod.WEEK -> "Par semaine"
                        }
                    },
                    onSelect = { p ->
                        if (p == GoalPeriod.WEEK && goalPeriod != GoalPeriod.WEEK) goalMinutes = 180
                        if (p == GoalPeriod.DAY && goalPeriod != GoalPeriod.DAY) goalMinutes = 30
                        goalPeriod = p
                    },
                )
                goalPeriod?.let { period ->
                    val step = if (period == GoalPeriod.DAY) 5 else 30
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { goalMinutes = (goalMinutes - step).coerceAtLeast(step) }) {
                            Text("−", style = MaterialTheme.typography.titleLarge)
                        }
                        Text(
                            Fmt.short(Duration.ofMinutes(goalMinutes.toLong())) + if (period == GoalPeriod.DAY) " par jour" else " par semaine",
                            style = MaterialTheme.typography.bodyLarge.tabular(),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { goalMinutes = (goalMinutes + step).coerceAtMost(if (period == GoalPeriod.DAY) 1440 else 10080) }) {
                            Text("+", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
                if (onArchive != null) {
                    TextButton(onClick = onArchive) { Text("Archiver cette activité") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(ActivityValues(name, color, initial?.icon, interruption, goalPeriod?.let { Goal(goalMinutes, it) })) },
                enabled = name.isNotBlank(),
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
