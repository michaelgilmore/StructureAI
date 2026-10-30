package cc.gilmore.todoai.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.gilmore.todoai.connectivity.ConnectivityObserver
import cc.gilmore.todoai.data.local.GoalEntity
import cc.gilmore.todoai.model.GoalStatus
import cc.gilmore.todoai.repository.GoalRepository
import cc.gilmore.todoai.ui.components.DueDatePicker
import cc.gilmore.todoai.util.DateUtils
import java.time.LocalDate

/**
 * The "Goals" tab — see android-app/README.md's "Goals" section for why
 * this exists: high-level goals the planning AI reads alongside open todos
 * (aws-backend/lambdas/ai-orchestrator/index.ts's buildSubjectContext
 * 'planning' case) to suggest todos that actually move them forward.
 * Deliberately no detail/edit *screen* the way todos have one — a goal has
 * only three editable fields, so an in-place dialog (see [GoalEditDialog]
 * below) covers both add and edit without a second route.
 */
@Composable
fun GoalListScreen(
    repository: GoalRepository,
    connectivityObserver: ConnectivityObserver,
    onOpenSettings: () -> Unit,
) {
    val viewModel: GoalListViewModel = viewModel { GoalListViewModel(repository, connectivityObserver) }
    val goals by viewModel.goals.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val showArchived by viewModel.showArchived.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<GoalEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Goals") },
                actions = {
                    IconButton(onClick = viewModel::toggleShowArchived) {
                        if (showArchived) {
                            Icon(Icons.Filled.VisibilityOff, contentDescription = "Hide archived goals")
                        } else {
                            Icon(Icons.Filled.Visibility, contentDescription = "Show archived goals")
                        }
                    }
                    IconButton(onClick = viewModel::refresh) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add goal")
            }
        },
    ) { padding ->
        if (goals.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No goals yet — tap + to add one", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(goals, key = { it.id }) { goal ->
                    GoalRow(
                        goal = goal,
                        onClick = { editingGoal = goal },
                        onToggleArchived = { viewModel.setArchived(goal.id, goal.status != GoalStatus.ARCHIVED) },
                        onDelete = { viewModel.deleteGoal(goal.id) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        GoalEditDialog(
            initialTitle = "",
            initialNotes = "",
            initialTargetDate = null,
            onDismiss = { showAddDialog = false },
            onSave = { title, notes, targetDate ->
                viewModel.createGoal(title, notes, targetDate)
                showAddDialog = false
            },
        )
    }

    editingGoal?.let { goal ->
        GoalEditDialog(
            initialTitle = goal.title,
            initialNotes = goal.notes.orEmpty(),
            initialTargetDate = DateUtils.parseDueDate(goal.targetDate),
            onDismiss = { editingGoal = null },
            onSave = { title, notes, targetDate ->
                viewModel.updateGoal(goal.id, title, notes, targetDate, goal.status)
                editingGoal = null
            },
        )
    }
}

@Composable
private fun GoalRow(goal: GoalEntity, onClick: () -> Unit, onToggleArchived: () -> Unit, onDelete: () -> Unit) {
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
private fun GoalEditDialog(
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
