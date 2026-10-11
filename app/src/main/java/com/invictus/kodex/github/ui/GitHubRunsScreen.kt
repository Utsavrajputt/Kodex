package com.invictus.kodex.github.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.github.api.GhRun
import com.invictus.kodex.ui.icons.XIcons

/** Every workflow run of the project, with the status / workflow filters. Opened from "View all". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubRunsScreen(
    projectPath: String,
    onBack: () -> Unit,
    onOpenRunLog: (Long) -> Unit,
    vm: GitHubManagerViewModel = viewModel(
        key = "gh_runs_$projectPath",
        factory = GitHubManagerViewModel.factory(projectPath, lite = true),
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<GhRun?>(null) }
    var pendingCancel by remember { mutableStateOf<GhRun?>(null) }

    LaunchedEffect(vm) {
        vm.events.collect { event -> handleEvent(event, context, snackbar, scope) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gh_runs_title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(XIcons.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.gate == GitHubGate.Resolving) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item(key = "filters") { RunFilters(state, vm) }
                        when {
                            state.runsLoading -> item(key = "loading") {
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                            }
                            state.runsError != null && state.runs.isEmpty() -> item(key = "error") {
                                ErrorBlock(state.runsError?.resolve(context).orEmpty(), vm::retryRuns)
                            }
                            state.runs.isEmpty() -> item(key = "empty") {
                                Text(
                                    stringResource(R.string.gh_runs_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                            else -> {
                                items(state.runs, key = { it.id }) { run ->
                                    RunRow(
                                        run = run,
                                        workflowName = state.workflowName(run),
                                        busy = run.id in state.busyRunIds,
                                        onOpen = { onOpenRunLog(run.id) },
                                        onCopyError = { vm.copyError(run) },
                                        onRerun = { failedOnly -> vm.rerun(run, failedOnly) },
                                        onCancel = { pendingCancel = run },
                                        onDelete = { pendingDelete = run },
                                    )
                                }
                                if (state.hasMore) {
                                    item(key = "more") {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                            if (state.loadingMore) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                            else TextButton(onClick = vm::loadMore) { Text(stringResource(R.string.gh_load_more)) }
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

    pendingDelete?.let { run ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.gh_delete_title)) },
            text = { Text(stringResource(R.string.gh_delete_body, run.runNumber, run.name, run.branch)) },
            confirmButton = {
                Button(
                    onClick = { pendingDelete = null; vm.deleteRun(run) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.gh_delete_confirm, 1)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    pendingCancel?.let { run ->
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            title = { Text(stringResource(R.string.gh_cancel_title)) },
            text = { Text(stringResource(R.string.gh_cancel_body, run.runNumber, run.name, run.branch)) },
            confirmButton = {
                Button(
                    onClick = { pendingCancel = null; vm.cancelRun(run) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.gh_cancel_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pendingCancel = null }) { Text(stringResource(R.string.gh_keep_running)) } },
        )
    }
}
