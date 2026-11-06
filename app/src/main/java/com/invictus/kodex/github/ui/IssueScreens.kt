package com.invictus.kodex.github.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.ai.AiGate
import com.invictus.kodex.ai.AiSummaryKind
import com.invictus.kodex.ai.ui.chat.MarkdownContent
import com.invictus.kodex.github.api.GhIssue
import com.invictus.kodex.github.api.GhIssueComment
import com.invictus.kodex.github.api.GhLabel
import com.invictus.kodex.github.api.IssueFilter
import com.invictus.kodex.ui.icons.XIcons

/** Markdown body drawn in the GitHub screens' own text colour (the plain theme colour is unreadable on their dark cards). */
@Composable
internal fun GhMarkdown(text: String, p: GhPalette) {
    CompositionLocalProvider(LocalContentColor provides p.onCard) { MarkdownContent(text) }
}

private val ClosedPurple = Color(0xFF8957E5)

private class IssueVisual(val icon: ImageVector, val label: Int, val color: Color)

@Composable
private fun issueVisual(i: GhIssue, p: GhPalette): IssueVisual = when {
    i.isOpen -> IssueVisual(XIcons.IssueOpened, R.string.issue_status_open, p.success)
    i.stateReason == "not_planned" -> IssueVisual(XIcons.IssueClosed, R.string.issue_status_not_planned, p.onCardMuted)
    i.stateReason == "duplicate" -> IssueVisual(XIcons.IssueClosed, R.string.issue_status_duplicate, p.onCardMuted)
    else -> IssueVisual(XIcons.IssueClosed, R.string.issue_status_closed, ClosedPurple)
}

/** Plain-text digest of issues for the AI summary (titles, authors, labels, short description). */
internal fun issuesDigest(items: List<GhIssue>): String = items.take(40).joinToString("\n\n") { i ->
    buildString {
        append("#${i.number} ${i.title} (by ${i.author}")
        if (i.labels.isNotEmpty()) append(", labels: ${i.labels.joinToString { it.name }}")
        append(", ${i.comments} comments, created ${i.createdAt?.toString()?.take(10) ?: "?"})")
        if (i.body.isNotBlank()) append("\n").append(i.body.trim().take(300))
    }
}

/** One issue plus its comments, for the thread summary. */
private fun threadDigest(issue: GhIssue, comments: List<GhIssueComment>): String = buildString {
    append("Issue #${issue.number}: ${issue.title}\nState: ${issue.state}, opened by ${issue.author}\n")
    if (issue.body.isNotBlank()) append("\nDescription:\n").append(issue.body.trim().take(2500)).append('\n')
    comments.take(30).forEach { c -> append("\nComment by ${c.author}:\n").append(c.body.trim().take(700)).append('\n') }
}

private fun labelColor(l: GhLabel): Color =
    runCatching { Color(android.graphics.Color.parseColor("#" + l.color)) }.getOrDefault(Color.Gray)

@Composable
private fun LabelChip(l: GhLabel) {
    val c = labelColor(l)
    Text(
        l.name, style = MaterialTheme.typography.labelSmall, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(CircleShape).background(c.copy(alpha = 0.16f)).border(1.dp, c.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
internal fun IssueRow(issue: GhIssue, nowMs: Long, p: GhPalette, onClick: () -> Unit) {
    val v = issueVisual(issue, p)
    val shape = RoundedCornerShape(18.dp)
    val ago = agoText(issue.createdAt, nowMs)
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
            Icon(v.icon, contentDescription = stringResource(v.label), tint = v.color, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                issue.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = p.onCard, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (ago != null) stringResource(R.string.issue_row_meta, issue.number, issue.author, ago)
                else stringResource(R.string.issue_row_meta_no_time, issue.number, issue.author),
                style = MaterialTheme.typography.bodySmall, color = p.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (issue.labels.isNotEmpty() || issue.comments > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    issue.labels.take(2).forEach { LabelChip(it) }
                    if (issue.comments > 0) {
                        Icon(XIcons.Comment, contentDescription = null, tint = p.onCardMuted, modifier = Modifier.size(14.dp))
                        Text(issue.comments.toString(), style = MaterialTheme.typography.bodySmall, color = p.onCardMuted)
                    }
                }
            }
        }
        Icon(XIcons.ChevronRight, contentDescription = null, tint = p.onCardMuted)
    }
}

@Composable
private fun IssueFilters(selected: IssueFilter, onSelect: (IssueFilter) -> Unit, p: GhPalette) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IssueFilter.entries.forEach { f ->
            val (label, dot) = when (f) {
                IssueFilter.Open -> R.string.issue_filter_open to p.success
                IssueFilter.Closed -> R.string.issue_filter_closed to ClosedPurple
                IssueFilter.All -> R.string.issue_filter_all to p.accent
                IssueFilter.Mine -> R.string.issue_filter_mine to null
            }
            RunFilterChip(label = stringResource(label), dot = dot, selected = selected == f, onClick = { onSelect(f) })
        }
    }
}

