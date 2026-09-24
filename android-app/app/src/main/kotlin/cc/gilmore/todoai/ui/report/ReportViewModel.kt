package cc.gilmore.todoai.ui.report

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.gilmore.todoai.model.TodoStatus
import cc.gilmore.todoai.repository.TodoRepository
import cc.gilmore.todoai.util.DateUtils
import cc.gilmore.todoai.util.PdfReportGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/**
 * Client-side report: TodoRepository's Room cache already holds a full
 * local copy of every non-deleted todo (see its doc comment), so this
 * filters a one-shot snapshot ([TodoRepository.getAllTodosOnce]) by
 * completedAt in-memory rather than adding a new server-side date-range
 * query. A todo completed while offline shows up here immediately too,
 * since TodoRepository.updateTodo stamps completedAt optimistically, not
 * only after sync.
 */
class ReportViewModel(private val repository: TodoRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<ReportUiState>(ReportUiState.Idle)
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    fun generate(context: Context, from: LocalDate, to: LocalDate) {
        viewModelScope.launch {
            _uiState.value = ReportUiState.Loading
            runCatching {
                val matches = repository.getAllTodosOnce()
                    .asSequence()
                    .filter { it.status == TodoStatus.DONE }
                    .mapNotNull { todo -> DateUtils.parseCompletedAt(todo.completedAt)?.let { date -> todo to date } }
                    .filter { (_, date) -> !date.isBefore(from) && !date.isAfter(to) }
                    .sortedByDescending { (_, date) -> date }
                    .map { (todo, _) -> todo }
                    .toList()
                val file = PdfReportGenerator.generate(context, from, to, matches)
                file to matches.size
            }.fold(
                onSuccess = { (file, count) -> _uiState.value = ReportUiState.Ready(file, count) },
                onFailure = { e -> _uiState.value = ReportUiState.Error(e.message ?: "Couldn't generate the report") },
            )
        }
    }

    fun reset() {
        _uiState.value = ReportUiState.Idle
    }
}

sealed interface ReportUiState {
    data object Idle : ReportUiState
    data object Loading : ReportUiState
    data class Ready(val file: File, val count: Int) : ReportUiState
    data class Error(val message: String) : ReportUiState
}
