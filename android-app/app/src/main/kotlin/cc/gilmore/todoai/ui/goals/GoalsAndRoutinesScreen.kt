package cc.gilmore.todoai.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.gilmore.todoai.connectivity.ConnectivityObserver
import cc.gilmore.todoai.data.local.GoalEntity
import cc.gilmore.todoai.data.local.RoutineEntity
import cc.gilmore.todoai.model.GoalStatus
import cc.gilmore.todoai.model.RoutinePeriod
import cc.gilmore.todoai.model.RoutineStatus
import cc.gilmore.todoai.repository.GoalRepository
import cc.gilmore.todoai.repository.RoutineRepository
import cc.gilmore.todoai.repository.TodoRepository
import cc.gilmore.todoai.util.DateUtils

private enum class GoalsRoutinesTab { GOALS, ROUTINES }

/**
 * The combined "Goals" bottom-nav tab — Goals and Routines side by side
 * behind a segmented [TabRow], per the user's own choice when this was
 * built ("combined into the Goals tab" rather than a 5th bottom-nav icon
 * or a Settings-only entry point). Both lists, both edit dialogs
 * ([GoalEditDialog]/[RoutineEditDialog] in GoalRow.kt/RoutineRow.kt), and
 * the top bar's show-archived/refresh actions all key off [selectedTab] —
 * see android-app/README.md's "Goals" and "Routines" sections.
 *
 * Both view models are created unconditionally (not just the active
 * tab's) — simpler than tearing one down and recreating it every time the
 * user flips tabs, and cheap: neither does real work until something
 * actually reads its StateFlows.
 */
@Composable
fun GoalsAndRoutinesScreen(
    goalRepository: GoalRepository,
    routineRepository: RoutineRepository,
    todoRepository: TodoRepository,
    connectivityObserver: ConnectivityObserver,
    onOpenSettings: () -> Unit,
) {
    val goalViewModel: GoalListViewModel = viewModel { GoalListViewModel(goalRepository, connectivityObserver) }
    val routineViewModel: RoutineListViewModel = viewModel {
        RoutineListViewModel(routineRepository, todoRepository, connectivityObserver)
    }

    var selectedTab by remember { mutableStateOf(GoalsRoutinesTab.GOALS) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<GoalEntity?>(null) }
    var editingRoutine by remember { mutableStateOf<RoutineEntity?>(null) }

    val goals by goalViewModel.goals.collectAsState()
    val showArchivedGoals by goalViewModel.showArchived.collectAsState()
    val isRefreshingGoals by goalViewModel.isRefreshing.collectAsState()

    val routines by routineViewModel.routines.collectAsState()
    val todosForProgress by routineViewModel.todos.collectAsState()
    val showArchivedRoutines by routineViewModel.showArchived.collectAsState()
    val isRefreshingRoutines by routineViewModel.isRefreshing.collectAsState()

    val showArchived = if (selectedTab == GoalsRoutinesTab.GOALS) showArchivedGoals else showArchivedRoutines
    val isRefreshing = if (selectedTab == GoalsRoutinesTab.GOALS) isRefreshingGoals else isRefreshingRoutines

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Goals & Routines") },
                    actions = {
                        IconButton(
                            onClick = {
                                if (selectedTab == GoalsRoutinesTab.GOALS) goalViewModel.toggleShowArchived() else routineViewModel.toggleShowArchived()
                            },
                        ) {
                            if (showArchived) {
                                Icon(Icons.Filled.VisibilityOff, contentDescription = "Hide archived")
                            } else {
                                Icon(Icons.Filled.Visibility, contentDescription = "Show archived")
                            }
                        }
                        IconButton(
                            onClick = {
                                if (selectedTab == GoalsRoutinesTab.GOALS) goalViewModel.refresh() else routineViewModel.refresh()
                            },
                        ) {
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
                TabRow(selectedTabIndex = selectedTab.ordinal) {
                    Tab(
                        selected = selectedTab == GoalsRoutinesTab.GOALS,
                        onClick = { selectedTab = GoalsRoutinesTab.GOALS },
                        text = { Text("Goals") },
                    )
                    Tab(
                        selected = selectedTab == GoalsRoutinesTab.ROUTINES,
                        onClick = { selectedTab = GoalsRoutinesTab.ROUTINES },
                        text = { Text("Routines") },
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = if (selectedTab == GoalsRoutinesTab.GOALS) "Add goal" else "Add routine",
                )
            }
        },
    ) { padding ->
        when (selectedTab) {
            GoalsRoutinesTab.GOALS -> {
                if (goals.isEmpty()) {
                    EmptyState(padding, "No goals yet — tap + to add one")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                        items(goals, key = { it.id }) { goal ->
                            GoalRow(
                                goal = goal,
                                onClick = { editingGoal = goal },
                                onToggleArchived = { goalViewModel.setArchived(goal.id, goal.status != GoalStatus.ARCHIVED) },
                                onDelete = { goalViewModel.deleteGoal(goal.id) },
                            )
                        }
                    }
                }
            }
            GoalsRoutinesTab.ROUTINES -> {
                if (routines.isEmpty()) {
                    EmptyState(padding, "No routines yet — tap + to add one")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                        items(routines, key = { it.id }) { routine ->
                            RoutineRow(
                                routine = routine,
                                doneCount = routineViewModel.doneCount(routine, todosForProgress),
                                onClick = { editingRoutine = routine },
                                onToggleArchived = {
                                    routineViewModel.setArchived(routine.id, routine.status != RoutineStatus.ARCHIVED)
                                },
                                onDelete = { routineViewModel.deleteRoutine(routine.id) },
                                onCreateTask = { routineViewModel.createTaskForRoutine(routine) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        when (selectedTab) {
            GoalsRoutinesTab.GOALS -> GoalEditDialog(
                initialTitle = "",
                initialNotes = "",
                initialTargetDate = null,
                onDismiss = { showAddDialog = false },
                onSave = { title, notes, targetDate ->
                    goalViewModel.createGoal(title, notes, targetDate)
                    showAddDialog = false
                },
            )
            GoalsRoutinesTab.ROUTINES -> RoutineEditDialog(
                initialTitle = "",
                initialNotes = "",
                initialTargetCount = 1,
                initialPeriod = RoutinePeriod.WEEK,
                onDismiss = { showAddDialog = false },
                onSave = { title, notes, targetCount, period ->
                    routineViewModel.createRoutine(title, notes, targetCount, period)
                    showAddDialog = false
                },
            )
        }
    }

    editingGoal?.let { goal ->
        GoalEditDialog(
            initialTitle = goal.title,
            initialNotes = goal.notes.orEmpty(),
            initialTargetDate = DateUtils.parseDueDate(goal.targetDate),
            onDismiss = { editingGoal = null },
            onSave = { title, notes, targetDate ->
                goalViewModel.updateGoal(goal.id, title, notes, targetDate, goal.status)
                editingGoal = null
            },
        )
    }

    editingRoutine?.let { routine ->
        RoutineEditDialog(
            initialTitle = routine.title,
            initialNotes = routine.notes.orEmpty(),
            initialTargetCount = routine.targetCount,
            initialPeriod = routine.period,
            onDismiss = { editingRoutine = null },
            onSave = { title, notes, targetCount, period ->
                routineViewModel.updateRoutine(routine.id, title, notes, targetCount, period, routine.status)
                editingRoutine = null
            },
        )
    }
}

@Composable
private fun EmptyState(padding: PaddingValues, message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}
