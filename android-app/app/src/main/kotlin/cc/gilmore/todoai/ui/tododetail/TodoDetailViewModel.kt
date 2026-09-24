package cc.gilmore.todoai.ui.tododetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.gilmore.todoai.model.Priority
import cc.gilmore.todoai.model.TodoStatus
import cc.gilmore.todoai.repository.TodoRepository
import cc.gilmore.todoai.ui.components.RecurrenceRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

data class TodoDetailUiState(
    val isNew: Boolean = true,
    val isLoading: Boolean = true,
    val title: String = "",
    val notes: String = "",
    val priority: Priority = Priority.MEDIUM,
    val status: TodoStatus = TodoStatus.OPEN,
    val dueDate: LocalDate? = null,
    val reminderAt: LocalDateTime? = null,
    val recurrenceRule: RecurrenceRule? = null,
    val project: String = "",
    // No tag editor in this pass yet — carried through as-is so editing an
    // existing todo doesn't silently wipe tags set some other way (e.g.
    // directly via the API, or a future AI-assisted creation path).
    val tags: List<String> = emptyList(),
    val didSave: Boolean = false,
)

class TodoDetailViewModel(
    private val repository: TodoRepository,
    private val todoId: String?,
    // Only meaningful when todoId == null (a brand-new todo) — set by the
    // Today screen's FAB so a todo created from there starts out due today
    // instead of empty, without changing the Todos tab's FAB, which still
    // passes null. Never read again once the ui state is seeded below, so
    // it can't clobber a date the user picks or clears afterward.
    defaultDueDate: LocalDate? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        TodoDetailUiState(
            isNew = todoId == null,
            isLoading = todoId != null,
            dueDate = if (todoId == null) defaultDueDate else null,
        ),
    )
    val uiState: StateFlow<TodoDetailUiState> = _uiState.asStateFlow()

    init {
        if (todoId != null) {
            viewModelScope.launch {
                val existing = repository.getTodo(todoId)
                if (existing != null) {
                    _uiState.value = TodoDetailUiState(
                        isNew = false,
                        isLoading = false,
                        title = existing.title,
                        notes = existing.notes.orEmpty(),
                        priority = existing.priority,
                        status = existing.status,
                        dueDate = cc.gilmore.todoai.util.DateUtils.parseDueDate(existing.dueDate),
                        reminderAt = cc.gilmore.todoai.util.DateUtils.parseReminderAt(existing.reminderAt),
                        recurrenceRule = RecurrenceRule.fromWireString(existing.recurrenceRule),
                        project = existing.project.orEmpty(),
                        tags = existing.tags,
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
        }
    }

    fun onTitleChange(value: String) { _uiState.value = _uiState.value.copy(title = value) }
    fun onNotesChange(value: String) { _uiState.value = _uiState.value.copy(notes = value) }
    fun onPriorityChange(value: Priority) { _uiState.value = _uiState.value.copy(priority = value) }
    fun onDueDateChange(value: LocalDate?) { _uiState.value = _uiState.value.copy(dueDate = value) }
    fun onReminderChange(value: LocalDateTime?) { _uiState.value = _uiState.value.copy(reminderAt = value) }
    fun onRecurrenceChange(value: RecurrenceRule?) { _uiState.value = _uiState.value.copy(recurrenceRule = value) }
    fun onProjectChange(value: String) { _uiState.value = _uiState.value.copy(project = value) }

    fun save() {
        val state = _uiState.value
        if (state.title.isBlank()) return
        viewModelScope.launch {
            val dueDateWire = state.dueDate?.let { cc.gilmore.todoai.util.DateUtils.formatDueDate(it) }
            val reminderWire = state.reminderAt?.let { cc.gilmore.todoai.util.DateUtils.formatReminderAt(it) }
            val recurrenceWire = state.recurrenceRule?.toWireString()
            val notes = state.notes.ifBlank { null }
            val project = state.project.ifBlank { null }

            if (state.isNew) {
                repository.createTodo(
                    title = state.title.trim(),
                    notes = notes,
                    priority = state.priority,
                    dueDate = dueDateWire,
                    reminderAt = reminderWire,
                    recurrenceRule = recurrenceWire,
                    project = project,
                    tags = state.tags,
                )
            } else if (todoId != null) {
                repository.updateTodo(
                    id = todoId,
                    title = state.title.trim(),
                    notes = notes,
                    status = state.status,
                    priority = state.priority,
                    dueDate = dueDateWire,
                    reminderAt = reminderWire,
                    recurrenceRule = recurrenceWire,
                    project = project,
                    tags = state.tags,
                )
            }
            _uiState.value = _uiState.value.copy(didSave = true)
        }
    }
}
