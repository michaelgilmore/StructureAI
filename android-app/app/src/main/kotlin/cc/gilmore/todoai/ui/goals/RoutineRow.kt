package cc.gilmore.todoai.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cc.gilmore.todoai.data.local.RoutineEntity
import cc.gilmore.todoai.model.RoutinePeriod
import cc.gilmore.todoai.model.RoutineStatus

/**
 * Row + edit dialog for one Routine — used by GoalsAndRoutinesScreen.kt
 * (see its doc comment). Mirrors GoalRow.kt's shape, plus a progress bar
 * ("2 of 3 this week") and a one-tap "Create task" action, since — unlike
 * a Goal — a Routine's whole point is "I will create tasks for them from
 * the Routines screen" (the user's own words). [doneCount] is passed in
 * rather than computed here, so this stays a dumb display component; the
 * actual counting (RoutineListViewModel.doneCount) mirrors
 * aws-backend/lambdas/ai-orchestrator/index.ts's routineProgress exactly.
 */
@Composable
internal fun RoutineRow(
    routine: RoutineEntity,
    doneCount: Int,
    onClick: () -> Unit,
    onToggleArchived: () -> Unit,
    onDelete: () -> Unit,
    onCreateTask: () -> Unit,
) {
    val isArchived = routine.status == RoutineStatus.ARCHIVED
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        routine.title,
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (isArchived) TextDecoration.LineThrough else null,
                    )
                    if (!routine.notes.isNullOrBlank()) {
                        Text(routine.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    Text(
                        "$doneCount of ${routine.targetCount} ${periodLabel(routine.period)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onCreateTask) {
                    Icon(Icons.Filled.PlaylistAdd, contentDescription = "Create task")
                }
                IconButton(onClick = onToggleArchived) {
                    if (isArchived) {
                        Icon(Icons.Filled.Unarchive, contentDescription = "Unarchive")
                    } else {
                        Icon(Icons.Filled.Archive, contentDescription = "Archive")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
            LinearProgressIndicator(
                progress = (doneCount.toFloat() / routine.targetCount.toFloat()).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

private fun periodLabel(period: RoutinePeriod): String = when (period) {
    RoutinePeriod.DAY -> "today"
    RoutinePeriod.WEEK -> "this week"
    RoutinePeriod.MONTH -> "this month"
}

@Composable
internal fun RoutineEditDialog(
    initialTitle: String,
    initialNotes: String,
    initialTargetCount: Int,
    initialPeriod: RoutinePeriod,
    onDismiss: () -> Unit,
    onSave: (title: String, notes: String?, targetCount: Int, period: RoutinePeriod) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var notes by remember { mutableStateOf(initialNotes) }
    var targetCountText by remember { mutableStateOf(initialTargetCount.toString()) }
    var period by remember { mutableStateOf(initialPeriod) }

    val targetCount = targetCountText.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialTitle.isBlank()) "Add routine" else "Edit routine") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = targetCountText,
                        onValueChange = { text -> targetCountText = text.filter { it.isDigit() } },
                        label = { Text("Times") },
                        singleLine = true,
                        modifier = Modifier.width(80.dp),
                    )
                    Text("per")
                    RoutinePeriod.entries.forEach { candidate ->
                        FilterChip(
                            selected = period == candidate,
                            onClick = { period = candidate },
                            label = { Text(candidate.wireValue) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title, notes, targetCount ?: 1, period) },
                enabled = title.isNotBlank() && (targetCount ?: 0) > 0,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
