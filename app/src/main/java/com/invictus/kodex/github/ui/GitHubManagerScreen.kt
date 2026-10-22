package com.invictus.kodex.github.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.invictus.kodex.R
import com.invictus.kodex.KodexApp
import com.invictus.kodex.github.api.GhArtifact
import com.invictus.kodex.github.api.GhDeployment
import com.invictus.kodex.github.api.GhRun
import com.invictus.kodex.github.api.RunDisplayStatus
import com.invictus.kodex.github.data.RunStatusFilter
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Per-project GitHub Manager (plan 6.1). The repo is derived from the project's remote; no picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubManagerScreen(
    projectPath: String,
    onBack: () -> Unit,
    onOpenRunLog: (Long) -> Unit,
    onOpenAllRuns: () -> Unit,
    onOpenRemotes: () -> Unit,
    onGitSetup: () -> Unit,
    onOpenGitHubSettings: () -> Unit,
    viewModel: GitHubManagerViewModel = viewModel(
        key = "gh_manager_$projectPath",
        factory = GitHubManagerViewModel.factory(projectPath),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toolsState by viewModel.tools.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onPause() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> handleEvent(event, context, snackbar, scope) }
    }

    val pal = ghPalette()

    Scaffold(
        modifier = Modifier.background(pal.screen),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    titleContentColor = pal.onCard,
                    navigationIconContentColor = pal.onCard,
                    actionIconContentColor = pal.onCard,
                ),
                title = {
                    Text(
                        stringResource(R.string.gh_title), maxLines = 1,
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.gate == GitHubGate.Ready) {
                        IconButton(onClick = { viewModel.tools.openCleanup() }) {
                            Icon(XIcons.DeleteSweep, contentDescription = stringResource(R.string.gh_cleanup_title))
                        }
                        IconButton(onClick = viewModel::refresh) {
                            Icon(XIcons.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state.gate) {
                GitHubGate.Resolving -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                GitHubGate.NotGit -> EmptyState(
                    title = R.string.gh_empty_not_git_title, body = R.string.gh_empty_not_git_body,
                    action = R.string.gh_empty_not_git_action, onAction = onGitSetup,
                )
                GitHubGate.NoGitHubRemote -> EmptyState(
                    title = R.string.gh_empty_no_remote_title, body = R.string.gh_empty_no_remote_body,
                    action = R.string.gh_empty_no_remote_action, onAction = onOpenRemotes,
                )
                GitHubGate.NoToken -> EmptyState(
                    title = R.string.gh_empty_no_token_title, body = R.string.gh_empty_no_token_body,
                    action = R.string.gh_empty_no_token_action, onAction = onOpenGitHubSettings,
                )
                GitHubGate.Ready -> {
                    ReadyContent(state, toolsState, viewModel, onOpenRunLog, onOpenAllRuns, onOpenGitHubSettings)
                    GitHubToolsHost(toolsState, viewModel.tools, state.workflows, state.repo?.defaultBranch)
                }
            }
        }
    }
}

internal fun handleEvent(
    event: GhEvent,
    context: android.content.Context,
    snackbar: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val message = when (event) {
        is GhEvent.Snack -> event.text.resolve(context)
        is GhEvent.Copy -> {
            if (ClipboardHelper.copy(context, event.label, event.text)) event.confirmation.resolve(context)
            else context.getString(R.string.gh_copy_failed)
        }
    }
    scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(message)
    }
}

