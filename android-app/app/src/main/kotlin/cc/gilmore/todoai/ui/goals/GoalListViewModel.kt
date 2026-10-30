package cc.gilmore.todoai.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.gilmore.todoai.connectivity.ConnectivityObserver
import cc.gilmore.todoai.data.local.GoalEntity
import cc.gilmore.todoai.model.GoalStatus
import cc.gilmore.todoai.repository.GoalRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Mirrors TodoListViewModel's shape (see that class) — a showArchived filter standing in for its showCompleted one. */
class GoalListViewModel(
    private val repository: GoalRepository,
    connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    // Same "in-memory, resets on relaunch" tradeoff as TodoListViewModel's showCompleted.
    private val _showArchived = MutableStateFlow(false)
    val showArchived: StateFlow<Boolean> = _showArchived.asStateFlow()

    val goals: StateFlow<List<GoalEntity>> = combine(repository.observeGoals(), _showArchived) { all, showArchived ->
        if (showArchived) all else all.filterNot { it.status == GoalStatus.ARCHIVED }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        refresh()
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

    fun toggleShowArchived() {
        _showArchived.value = !_showArchived.value
    }

    fun createGoal(title: String, notes: String?, targetDate: String?) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { repository.createGoal(trimmed, notes?.trim()?.takeIf { it.isNotBlank() }, targetDate) }
    }

    fun updateGoal(id: String, title: String, notes: String?, targetDate: String?, status: GoalStatus) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            repository.updateGoal(id, trimmed, notes?.trim()?.takeIf { it.isNotBlank() }, targetDate, status)
        }
    }

    fun setArchived(id: String, archived: Boolean) {
        viewModelScope.launch { repository.setArchived(id, archived) }
    }

    fun deleteGoal(id: String) {
        viewModelScope.launch { repository.deleteGoal(id) }
    }
}
