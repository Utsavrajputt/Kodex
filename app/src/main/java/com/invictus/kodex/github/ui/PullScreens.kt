package com.invictus.kodex.github.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.ai.AiGate
import com.invictus.kodex.feature.git.BranchPickerDialog
import com.invictus.kodex.feature.git.BranchPickerPhase
import com.invictus.kodex.ai.ui.chat.MarkdownContent
import com.invictus.kodex.github.api.GhPull
import com.invictus.kodex.feature.git.GitCommitDetailScreen
import com.invictus.kodex.github.api.GhPullCommit
import com.invictus.kodex.github.api.GhPullFile
import com.invictus.kodex.github.api.PullDisplay
import com.invictus.kodex.github.api.PullFilter
import com.invictus.kodex.github.api.PullMergeMethod
import com.invictus.kodex.ui.icons.XIcons
import java.time.Instant

private val MergedPurple = Color(0xFF8957E5)

private class PullVisual(val icon: ImageVector, val label: Int, val color: Color)

@Composable
private fun pullVisual(d: PullDisplay, p: GhPalette): PullVisual = when (d) {
    PullDisplay.Open -> PullVisual(XIcons.PullRequest, R.string.pr_status_open, p.success)
    PullDisplay.Draft -> PullVisual(XIcons.PullRequest, R.string.pr_status_draft, p.onCardMuted)
    PullDisplay.Merged -> PullVisual(XIcons.MergeDown, R.string.pr_status_merged, MergedPurple)
    PullDisplay.Closed -> PullVisual(XIcons.PullRequest, R.string.pr_status_closed, p.danger)
}

private fun agoText(i: Instant?, nowMs: Long): String? = i?.let {
    val t = it.toEpochMilli()
    DateUtils.getRelativeTimeSpanString(t, maxOf(nowMs, t), DateUtils.MINUTE_IN_MILLIS).toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullTopBar(title: String, onBack: () -> Unit, p: GhPalette, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            titleContentColor = p.onCard,
            navigationIconContentColor = p.onCard,
            actionIconContentColor = p.onCard,
        ),
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
        },
        actions = { actions() },
    )
}

@Composable
private fun StatusPill(v: PullVisual) {
    Row(
        Modifier.clip(CircleShape).background(v.color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(v.icon, contentDescription = null, tint = v.color, modifier = Modifier.size(14.dp))
        Text(stringResource(v.label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = v.color)
    }
}

@Composable
private fun ErrorBlock(text: String, onRetry: () -> Unit, p: GhPalette) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = p.onCard)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.gh_retry)) }
    }
}

// =================================================================================================
// List
// =================================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRequestsScreen(
    projectPath: String,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onOpenPull: (Int) -> Unit,
    vm: PullListViewModel = viewModel(
        key = "gh_pulls_$projectPath",
        factory = PullListViewModel.factory(projectPath),
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val p = ghPalette()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val now = rememberNowMs()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    LaunchedEffect(vm) { vm.events.collect { handleEvent(it, context, snackbar, scope) } }

    Scaffold(
        modifier = Modifier.background(p.screen),
        containerColor = Color.Transparent,
        topBar = {
            PullTopBar(stringResource(R.string.pr_title), onBack, p) {
                IconButton(onClick = vm::refresh) { Icon(XIcons.Refresh, contentDescription = stringResource(R.string.action_refresh)) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "create") { CreatePullCard(onCreate, p) }
                item(key = "filters") { PullFilters(state.filter, vm::setFilter, p) }
                when {
                    state.loading -> item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp), color = p.accent) }
                    state.error != null && state.items.isEmpty() -> item(key = "error") {
                        ErrorBlock(state.error?.resolve(context).orEmpty(), vm::retry, p)
                    }
                    state.items.isEmpty() -> item(key = "empty") {
                        Text(
                            stringResource(R.string.pr_empty),
                            style = MaterialTheme.typography.bodyMedium, color = p.onCardMuted,
                            modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth(),
                        )
                    }
                    else -> {
                        items(state.items, key = { it.number }) { pull -> PullRow(pull, now, p) { onOpenPull(pull.number) } }
                        if (state.hasMore) item(key = "more-${state.items.size}") {
                            LaunchedEffect(Unit) { vm.loadMore() }
                            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = p.accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The first thing on the screen: start a new pull request. */
@Composable
private fun CreatePullCard(onClick: () -> Unit, p: GhPalette) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.accent.copy(alpha = 0.18f))
            .border(1.dp, p.accent.copy(alpha = 0.6f), shape)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) {
            Icon(XIcons.Add, contentDescription = null, tint = p.onInstall, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.pr_create), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.onCard)
            Text(stringResource(R.string.pr_create_hint), style = MaterialTheme.typography.bodySmall, color = p.onCardMuted)
        }
        Icon(XIcons.ChevronRight, contentDescription = null, tint = p.onCardMuted)
    }
}