@Composable
private fun EmptyState(title: Int, body: Int, action: Int, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAction) { Text(stringResource(action)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyContent(
    state: GitHubManagerState,
    toolsState: GitHubToolsState,
    vm: GitHubManagerViewModel,
    onOpenRunLog: (Long) -> Unit,
    onOpenAllRuns: () -> Unit,
    onOpenGitHubSettings: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<GhRun?>(null) }
    var pendingCancel by remember { mutableStateOf<GhRun?>(null) }
    var pendingArtifactDelete by remember { mutableStateOf<GhArtifact?>(null) }
    val tools = vm.tools
    val onRunShortcut = {
        val wf = tools.effectiveWorkflow(state.workflows, toolsState.run.selectedWorkflowId)
        val ref = toolsState.run.selectedBranch ?: state.repo?.defaultBranch
        if (wf != null && ref != null) tools.onRunPressed(wf, ref)
    }
    val visible = state.runs.take(RECENT_COUNT)

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.banner?.let { b -> item(key = "banner") { BannerCard(b, onOpenGitHubSettings) } }
            item(key = "header") { RepoHeaderCard(state, vm.projectPath) }
            item(key = "run_workflow") {
                RunWorkflowCard(toolsState.run, state.workflows, state.repo?.defaultBranch, tools)
            }
            state.live?.let { live ->
                item(key = "building") {
                    Box(Modifier.animateItem()) {
                        BuildingCard(live, onOpen = { onOpenRunLog(live.run.id) }, onCancel = { pendingCancel = live.run })
                    }
                }
            }
            // While a build runs the card stays hidden (no stale APK) until this run's ARM64 is uploaded, then it appears at once.
            val liveRunId = state.live?.run?.id
            val apkOfLiveRun = liveRunId != null && (toolsState.apk as? ApkCardState.Found)?.candidate?.artifact?.runId == liveRunId
            if ((liveRunId == null || apkOfLiveRun) && !state.settling && apkCardVisible(toolsState)) {
                item(key = "apk") {
                    Box(Modifier.animateItem()) { ApkCard(toolsState, tools, onRunShortcut, vm.projectPath, state.repo?.owner) }
                }
            }
            item(key = "runs_header") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.gh_runs_title),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = ghPalette().onCard,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenAllRuns, colors = ButtonDefaults.textButtonColors(contentColor = ghPalette().accent)) {
                        Text(stringResource(R.string.gh_view_all))
                        Icon(XIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
                when {
                    state.runsLoading -> item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
                    state.runsError != null && state.runs.isEmpty() -> item(key = "error") {
                        ErrorBlock(state.runsError.resolve(LocalContext.current), vm::retryRuns)
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
                        items(visible, key = { it.id }) { run ->
                            RunRow(
                                run = run,
                                workflowName = state.workflowName(run),
                                busy = run.id in state.busyRunIds,
                                percent = state.live?.takeIf { it.run.id == run.id && it.progress.hasSteps }?.progress?.percent,
                                onOpen = { onOpenRunLog(run.id) },
                                onCopyError = { vm.copyError(run) },
                                onRerun = { failedOnly -> vm.rerun(run, failedOnly) },
                                onCancel = { pendingCancel = run },
                                onDelete = { pendingDelete = run },
                            )
                        }
                    }
                }
            artifactsSection(toolsState, tools) { pendingArtifactDelete = it }
        }
    }

    pendingArtifactDelete?.let { art ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.gh_artifact_delete_title),
            body = stringResource(R.string.gh_artifact_delete_body, art.name),
            confirm = stringResource(R.string.action_delete),
            onDismiss = { pendingArtifactDelete = null },
            onConfirm = { pendingArtifactDelete = null; tools.deleteArtifact(art) },
        )
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

@Composable
private fun ConfirmDeleteDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun BannerCard(banner: GhBanner, onReconnect: () -> Unit) {
    val text = when (banner) {
        GhBanner.TokenInvalid -> stringResource(R.string.gh_banner_token_invalid)
        GhBanner.Offline -> stringResource(R.string.gh_banner_offline)
        is GhBanner.RateLimited -> banner.resetEpochSec?.let {
            stringResource(R.string.gh_banner_rate_limited_until, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it * 1000)))
        } ?: stringResource(R.string.gh_banner_rate_limited)
        is GhBanner.MissingPermission -> stringResource(R.string.gh_banner_permission)
    }
    val canReconnect = banner is GhBanner.TokenInvalid || banner is GhBanner.MissingPermission
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (banner is GhBanner.MissingPermission && banner.message.isNotBlank()) {
                Text(banner.message, style = MaterialTheme.typography.bodySmall)
            }
            if (canReconnect) {
                TextButton(onClick = onReconnect) { Text(stringResource(R.string.gh_reconnect)) }
            }
        }
    }
}

