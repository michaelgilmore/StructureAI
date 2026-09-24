package cc.gilmore.todoai.ui.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.gilmore.todoai.model.PlanScope
import cc.gilmore.todoai.repository.AiRepository
import cc.gilmore.todoai.repository.TodoRepository
import cc.gilmore.todoai.ui.components.DueDatePicker

@Composable
fun PlannerScreen(
    aiRepository: AiRepository,
    todoRepository: TodoRepository,
    onOpenTodo: (String) -> Unit,
    onDiscussPlan: (scope: String, periodStart: String, label: String) -> Unit,
) {
    val viewModel: PlannerViewModel = viewModel { PlannerViewModel(aiRepository, todoRepository) }
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Plan") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Scope", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanScope.entries.forEach { scope ->
                    FilterChip(
                        selected = scope == uiState.scope,
                        onClick = { viewModel.onScopeChange(scope) },
                        label = { Text(scope.label) },
                    )
                }
            }

            Text("Starting from", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            DueDatePicker(date = uiState.periodStart, onChange = { it?.let(viewModel::onPeriodStartChange) }, label = "Set start date")

            OutlinedTextField(
                value = uiState.focus,
                onValueChange = viewModel::onFocusChange,
                label = { Text("Focus (optional)") },
                placeholder = { Text("e.g. prioritize the client work") },
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            )

            Button(onClick = viewModel::generate, enabled = !uiState.isLoading, modifier = Modifier.padding(top = 16.dp)) {
                Text(if (uiState.isLoading) "Generating…" else "Generate plan")
            }

            if (uiState.isLoading) {
                Row(modifier = Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("Asking the AI…")
                }
            }

            uiState.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
            }

            uiState.plan?.let { plan ->
                Column(modifier = Modifier.padding(top = 20.dp)) {
                    Text(plan.summary, style = MaterialTheme.typography.bodyLarge)

                    OutlinedButton(
                        onClick = {
                            onDiscussPlan(plan.scope, plan.periodStart, "${uiState.scope.label} plan · ${plan.periodStart}")
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Icon(Icons.Filled.Chat, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Discuss this plan")
                    }

                    if (uiState.items.isNotEmpty()) {
                        // A plain Column, not LazyColumn: the outer Column
                        // here already scrolls (verticalScroll above), and
                        // nesting a lazy (independently-scrollable) list
                        // inside an unbounded-height scrolling container
                        // crashes at runtime ("vertically scrollable
                        // component was measured with an infinity maximum
                        // height constraint"). A plain Column is fine
                        // because plan items are capped server-side
                        // (MAX_PLAN_CANDIDATES in ai-orchestrator/index.ts,
                        // 60) — small enough to compose directly rather
                        // than needing virtualization.
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            uiState.items.forEach { item ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    onClick = { onOpenTodo(item.todoId) },
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(item.title, style = MaterialTheme.typography.bodyLarge)
                                        Text(item.rationale, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