@Composable
private fun PullFilters(selected: PullFilter, onSelect: (PullFilter) -> Unit, p: GhPalette) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PullFilter.entries.forEach { f ->
            val (label, dot) = when (f) {
                PullFilter.Open -> R.string.pr_filter_open to p.success
                PullFilter.Closed -> R.string.pr_filter_closed to p.danger
                PullFilter.Merged -> R.string.pr_filter_merged to MergedPurple
                PullFilter.All -> R.string.pr_filter_all to p.accent
                PullFilter.Draft -> R.string.pr_filter_draft to p.onCardMuted
                PullFilter.Mine -> R.string.pr_filter_mine to null
            }
            RunFilterChip(label = stringResource(label), dot = dot, selected = selected == f, onClick = { onSelect(f) })
        }
    }
}

@Composable
private fun PullRow(pull: GhPull, nowMs: Long, p: GhPalette, onClick: () -> Unit) {
    val v = pullVisual(pull.display, p)
    val shape = RoundedCornerShape(18.dp)
    val ago = agoText(pull.createdAt, nowMs)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.runCard)
            .border(1.dp, p.cardBorder, shape)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(v.icon, contentDescription = stringResource(v.label), tint = v.color, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                pull.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = p.onCard, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (ago != null) stringResource(R.string.pr_row_meta, pull.number, pull.author, ago)
                else stringResource(R.string.pr_row_meta_no_time, pull.number, pull.author),
                style = MaterialTheme.typography.bodySmall, color = p.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(XIcons.Branch, contentDescription = null, tint = p.onCardMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    stringResource(R.string.pr_branch_into, pull.headRef, pull.baseRef),
                    style = MaterialTheme.typography.bodySmall, color = p.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (pull.display == PullDisplay.Draft) {
                    Spacer(Modifier.width(8.dp))
                    StatusPill(v)
                }
            }
        }
        Icon(XIcons.ChevronRight, contentDescription = null, tint = p.onCardMuted)
    }
}