@Composable
private fun RepoHeaderCard(state: GitHubManagerState, projectPath: String) {
    val p = ghPalette()
    GhCard(p.card, p.cardBorder) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProjectIcon(projectPath, size = 48.dp, corner = 14.dp, ownerLogin = state.repo?.owner)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.repo?.name.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    color = p.onCard, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(state.repo?.fullName, state.repo?.defaultBranch).joinToString(" \u2022 "),
                    style = MaterialTheme.typography.bodySmall,
                    color = p.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            state.rateLimit?.takeIf { it.isLow() }?.let {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                    Text(
                        stringResource(R.string.gh_rate_low, it.remaining),
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val p = ghPalette()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.onCard, modifier = Modifier.weight(1f))
        Icon(
            if (expanded) XIcons.KeyboardArrowUp else XIcons.KeyboardArrowDown,
            contentDescription = stringResource(if (expanded) R.string.gh_collapse else R.string.gh_expand),
            tint = p.onCard,
        )
    }
}

@Composable
internal fun RunFilters(state: GitHubManagerState, vm: GitHubManagerViewModel) {
    val p = ghPalette()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RunStatusFilter.entries.forEach { f ->
            val (label, dot) = when (f) {
                RunStatusFilter.All -> R.string.gh_filter_all to p.accent
                RunStatusFilter.Success -> R.string.gh_filter_success to p.success
                RunStatusFilter.Running -> R.string.gh_filter_running to p.accent
                RunStatusFilter.Failed -> R.string.gh_filter_failed to p.danger
                RunStatusFilter.Cancelled -> R.string.gh_filter_cancelled to p.onCardMuted
            }
            RunFilterChip(
                label = stringResource(label),
                dot = dot,
                selected = state.filters.status == f,
                onClick = { vm.setStatusFilter(f) },
            )
        }
        var menu by remember { mutableStateOf(false) }
        val current = state.workflows.firstOrNull { it.id == state.filters.workflowId }
        Box {
            RunFilterChip(
                label = current?.name ?: stringResource(R.string.gh_filter_workflow),
                dot = null,
                selected = current != null,
                onClick = { menu = true },
                trailing = XIcons.KeyboardArrowDown,
                maxLabelWidth = IntrinsicWidthCap,
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gh_filter_all_workflows)) },
                    onClick = { menu = false; vm.setWorkflowFilter(null) },
                )
                state.workflows.forEach { w ->
                    DropdownMenuItem(text = { Text(w.name) }, onClick = { menu = false; vm.setWorkflowFilter(w.id) })
                }
            }
        }
    }
}

/** Pill filter: optional coloured status dot + label; selected = accent tint fill and border, else dark fill and dim border. */
@Composable
private fun RunFilterChip(
    label: String,
    dot: Color?,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: androidx.compose.ui.graphics.vector.ImageVector? = null,
    maxLabelWidth: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
) {
    val p = ghPalette()
    val shape = androidx.compose.foundation.shape.CircleShape
    Row(
        Modifier
            .height(38.dp)
            .clip(shape)
            .background(if (selected) p.accent.copy(alpha = 0.22f) else p.runCard)
            .border(1.dp, if (selected) p.accent.copy(alpha = 0.7f) else p.pickerBorder, shape)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dot != null) {
            Box(Modifier.size(8.dp).clip(shape).background(dot))
        }
        Text(
            label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            color = p.onCard,
            modifier = if (maxLabelWidth != androidx.compose.ui.unit.Dp.Unspecified) Modifier.widthIn(max = maxLabelWidth) else Modifier,
        )
        if (trailing != null) {
            Icon(trailing, contentDescription = null, tint = p.onCardMuted, modifier = Modifier.size(18.dp))
        }
    }
}

private val IntrinsicWidthCap = 140.dp
private const val RECENT_COUNT = 3

