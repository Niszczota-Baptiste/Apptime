package fr.montemps.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.montemps.AppContainer
import fr.montemps.domain.EditResult
import fr.montemps.domain.model.Session
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.ui.message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class SessionEditState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val original: Session? = null,
    val activities: List<TrackedActivity> = emptyList(),
    val activityId: String? = null,
    val start: LocalDateTime = LocalDateTime.now(),
    val end: LocalDateTime = LocalDateTime.now(),
    val note: String = "",
    val error: String? = null,
    val saving: Boolean = false,
    val done: Boolean = false,
) {
    val isActive: Boolean get() = original?.isActive == true
}

/**
 * Saisie d'une session : les heures locales sont converties dans le fuseau courant. Pendant un
 * changement d'heure, une heure inexistante est décalée vers l'avant et une heure ambiguë prend
 * le premier décalage (règles java.time).
 */
class SessionEditViewModel(
    private val c: AppContainer,
    private val sessionId: Long?,
    private val date: LocalDate?,
) : ViewModel() {
    private val zone: ZoneId get() = c.time.zone()
    private val _state = MutableStateFlow(SessionEditState())
    val state: StateFlow<SessionEditState> = _state

    init {
        viewModelScope.launch {
            val activities = c.store.activities()
            val session = sessionId?.let { c.store.session(it) }
            if (session != null) {
                _state.value = SessionEditState(
                    loading = false,
                    isNew = false,
                    original = session,
                    activities = activities,
                    activityId = session.activityId,
                    start = session.start.atZone(zone).toLocalDateTime(),
                    end = (session.end ?: c.time.now()).atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES),
                    note = session.note.orEmpty(),
                )
            } else {
                val now = c.time.now().atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
                val day = date ?: now.toLocalDate()
                val end = if (day == now.toLocalDate()) now.withMinute(now.minute - now.minute % 5) else LocalDateTime.of(day, LocalTime.of(10, 0))
                _state.value = SessionEditState(
                    loading = false,
                    isNew = true,
                    activities = activities,
                    activityId = null,
                    start = end.minusHours(1),
                    end = end,
                    error = if (sessionId != null) "Cette session n'existe plus." else null,
                )
            }
        }
    }

    fun setActivity(id: String) = _state.update { it.copy(activityId = id, error = null) }
    fun setStart(v: LocalDateTime) = _state.update { it.copy(start = v, error = null) }
    fun setEnd(v: LocalDateTime) = _state.update { it.copy(end = v, error = null) }
    fun setNote(v: String) = _state.update { it.copy(note = v.take(500)) }

    fun save() = viewModelScope.launch {
        val s = _state.value
        val activityId = s.activityId ?: run {
            _state.update { it.copy(error = "Choisissez une activité.") }
            return@launch
        }
        _state.update { it.copy(saving = true) }
        val start = ZonedDateTime.of(s.start, zone).toInstant()
        val end = ZonedDateTime.of(s.end, zone).toInstant()
        val result = when {
            s.original == null -> c.editor.addPastSession(activityId, start, end, s.note)
            s.isActive -> c.editor.updateSession(s.original.id, activityId, s.original.start, null, s.note)
            else -> c.editor.updateSession(s.original.id, activityId, start, end, s.note)
        }
        when (result) {
            is EditResult.Saved -> _state.update { it.copy(saving = false, done = true) }
            is EditResult.Invalid -> _state.update {
                it.copy(saving = false, error = result.error.message(s.activities.associateBy { a -> a.id }, zone))
            }
        }
    }

    fun delete() = viewModelScope.launch {
        val id = _state.value.original?.id ?: return@launch
        c.editor.deleteSession(id)
        _state.update { it.copy(done = true) }
    }
}
