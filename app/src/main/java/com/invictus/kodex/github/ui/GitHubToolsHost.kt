package com.invictus.kodex.github.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.github.api.GhWorkflow
import com.invictus.kodex.github.data.CleanupFilters
import com.invictus.kodex.github.data.CleanupItem
import com.invictus.kodex.github.data.CleanupKind
import com.invictus.kodex.github.data.WorkflowInput
import com.invictus.kodex.ui.icons.XIcons

/**
 * Everything the M16 tools show outside the list: the system "create document" pickers, the
 * inputs sheet, the ready-to-install dialog, install prompts and the cleanup sheet.
 */
@Composable
fun GitHubToolsHost(
    state: GitHubToolsState,
    tools: GitHubToolsController,
    workflows: List<GhWorkflow>,
    defaultBranch: String?,
) {
    val context = LocalContext.current

    val apkSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(GitHubToolsController.MIME_APK)) {
        tools.completeSave(it)
    }
    val zipSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(GitHubToolsController.MIME_ZIP)) {
        tools.completeSave(it)
    }
    LaunchedEffect(state.pendingSave) {
        val p = state.pendingSave
        if (p != null && !p.launched) {
            tools.onSaveLaunched()
            if (p.mimeType == GitHubToolsController.MIME_APK) apkSave.launch(p.suggestedName) else zipSave.launch(p.suggestedName)
        }
    }

    if (state.run.sheetOpen) {
        val workflow = tools.effectiveWorkflow(workflows, state.run.selectedWorkflowId)
        val ref = state.run.selectedBranch ?: defaultBranch
        if (workflow != null && ref != null) RunInputsSheet(workflow, ref, state.run, tools)
    }

    // An APK started from an artifact row (the APK card shows its own ready state inline).
    state.ready?.takeIf { !it.fromApkCard && !it.silent }?.let { ready ->
        AlertDialog(
            onDismissRequest = tools::dismissReady,
            title = { Text(ready.artifactName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                ReadyApkBody(
                    ready = ready,
                    installing = state.installing,
                    onInstall = tools::onInstallTapped,
                    onSave = tools::saveReadyApk,
                    onDismiss = tools::dismissReady,
                )
            },
            confirmButton = {},
        )
    }

    when (state.installDialog) {
        InstallDialog.UnknownSources -> AlertDialog(
            onDismissRequest = tools::dismissInstallDialog,
            title = { Text(stringResource(R.string.gh_unknown_title)) },
            text = { Text(stringResource(R.string.gh_unknown_body)) },
            confirmButton = {
                TextButton(onClick = { runCatching { context.startActivity(tools.unknownSourcesIntent()) } }) {
                    Text(stringResource(R.string.gh_unknown_open))
                }
            },
            dismissButton = { TextButton(onClick = tools::dismissInstallDialog) { Text(stringResource(R.string.action_cancel)) } },
        )
        InstallDialog.SelfInstall -> AlertDialog(
            onDismissRequest = tools::dismissInstallDialog,
            title = { Text(stringResource(R.string.gh_self_title)) },
            text = { Text(stringResource(R.string.gh_self_body)) },
            confirmButton = { TextButton(onClick = tools::confirmSelfInstall) { Text(stringResource(R.string.gh_install)) } },
            dismissButton = { TextButton(onClick = tools::dismissInstallDialog) { Text(stringResource(R.string.action_cancel)) } },
        )
        null -> Unit
    }

    if (state.cleanup.open) CleanupSheet(state.cleanup, workflows, tools)
}

// ---- Run workflow inputs sheet (plan 8.2) -----------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunInputsSheet(workflow: GhWorkflow, ref: String, state: RunWorkflowState, tools: GitHubToolsController) {
    val info = (state.info as? DispatchInfoState.Loaded)?.info
    ModalBottomSheet(
        onDismissRequest = tools::closeInputsSheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.gh_inputs_title, workflow.name, ref), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            info?.inputs.orEmpty().forEach { input ->
                InputField(
                    input = input,
                    value = state.values[input.name].orEmpty(),
                    error = input.name in state.missingRequired,
                    onChange = { tools.setInput(input.name, it) },
                )
            }

            if (state.rawInputs.isNotEmpty()) {
                Text(stringResource(R.string.gh_inputs_raw_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.rawInputs.forEachIndexed { index, raw ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = raw.key, onValueChange = { tools.setRawInput(index, raw.copy(key = it)) },
                            label = { Text(stringResource(R.string.gh_inputs_key)) }, singleLine = true, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = raw.value, onValueChange = { tools.setRawInput(index, raw.copy(value = it)) },
                            label = { Text(stringResource(R.string.gh_inputs_value)) }, singleLine = true, modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { tools.removeRawInput(index) }) {
                            Icon(XIcons.Close, contentDescription = stringResource(R.string.gh_inputs_remove))
                        }
                    }
                }
                TextButton(onClick = tools::addRawInput) {
                    Icon(XIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.gh_inputs_add))
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = tools::closeInputsSheet) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = { tools.submitInputs(workflow, ref) }, enabled = !state.dispatching) {
                    if (state.dispatching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.gh_run_action_label))
                }
            }
        }
    }
}

