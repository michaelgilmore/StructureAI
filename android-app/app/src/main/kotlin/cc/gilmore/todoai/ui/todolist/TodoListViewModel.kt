package cc.gilmore.todoai.ui.todolist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.gilmore.todoai.connectivity.ConnectivityObserver
import cc.gilmore.todoai.data.local.TodoEntity
import cc.gilmore.todoai.model.TodoStatus
import cc.gilmore.todoai.repository.AuthRepository
import cc.gilmore.todoai.repository.TodoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TodoListViewModel(
    private val repository: TodoRepository,
    private val authRepository: AuthRepository,
    connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    // Deliberately in-memory, not persisted (e.g. via DataStore) — this is a
    // lightweight view filter, not a sticky preference worth the extra
    // moving part; it just resets to "hide completed" on next app launch.
    private val _showCompleted = MutableStateFlow(false)
    val showCompleted: StateFlow<Boolean> = _showCompleted.asStateFlow()

    val todos: StateFlow<List<TodoEntity>> = combine(repository.observeTodos(), _showCompleted) { all, showCompleted ->
        if (showCompleted) all else all.filterNot { it.status == TodoStatus.DONE }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        refresh()
        // Sync promptly when the app regains a network, on top of
        // SyncScheduler's WorkManager-driven schedule — see
        // TodoRepository's doc comment for why both exist.
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

    fun toggleShowCompleted() {
        _showCompleted.value = !_showCompleted.value
    }

    fun completeTodo(id: String) {
        viewModelScope.launch { repository.completeTodo(id) }
    }

    fun deleteTodo(id: String) {
        viewModelScope.launch { repository.deleteTodo(id) }
    }

    fun signOut() {
        authRepository.signOut()
    }
}
