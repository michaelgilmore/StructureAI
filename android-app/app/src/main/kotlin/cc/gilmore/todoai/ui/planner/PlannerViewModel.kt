package cc.gilmore.todoai.ui.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.gilmore.todoai.data.remote.dto.PlanResultDto
import cc.gilmore.todoai.model.PlanScope
import cc.gilmore.todoai.repository.AiRepository
import cc.gilmore.todoai.repository.TodoRepository
import cc.gilmore.todoai.util.DateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class PlannerItemDisplay(val todoId: String, val title: String, val rationale: String)

data class PlannerUiState(
    val scope: PlanScope = PlanScope.DAY,
    val periodStart: LocalDate = LocalDate.now(),
    val focus: String = "",
    val isLoading: Boolean = false,
    val plan: PlanResultDto? = null,
    val items: List<PlannerItemDisplay> = emptyList(),
    val error: String? = null,
)

/**
 * No local cache for AI output (see AiRepository's doc comment) — [items]
 * resolves each generated item's title from [todoRepository]'s Room cache
 * purely for display; a todo deleted locally since the plan was generated
 * just falls back to a placeholder instead of failing the whole screen.
 */
class PlannerViewModel(
    private val aiRepository: AiRepository,
    private val todoRepository: TodoRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlannerUiState())
    val uiState: StateFlow<PlannerUiState> = _uiState.asStateFlow()

    fun onScopeChange(scope: PlanScope) {
        _uiState.update { it.copy(scope = scope) }
    }

    fun onPeriodStartChange(date: LocalDate) {
        _uiState.update { it.copy(periodStart = date) }
    }

    fun onFocusChange(focus: String) {
        _uiState.update { it.copy(focus = focus) }
    }

    fun generate() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            aiRepository.generatePlan(state.scope, DateUtils.formatDueDate(state.periodStart), state.focus)
                .onSuccess { plan ->
                    val items = plan.items.map { item ->
                        val title = todoRepository.getTodo(item.todoId)?.title ?: "(todo not found locally)"
                        PlannerItemDisplay(todoId = item.todoId, title = title, rationale = item.rationale)
                    }
                    _uiState.update { it.copy(isLoading = false, plan = plan, items = items) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Something went wrong") }
                }
        }
    }
}
