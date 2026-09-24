package cc.gilmore.todoai.ui.pod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.gilmore.todoai.connectivity.ConnectivityObserver
import cc.gilmore.todoai.repository.TodoRepository
import cc.gilmore.todoai.ui.components.TodoRow

/**
 * "Today" tab — the Plan Of the Day: every open todo due today or overdue,
 * grouped into two sections. See PodViewModel's doc comment for exactly
 * what counts and how it's derived. Reuses `ui/components/TodoRow.kt` for
 * each row (same checkbox/delete/tap behavior as the main todo list) and
 * PlannerScreen/ReportScreen's general screen shape (TopAppBar + content),
 * not the AI-generated agenda `PlannerScreen` builds — this is a plain
 * mechanical due-date filter, no model call involved.
 */
@Composable
fun PodScreen(
    repository: TodoRepository,
    connectivityObserver: ConnectivityObserver,
    onOpenTodo: (String) -> Unit,
    onAddTodo: () -> Unit,
) {
    val viewModel: PodViewModel = viewModel { PodViewModel(repository, connectivityObserver) }
    val uiState by viewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Today") },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
            )
        },
        // Unlike the Todos tab's FAB, a todo created from here should default
        // to today's due date — see NavGraph.kt's onAddTodo wiring for this
        // route, which passes Routes.DEFAULT_DUE_DATE_ARG=today into
        // TodoDetailScreen rather than leaving the field empty.
        floatingActionButton = {
            FloatingActionButton(onClick = onAddTodo) {
                Icon(Icons.Filled.Add, contentDescription = "Add todo")
            }
        },
    ) { padding ->
        if (uiState.isEmpty) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Nothing due today or overdue — you're all caught up.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (uiState.overdue.isNotEmpty()) {
                    item(key = "overdue-header") {
                        SectionHeader("Overdue (${uiState.overdue.size})", isError = true)
                    }
                    items(uiState.overdue, key = { it.id }) { todo ->
                        TodoRow(
                            todo = todo,
                            onToggleComplete = { viewModel.completeTodo(todo.id) },
                            onDelete = { viewModel.deleteTodo(todo.id) },
                            onClick = { onOpenTodo(todo.id) },
                        )
                    }
                }
                if (uiState.dueToday.isNotEmpty()) {
                    item(key = "today-header") {
                        SectionHeader("Due today (${uiState.dueToday.size})")
                    }
                    items(uiState.dueToday, key = { it.id }) { todo ->
                        TodoRow(
                            todo = todo,
                            onToggleComplete = { viewModel.completeTodo(todo.id) },
                            onDelete = { viewModel.deleteTodo(todo.id) },
                            onClick = { onOpenTodo(todo.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, isError: Boolean = false) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}