@Composable
private fun InputField(input: WorkflowInput, value: String, error: Boolean, onChange: (String) -> Unit) {
    val label = if (input.required) "${input.name} *" else input.name
    when {
        input.type == "boolean" -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (input.description.isNotBlank()) Muted(input.description)
            }
            Switch(checked = value.equals("true", ignoreCase = true), onCheckedChange = { onChange(it.toString()) })
        }
        input.type == "choice" && input.options.isNotEmpty() -> {
            var open by remember { mutableStateOf(false) }
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge)
                if (input.description.isNotBlank()) Muted(input.description)
                Box {
                    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(value.ifEmpty { input.options.first() }, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(XIcons.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        input.options.forEach { o ->
                            DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onChange(o) })
                        }
                    }
                }
            }
        }
        else -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            supportingText = {
                when {
                    error -> Text(stringResource(R.string.gh_inputs_required))
                    input.description.isNotBlank() -> Text(input.description)
                }
            },
            isError = error,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (input.type == "number") KeyboardType.Number else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---- Cleanup sheet (plan 8.6) ---------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanupSheet(c: CleanupState, workflows: List<GhWorkflow>, tools: GitHubToolsController) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    // Swiping the sheet away only hides it: a running cleanup continues in the background.
    ModalBottomSheet(
        onDismissRequest = tools::closeCleanup,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.gh_cleanup_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            // What to clean.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CleanupKind.entries.forEach { k ->
                    FilterChip(
                        selected = c.filters.kind == k,
                        enabled = !c.running,
                        onClick = { tools.setCleanupKind(k) },
                        label = { Text(stringResource(kindLabel(k))) },
                    )
                }
            }

            val result = c.result
            when {
                c.running -> RunningBlock(c, onStop = tools::stopCleanup)
                result != null -> ResultBlock(c, onRetry = tools::retryFailedCleanup, onDone = tools::closeCleanup)
                else -> {
                    FilterRows(c, workflows, tools)
                    HorizontalDivider()
                    PlanBlock(c, tools, onDelete = { confirm = true })
                }
            }
        }
    }

    if (confirm) {
        val items = c.plan?.items.orEmpty().filter { it.id in c.selected }
        val bytes = items.sumOf { it.sizeBytes }
        val kind = c.filters.kind
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.gh_cleanup_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.gh_cleanup_confirm_body, items.size, stringResource(kindLabel(kind)).lowercase()))
                    if (bytes > 0) Muted(stringResource(R.string.gh_cleanup_size, Formatter.formatShortFileSize(context, bytes)))
                    items.take(3).forEach { Muted("• ${it.title}") }
                    if (items.size > 3) Muted(stringResource(R.string.gh_cleanup_and_more, items.size - 3))
                }
            },
            confirmButton = {
                Button(
                    onClick = { confirm = false; tools.runCleanup() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.gh_cleanup_delete_n, items.size, stringResource(kindLabel(kind)).lowercase())) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun kindLabel(k: CleanupKind): Int = when (k) {
    CleanupKind.Runs -> R.string.gh_kind_runs
    CleanupKind.Deployments -> R.string.gh_kind_deployments
    CleanupKind.Artifacts -> R.string.gh_kind_artifacts
}

private val OLDER_CHOICES = listOf<Int?>(null, 7, 30, 90)
private val KEEP_CHOICES = listOf<Int?>(null, 3, 5, 10)

@Composable
private fun FilterRows(c: CleanupState, workflows: List<GhWorkflow>, tools: GitHubToolsController) {
    val f = c.filters
    fun set(new: CleanupFilters) = tools.updateCleanupFilters(new)

    if (f.kind == CleanupKind.Runs) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = f.failed, onClick = { set(f.copy(failed = !f.failed)) }, label = { Text(stringResource(R.string.gh_filter_failed)) })
            FilterChip(selected = f.cancelled, onClick = { set(f.copy(cancelled = !f.cancelled)) }, label = { Text(stringResource(R.string.gh_filter_cancelled)) })
            var menu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { menu = true }) {
                    Text(
                        workflows.firstOrNull { it.id == f.workflowId }?.name ?: stringResource(R.string.gh_filter_all_workflows),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.gh_filter_all_workflows)) }, onClick = { menu = false; set(f.copy(workflowId = null)) })
                    workflows.forEach { w -> DropdownMenuItem(text = { Text(w.name) }, onClick = { menu = false; set(f.copy(workflowId = w.id)) }) }
                }
            }
        }
    }
    if (f.kind == CleanupKind.Artifacts) {
        FilterChip(selected = f.expiredOnly, onClick = { set(f.copy(expiredOnly = !f.expiredOnly)) }, label = { Text(stringResource(R.string.gh_cleanup_expired_only)) })
    }

    Muted(stringResource(R.string.gh_cleanup_older_than))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OLDER_CHOICES.forEach { d ->
            FilterChip(
                selected = f.olderThanDays == d,
                onClick = { set(f.copy(olderThanDays = d)) },
                label = { Text(if (d == null) stringResource(R.string.gh_cleanup_any) else stringResource(R.string.gh_cleanup_days, d)) },
            )
        }
    }
    Muted(stringResource(R.string.gh_cleanup_keep_latest))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KEEP_CHOICES.forEach { n ->
            FilterChip(
                selected = f.keepLatest == n,
                onClick = { set(f.copy(keepLatest = n)) },
                label = { Text(if (n == null) stringResource(R.string.gh_cleanup_off) else n.toString()) },
            )
        }
    }
}

