@file:OptIn(ExperimentalMaterial3Api::class)

package fr.montemps.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.montemps.domain.report.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Ligne « Libellé : [date] [heure] ». Les sélecteurs Material 3 manipulent des dates en UTC ;
 * la conversion se fait ici, en dates locales sans fuseau.
 */
@Composable
fun DateTimeField(
    label: String,
    value: LocalDateTime,
    onChange: (LocalDateTime) -> Unit,
    enabled: Boolean = true,
) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { showDate = true },
                enabled = enabled,
                modifier = Modifier.weight(1.4f).heightIn(min = 52.dp),
            ) { Text(Fmt.dayMedium(value.toLocalDate())) }
            OutlinedButton(
                onClick = { showTime = true },
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text(value.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))) }
        }
    }
    if (showDate) {
        DatePickerDialogFor(value.toLocalDate(), onDismiss = { showDate = false }) {
            onChange(LocalDateTime.of(it, value.toLocalTime()))
            showDate = false
        }
    }
    if (showTime) {
        TimePickerDialogFor(value.toLocalTime(), onDismiss = { showTime = false }) {
            onChange(LocalDateTime.of(value.toLocalDate(), it))
            showTime = false
        }
    }
}

@Composable
fun DatePickerDialogFor(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    ) {
        DatePicker(state = state)
    }
}

@Composable
fun TimePickerDialogFor(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        text = { TimePicker(state = state) },
    )
}
