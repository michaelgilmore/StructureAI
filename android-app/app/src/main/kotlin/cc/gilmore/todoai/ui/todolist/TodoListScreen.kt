package cc.gilmore.todoai.ui.todolist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.gilmore.todoai.R
import cc.gilmore.todoai.connectivity.ConnectivityObserver
import cc.gilmore.todoai.repository.AuthRepository
import cc.gilmore.todoai.repository.TodoRepository
import cc.gilmore.todoai.ui.components.TodoRow
import cc.gilmore.todoai.ui.components.VoiceInputButton
import kotlinx.coroutines.launch

@Composable
fun TodoListScreen(
    repository: TodoRepository,
    authRepository: AuthRepository,
    connectivityObserver: ConnectivityObserver,
    onAddTodo: () -> Unit,
    onOpenTodo: (String) -> Unit,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReport: () -> Unit,
) {
    val viewModel: TodoListViewModel = viewModel { TodoListViewModel(repository, authRepository, connectivityObserver) }
    val todos by viewModel.todos.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val showCompleted by viewModel.showCompleted.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                // Sourced from R.string.app_name — see LoginScreen.kt's
                // matching comment.
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    // General-purpose dialog entry point (Phase 2) — grounded
                    // chat also opens from TodoDetailScreen ("Ask AI about
                    // this") and PlannerScreen ("Discuss this plan"); this is
                    // the one for "no specific todo/plan in mind".
                    IconButton(onClick = onOpenChat) {
                        Icon(Icons.Filled.Chat, contentDescription = "Ask AI")
                    }
                    IconButton(onClick = viewModel::toggleShowCompleted) {
                        if (showCompleted) {
                            Icon(Icons.Filled.VisibilityOff, contentDescription = "Hide completed todos")
                        } else {
                            Icon(Icons.Filled.Visibility, contentDescription = "Show completed todos")
                        }
                    }
                    IconButton(onClick = onOpenReport) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = "Completed todos report")
                    }
                    IconButton(onClick = viewModel::refresh) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                    IconButton(onClick = viewModel::signOut) {
                        Icon(Icons.Filled.Logout, contentDescription = "Sign out")
                    }
                    // Settings also has its own "Sign out" button (plus
                    // the AI mode / Home / Telegram / about rows) — kept
                    // here too rather than removed, since this is the
                    // faster path for the one action most people actually
                    // use day to day.
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Voice (small, secondary) stacked above the manual add FAB —
            // see ui/components/VoiceInputButton.kt's doc comment for why
            // this is a system RecognizerIntent call, not a custom
            // SpeechRecognizer. No due-date default here, matching this
            // FAB's own manual-add behavior (PodScreen's equivalent voice
            // button defaults to today instead, matching ITS manual FAB).
            Column(horizontalAlignment = Alignment.End) {
                VoiceInputButton(
                    onResult = { transcript ->
                        viewModel.addTodoFromVoice(transcript)
                        scope.launch { snackbarHostState.showSnackbar("Added \"$transcript\"") }
                    },
                    onUnavailable = {
                        scope.launch { snackbarHostState.showSnackbar("Voice input isn't available on this device") }
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                FloatingActionButton(onClick = onAddTodo) {
                    Icon(Icons.Filled.Add, contentDescription = "Add todo")
                }
            }
        },
    ) { padding ->
        if (todos.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No todos yet — tap + to add one", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(todos, key = { it.id }) { todo ->
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