@Composable
private fun PlanBlock(c: CleanupState, tools: GitHubToolsController, onDelete: () -> Unit) {
    val context = LocalContext.current
    when {
        c.scanning -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        c.scanError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(c.scanError.resolve(context), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = tools::scanCleanup) { Text(stringResource(R.string.gh_retry)) }
        }
        c.plan == null -> Unit
        c.plan.items.isEmpty() -> Muted(stringResource(R.string.gh_cleanup_nothing))
        else -> {
            val items = c.plan.items
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.gh_cleanup_selected, c.selected.size, items.size),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = tools::selectAllCleanup) { Text(stringResource(R.string.gh_cleanup_all)) }
                TextButton(onClick = tools::selectNoneCleanup) { Text(stringResource(R.string.gh_cleanup_none)) }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                items(items, key = { it.id }) { item -> CleanupRow(item, checked = item.id in c.selected) { tools.toggleCleanupItem(item.id) } }
            }
            val bytes = items.filter { it.id in c.selected }.sumOf { it.sizeBytes }
            Button(
                onClick = onDelete,
                enabled = c.selected.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val noun = stringResource(kindLabel(c.filters.kind)).lowercase()
                val size = if (bytes > 0) " (≈${Formatter.formatShortFileSize(context, bytes)})" else ""
                Text(stringResource(R.string.gh_cleanup_delete_n, c.selected.size, noun) + size)
            }
        }
    }
}

@Composable
private fun CleanupRow(item: CleanupItem, checked: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(
                item.subtitle,
                if (item.sizeBytes > 0) Formatter.formatShortFileSize(context, item.sizeBytes) else "",
                item.createdAt?.let { dateOf(it) }.orEmpty(),
            ).filter { it.isNotBlank() }.joinToString(" • ")
            Muted(sub)
            if (item.isLatestBuild) {
                Text(stringResource(R.string.gh_cleanup_latest_build), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun RunningBlock(c: CleanupState, onStop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.gh_cleanup_progress, c.progressDone, c.progressTotal), style = MaterialTheme.typography.bodyMedium)
        val total = c.progressTotal.coerceAtLeast(1)
        LinearProgressIndicator(progress = { c.progressDone.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = onStop, enabled = !c.stopRequested) {
            Text(stringResource(if (c.stopRequested) R.string.gh_cleanup_stopping else R.string.gh_cleanup_stop))
        }
    }
}

@Composable
private fun ResultBlock(c: CleanupState, onRetry: () -> Unit, onDone: () -> Unit) {
    val r = c.result ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.gh_cleanup_done, r.deleted, r.failed.size), style = MaterialTheme.typography.titleSmall)
        if (r.notAttempted.isNotEmpty()) Muted(stringResource(R.string.gh_cleanup_not_attempted, r.notAttempted.size))
        if (r.failed.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                items(r.failed, key = { it.item.id }) { f ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(f.item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(f.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.failed.isNotEmpty()) OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.gh_cleanup_retry_failed)) }
            Button(onClick = onDone) { Text(stringResource(R.string.gh_done)) }
        }
    }
}
