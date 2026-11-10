package com.invictus.kodex.feature.git

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.KodexApp
import com.invictus.kodex.R
import com.invictus.kodex.core.git.GitResult
import com.invictus.kodex.core.git.GitSessionRegistry
import com.invictus.kodex.core.git.GitTrigger
import com.invictus.kodex.core.git.model.GitCommitSummary
import com.invictus.kodex.core.git.model.GitErrorDetails
import com.invictus.kodex.core.git.model.GitReflogEntry
import com.invictus.kodex.core.git.model.GitResetMode
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ---- raw reflog text -> plain words ----------------------------------------------------------------

/** What kind of move this was; drives the small label and the one-line explanation in the sheet. */
internal enum class ReflogKind(@StringRes val label: Int, @StringRes val explain: Int) {
    COMMIT(R.string.git_reflog_kind_commit, R.string.git_reflog_explain_commit),
    SWITCH(R.string.git_reflog_kind_switch, R.string.git_reflog_explain_switch),
    RESET(R.string.git_reflog_kind_reset, R.string.git_reflog_explain_reset),
    MERGE(R.string.git_reflog_kind_merge, R.string.git_reflog_explain_merge),
    PULL(R.string.git_reflog_kind_pull, R.string.git_reflog_explain_pull),
    REBASE(R.string.git_reflog_kind_rebase, R.string.git_reflog_explain_rebase),
    PICK(R.string.git_reflog_kind_pick, R.string.git_reflog_explain_pick),
    REVERT(R.string.git_reflog_kind_revert, R.string.git_reflog_explain_revert),
    CLONE(R.string.git_reflog_kind_clone, R.string.git_reflog_explain_clone),
    AUTHOR(R.string.git_reflog_kind_author, R.string.git_reflog_explain_author),
    BRANCH(R.string.git_reflog_kind_branch, R.string.git_reflog_explain_branch),
    OTHER(R.string.git_reflog_kind_other, R.string.git_reflog_explain_other),
}

/** A reflog line in plain words: [title] (with [titleArg] filled in when it has a %s) and an optional [detail]. */
internal class ReflogText(
    val kind: ReflogKind,
    @StringRes val title: Int,
    val titleArg: String? = null,
    val detail: String? = null,
)

/** Git writes lines like "commit (amend): msg" or "checkout: moving from a to b"; turn them into words. */
internal fun describeReflog(raw: String): ReflogText {
    val msg = raw.trim()
    // "<hash>: updating HEAD" is git moving HEAD itself (e.g. around a reset): nothing the person did.
    if (msg.endsWith("updating HEAD")) return ReflogText(ReflogKind.OTHER, R.string.git_reflog_t_head_updated)
    val colon = msg.indexOf(':')
    val head = if (colon >= 0) msg.substring(0, colon).trim() else msg
    val rest = if (colon >= 0) msg.substring(colon + 1).trim().ifBlank { null } else null
    return when {
        head == "commit (initial)" -> ReflogText(ReflogKind.COMMIT, R.string.git_reflog_t_first_commit, detail = rest)
        head == "commit (amend)" -> ReflogText(ReflogKind.COMMIT, R.string.git_reflog_t_amend, detail = rest)
        head == "commit (merge)" -> ReflogText(ReflogKind.MERGE, R.string.git_reflog_t_merge_commit, detail = rest)
        head == "commit" -> ReflogText(ReflogKind.COMMIT, R.string.git_reflog_t_commit, detail = rest)
        head == "checkout" -> {
            val m = Regex("""moving from (.+) to (.+)""").find(rest.orEmpty())
            ReflogText(ReflogKind.SWITCH, R.string.git_reflog_t_switch, detail = m?.let { "${it.groupValues[1]} → ${it.groupValues[2]}" } ?: rest)
        }
        head == "reset" -> {
            val target = rest?.removePrefix("moving to")?.trim()?.ifBlank { null }
            if (target != null) ReflogText(ReflogKind.RESET, R.string.git_reflog_t_reset_to, titleArg = target)
            else ReflogText(ReflogKind.RESET, R.string.git_reflog_t_reset, detail = rest)
        }
        head.startsWith("merge ") -> {
            val what = head.removePrefix("merge ").trim()
            // A fast-forward needs no merge commit: the branch just slid forward.
            if (rest?.contains("Fast-forward", ignoreCase = true) == true) {
                ReflogText(ReflogKind.MERGE, R.string.git_reflog_t_merged_ff, titleArg = what)
            } else {
                ReflogText(ReflogKind.MERGE, R.string.git_reflog_t_merged, titleArg = what, detail = rest)
            }
        }
        head.startsWith("pull") -> ReflogText(ReflogKind.PULL, R.string.git_reflog_t_pull, detail = rest)
        head.startsWith("rebase") -> when {
            head.contains("finish") -> ReflogText(ReflogKind.REBASE, R.string.git_reflog_t_rebase_done)
            head.contains("start") -> ReflogText(ReflogKind.REBASE, R.string.git_reflog_t_rebase_start, detail = rest)
            head.contains("pick") -> ReflogText(ReflogKind.REBASE, R.string.git_reflog_t_rebase_pick, detail = rest)
            else -> ReflogText(ReflogKind.REBASE, R.string.git_reflog_t_rebase_step, detail = rest)
        }
        head.startsWith("cherry-pick") -> ReflogText(ReflogKind.PICK, R.string.git_reflog_t_pick, detail = rest)
        head.startsWith("revert") -> ReflogText(ReflogKind.REVERT, R.string.git_reflog_t_revert, detail = rest)
        head.startsWith("clone") -> ReflogText(ReflogKind.CLONE, R.string.git_reflog_t_clone, detail = rest)
        head == "rewrite" -> ReflogText(ReflogKind.AUTHOR, R.string.git_reflog_t_author, detail = rest)
        head.startsWith("branch") -> ReflogText(ReflogKind.BRANCH, R.string.git_reflog_t_branch, detail = rest)
        else -> ReflogText(ReflogKind.OTHER, R.string.git_reflog_t_other, detail = msg.ifBlank { null })
    }
}

