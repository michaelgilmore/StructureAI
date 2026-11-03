package cc.gilmore.todoai.ui.pod

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.LaunchedEffect
import android.util.Log
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.gilmore.todoai.TodoAiApplication
import cc.gilmore.todoai.connectivity.ConnectivityObserver
import cc.gilmore.todoai.data.local.VoiceLaunchPreferenceStore
import cc.gilmore.todoai.repository.AiRepository
import cc.gilmore.todoai.repository.TodoRepository
import cc.gilmore.todoai.ui.components.TodoRow
import cc.gilmore.todoai.ui.components.VoiceInputButton
import cc.gilmore.todoai.ui.components.rememberVoiceCommandLauncher
import kotlinx.coroutines.launch

/**
 * "Today" tab — the Plan Of the Day: every open todo due today or overdue,
 * grouped into two sections. See PodViewModel's doc comment for exactly
 * what counts and how it's derived. Reuses `ui/components/TodoRow.kt` for
 * each row (same checkbox/delete/tap behavior as the main todo list) and
 * PlannerScreen/ReportScreen's general screen shape (TopAppBar + content),
 * not the AI-generated agenda `PlannerScreen` builds — this is a plain
 * mechanical due-date filter, no model call involved.
 *
 * Also owns the cold-start "auto-listen" check (SettingsScreen's "Listen
 * for what to do when I open the app" switch) — this is the start
 * destination (see NavGraph's `startDestination = Routes.POD`), so it's
 * the one screen that's guaranteed to compose on every real app launch.
 * "Cold start," specifically, NOT just "this screen entered composition":
 * navigating here from another tab re-enters composition the same way a
 * fresh launch does, and so does resuming an already-running process after
 * "Ok Google, launch Structure" brings its existing task back to the
 * foreground (Android doesn't recreate the process for that). Neither of
 * those should re-pop the mic dialog. `TodoAiApplication.hasAutoListenedThisLaunch`
 * is what actually distinguishes them — a plain flag on the Application
 * singleton, set once and never reset until the process itself restarts,
 * which is the only time "cold start" is really true.
 *
 * The mic FAB and the auto-listen effect both drive the same voice-first
 * session (`ui/components/VoiceInputButton.kt`'s `rememberVoiceCommandLauncher`)
 * — "What would you like to do?", not just "add a todo" — see
 * android-app/README.md's "Voice-first" section.
 */
@Composable
fun PodScreen(
    repository: TodoRepository,
    aiRepository: AiRepository,
    connectivityObserver: ConnectivityObserver,
    voiceLaunchPreferenceStore: VoiceLaunchPreferenceStore,
    onOpenTodo: (String) -> Unit,
    onAddTodo: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartPlanning: () -> Unit,
) {
    val viewModel: PodViewModel = viewModel { PodViewModel(repository, connectivityObserver, voiceLaunchPreferenceStore) }
    val uiState by viewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val application = LocalContext.current.applicationContext as TodoAiApplication

    val startVoiceSession = rememberVoiceCommandLauncher(
        todoRepository = repository,
        aiRepository = aiRepository,
        onStartPlanning = onStartPlanning,
        onSpoken = { text -> scope.launch { snackbarHostState.showSnackbar(text) } },
        onUnavailable = {
            scope.launch { snackbarHostState.showSnackbar("Voice input isn't available on this device") }
        },
    )

    // Logged at every step (not just failures) so a logcat capture tagged
    // "TodoAi.PodScreen" tells you exactly which of the three gates stopped
    // it: already consumed this process (expected on anything that isn't a
    // genuine cold start — see this screen's doc comment), the Settings
    // switch is off (its default), or it actually fired.
    LaunchedEffect(Unit) {
        if (application.hasAutoListenedThisLaunch) {
            Log.d(TAG, "auto-listen: already consumed this process — not a cold start, skipping")
            return@LaunchedEffect
        }
        // The post-splash "use voice this launch?" prompt (see
        // ui/voiceprompt/VoiceLaunchPromptDialog.kt and NavGraph.kt) has
        // already resolved this decision before this screen could even
        // compose — NavGraph.kt gates everything else in the main app on
        // it. The `?:` fallback is defensive only (that gate always runs
        // on a real cold start); it never writes the decision back to
        // VoiceLaunchPreferenceStore either way.
        val enabled = application.voiceLaunchPromptDecision ?: viewModel.shouldAutoListenOnLaunch()
        Log.d(TAG, "auto-listen: enabled=$enabled")
        if (enabled) {
            application.hasAutoListenedThisLaunch = true
            Log.d(TAG, "auto-listen: triggering startVoiceSession()")
            startVoiceSession()
        }
    }

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
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                VoiceInputButton(
                    todoRepository = repository,
                    aiRepository = aiRepository,
                    onStartPlanning = onStartPlanning,
                    onSpoken = { text -> scope.launch { snackbarHostState.showSnackbar(text) } },
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

private const val TAG = "TodoAi.PodScreen"

@Composable
private fun SectionHeader(title: String, isError: Boolean = false) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}
