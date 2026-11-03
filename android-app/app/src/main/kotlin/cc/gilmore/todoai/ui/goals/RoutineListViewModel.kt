package cc.gilmore.todoai.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.gilmore.todoai.connectivity.ConnectivityObserver
import cc.gilmore.todoai.data.local.RoutineEntity
import cc.gilmore.todoai.data.local.TodoEntity
import cc.gilmore.todoai.model.Priority
import cc.gilmore.todoai.model.RoutinePeriod
import cc.gilmore.todoai.model.RoutineStatus
import cc.gilmore.todoai.model.TodoStatus
import cc.gilmore.todoai.repository.RoutineRepository
import cc.gilmore.todoai.repository.TodoRepository
import cc.gilmore.todoai.util.DateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Mirrors GoalListViewModel's shape (see that class) — a showArchived
 * filter standing in for its own, plus [todos] (read-only, for computing
 * each routine's progress — see [doneCount]) and [createTaskForRoutine]
 * (the Routines screen's "Create task" action the user asked for: "I will
 * create tasks for them from the Routines screen").
 */
class RoutineListViewModel(
    private val routineRepository: RoutineRepository,
    private val todoRepository: TodoRepository,
    connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    private val _showArchived = MutableStateFlow(false)
    val showArchived: StateFlow<Boolean> = _showArchived.asStateFlow()

    val routines: StateFlow<List<RoutineEntity>> = combine(routineRepository.observeRoutines(), _showArchived) { all, showArchived ->
        if (showArchived) all else all.filterNot { it.status == RoutineStatus.ARCHIVED }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Read-only — only ever used here to compute [doneCount]; all todo mutations still go through TodoRepository from the usual screens. */
    val todos: StateFlow<List<TodoEntity>> = todoRepository.observeTodos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            connectivityObserver.observe()
                .distinctUntilChanged()
                .filter { online -> online }
                .collect { routineRepository.syncNow() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            routineRepository.syncNow()
            _isRefreshing.value = false
        }
    }

    fun toggleShowArchived() {
        _showArchived.value = !_showArchived.value
    }

    fun createRoutine(title: String, notes: String?, targetCount: Int, period: RoutinePeriod) {
        val trimmed = title.trim()
        if (trimmed.isBlank() || targetCount <= 0) return
        viewModelScope.launch {
            routineRepository.createRoutine(trimmed, notes?.trim()?.takeIf { it.isNotBlank() }, targetCount, period)
        }
    }

    fun updateRoutine(id: String, title: String, notes: String?, targetCount: Int, period: RoutinePeriod, status: RoutineStatus) {
        val trimmed = title.trim()
        if (trimmed.isBlank() || targetCount <= 0) return
        viewModelScope.launch {
            routineRepository.updateRoutine(id, trimmed, notes?.trim()?.takeIf { it.isNotBlank() }, targetCount, period, status)
        }
    }

    fun setArchived(id: String, archived: Boolean) {
        viewModelScope.launch { routineRepository.setArchived(id, archived) }
    }

    fun deleteRoutine(id: String) {
        viewModelScope.launch { routineRepository.deleteRoutine(id) }
    }

    /**
     * How many times [routine] has been completed within its own current
     * period (today/this week/this month) — exactly mirrors
     * aws-backend/lambdas/ai-orchestrator/index.ts's routineProgress
     * (same `routineId` + status DONE + completedAt-within-period test),
     * so what the user sees here matches what the planning AI is told.
     */
    fun doneCount(routine: RoutineEntity, allTodos: List<TodoEntity>): Int {
        val periodStart = periodStartFor(routine.period, LocalDate.now())
        return allTodos.count { todo ->
            todo.routineId == routine.id &&
                todo.status == TodoStatus.DONE &&
                todo.completedAt != null &&
                DateUtils.parseDueDate(todo.completedAt.take(10))?.let { !it.isBefore(periodStart) } == true
        }
    }

    /**
     * The Routines screen's own "Create task" action — the one explicit
     * link in this app between a Todo and anything else (see
     * TodoEntity.routineId's doc comment). Defaults to today, medium
     * priority, no notes/project/tags/reminder — a quick one-tap action,
     * not a form; the user can still open the created todo from Today/
     * Todos afterward to fill in more.
     */
    fun createTaskForRoutine(routine: RoutineEntity) {
        viewModelScope.launch {
            todoRepository.createTodo(
                title = routine.title,
                notes = null,
                priority = Priority.MEDIUM,
                dueDate = DateUtils.formatDueDate(LocalDate.now()),
                reminderAt = null,
                recurrenceRule = null,
                project = null,
                tags = emptyList(),
                routineId = routine.id,
            )
        }
    }
}

/** Mirrors aws-backend/lambdas/ai-orchestrator/index.ts's periodStartFor exactly (Mon-start ISO week) — see doneCount's doc comment. */
private fun periodStartFor(period: RoutinePeriod, today: LocalDate): LocalDate = when (period) {
    RoutinePeriod.DAY -> today
    RoutinePeriod.WEEK -> today.minusDays((today.dayOfWeek.value - 1).toLong())
    RoutinePeriod.MONTH -> today.withDayOfMonth(1)
}