@Composable
internal fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.gh_retry)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RunRow(
    run: GhRun,
    workflowName: String,
    busy: Boolean,
    onOpen: () -> Unit,
    onCopyError: () -> Unit,
    onRerun: (failedOnly: Boolean) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    /** Progress of the run while it is building (shown instead of the chevron). */
    percent: Int? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val pal = ghPalette()
    val (icon, label, tint) = statusVisuals(run.display, pal)
    val statusText = stringResource(label)
    Box(Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = pal.runCard, contentColor = pal.onCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, pal.cardBorder),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        enabled = !busy,
                        onClick = onOpen,
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menu = true
                        },
                    )
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(run.display, icon, tint, pal, statusText)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "#${run.runNumber} $workflowName", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = pal.onCard, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (run.commitMessage.isNotBlank()) {
                        Text(
                            run.commitMessage, style = MaterialTheme.typography.bodyMedium,
                            color = pal.onCard.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(XIcons.Branch, contentDescription = null, tint = pal.onCardMuted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            runSubtitle(run), style = MaterialTheme.typography.bodySmall,
                            color = pal.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                when {
                    busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    percent != null -> Text(
                        stringResource(R.string.gh_percent, percent),
                        style = MaterialTheme.typography.labelLarge, color = pal.onCardMuted,
                    )
                    else -> Icon(XIcons.ChevronRight, contentDescription = null, tint = pal.onCardMuted, modifier = Modifier.size(22.dp))
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.gh_action_open_log)) },
                leadingIcon = { Icon(XIcons.Article, contentDescription = null) },
                onClick = { menu = false; onOpen() },
            )
            if (run.isFailed) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gh_action_copy_error)) },
                    leadingIcon = { Icon(XIcons.ContentCopy, contentDescription = null) },
                    onClick = { menu = false; onCopyError() },
                )
            }
            if (run.canRerun) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (run.isFailed) R.string.gh_action_rerun_failed else R.string.gh_action_rerun)) },
                    leadingIcon = { Icon(XIcons.Refresh, contentDescription = null) },
                    onClick = { menu = false; onRerun(run.isFailed) },
                )
            }
            if (run.canCancel) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gh_action_cancel_run)) },
                    leadingIcon = { Icon(XIcons.CancelCircle, contentDescription = null) },
                    onClick = { menu = false; onCancel() },
                )
            }
            if (run.canDelete) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(XIcons.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun statusVisuals(s: RunDisplayStatus, pal: GhPalette): Triple<ImageVector, Int, Color> = when (s) {
    RunDisplayStatus.Success -> Triple(XIcons.Check, R.string.gh_status_success, pal.success)
    RunDisplayStatus.Failed -> Triple(XIcons.Close, R.string.gh_status_failed, pal.danger)
    RunDisplayStatus.Running -> Triple(XIcons.Sync, R.string.gh_status_running, pal.accent)
    RunDisplayStatus.Queued -> Triple(XIcons.Schedule, R.string.gh_status_queued, pal.onCardMuted)
    RunDisplayStatus.Cancelled -> Triple(XIcons.CancelCircle, R.string.gh_status_cancelled, pal.onCardMuted)
    RunDisplayStatus.Skipped -> Triple(XIcons.RemoveCircle, R.string.gh_status_skipped, pal.onCardMuted)
}

/** Solid round badge for finished runs (check / cross), a spinner while running, a plain glyph otherwise. */
@Composable
private fun StatusBadge(status: RunDisplayStatus, icon: ImageVector, tint: Color, pal: GhPalette, description: String) {
    val a11y = Modifier.semantics { contentDescription = description }
    when (status) {
        RunDisplayStatus.Success, RunDisplayStatus.Failed -> Box(
            Modifier.size(36.dp).clip(CircleShape).background(tint).then(a11y),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, contentDescription = null, modifier = Modifier.size(22.dp),
                tint = if (status == RunDisplayStatus.Success) pal.onSuccess else pal.onDanger,
            )
        }
        RunDisplayStatus.Running -> Box(Modifier.size(36.dp).then(a11y), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(30.dp), color = tint, trackColor = tint.copy(alpha = 0.2f), strokeWidth = 3.dp)
        }
        else -> Box(Modifier.size(36.dp).then(a11y), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(30.dp))
        }
    }
}

private fun runSubtitle(run: GhRun): String {
    val parts = mutableListOf(run.branch)
    (run.createdAt ?: run.startedAt)?.let {
        parts += DateUtils.getRelativeTimeSpanString(it.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    run.durationMs?.let { parts += formatDuration(it) }
    return parts.filter { it.isNotBlank() }.joinToString(" • ")
}

internal fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