// =================================================================================================
// Full list
// =================================================================================================

/** Full issue list with filters and paging (opened from the hub's "Show all"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueListScreen(
    projectPath: String,
    onBack: () -> Unit,
    onOpenIssue: (Int) -> Unit,
    vm: IssueListViewModel = viewModel(
        key = "gh_issue_list_$projectPath",
        factory = IssueListViewModel.factory(projectPath),
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
            PullTopBar(stringResource(R.string.issue_list_title), onBack, p) {
                AiGate?.ListSummaryAi(AiSummaryKind.ISSUES, issuesDigest(state.items), context.applicationContext as Application)
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
                if (!state.disabled) item(key = "filters") { IssueFilters(state.filter, vm::setFilter, p) }
                when {
                    state.disabled -> item(key = "disabled") {
                        NoticeBlock(stringResource(R.string.issue_disabled_title), stringResource(R.string.issue_disabled_body, state.repoName), p)
                    }
                    state.loading -> item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp), color = p.accent) }
                    state.error != null && state.items.isEmpty() -> item(key = "error") {
                        ErrorBlock(state.error?.resolve(context).orEmpty(), vm::retry, p)
                    }
                    state.items.isEmpty() -> item(key = "empty") {
                        Text(
                            stringResource(R.string.issue_empty),
                            style = MaterialTheme.typography.bodyMedium, color = p.onCardMuted,
                            modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth(),
                        )
                    }
                    else -> {
                        items(state.items, key = { it.number }) { issue -> IssueRow(issue, now, p) { onOpenIssue(issue.number) } }
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

// =================================================================================================
// Detail
// =================================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueDetailScreen(
    projectPath: String,
    number: Int,
    onBack: () -> Unit,
    vm: IssueDetailViewModel = viewModel(
        key = "gh_issue_${projectPath}_$number",
        factory = IssueDetailViewModel.factory(projectPath, number),
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val p = ghPalette()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val now = rememberNowMs()
    val issue = state.issue

    LaunchedEffect(vm) { vm.events.collect { handleEvent(it, context, snackbar, scope) } }

    Scaffold(
        modifier = Modifier.background(p.screen),
        containerColor = Color.Transparent,
        topBar = {
            PullTopBar(stringResource(R.string.issue_header_title, number), onBack, p) {
                if (issue != null) {
                    AiGate?.ListSummaryAi(AiSummaryKind.THREAD, threadDigest(issue, state.comments), context.applicationContext as Application)
                    if (issue.htmlUrl.isNotBlank()) {
                        IconButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(issue.htmlUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Icon(XIcons.OpenInNew, contentDescription = stringResource(R.string.pr_open_on_github)) }
                    }
                }
                IconButton(onClick = vm::refresh) { Icon(XIcons.Refresh, contentDescription = stringResource(R.string.action_refresh)) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (issue != null) IssueComposer(issue, state, vm, p) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
                state.error != null && issue == null -> ErrorBlock(state.error?.resolve(context).orEmpty(), vm::retry, p)
                issue != null -> {
                    if (state.acting) LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
                    IssueBody(issue, state, now, p, context, vm)
                }
            }
        }
    }

    if (state.closeDialog) {
        AlertDialog(
            onDismissRequest = vm::dismissDialogs,
            title = { Text(stringResource(R.string.issue_close_title)) },
            text = {
                Column {
                    listOf(
                        "completed" to R.string.issue_close_completed,
                        "not_planned" to R.string.issue_close_not_planned,
                        "duplicate" to R.string.issue_close_duplicate,
                    ).forEach { (reason, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.setCloseReason(reason) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.closeReason == reason, onClick = { vm.setCloseReason(reason) })
                            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = vm::confirmClose,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text(stringResource(R.string.issue_close)) }
            },
            dismissButton = { TextButton(onClick = vm::dismissDialogs) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun IssueBody(issue: GhIssue, state: IssueDetailState, nowMs: Long, p: GhPalette, context: android.content.Context, vm: IssueDetailViewModel) {
    val v = issueVisual(issue, p)
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(issue.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = p.onCard, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.clip(CircleShape).background(v.color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(v.icon, contentDescription = null, tint = v.color, modifier = Modifier.size(14.dp))
                Text(stringResource(v.label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = v.color)
            }
            Text(
                stringResource(R.string.issue_by_author, issue.author),
                style = MaterialTheme.typography.bodyMedium, color = p.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (issue.labels.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { issue.labels.forEach { LabelChip(it) } }
        }
        Column(
            Modifier.fillMaxWidth().clip(shape).background(p.runCard).border(1.dp, p.cardBorder, shape).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.issue_description), style = MaterialTheme.typography.labelLarge, color = p.onCardMuted)
            if (issue.body.isBlank()) Text(stringResource(R.string.issue_no_description), style = MaterialTheme.typography.bodyMedium, color = p.onCardMuted)
            else GhMarkdown(issue.body, p)
        }
        Text(
            stringResource(R.string.issue_comments_title) + " (${issue.comments})",
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = p.onCard,
        )
        when {
            state.commentsLoading && state.comments.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
            state.commentsError != null && state.comments.isEmpty() -> ErrorBlock(state.commentsError.resolve(context), vm::retry, p)
            state.comments.isEmpty() -> Text(stringResource(R.string.issue_no_comments), style = MaterialTheme.typography.bodyMedium, color = p.onCardMuted)
            else -> state.comments.forEach { c -> CommentCard(c, nowMs, p) }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun CommentCard(c: GhIssueComment, nowMs: Long, p: GhPalette) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(p.runCard).border(1.dp, p.cardBorder, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.author, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = p.onCard)
            agoText(c.createdAt, nowMs)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = p.onCardMuted) }
        }
        if (c.body.isNotBlank()) GhMarkdown(c.body, p)
    }
}

/** Bottom bar: close / reopen and the comment box. */
@Composable
private fun IssueComposer(issue: GhIssue, state: IssueDetailState, vm: IssueDetailViewModel, p: GhPalette) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.draft, onValueChange = vm::setDraft,
                placeholder = { Text(stringResource(R.string.issue_comment_hint)) },
                maxLines = 4, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = vm::postComment, enabled = state.draft.isNotBlank() && !state.posting) {
                if (state.posting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = p.accent)
                else Icon(XIcons.Send, contentDescription = stringResource(R.string.issue_comment_send), tint = if (state.draft.isNotBlank()) p.accent else p.onCardMuted)
            }
        }
        if (issue.isOpen) {
            Button(
                onClick = vm::openCloseDialog, enabled = !state.acting,
                colors = ButtonDefaults.buttonColors(containerColor = p.danger.copy(alpha = 0.18f), contentColor = p.danger),
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) { Text(stringResource(R.string.issue_close)) }
        } else {
            Button(onClick = vm::reopen, enabled = !state.acting, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                Text(stringResource(R.string.issue_reopen))
            }
        }
    }
}
