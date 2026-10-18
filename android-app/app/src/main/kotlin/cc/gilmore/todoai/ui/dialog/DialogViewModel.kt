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
import java.util.UUID

/** A todo the AI created in response to this message — see AiDto.kt's ChatSendResultDto.createdTodos doc comment. Just enough to render a tappable chip; DialogScreen navigates to the real TodoDetailScreen for anything more. */
data class CreatedTodoUi(val id: String, val title: String)

/**
 * A proposed due-date change for a todo that already exists — see
 * AiDto.kt's DueDateChangeSuggestionDto doc comment. [currentDueDate] is
 * resolved locally (from Room, via [TodoRepository.getTodo]) rather than
 * sent by the server, purely so DialogScreen can show "was <date>" without
 * a second round trip; [isApplied] flips to true the moment the person
 * accepts it (see DialogViewModel.acceptDueDateSuggestion) so the chip in
 * DialogScreen swaps to a disabled "Moved" state instead of staying
 * tappable.
 */
data class DueDateSuggestionUi(
    val todoId: String,
    val title: String,
    val currentDueDate: String?,
    val newDueDate: String,
    val rationale: String,
    val isApplied: Boolean = false,
)

data class ChatMessageUi(
    val role: String,
    val text: String,
    val isError: Boolean = false,
    val createdTodos: List<CreatedTodoUi> = emptyList(),
    val dueDateSuggestions: List<DueDateSuggestionUi> = emptyList(),
    /**
     * Stable identity independent of [text]/[role] — needed so
     * [DialogViewModel.acceptDueDateSuggestion] can find and update the
     * one message a suggestion came from (marking just that suggestion
     * applied) without relying on list position, which shifts as new
     * messages are appended.
     */
    val id: String = UUID.randomUUID().toString(),
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

    init {
        // Planning mode opens straight into the "how many days" question
        // rather than a blank screen — see ai-orchestrator/index.ts's
        // buildChatSystemPrompt doc comment, which instructs the model to
        // ask this itself on the FIRST real turn too (this seed message is
        // never sent to the backend and never part of conversationId
        // history — it's purely a local UI nicety so the person isn't
        // staring at an empty chat before they've typed anything).
        if (subjectType == ChatSubjectType.PLANNING) {
            _uiState.update {
                it.copy(messages = listOf(ChatMessageUi("assistant", "How many days would you like me to plan for?")))
            }
        }
    }

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
                    // Resolve each suggestion's todo locally so the bubble
                    // can show a title and "was <date>" without a second
                    // round trip — see DueDateSuggestionUi's doc comment.
                    // mapNotNull silently drops a suggestion whose todo
                    // isn't in Room yet (e.g. a sync race): accepting one
                    // requires the full existing TodoEntity (see
                    // acceptDueDateSuggestion below), so there'd be nothing
                    // useful to show or act on anyway.
                    val suggestions = result.suggestedDueDateChanges.mapNotNull { change ->
                        val todo = todoRepository.getTodo(change.todoId) ?: return@mapNotNull null
                        DueDateSuggestionUi(
                            todoId = change.todoId,
                            title = todo.title,
                            currentDueDate = todo.dueDate,
                            newDueDate = change.newDueDate,
                            rationale = change.rationale,
                        )
                    }
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            conversationId = result.conversationId,
                            messages = it.messages + ChatMessageUi(
                                role = "assistant",
                                text = result.reply,
                                createdTodos = result.createdTodos.map { todo -> CreatedTodoUi(todo.id, todo.title) },
                                dueDateSuggestions = suggestions,
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

    /**
     * Applies one accepted due-date suggestion. [TodoRepository.updateTodo]
     * is a full replace, not a partial patch (see its doc comment), so this
     * fetches the existing entity and resubmits every field unchanged
     * except [DueDateSuggestionUi.newDueDate] — the same
     * fetch-then-full-resubmit shape [TodoRepository.completeTodo] already
     * uses for a single-field change. Marks only the matching suggestion
     * (by messageId + todoId, not list position) as applied, so the chip in
     * DialogScreen flips to a disabled "Moved" state and every other
     * suggestion — in this message or any other — is untouched.
     */
    fun acceptDueDateSuggestion(messageId: String, suggestion: DueDateSuggestionUi) {
        viewModelScope.launch {
            val existing = todoRepository.getTodo(suggestion.todoId) ?: return@launch
            todoRepository.updateTodo(
                id = existing.id,
                title = existing.title,
                notes = existing.notes,
                status = existing.status,
                priority = existing.priority,
                dueDate = suggestion.newDueDate,
                reminderAt = existing.reminderAt,
                recurrenceRule = existing.recurrenceRule,
                project = existing.project,
                tags = existing.tags,
            )
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map { message ->
                        if (message.id != messageId) return@map message
                        message.copy(
                            dueDateSuggestions = message.dueDateSuggestions.map {
                                if (it.todoId == suggestion.todoId) it.copy(isApplied = true) else it
                            },
                        )
                    },
                )
            }
        }
    }
}
