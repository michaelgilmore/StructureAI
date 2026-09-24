package cc.gilmore.todoai.ui.tododetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.gilmore.todoai.repository.TodoRepository
import cc.gilmore.todoai.ui.components.DueDatePicker
import cc.gilmore.todoai.ui.components.PrioritySelector
import cc.gilmore.todoai.ui.components.RecurrencePicker
import cc.gilmore.todoai.ui.components.ReminderPicker
import java.time.LocalDate

@Composable
fun TodoDetailScreen(
    repository: TodoRepository,
    todoId: String?,
    onDone: () -> Unit,
    onAskAi: (todoId: String, title: String) -> Unit,
    // See TodoDetailViewModel's doc comment — only applied for a new todo.
    defaultDueDate: LocalDate? = null,
) {
    val viewModel: TodoDetailViewModel = viewModel(key = todoId ?: "new") { TodoDetailViewModel(repository, todoId, defaultDueDate) }
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.didSave) {
        if (uiState.didSave) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isNew) "New todo" else "Edit todo") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Only for an existing todo — a not-yet-saved "new todo"
                    // has no id yet for the dialog to be grounded in.
                    if (!uiState.isNew && todoId != null) {
                        IconButton(onClick = { onAskAi(todoId, uiState.title) }) {
                            Icon(Icons.Filled.Chat, contentDescription = "Ask AI about this")
                        }
                    }
                    TextButton(onClick = viewModel::save, enabled = uiState.title.isNotBlank()) {
                        Text("Save")
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            // imePadding() goes last (innermost) so it pads the *scrollable
            // content*, not the viewport around it — that's what makes the
            // content taller than the visible area once the keyboard is up,
            // which is what actually gives verticalScroll() something to
            // scroll so Due date/Reminder/Recurrence can be reached instead
            // of staying pinned behind the keyboard. Paired with
            // AndroidManifest.xml's windowSoftInputMode="adjustResize" on
            // MainActivity, which older OS versions (pre-Android 15) need
            // for the ime inset to be reported at all; on targetSdk 35 with
            // edge-to-edge enforced, the window no longer resizes itself and
            // this imePadding() modifier is what does the work instead.
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            OutlinedTextField(
                value = uiState.title,
                onValueChange = viewModel::onTitleChange,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.notes,
                onValueChange = viewModel::onNotesChange,
                label = { Text("Notes") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            OutlinedTextField(
                value = uiState.project,
                onValueChange = viewModel::onProjectChange,
                label = { Text("Project (optional)") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            Text("Priority", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            PrioritySelector(selected = uiState.priority, onSelect = viewModel::onPriorityChange)

            Text("Due date", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            DueDatePicker(date = uiState.dueDate, onChange = viewModel::onDueDateChange)

            Text("Reminder", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            ReminderPicker(dateTime = uiState.reminderAt, onChange = viewModel::onReminderChange)

            Text("Recurrence", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            RecurrencePicker(rule = uiState.recurrenceRule, onChange = viewModel::onRecurrenceChange)
        }
    }
}
