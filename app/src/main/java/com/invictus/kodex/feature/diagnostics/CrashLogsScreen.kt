package com.invictus.kodex.feature.diagnostics

import com.invictus.kodex.ai.AiGate
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.core.diagnostics.CrashHandler
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.launch

/**
 * Lists recorded crash/caught-exception traces (see [CrashHandler]) so they can be
 * reviewed and copied/shared without adb -- opened from Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var logs by remember { mutableStateOf(CrashHandler.listLogs(context)) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val copiedLabel = stringResource(R.string.diagnostics_copied)

    val selectedEntry = logs.firstOrNull { it.file.absolutePath == selected }
    val selectedText = selectedEntry?.let {
        runCatching { it.file.readText() }.getOrDefault("")
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (selectedEntry != null) selected = null else onBack() }) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.diagnostics_title)) },
                actions = {
                    if (selectedEntry != null && selectedText != null) {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("crash log", selectedText))
                            scope.launch { snackbarHostState.showSnackbar(copiedLabel) }
                        }) {
                            Icon(XIcons.Save, contentDescription = stringResource(R.string.action_copy))
                        }
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, selectedText)
                            }
                            context.startActivity(Intent.createChooser(send, null))
                        }) {
                            Icon(XIcons.CloudUpload, contentDescription = stringResource(R.string.action_share))
                        }
                        // M20: explain the selected crash log with AI (ai flavor only)
                        AiGate?.let { g ->
                            g.ExplainErrorAi(
                                errorText = selectedText.orEmpty(),
                                projectPath = null,
                                app = context.applicationContext as android.app.Application,
                            )
                        }
                    } else if (logs.isNotEmpty()) {
                        IconButton(onClick = {
                            CrashHandler.clearLogs(context)
                            logs = CrashHandler.listLogs(context)
                        }) {
                            Icon(XIcons.Close, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                selectedEntry != null -> SelectionContainer {
                    Text(
                        text = selectedText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    )
                }
                logs.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(R.string.diagnostics_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(logs, key = { it.file.absolutePath }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.label) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        if (entry.fatal) R.string.diagnostics_kind_crash
                                        else R.string.diagnostics_kind_caught,
                                    ),
                                )
                            },
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .clickable { selected = entry.file.absolutePath },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
