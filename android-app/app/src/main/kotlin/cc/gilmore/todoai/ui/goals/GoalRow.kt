package cc.gilmore.todoai.ui.goals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import cc.gilmore.todoai.data.local.GoalEntity
import cc.gilmore.todoai.model.GoalStatus
import cc.gilmore.todoai.ui.components.DueDatePicker
import cc.gilmore.todoai.util.DateUtils
import java.time.LocalDate

/**
 * Row + edit dialog for one Goal — used by GoalsAndRoutinesScreen.kt (the
 * combined "Goals" bottom-nav tab, which also hosts Routines via a
 * segmented toggle — see that file's doc comment and android-app/
 * README.md's "Goals" and "Routines" sections). Deliberately no
 * detail/edit *screen* the way todos have one — a goal has only three
 * editable fields, so [GoalEditDialog] covers both add and edit without a
 * second route.
 */
@Composable
internal fun GoalRow(goal: GoalEntity, onClick: () -> Unit, onToggleArchived: () -> Unit, onDelete: () -> Unit) {
    val isArchived = goal.status == GoalStatus.ARCHIVED
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    goal.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (isArchived) TextDecoration.LineThrough else null,
                )
                if (!goal.notes.isNullOrBlank()) {
                    Text(goal.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                if (goal.targetDate != null) {
                    Text("Target: ${DateUtils.displayDueDate(goal.targetDate)}", style = MaterialTheme.typography.bodySmall)
                }
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
    }
}

@Composable
internal fun GoalEditDialog(
    initialTitle: String,
    initialNotes: String,
    initialTargetDate: LocalDate?,
    onDismiss: () -> Unit,
    onSave: (title: String, notes: String?, targetDate: String?) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var notes by remember { mutableStateOf(initialNotes) }
    var targetDate by remember { mutableStateOf(initialTargetDate) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialTitle.isBlank()) "Add goal" else "Edit goal") },
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
                DueDatePicker(
                    date = targetDate,
                    onChange = { targetDate = it },
                    modifier = Modifier.padding(top = 8.dp),
                    label = "Set target date",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title, notes, targetDate?.let { DateUtils.formatDueDate(it) }) },
                enabled = title.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
