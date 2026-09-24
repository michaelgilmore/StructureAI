package cc.gilmore.todoai.ui.dialog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.gilmore.todoai.model.ChatSubjectType
import cc.gilmore.todoai.repository.AiRepository
import cc.gilmore.todoai.repository.TodoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A todo the AI created in response to this message — see AiDto.kt's ChatSendResultDto.createdTodos doc comment. Just enough to render a tappable chip; DialogScreen navigates to the real TodoDetailScreen for anything more. */
data class CreatedTodoUi(val id: String, val title: String)

data class ChatMessageUi(
    val role: String,
    val text: String,
    val isError: Boolean = false,
    val createdTodos: List<CreatedTodoUi> = emptyList(),
)

data class DialogUiState(
    val subjectType: ChatSubjectType,
    val subjectId: String?,
    val subjectLabel: String,
    val conversationId: String? = null,
    val messages: List<ChatMessageUi> = emptyList(),
    val draft: String = "",
    val isSending: Boolean = false,
)

/**
 * One dialog per screen instance — no conversation history persisted
 * locally (see AiRepository's doc comment on why AI screens have no local
 * cache). [DialogUiState.conversationId] is what keeps a multi-turn
 * exchange threaded server-side (docs/api/README.md's `conversationId`);
 * losing it (process death, or navigating away and back — a fresh
 * viewModel() call per DialogScreen.kt's `key`) just starts a new
 * conversation on the next send instead of crashing.
 */
class DialogViewModel(
    private val aiRepository: AiRepository,
    private val todoRepository: TodoRepository,
    subjectType: ChatSubjectType,
    subjectId: String?,
    subjectLabel: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DialogUiState(subjectType = subjectType, subjectId = subjectId, subjectLabel = subjectLabel),
    )
    val uiState: StateFlow<DialogUiState> = _uiState.asStateFlow()

    fun onDraftChange(text: String) {
        _uiState.update { it.copy(draft = text) }
    }

    fun send() {
        val state = _uiState.value
        val message = state.draft.trim()
        if (message.isEmpty() || state.isSending) return

        _uiState.update { it.copy(messages = it.messages + ChatMessageUi("user", message), draft = "", isSending = true) }

        viewModelScope.launch {
            aiRepository.sendChatMessage(state.subjectType, state.subjectId, state.conversationId, message)
                .onSuccess { result ->
                    // Land any AI-created todos in Room right away (see
                    // TodoRepository.upsertFromServer's doc comment) so
                    // they're visible on TodoListScreen/PodScreen the
                    // moment this reply renders, not just after the next
                    // background sync.
                    result.createdTodos.forEach { todoRepository.upsertFromServer(it) }
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            conversationId = result.conversationId,
                            messages = it.messages + ChatMessageUi(
                                role = "assistant",
                                text = result.reply,
                                createdTodos = result.createdTodos.map { todo -> CreatedTodoUi(todo.id, todo.title) },
                            ),
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            messages = it.messages + ChatMessageUi("assistant", e.message ?: "Something went wrong", isError = true),
                        )
                    }
                }
        }
    }
}
