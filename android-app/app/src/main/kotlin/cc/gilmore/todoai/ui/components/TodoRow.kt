package cc.gilmore.todoai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cc.gilmore.todoai.data.local.SyncState
import cc.gilmore.todoai.data.local.TodoEntity
import cc.gilmore.todoai.model.TodoStatus
import cc.gilmore.todoai.util.DateUtils

/**
 * One todo as a card row: checkbox, title (strikethrough when done),
 * completed-date or due-date line, an unsynced-changes indicator, and a
 * delete button. Originally private to `ui/todolist/TodoListScreen.kt`;
 * pulled out here so `ui/pod/PodScreen.kt` (the Plan Of the Day screen)
 * can render the exact same row for its overdue/due-today lists instead of
 * a second, drifting copy.
 */
@Composable
fun TodoRow(todo: TodoEntity, onToggleComplete: () -> Unit, onDelete: () -> Unit, onClick: () -> Unit) {
    val isDone = todo.status == TodoStatus.DONE
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = isDone, onCheckedChange = { onToggleComplete() })
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    todo.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (isDone) TextDecoration.LineThrough else null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isDone && todo.completedAt != null) {
                        Text(DateUtils.displayCompletedAt(todo.completedAt), style = MaterialTheme.typography.bodySmall)
                    } else if (todo.dueDate != null) {
                        Text(DateUtils.displayDueDate(todo.dueDate), style = MaterialTheme.typography.bodySmall)
                    }
                    if (todo.syncState != SyncState.SYNCED) {
                        Icon(
                            Icons.Filled.Sync,
                            contentDescription = "Not yet synced",
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}