// =================================================================================================
// Detail
// =================================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullDetailScreen(
    projectPath: String,
    number: Int,
    onBack: () -> Unit,
    vm: PullDetailViewModel = viewModel(
        key = "gh_pull_${projectPath}_$number",
        factory = PullDetailViewModel.factory(projectPath, number),
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val p = ghPalette()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val now = rememberNowMs()
    val pull = state.pull

    LaunchedEffect(vm) { vm.events.collect { handleEvent(it, context, snackbar, scope) } }

    Scaffold(
        modifier = Modifier.background(p.screen),
        containerColor = Color.Transparent,
        topBar = {
            PullTopBar(stringResource(R.string.pr_header_title, number), onBack, p) {
                if (pull != null && pull.htmlUrl.isNotBlank()) {
                    IconButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pull.htmlUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Icon(XIcons.OpenInNew, contentDescription = stringResource(R.string.pr_open_on_github)) }
                }
                IconButton(onClick = vm::refresh) { Icon(XIcons.Refresh, contentDescription = stringResource(R.string.action_refresh)) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (pull != null) PullActionBar(pull, state.acting, vm, p) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
                state.error != null && pull == null -> ErrorBlock(state.error?.resolve(context).orEmpty(), vm::retry, p)
                pull != null -> {
                    if (state.acting) LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
                    PullHeader(pull, p)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        PullTab.entries.forEachIndexed { index, tab ->
                            SegmentedButton(
                                selected = state.tab == tab,
                                onClick = { vm.setTab(tab) },
                                shape = SegmentedButtonDefaults.itemShape(index, PullTab.entries.size),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = p.accent.copy(alpha = 0.2f),
                                    activeContentColor = p.onCard,
                                    activeBorderColor = p.accent.copy(alpha = 0.6f),
                                    inactiveContainerColor = Color.Transparent,
                                    inactiveContentColor = p.onCardMuted,
                                    inactiveBorderColor = p.pickerBorder,
                                ),
                            ) {
                                Text(
                                    stringResource(
                                        when (tab) {
                                            PullTab.Overview -> R.string.pr_tab_overview
                                            PullTab.Commits -> R.string.pr_tab_commits
                                            PullTab.Files -> R.string.pr_tab_files
                                        },
                                    ),
                                )
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (state.tab) {
                            PullTab.Overview -> OverviewTab(pull, now, p)
                            PullTab.Commits -> CommitsTab(state.commits, state.commitsLoading, state.commitsError?.resolve(context), vm::retryTab, vm::openCommit, now, p)
                            PullTab.Files -> FilesTab(state.files, state.filesLoading, state.filesError?.resolve(context), vm::retryTab, p)
                        }
                    }
                }
            }
        }
    }

    state.commitView?.let { cv ->
        androidx.activity.compose.BackHandler { if (cv.selected != null) vm.closeCommitFile() else vm.closeCommit() }
        Box(Modifier.fillMaxSize()) {
            GitCommitDetailScreen(
                commit = cv.summary,
                files = cv.files,
                filesLoading = cv.loading,
                selectedFile = cv.selected,
                fileDiff = cv.diff,
                diffLoading = cv.diffLoading,
                totalAdded = cv.added,
                totalRemoved = cv.removed,
                onOpenFile = vm::openCommitFile,
                onCloseFile = vm::closeCommitFile,
                onCancel = vm::closeCommit,
            )
        }
    }

    if (state.mergeDialog && pull != null) MergeDialog(state.mergeMethod, state.deleteBranch, pull.canDeleteBranch, vm)
    if (state.closeDialog) {
        AlertDialog(
            onDismissRequest = vm::dismissDialogs,
            title = { Text(stringResource(R.string.pr_close_title)) },
            text = { Text(stringResource(R.string.pr_close_body)) },
            confirmButton = {
                Button(
                    onClick = vm::confirmClose,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text(stringResource(R.string.pr_close)) }
            },
            dismissButton = { TextButton(onClick = vm::dismissDialogs) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PullHeader(pull: GhPull, p: GhPalette) {
    val v = pullVisual(pull.display, p)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(pull.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = p.onCard, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusPill(v)
            Text(
                stringResource(if (pull.merged) R.string.pr_by_author_merged else R.string.pr_by_author, pull.author),
                style = MaterialTheme.typography.bodyMedium, color = p.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(XIcons.Branch, contentDescription = null, tint = p.onCardMuted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                stringResource(R.string.pr_branch_into, pull.headRef, pull.baseRef),
                style = MaterialTheme.typography.bodySmall, color = p.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PullActionBar(pull: GhPull, acting: Boolean, vm: PullDetailViewModel, p: GhPalette) {
    val canMerge = pull.isOpen && !pull.draft && pull.mergeable != false
    when {
        pull.isOpen -> Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pull.draft) {
                Text(stringResource(R.string.pr_draft_hint), style = MaterialTheme.typography.bodySmall, color = p.onCardMuted)
            } else if (pull.mergeable == false) {
                Text(stringResource(R.string.pr_mergeable_conflict), style = MaterialTheme.typography.bodySmall, color = p.danger)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = vm::openCloseDialog, enabled = !acting,
                    border = BorderStroke(1.dp, p.danger.copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = p.danger),
                    modifier = Modifier.height(48.dp),
                ) { Text(stringResource(R.string.pr_close)) }
                Button(
                    onClick = vm::openMergeDialog, enabled = canMerge && !acting,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(XIcons.MergeDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pr_merge))
                }
            }
        }
        !pull.merged -> Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(14.dp)) {
            Button(onClick = vm::reopen, enabled = !acting, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.pr_reopen)) }
        }
        else -> Unit
    }
}

@Composable
private fun MergeDialog(method: PullMergeMethod, deleteBranch: Boolean, canDeleteBranch: Boolean, vm: PullDetailViewModel) {
    AlertDialog(
        onDismissRequest = vm::dismissDialogs,
        title = { Text(stringResource(R.string.pr_merge_title)) },
        text = {
            Column {
                PullMergeMethod.entries.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.setMethod(m) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = method == m, onClick = { vm.setMethod(m) })
                        Text(
                            stringResource(
                                when (m) {
                                    PullMergeMethod.Merge -> R.string.pr_method_merge
                                    PullMergeMethod.Squash -> R.string.pr_method_squash
                                    PullMergeMethod.Rebase -> R.string.pr_method_rebase
                                },
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (canDeleteBranch) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.setDeleteBranch(!deleteBranch) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = deleteBranch, onCheckedChange = { vm.setDeleteBranch(it) })
                        Text(stringResource(R.string.pr_delete_branch), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = vm::confirmMerge) { Text(stringResource(R.string.pr_merge)) } },
        dismissButton = { TextButton(onClick = vm::dismissDialogs) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun OverviewTab(pull: GhPull, nowMs: Long, p: GhPalette) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatChip(stringResource(R.string.pr_stat_commits, pull.commits), p.onCard, p)
            StatChip(stringResource(R.string.pr_stat_files, pull.changedFiles), p.onCard, p)
            StatChip("+${pull.additions}", p.success, p)
            StatChip("\u2212${pull.deletions}", p.danger, p)
        }
        if (pull.isOpen) {
            val (text, color) = when (pull.mergeable) {
                true -> R.string.pr_mergeable_ok to p.success
                false -> R.string.pr_mergeable_conflict to p.danger
                null -> R.string.pr_mergeable_checking to p.onCardMuted
            }
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Medium)
        } else {
            val time = agoText(pull.mergedAt ?: pull.updatedAt, nowMs)
            if (time != null) Text(time, style = MaterialTheme.typography.bodyMedium, color = p.onCardMuted)
        }
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(p.runCard).border(1.dp, p.cardBorder, shape).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.pr_description), style = MaterialTheme.typography.labelLarge, color = p.onCardMuted)
            if (pull.body.isBlank()) {
                Text(stringResource(R.string.pr_no_description), style = MaterialTheme.typography.bodyMedium, color = p.onCardMuted)
            } else {
                MarkdownContent(pull.body)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StatChip(text: String, color: Color, p: GhPalette) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color,
        modifier = Modifier.clip(CircleShape).background(p.runCard).border(1.dp, p.pickerBorder, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun CommitsTab(commits: List<GhPullCommit>?, loading: Boolean, error: String?, onRetry: () -> Unit, onOpen: (GhPullCommit) -> Unit, nowMs: Long, p: GhPalette) {
    when {
        error != null && commits == null -> ErrorBlock(error, onRetry, p)
        commits == null || loading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
        commits.isEmpty() -> Text(stringResource(R.string.pr_no_commits), color = p.onCardMuted, modifier = Modifier.padding(24.dp))
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(commits, key = { it.sha }) { c ->
                val shape = RoundedCornerShape(14.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(p.runCard).border(1.dp, p.cardBorder, shape).clickable { onOpen(c) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(XIcons.Commit, contentDescription = null, tint = p.accent, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(c.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = p.onCard, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(c.shortSha, c.author.ifBlank { null }, agoText(c.date, nowMs)).joinToString(" \u2022 ")
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = p.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesTab(files: List<GhPullFile>?, loading: Boolean, error: String?, onRetry: () -> Unit, p: GhPalette) {
    when {
        error != null && files == null -> ErrorBlock(error, onRetry, p)
        files == null || loading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
        files.isEmpty() -> Text(stringResource(R.string.pr_no_files), color = p.onCardMuted, modifier = Modifier.padding(24.dp))
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files, key = { it.filename }) { f -> FileCard(f, p) }
        }
    }
}

@Composable
private fun FileCard(f: GhPullFile, p: GhPalette) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val statusColor = when (f.status) {
        "added" -> p.success
        "removed" -> p.danger
        "renamed" -> MergedPurple
        else -> p.accent
    }
    Column(Modifier.fillMaxWidth().clip(shape).background(p.runCard).border(1.dp, p.cardBorder, shape)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor))
            Column(Modifier.weight(1f)) {
                Text(f.filename, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = p.onCard, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(
                        when (f.status) {
                            "added" -> R.string.pr_file_added
                            "removed" -> R.string.pr_file_removed
                            "renamed" -> R.string.pr_file_renamed
                            else -> R.string.pr_file_modified
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall, color = statusColor,
                )
            }
            Text("+${f.additions}", style = MaterialTheme.typography.labelLarge, color = p.success, fontWeight = FontWeight.SemiBold)
            Text("\u2212${f.deletions}", style = MaterialTheme.typography.labelLarge, color = p.danger, fontWeight = FontWeight.SemiBold)
        }
        if (expanded) {
            val patch = f.patch
            if (patch == null) {
                Text(stringResource(R.string.pr_diff_unavailable), style = MaterialTheme.typography.bodySmall, color = p.onCardMuted, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
            } else {
                PatchView(patch, p)
            }
        }
    }
}

private const val MAX_PATCH_LINES = 600

@Composable
private fun PatchView(patch: String, p: GhPalette) {
    val addBg = p.success.copy(alpha = 0.18f)
    val delBg = p.danger.copy(alpha = 0.18f)
    val text: AnnotatedString = remember(patch, addBg, delBg, p.accent) {
        val lines = patch.lines()
        buildAnnotatedString {
            lines.take(MAX_PATCH_LINES).forEachIndexed { i, line ->
                val style = when {
                    line.startsWith("@@") -> SpanStyle(color = p.accent)
                    line.startsWith("+") -> SpanStyle(background = addBg)
                    line.startsWith("-") -> SpanStyle(background = delBg)
                    else -> SpanStyle()
                }
                withStyle(style) { append(line.ifEmpty { " " }) }
                if (i < lines.size - 1 && i < MAX_PATCH_LINES - 1) append('\n')
            }
        }
    }
    val truncated = remember(patch) { patch.lineSequence().drop(MAX_PATCH_LINES).any() }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
            color = p.onCard,
            softWrap = false,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        )
        if (truncated) {
            Text(stringResource(R.string.pr_diff_truncated), style = MaterialTheme.typography.bodySmall, color = p.onCardMuted, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp))
        }
    }
}

// =================================================================================================
// Create
// =================================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePullRequestScreen(
    projectPath: String,
    onBack: () -> Unit,
    onCreated: (Int) -> Unit,
    vm: CreatePullViewModel = viewModel(
        key = "gh_pull_create_$projectPath",
        factory = CreatePullViewModel.factory(projectPath),
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val p = ghPalette()
    val context = LocalContext.current

    LaunchedEffect(vm) { vm.created.collect { onCreated(it) } }
    LaunchedEffect(vm) {
        vm.createdUpstream.collect { url ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            onBack()
        }
    }

    Scaffold(
        modifier = Modifier.background(p.screen),
        containerColor = Color.Transparent,
        topBar = { PullTopBar(stringResource(R.string.pr_create_screen_title), onBack, p) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = p.accent)
                state.error != null -> ErrorBlock(state.error?.resolve(context).orEmpty(), vm::retry, p)
                else -> CreateForm(state, vm, p)
            }
        }
    }

    state.picker?.let { target ->
        val items = when (target) {
            BranchPickerTarget.Repo -> listOfNotNull(state.repoName, state.upstreamName)
            BranchPickerTarget.Base -> state.baseBranches
            BranchPickerTarget.Head -> state.branches
        }
        BranchPickerDialog(
            title = stringResource(
                when (target) {
                    BranchPickerTarget.Repo -> R.string.pr_base_repo
                    BranchPickerTarget.Base -> R.string.pr_base_branch
                    BranchPickerTarget.Head -> R.string.pr_head_branch
                },
            ),
            description = null,
            phase = if (items.isEmpty()) BranchPickerPhase.EMPTY else BranchPickerPhase.LIST,
            branches = items,
            busyText = "",
            emptyText = stringResource(R.string.pr_no_branches),
            dismissible = true,
            selected = when (target) {
                BranchPickerTarget.Repo -> state.baseRepoName
                BranchPickerTarget.Base -> state.base
                BranchPickerTarget.Head -> state.head
            },
            onPick = vm::pickBranch,
            onDismiss = vm::dismissPicker,
        )
    }
}

@Composable
private fun CreateForm(state: CreatePullState, vm: CreatePullViewModel, p: GhPalette) {
    val context = LocalContext.current
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = p.accent, unfocusedBorderColor = p.pickerBorder,
        focusedTextColor = p.onCard, unfocusedTextColor = p.onCard,
        focusedLabelColor = p.accent, unfocusedLabelColor = p.onCardMuted, cursorColor = p.accent,
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(state.repoName, style = MaterialTheme.typography.bodyMedium, color = p.onCardMuted)
        if (state.upstreamName != null) {
            BranchField(stringResource(R.string.pr_base_repo), state.baseRepoName, p) { vm.openPicker(BranchPickerTarget.Repo) }
        }
        BranchField(stringResource(R.string.pr_base_branch), state.base, p) { vm.openPicker(BranchPickerTarget.Base) }
        BranchField(stringResource(R.string.pr_head_branch), state.head.ifEmpty { stringResource(R.string.pr_pick_head) }, p) { vm.openPicker(BranchPickerTarget.Head) }
        if (state.head.isNotEmpty() && state.base.isNotEmpty()) {
            Text(stringResource(R.string.pr_merge_summary, state.head, if (state.useUpstream) "${state.baseRepoName}:${state.base}" else state.base), style = MaterialTheme.typography.bodySmall, color = p.onCardMuted)
        }
        OutlinedTextField(
            value = state.title, onValueChange = vm::setTitle,
            label = { Text(stringResource(R.string.pr_field_title)) },
            singleLine = true,
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.autoFilling) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = p.accent)
                    AiGate?.let { g ->
                        g.PullRequestAi(
                            canGenerate = state.head.isNotEmpty() && !state.creating,
                            loadChanges = vm::aiChanges,
                            onFill = vm::applyAiText,
                            app = context.applicationContext as android.app.Application,
                        )
                    }
                }
            },
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.body, onValueChange = vm::setBody,
            label = { Text(stringResource(R.string.pr_field_body)) },
            minLines = 5, maxLines = 12,
            keyboardOptions = KeyboardOptions.Default,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().clickable { vm.setDraft(!state.draft) }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pr_draft_toggle), style = MaterialTheme.typography.bodyLarge, color = p.onCard)
                Text(stringResource(R.string.pr_draft_toggle_hint), style = MaterialTheme.typography.bodySmall, color = p.onCardMuted)
            }
            Switch(checked = state.draft, onCheckedChange = vm::setDraft)
        }
        state.formError?.let { Text(it.resolve(context), style = MaterialTheme.typography.bodyMedium, color = p.danger) }
        Button(
            onClick = vm::create, enabled = !state.creating,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (state.creating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text(stringResource(R.string.pr_create), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BranchField(label: String, value: String, p: GhPalette, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(p.runCard).border(1.dp, p.pickerBorder, shape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(XIcons.Branch, contentDescription = null, tint = p.accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = p.onCardMuted)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = p.onCard, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(XIcons.KeyboardArrowDown, contentDescription = null, tint = p.onCardMuted)
    }
}
