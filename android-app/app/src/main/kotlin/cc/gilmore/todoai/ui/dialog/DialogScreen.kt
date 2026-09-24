package cc.gilmore.todoai.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.gilmore.todoai.model.ChatSubjectType
import cc.gilmore.todoai.repository.AiRepository
import cc.gilmore.todoai.repository.TodoRepository

@Composable
fun DialogScreen(
    aiRepository: AiRepository,
    todoRepository: TodoRepository,
    subjectType: ChatSubjectType,
    subjectId: String?,
    subjectLabel: String,
    onBack: () -> Unit,
    onOpenTodo: (String) -> Unit,
) {
    // Keyed on the subject so navigating from one grounded chat straight
    // to another (e.g. todo A's "Ask AI" -> back -> todo B's "Ask AI")
    // gets a fresh ViewModel/conversation instead of reusing todo A's.
    val viewModel: DialogViewModel = viewModel(key = "$subjectType:$subjectId") {
        DialogViewModel(aiRepository, todoRepository, subjectType, subjectId, subjectLabel)
    }
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) listState.animateScrollToItem(uiState.messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.subjectLabel) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = uiState.draft,
                    onValueChange = viewModel::onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask something…") },
                    enabled = !uiState.isSending,
                )
                IconButton(onClick = viewModel::send, enabled = !uiState.isSending && uiState.draft.isNotBlank()) {
                    if (uiState.isSending) {
                        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    } else {
                        Icon(Icons.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        },
    ) { padding ->
        if (uiState.messages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "Ask anything — grounded in ${uiState.subjectLabel.lowercase()}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.messages) { message -> ChatBubble(message, onOpenTodo) }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessageUi, onOpenTodo: (String) -> Unit) {
    val isUser = message.role == "user"
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
            Card(
                colors = when {
                    message.isError -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    isUser -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    else -> CardDefaults.cardColors()
                },
            ) {
                Text(message.text, modifier = Modifier.padding(12.dp))
            }
        }
        // One tappable chip per todo this reply actually created (see
        // AiDto.kt's ChatSendResultDto.createdTodos doc comment) — already
        // upserted into Room by DialogViewModel.send by the time this
        // renders, so tapping straight into TodoDetailScreen shows real
        // synced data, not a preview.
        if (message.createdTodos.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                message.createdTodos.forEach { todo ->
                    AssistChip(
                        onClick = { onOpenTodo(todo.id) },
                        label = { Text(todo.title) },
                        leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                        modifier = Modifier.wrapContentWidth(),
                    )
                }
            }
        }
    }
}
