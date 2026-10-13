package com.invictus.kodex.github.ui

import com.invictus.kodex.ai.AiGate
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.github.api.GhJob
import com.invictus.kodex.github.api.GhRun
import com.invictus.kodex.github.api.RunDisplayStatus
import com.invictus.kodex.ui.icons.XIcons

/**
 * Run details: commit on top, status line, live progress while the run builds, then the jobs with their
 * steps (tap a finished job to read its log) and a Log tab with the error excerpt / full log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunLogScreen(
    projectPath: String,
    runId: Long,
    onBack: () -> Unit,
    viewModel: RunLogViewModel = viewModel(
        key = "gh_run_log_${projectPath}_$runId",
        factory = RunLogViewModel.factory(projectPath, runId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val p = ghPalette()
    val run = state.run

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> handleEvent(event, context, snackbar, scope) }
    }

    Scaffold(
        modifier = Modifier.background(p.screen),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    titleContentColor = p.onCard,
                    navigationIconContentColor = p.onCard,
                    actionIconContentColor = p.onCard,
                ),
                title = {
                    Text(
                        if (run != null) stringResource(R.string.gh_run_header_title, run.runNumber) else stringResource(R.string.gh_log_title),
                        maxLines = 1, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (run?.isActive == true) {
                        TextButton(onClick = viewModel::cancelRun, colors = ButtonDefaults.textButtonColors(contentColor = p.danger)) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    } else {
                        IconButton(onClick = viewModel::copyError, enabled = !state.loading && state.error == null) {
                            Icon(XIcons.ContentCopy, contentDescription = stringResource(R.string.gh_copy_error))
                        }
                        IconButton(onClick = viewModel::copyFull, enabled = !state.loading && state.hasLogFile) {
                            Icon(XIcons.FileCopy, contentDescription = stringResource(R.string.gh_copy_full_log))
                        }
                        // M20: explain the visible log with AI
                        AiGate?.let { g ->
                            val explainText = state.excerptLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
                                ?: state.fullLines?.takeIf { it.isNotEmpty() }?.joinToString("\n")
                            if (explainText != null && !state.loading) {
                                g.ExplainErrorAi(
                                    errorText = explainText,
                                    projectPath = projectPath,
                                    app = LocalContext.current.applicationContext as android.app.Application,
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (run != null) RunHeader(run, state.workflowName, p)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                RunTab.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = state.tab == tab,
                        onClick = { viewModel.setTab(tab) },
                        shape = SegmentedButtonDefaults.itemShape(index, RunTab.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = p.accent.copy(alpha = 0.2f),
                            activeContentColor = p.onCard,
                            activeBorderColor = p.accent.copy(alpha = 0.6f),
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = p.onCardMuted,
                            inactiveBorderColor = p.pickerBorder,
                        ),
                    ) { Text(stringResource(if (tab == RunTab.Steps) R.string.gh_tab_steps else R.string.gh_tab_log)) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.error != null -> Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(state.error!!.resolve(context), style = MaterialTheme.typography.bodyMedium, color = p.onCard)
                        TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.gh_retry)) }
                        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                    }
                    state.tab == RunTab.Steps -> StepsTab(state, p) { job ->
                        viewModel.selectJob(job.id)
                        viewModel.setTab(RunTab.Log)
                    }
                    else -> LogTab(state, viewModel, p)
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
            }
        }
    }
}

/** Commit message first (it is what people recognise a run by), then status, workflow and where / when. */
@Composable
private fun RunHeader(run: GhRun, workflowName: String, p: GhPalette) {
    val tint = statusTintOf(run.display, p)
    GhCard(p.card, p.cardBorder, Modifier.padding(horizontal = 14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                run.commitMessage.ifBlank { run.name },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = p.onCard, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(CircleShape).background(tint.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(statusLabel(run.display)), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tint)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    listOf("#${run.runNumber}", workflowName).filter { it.isNotBlank() }.joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium, color = p.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(XIcons.Branch, contentDescription = null, tint = p.onCardMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    runMeta(run), style = MaterialTheme.typography.bodySmall,
                    color = p.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun runMeta(run: GhRun): String {
    val parts = mutableListOf(run.branch, run.shortSha, run.event.replace('_', ' '))
    (run.startedAt ?: run.createdAt)?.let {
        parts += DateUtils.getRelativeTimeSpanString(it.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    run.durationMs?.let { parts += formatDuration(it) }
    return parts.filter { it.isNotBlank() }.joinToString(" \u2022 ")
}

@Composable
private fun StepsTab(state: RunLogState, p: GhPalette, onOpenJobLog: (GhJob) -> Unit) {
    val run = state.run
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (run != null && run.isActive) {
            item(key = "progress") {
                GhCard(p.card, p.cardBorder) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = p.accent, trackColor = p.accent.copy(alpha = 0.2f), strokeWidth = 2.5.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                stringResource(if (run.display == RunDisplayStatus.Queued) R.string.gh_build_queued else R.string.gh_build_building),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = p.onCard,
                            )
                        }
                        ProgressBlock(state.progress, p)
                    }
                }
            }
        }
        if (state.jobs.isEmpty() && !state.loading) {
            item(key = "none") {
                Text(stringResource(R.string.gh_steps_none), style = MaterialTheme.typography.bodyMedium, color = p.onCardMuted)
            }
        }
        items(state.jobs, key = { it.id }) { job ->
            JobCard(job, p, selected = job.id == state.selectedJobId, onClick = if (job.status == "completed") ({ onOpenJobLog(job) }) else null)
        }
    }
}

@Composable
private fun JobCard(job: GhJob, p: GhPalette, selected: Boolean, onClick: (() -> Unit)?) {
    val steps = job.steps.filterNot { isHousekeepingStep(it) }
    val jobState = jobState(job)
    // Running and failed jobs show their steps; finished and queued ones fold away until the user opens them.
    var userExpanded by remember(job.id) { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: (jobState == StepState.Current || jobState == StepState.Failed)
    GhCard(
        p.card,
        if (selected) p.accent.copy(alpha = 0.5f) else p.cardBorder,
        onClick = onClick,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepStateIcon(jobState, p, size = 22.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        job.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        color = p.onCard, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (!expanded && jobState == StepState.Current) {
                        steps.firstOrNull { stepState(it.status, it.conclusion) == StepState.Current }?.let {
                            Text(
                                prettyStepName(it.name), style = MaterialTheme.typography.labelSmall,
                                color = p.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                job.durationMs?.let { Text(formatDuration(it), style = MaterialTheme.typography.labelMedium, color = p.onCardMuted) }
                if (steps.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (expanded) XIcons.KeyboardArrowUp else XIcons.KeyboardArrowDown,
                        contentDescription = null,
                        tint = p.onCardMuted,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable { userExpanded = !expanded }
                            .padding(2.dp),
                    )
                }
                if (onClick != null) {
                    Icon(XIcons.ChevronRight, contentDescription = null, tint = p.onCardMuted, modifier = Modifier.size(20.dp))
                }
            }
            if (expanded && steps.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                steps.forEach { step ->
                    val st = stepState(step.status, step.conclusion)
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        StepStateIcon(st, p, size = 16.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            prettyStepName(step.name), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (st == StepState.Current) FontWeight.Bold else FontWeight.Normal,
                            color = when (st) {
                                StepState.Failed -> p.danger
                                StepState.Pending, StepState.Skipped -> p.onCardMuted
                                else -> p.onCard
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        step.durationMs?.let { Text(formatDuration(it), style = MaterialTheme.typography.labelSmall, color = p.onCardMuted) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogTab(state: RunLogState, vm: RunLogViewModel, p: GhPalette) {
    val running = state.run?.isActive == true
    Column(Modifier.fillMaxSize()) {
        if (running) {
            Text(
                stringResource(R.string.gh_log_after_finish),
                style = MaterialTheme.typography.bodyMedium, color = p.onCardMuted,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )
            return@Column
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            LogMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.mode == mode,
                    onClick = { vm.setMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, LogMode.entries.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = p.accent.copy(alpha = 0.2f),
                        activeContentColor = p.onCard,
                        activeBorderColor = p.accent.copy(alpha = 0.6f),
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = p.onCardMuted,
                        inactiveBorderColor = p.pickerBorder,
                    ),
                ) { Text(stringResource(if (mode == LogMode.Error) R.string.gh_log_mode_error else R.string.gh_log_mode_full)) }
            }
        }
        if (state.jobs.size > 1) JobPicker(state, vm, p)
        Box(
            Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 12.dp)
                .clip(RoundedCornerShape(16.dp)).background(p.runCard),
        ) {
            when {
                state.loading -> Unit
                state.mode == LogMode.Error -> LogLines(state.excerptLines, note = null)
                state.fullLoading || state.fullLines == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = p.accent)
                else -> LogLines(
                    state.fullLines!!,
                    note = if (state.fullCutAtStart) stringResource(R.string.gh_log_showing_tail) else null,
                )
            }
        }
    }
}

@Composable
private fun JobPicker(state: RunLogState, vm: RunLogViewModel, p: GhPalette) {
    var open by remember { mutableStateOf(false) }
    val current = state.jobs.firstOrNull { it.id == state.selectedJobId }
    Box(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        OutlinedButton(
            onClick = { open = true },
            shape = CircleShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = p.onCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, p.pickerBorder),
        ) {
            Text(current?.name ?: stringResource(R.string.gh_log_pick_job), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(4.dp))
            Icon(XIcons.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.jobs.filter { it.status == "completed" }.forEach { job ->
                DropdownMenuItem(text = { Text(job.name) }, onClick = { open = false; vm.selectJob(job.id) })
            }
        }
    }
}

/** Monospace virtualised lines; one shared horizontal scroll so the whole block pans together. */
@Composable
private fun LogLines(lines: List<String>, note: String?) {
    val p = ghPalette()
    val hScroll = rememberScrollState()
    val style = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, color = p.onCard,
    )
    Column(Modifier.fillMaxSize()) {
        note?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = p.onCardMuted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            LazyColumn(Modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 8.dp)) {
                itemsIndexed(lines) { _, line ->
                    Text(line, style = style, softWrap = false)
                }
            }
        }
    }
}

private fun statusTintOf(s: RunDisplayStatus, p: GhPalette): Color = when (s) {
    RunDisplayStatus.Success -> p.success
    RunDisplayStatus.Failed -> p.danger
    RunDisplayStatus.Running -> p.accent
    RunDisplayStatus.Queued, RunDisplayStatus.Cancelled, RunDisplayStatus.Skipped -> p.onCardMuted
}

private fun statusLabel(s: RunDisplayStatus): Int = when (s) {
    RunDisplayStatus.Success -> R.string.gh_status_success
    RunDisplayStatus.Failed -> R.string.gh_status_failed
    RunDisplayStatus.Running -> R.string.gh_status_running
    RunDisplayStatus.Queued -> R.string.gh_status_queued
    RunDisplayStatus.Cancelled -> R.string.gh_status_cancelled
    RunDisplayStatus.Skipped -> R.string.gh_status_skipped
}
