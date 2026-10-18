package cc.gilmore.todoai.ui.pod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.gilmore.todoai.connectivity.ConnectivityObserver
import cc.gilmore.todoai.data.local.TodoEntity
import cc.gilmore.todoai.data.local.VoiceLaunchPreferenceStore
import cc.gilmore.todoai.model.Priority
import cc.gilmore.todoai.model.TodoStatus
import cc.gilmore.todoai.repository.TodoRepository
import cc.gilmore.todoai.util.DateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * "Plan Of the Day" — every OPEN todo whose dueDate is today or earlier,
 * split into Overdue / Due today, read live from the same Room cache
 * `TodoListViewModel` uses (`TodoRepository.observeTodos()`) rather than a
 * separate query — no new DAO method needed, just a different filter over
 * the same stream. A todo with no due date never appears here, same as it
 * never appears on any date-based view elsewhere in this app.
 *
 * `LocalDate.now()` is evaluated fresh on every `todos` emission, not once
 * — but if nothing else changes between 11:59pm and 12:01am, this screen
 * won't itself re-derive at the stroke of midnight (nothing re-emits the
 * underlying Flow just because the clock ticked). A pull-to-refresh
 * (`refresh()`, same as TodoListViewModel's) or any local edit re-triggers
 * it, so this is a minor rough edge, not a full-on inconsistency.
 */
class PodViewModel(
    private val repository: TodoRepository,
    connectivityObserver: ConnectivityObserver,
    private val voiceLaunchPreferenceStore: VoiceLaunchPreferenceStore,
) : ViewModel() {

    val uiState: StateFlow<PodUiState> = repository.observeTodos()
        .map { all ->
            val today = LocalDate.now()
            val open = all.filter { it.status == TodoStatus.OPEN }
            val overdue = open
                .filter { todo -> DateUtils.parseDueDate(todo.dueDate)?.isBefore(today) == true }
                .sortedWith(compareBy<TodoEntity> { DateUtils.parseDueDate(it.dueDate) }.thenByDescending { it.createdAt })
            val dueToday = open
                .filter { todo -> DateUtils.parseDueDate(todo.dueDate) == today }
                .sortedByDescending { it.createdAt }
            PodUiState(overdue = overdue, dueToday = dueToday)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PodUiState())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        refresh()
        // Same reasoning as TodoListViewModel's own copy of this: sync
        // promptly on regaining network, on top of SyncWorker's own
        // schedule.
        viewModelScope.launch {
            connectivityObserver.observe()
                .distinctUntilChanged()
                .filter { online -> online }
                .collect { repository.syncNow() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            repository.syncNow()
            _isRefreshing.value = false
        }
    }

    fun completeTodo(id: String) {
        viewModelScope.launch { repository.completeTodo(id) }
    }

    fun deleteTodo(id: String) {
        viewModelScope.launch { repository.deleteTodo(id) }
    }

    /**
     * Same one-shot voice create as TodoListViewModel.addTodoFromVoice, but
     * defaults dueDate to today — matching this screen's own manual-add FAB
     * (see PodScreen's floatingActionButton doc comment) so a voice-added
     * todo actually shows up on Today instead of landing undated on the
     * Todos tab.
     */
    fun addTodoFromVoice(title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            repository.createTodo(
                title = trimmed,
                notes = null,
                priority = Priority.MEDIUM,
                dueDate = DateUtils.formatDueDate(LocalDate.now()),
                reminderAt = null,
                recurrenceRule = null,
                project = null,
                tags = emptyList(),
            )
        }
    }

    /**
     * One-shot read, awaited directly by PodScreen's cold-start
     * `LaunchedEffect` rather than collected as a StateFlow — see
     * VoiceLaunchPreferenceStore.currentlyEnabled's doc comment on why
     * that avoids a race against DataStore's first real emission.
     */
    suspend fun shouldAutoListenOnLaunch(): Boolean = voiceLaunchPreferenceStore.currentlyEnabled()
}

data class PodUiState(
    val overdue: List<TodoEntity> = emptyList(),
    val dueToday: List<TodoEntity> = emptyList(),
) {
    val isEmpty: Boolean get() = overdue.isEmpty() && dueToday.isEmpty()
}
