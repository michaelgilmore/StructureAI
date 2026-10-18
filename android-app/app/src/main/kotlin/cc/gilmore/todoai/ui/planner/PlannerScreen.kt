package cc.gilmore.todoai.ui.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Launcher for the "planning" conversation (see
 * aws-backend/docs/api/README.md's "Planning mode" section and
 * ui/dialog/DialogViewModel.kt) — this screen no longer collects a
 * scope/date/focus and calls a one-shot "generate plan" endpoint itself;
 * generating a plan now IS a conversation, so this just hands off straight
 * to DialogScreen (subjectType = ChatSubjectType.PLANNING) via
 * [onStartPlanning]. The AI asks how many days to plan for as the first
 * message in that chat (DialogViewModel's init block seeds the question
 * locally; the model asks it for real on the first real turn too — see
 * ai-orchestrator/index.ts's buildChatSystemPrompt).
 *
 * The old scope/date/focus form and one-shot plan/PlanItem rendering still
 * exist (PlannerViewModel.kt, AiRepository.generatePlan,
 * ai-orchestrator's `kind: "plan"` path) but are unreferenced by any
 * screen now — left in place rather than deleted; see android-app/README.md's
 * verification notes.
 */
@Composable
fun PlannerScreen(onStartPlanning: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Plan") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Chat,
                contentDescription = null,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Text(
                "Plan with AI",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "Tell the AI how many days you're planning for and it'll review everything still open — including " +
                    "suggesting due-date changes for todos that are related or depend on each other.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            Button(onClick = onStartPlanning) {
                Icon(Icons.Filled.Chat, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Start planning")
            }
        }
    }
}
