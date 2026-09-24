package cc.gilmore.todoai.ui.report

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.gilmore.todoai.repository.TodoRepository
import cc.gilmore.todoai.ui.components.DueDatePicker
import java.time.LocalDate

/**
 * Date-range picker + "Generate PDF" for the completed-todos report — see
 * ReportViewModel's doc comment for where the data comes from, and
 * util/PdfReportGenerator.kt for how the PDF itself is built. Reuses
 * ui/components/Pickers.kt's DueDatePicker (twice, "From"/"To") rather than
 * Material3's separate DateRangePicker, for visual/interaction consistency
 * with the rest of the app (see e.g. PlannerScreen's own reuse of it).
 */
@Composable
fun ReportScreen(
    repository: TodoRepository,
    onBack: () -> Unit,
) {
    val viewModel: ReportViewModel = viewModel { ReportViewModel(repository) }
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var from by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var to by remember { mutableStateOf(LocalDate.now()) }
    val rangeValid = !from.isAfter(to)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Completed todos report") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                "Choose a date range — the PDF lists every todo completed in it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))
            // DueDatePicker's "Clear" button (shown whenever its date is
            // non-null, which is always true here) resets back to today
            // rather than leaving From/To in a null state a report can't
            // use — there's no meaningful "no date" for a range endpoint.
            DueDatePicker(date = from, onChange = { from = it ?: LocalDate.now() }, label = "From")
            Spacer(modifier = Modifier.height(8.dp))
            DueDatePicker(date = to, onChange = { to = it ?: LocalDate.now() }, label = "To")
            Spacer(modifier = Modifier.height(24.dp))

            if (!rangeValid) {
                Text(
                    "\"From\" must be on or before \"To\".",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = { viewModel.generate(context, from, to) },
                enabled = rangeValid && uiState !is ReportUiState.Loading,
            ) {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Generate PDF")
            }

            Spacer(modifier = Modifier.height(24.dp))

            when (val state = uiState) {
                is ReportUiState.Idle -> Unit
                is ReportUiState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text("Generating…")
                    }
                }
                is ReportUiState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
                is ReportUiState.Ready -> {
                    Text(
                        if (state.count == 0) "No todos were completed in this range."
                        else if (state.count == 1) "1 todo completed in this range."
                        else "${state.count} todos completed in this range.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            // authority must match AndroidManifest.xml's
                            // FileProvider entry ("${applicationId}.fileprovider").
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", state.file)
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share completed todos report"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Share / view PDF")
                    }
                }
            }
        }
    }
}