// ---- state -----------------------------------------------------------------------------------------

class GitReflogViewModel(
    private val projectPath: String,
    globalIdentityFile: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val session = GitSessionRegistry.acquire(File(projectPath), globalIdentityFile, io)

    override fun onCleared() {
        GitSessionRegistry.release(File(projectPath))
    }

    data class UiState(
        val loading: Boolean = true,
        val entries: List<GitReflogEntry> = emptyList(),
        /** Entry whose sheet is open. */
        val selected: GitReflogEntry? = null,
        /** Entry the "go back" dialog is for. */
        val resetTarget: GitReflogEntry? = null,
        val resetHardPending: Boolean = false,
        val resetting: Boolean = false,
        val error: GitErrorDetails? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    init { reload() }

    fun messageHandled() { _messages.value = null }
    fun showMessage(msg: String) { _messages.value = msg }

    fun reload() {
        viewModelScope.launch(io + GitTrigger("reflog")) {
            _ui.update { it.copy(loading = it.entries.isEmpty()) }
            when (val r = session.reflog()) {
                is GitResult.Ok -> _ui.update { it.copy(loading = false, entries = r.value) }
                is GitResult.Err -> _ui.update { it.copy(loading = false, error = r.error) }
            }
        }
    }

    fun select(e: GitReflogEntry) = _ui.update { it.copy(selected = e) }
    fun dismissSelected() = _ui.update { it.copy(selected = null) }

    fun openReset(e: GitReflogEntry) = _ui.update { it.copy(resetTarget = e, selected = null) }
    fun dismissReset() = _ui.update { it.copy(resetTarget = null, resetHardPending = false) }

    /** Soft/Mixed run right away; Hard asks once more first. */
    fun resetHere(mode: GitResetMode) {
        if (mode == GitResetMode.HARD) _ui.update { it.copy(resetHardPending = true) } else performReset(mode)
    }

    fun confirmHardReset() = performReset(GitResetMode.HARD)

    private fun performReset(mode: GitResetMode) {
        val target = _ui.value.resetTarget ?: return
        if (_ui.value.resetting) return
        viewModelScope.launch(io + GitTrigger("reflog-reset")) {
            _ui.update { it.copy(resetting = true) }
            when (val r = session.resetTo(target.newId, mode)) {
                is GitResult.Ok -> {
                    _ui.update { it.copy(resetTarget = null, resetHardPending = false) }
                    _messages.value = "Branch moved to ${target.shortNew}"
                    reload()
                }
                is GitResult.Err -> _ui.update { it.copy(error = r.error, resetHardPending = false) }
            }
            _ui.update { it.copy(resetting = false) }
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    companion object {
        fun factory(projectPath: String) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                GitReflogViewModel(projectPath, app.container.gitGlobalIdentityFile)
            }
        }
    }
}

// ---- UI --------------------------------------------------------------------------------------------

private sealed interface ReflogRow {
    data object Intro : ReflogRow
    data class Day(val date: LocalDate, val ms: Long, val pos: Int) : ReflogRow
    data class Item(val entry: GitReflogEntry, val isCurrent: Boolean, val index: Int) : ReflogRow
}

private fun buildReflogRows(entries: List<GitReflogEntry>): List<ReflogRow> = buildList {
    add(ReflogRow.Intro)
    var lastDay: LocalDate? = null
    entries.forEachIndexed { i, e ->
        val day = Instant.ofEpochMilli(e.timeMs).atZone(ZoneId.systemDefault()).toLocalDate()
        if (day != lastDay) {
            add(ReflogRow.Day(day, e.timeMs, pos = i))
            lastDay = day
        }
        add(ReflogRow.Item(e, isCurrent = i == 0, index = i))
    }
}

/** A reflog entry as the commit the reset dialog expects: the state right after that step. */
private fun GitReflogEntry.asCommit() = GitCommitSummary(
    id = newId, shortId = shortNew, message = message, author = who, timeMs = timeMs,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitReflogScreen(projectPath: String, onBack: () -> Unit) {
    val vm: GitReflogViewModel = viewModel(
        key = "reflog:$projectPath",
        factory = GitReflogViewModel.factory(projectPath),
    )
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val rows = remember(ui.entries) { buildReflogRows(ui.entries) }
    val copiedMsg = stringResource(R.string.git_history_hash_copied)

    LaunchedEffect(Unit) {
        vm.messages.collect { msg -> msg?.let { snackbar.showSnackbar(it); vm.messageHandled() } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.git_reflog_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::reload) {
                        Icon(XIcons.Sync, contentDescription = stringResource(R.string.git_reflog_refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(
                        rows,
                        key = { r ->
                            when (r) {
                                ReflogRow.Intro -> "intro"
                                is ReflogRow.Day -> "day:${r.pos}"
                                // Entries can repeat (same second, same id, same text): the position keeps keys unique.
                                is ReflogRow.Item -> "e:${r.index}"
                            }
                        },
                    ) { row ->
                        when (row) {
                            ReflogRow.Intro -> ReflogIntro()
                            is ReflogRow.Day -> Text(
                                dayLabel(context, row.ms),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
                            )
                            is ReflogRow.Item -> ReflogItem(row.entry, row.isCurrent) { vm.select(row.entry) }
                        }
                    }
                    if (ui.entries.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(R.string.git_reflog_empty),
                                Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item(key = "end") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    ui.selected?.let { e ->
        ReflogDetailSheet(
            entry = e,
            isCurrent = ui.entries.firstOrNull() == e,
            onGoBack = { vm.openReset(e) },
            onCopy = {
                clipboard.setText(AnnotatedString(e.newId))
                vm.showMessage(copiedMsg)
                vm.dismissSelected()
            },
            onDismiss = vm::dismissSelected,
        )
    }
    ui.resetTarget?.let { e ->
        GitResetModeDialog(
            commit = e.asCommit(),
            resetting = ui.resetting,
            onConfirm = { mode -> vm.resetHere(mode) },
            onDismiss = vm::dismissReset,
        )
    }
    if (ui.resetHardPending) {
        GitConfirmDialog(
            title = stringResource(R.string.git_reset_hard_confirm_title),
            text = stringResource(R.string.git_reset_hard_confirm_text, ui.resetTarget?.shortNew.orEmpty()),
            confirmLabel = stringResource(R.string.git_reset_confirm),
            danger = true,
            onConfirm = vm::confirmHardReset,
            onDismiss = vm::dismissReset,
        )
    }
    ui.error?.let { GitErrorDialog(details = it, onDismiss = vm::dismissError) }
}

@Composable
private fun ReflogIntro() {
    Text(
        stringResource(R.string.git_reflog_intro_tip),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ReflogItem(entry: GitReflogEntry, isCurrent: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val text = remember(entry.message) { describeReflog(entry.message) }
    // One line of what happened (for a commit: its message), and the time under it. Details are in the sheet.
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            lineOf(text),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                timeLabel(context, entry.timeMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isCurrent) {
                Text(
                    "  ·  " + stringResource(R.string.git_reflog_current),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun titleOf(t: ReflogText): String =
    if (t.titleArg != null) stringResource(t.title, t.titleArg) else stringResource(t.title)

/** The single line shown in the list: a commit shows just its message, anything else "what: detail". */
@Composable
private fun lineOf(t: ReflogText): String {
    val title = titleOf(t)
    val detail = t.detail
    return when {
        detail == null -> title
        t.title == R.string.git_reflog_t_commit || t.title == R.string.git_reflog_t_first_commit ||
            t.title == R.string.git_reflog_t_merge_commit -> detail
        t.kind == ReflogKind.OTHER -> detail
        else -> "$title: $detail"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReflogDetailSheet(
    entry: GitReflogEntry,
    isCurrent: Boolean,
    onGoBack: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val text = remember(entry.message) { describeReflog(entry.message) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(titleOf(text), style = MaterialTheme.typography.titleMedium)
            text.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                stringResource(text.kind.explain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.git_reflog_when, fullTime(context, entry.timeMs)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (entry.fromNothing) {
                    stringResource(R.string.git_reflog_moved_from_nothing, entry.shortNew)
                } else {
                    stringResource(R.string.git_reflog_moved, entry.shortOld, entry.shortNew)
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            if (isCurrent) {
                Text(
                    stringResource(R.string.git_reflog_already_here),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                Button(onClick = onGoBack, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.git_reflog_go_back))
                }
                Text(
                    stringResource(R.string.git_reflog_go_back_hint, entry.shortNew),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.git_reflog_copy_id))
            }
        }
    }
}

private fun dayLabel(context: android.content.Context, ms: Long): String = when {
    DateUtils.isToday(ms) -> context.getString(R.string.git_reflog_today)
    DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.git_reflog_yesterday)
    else -> DateUtils.formatDateTime(
        context, ms,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL,
    )
}

/** "10:42 · 5 min ago". */
private fun timeLabel(context: android.content.Context, ms: Long): String =
    DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_TIME) + "  ·  " +
        DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

private fun fullTime(context: android.content.Context, ms: Long): String =
    DateUtils.formatDateTime(
        context, ms,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
    )
