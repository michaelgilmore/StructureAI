package cc.gilmore.todoai.ui.components

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cc.gilmore.todoai.model.Priority
import cc.gilmore.todoai.util.DateUtils
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Composable
fun PrioritySelector(selected: Priority, onSelect: (Priority) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Priority.entries.forEach { priority ->
            FilterChip(
                selected = priority == selected,
                onClick = { onSelect(priority) },
                label = { Text(priority.name.lowercase().replaceFirstChar { it.uppercase() }) },
            )
        }
    }
}

@Composable
fun DueDatePicker(
    date: LocalDate?,
    onChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    // Generic beyond its name below — reused by PlannerScreen for "period
    // start" with a different label rather than a copy-pasted composable.
    label: String = "Set due date",
) {
    val context = LocalContext.current
    Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val base = date ?: LocalDate.now()
            DatePickerDialog(
                context,
                { _, year, month, day -> onChange(LocalDate.of(year, month + 1, day)) },
                base.year, base.monthValue - 1, base.dayOfMonth,
            ).show()
        }) {
            Text(if (date != null) DateUtils.formatDueDate(date) else label)
        }
        if (date != null) {
            TextButton(onClick = { onChange(null) }) { Text("Clear") }
        }
    }
}

@Composable
fun ReminderPicker(dateTime: LocalDateTime?, onChange: (LocalDateTime?) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val base = dateTime ?: LocalDateTime.now()
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    TimePickerDialog(
                        context,
                        { _, hour, minute -> onChange(LocalDateTime.of(year, month + 1, day, hour, minute)) },
                        base.hour, base.minute, false,
                    ).show()
                },
                base.year, base.monthValue - 1, base.dayOfMonth,
            ).show()
        }) {
            Text(if (dateTime != null) DateUtils.displayLocalDateTime(dateTime) else "Set reminder")
        }
        if (dateTime != null) {
            TextButton(onClick = { onChange(null) }) { Text("Clear") }
        }
    }
}

@Composable
fun RecurrencePicker(rule: RecurrenceRule?, onChange: (RecurrenceRule?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(
                checked = rule != null,
                onCheckedChange = { enabled -> onChange(if (enabled) RecurrenceRule(Freq.WEEKLY) else null) },
            )
            Text("Repeats", modifier = Modifier.padding(start = 8.dp))
        }
        if (rule != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Every")
                var intervalText by remember(rule) { mutableStateOf(rule.interval.toString()) }
                androidx.compose.material3.OutlinedTextField(
                    value = intervalText,
                    onValueChange = { text ->
                        intervalText = text
                        text.toIntOrNull()?.takeIf { it > 0 }?.let { onChange(rule.copy(interval = it)) }
                    },
                    modifier = Modifier.width(64.dp),
                    singleLine = true,
                )
                FreqDropdown(selected = rule.freq, onSelect = { onChange(rule.copy(freq = it)) })
            }
        }
    }
}

@Composable
private fun FreqDropdown(selected: Freq, onSelect: (Freq) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Button(onClick = { expanded = true }) { Text(selected.label + "(s)") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Freq.entries.forEach { freq ->
                DropdownMenuItem(text = { Text(freq.label + "(s)") }, onClick = { onSelect(freq); expanded = false })
            }
        }
    }
}
