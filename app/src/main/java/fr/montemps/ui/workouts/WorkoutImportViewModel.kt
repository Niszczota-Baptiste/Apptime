package fr.montemps.ui.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.montemps.AppContainer
import fr.montemps.domain.EditResult
import fr.montemps.domain.model.CommandSource
import fr.montemps.domain.model.DefaultActivities
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.workouts.ProposalStatus
import fr.montemps.domain.workouts.WorkoutAvailability
import fr.montemps.domain.workouts.WorkoutImport
import fr.montemps.domain.workouts.WorkoutProposal
import fr.montemps.ui.message
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZoneId

sealed interface WorkoutImportUi {
    data object Loading : WorkoutImportUi
    data class Unavailable(val needsUpdate: Boolean) : WorkoutImportUi
    data object NeedsPermission : WorkoutImportUi
    data class Ready(
        val proposals: List<WorkoutProposal>,
        val selected: Set<String>,
        val activities: List<TrackedActivity>,
        val targetId: String?,
        /** Fuseau d'affichage (celui de l'app, comme les autres écrans). */
        val zone: ZoneId,
        val importing: Boolean = false,
    ) : WorkoutImportUi {
        val target: TrackedActivity? get() = activities.firstOrNull { it.id == targetId }
        val selectable: List<WorkoutProposal> get() = proposals.filter { it.status == ProposalStatus.NEW }
    }
    data class Failed(val message: String) : WorkoutImportUi
}

/**
 * Import des séances sportives (Health Connect) : lecture à la demande des 30 derniers jours,
 * propositions, puis ajout des séances choisies comme sessions passées via [fr.montemps.domain.SessionEditor]
 * (mêmes validations qu'une saisie manuelle).
 */
class WorkoutImportViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<WorkoutImportUi>(WorkoutImportUi.Loading)
    val state: StateFlow<WorkoutImportUi> = _state.asStateFlow()

    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        // L'activité choisie (« Ajouter comme ») est conservée d'un rechargement à l'autre.
        val previousTarget = (_state.value as? WorkoutImportUi.Ready)?.targetId
        _state.value = WorkoutImportUi.Loading
        _state.value = try {
            when (c.workouts.availability()) {
                WorkoutAvailability.UNAVAILABLE -> WorkoutImportUi.Unavailable(needsUpdate = false)
                WorkoutAvailability.NEEDS_UPDATE -> WorkoutImportUi.Unavailable(needsUpdate = true)
                WorkoutAvailability.AVAILABLE -> if (!c.workouts.hasPermission()) WorkoutImportUi.NeedsPermission else proposals(previousTarget)
            }
        } catch (e: Exception) {
            WorkoutImportUi.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun proposals(previousTarget: String?): WorkoutImportUi.Ready {
        val now = c.time.now()
        val from = now.minus(PERIOD)
        val workouts = c.workouts.workouts(from, now)
        val activities = c.store.activities()
        val sessions = c.store.sessionsOverlapping(from.minus(Duration.ofDays(1)), now)
        val proposals = WorkoutImport.propose(workouts, sessions, activities.associateBy { it.id }, now)
        val usable = activities.filter { !it.archived }.sortedBy { it.sortOrder }
        val target = previousTarget?.takeIf { id -> usable.any { it.id == id } }
            ?: usable.firstOrNull { it.id == DefaultActivities.SPORT }?.id
            ?: usable.firstOrNull()?.id
        return WorkoutImportUi.Ready(
            proposals = proposals,
            selected = proposals.filter { it.status == ProposalStatus.NEW }.map { it.workout.id }.toSet(),
            activities = usable,
            targetId = target,
            zone = c.time.zone(),
        )
    }

    fun onPermissionResult(granted: Set<String>) {
        if (granted.containsAll(fr.montemps.data.health.HealthConnectWorkouts.PERMISSIONS)) {
            load()
        } else {
            viewModelScope.launch { messages.send("Autorisation refusée : aucune séance n'est lue.") }
        }
    }

    fun toggle(workoutId: String) = _state.update { s ->
        if (s !is WorkoutImportUi.Ready) return@update s
        s.copy(selected = if (workoutId in s.selected) s.selected - workoutId else s.selected + workoutId)
    }

    fun setTarget(activityId: String) = _state.update { s -> if (s is WorkoutImportUi.Ready) s.copy(targetId = activityId) else s }

    fun import() = viewModelScope.launch {
        val s = _state.value as? WorkoutImportUi.Ready ?: return@launch
        val target = s.targetId ?: return@launch
        val chosen = s.selectable.filter { it.workout.id in s.selected }
        if (chosen.isEmpty()) return@launch
        _state.value = s.copy(importing = true)
        var added = 0
        val refused = mutableListOf<String>()
        val byId = s.activities.associateBy { it.id }
        val zone = c.time.zone()
        val targetSubs = c.store.subActivities().filter { it.activityId == target }
        for (p in chosen.sortedBy { it.workout.start }) {
            val w = p.workout
            val sub = WorkoutImport.suggestSub(w.type, targetSubs)
            when (val r = c.editor.addPastSession(target, w.start, w.end, WorkoutImport.note(w), CommandSource.HealthImport, sub)) {
                is EditResult.Saved -> added++
                is EditResult.Invalid -> refused += "${w.type} : ${r.error.message(byId, zone)}"
            }
        }
        messages.send(
            buildString {
                append(if (added == 1) "1 séance ajoutée à l'historique." else "$added séances ajoutées à l'historique.")
                if (refused.isNotEmpty()) append(" Non ajoutée(s) : ").append(refused.joinToString(" ; "))
            },
        )
        load()
    }

    companion object {
        /** Période lue (Health Connect autorise sans réglage particulier les 30 derniers jours). */
        val PERIOD: Duration = Duration.ofDays(30)
    }
}
