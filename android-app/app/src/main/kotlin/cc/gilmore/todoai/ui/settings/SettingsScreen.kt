package cc.gilmore.todoai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.gilmore.todoai.BuildConfig
import cc.gilmore.todoai.R
import cc.gilmore.todoai.data.local.AiModeStore
import cc.gilmore.todoai.model.AiMode
import cc.gilmore.todoai.repository.AuthRepository

@Composable
fun SettingsScreen(
    authRepository: AuthRepository,
    aiModeStore: AiModeStore,
    onBack: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel { SettingsViewModel(authRepository, aiModeStore) }
    val aiMode by viewModel.aiMode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Account")
            SettingsRow(title = viewModel.username ?: "Signed in")
            OutlinedButton(
                onClick = viewModel::signOut,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Sign out")
            }

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            SectionHeader("AI mode")
            // Not a live toggle yet — both chips are disabled, and their
            // `selected` state just reflects AiModeStore's persisted value
            // (always AiMode.CLOUD in practice, since nothing reachable
            // from the UI can write AiMode.HOME today). Cloud is the only
            // mode that actually works (see AiRepository /
            // aws-backend/lambdas/ai-orchestrator, which rejects
            // mode:"home" outright); the switch becomes real once Phase 3's
            // Telegram bridge + home-bridge-bot exist to answer for Home
            // mode — at that point this becomes `enabled = true` plus
            // `onClick = { viewModel.setMode(AiMode.HOME) }` (and the
            // Cloud chip's mirror), nothing more. See docs/architecture.md
            // §11 and AiModeStore's doc comment.
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = aiMode == AiMode.CLOUD, onClick = {}, enabled = false, label = { Text("Cloud") })
                FilterChip(selected = aiMode == AiMode.HOME, onClick = {}, enabled = false, label = { Text("Home") })
            }
            Text(
                "Cloud mode only for now — Home mode (your own Ollama, reached over Telegram) arrives in Phase 3.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            SectionHeader("Home (Phase 3)")
            SettingsRow(title = "Home Ollama status", subtitle = "Not available yet")
            SettingsRow(title = "Telegram link", subtitle = "Not available yet")

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            SectionHeader("About")
            // Sourced from R.string.app_name — see LoginScreen.kt's matching comment.
            SettingsRow(title = stringResource(R.string.app_name), subtitle = "Version ${BuildConfig.VERSION_NAME}")
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsRow(title: String, subtitle: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
