package com.invictus.kodex.feature.git

import com.invictus.kodex.ai.AiGate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.invictus.kodex.core.git.model.GitCommitFile
import com.invictus.kodex.core.git.model.GitFileDiffResult
import com.invictus.kodex.core.git.GitTrigger
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.kodex.ui.icons.XIcons
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.R
import com.invictus.kodex.KodexApp
import com.invictus.kodex.core.git.GitResult
import com.invictus.kodex.core.git.GitSessionRegistry
import com.invictus.kodex.core.git.model.GitCommitSummary
import com.invictus.kodex.core.git.model.GitErrorDetails
import com.invictus.kodex.core.git.model.GitLogSearchMode
import com.invictus.kodex.core.git.model.GitResetMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GitHistoryViewModel(
    private val projectPath: String,
    private val filePath: String?,
    globalIdentityFile: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val session = GitSessionRegistry.acquire(File(projectPath), globalIdentityFile, io)

    override fun onCleared() {
        GitSessionRegistry.release(File(projectPath))
    }

    data class UiState(
        val loading: Boolean = true,
        val commits: List<GitCommitSummary> = emptyList(),
        val hasMore: Boolean = false,
        val loadingMore: Boolean = false,
        val query: String = "",
        val mode: GitLogSearchMode = GitLogSearchMode.MESSAGE,
        val detail: GitCommitSummary? = null,
        val picking: Boolean = false,
        val error: GitErrorDetails? = null,
        // "Reset here"
        val resetTarget: GitCommitSummary? = null,
        val resetHardPending: Boolean = false,
        val resetting: Boolean = false,
        // commit detail screen
        val files: List<GitCommitFile> = emptyList(),
        val filesLoading: Boolean = false,
        val selectedFile: GitCommitFile? = null,
        val fileDiff: GitFileDiffResult? = null,
        val diffLoading: Boolean = false,
        // "Change author"
        val authorTarget: GitCommitSummary? = null,
        val rewriting: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    private var searchJob: Job? = null

    init { reload("screen-open") }

    fun messageHandled() { _messages.value = null }

    private fun reload(trigger: String = "user") {
        viewModelScope.launch(io + GitTrigger(trigger)) {
            _ui.update { it.copy(loading = it.commits.isEmpty()) }
            when (val r = session.log(max = PAGE_SIZE, path = filePath)) {
                is GitResult.Ok -> _ui.update {
                    it.copy(loading = false, commits = r.value, hasMore = r.value.size >= PAGE_SIZE)
                }
                is GitResult.Err -> _ui.update { it.copy(loading = false, error = r.error) }
            }
        }
    }

    /** "Load more" button: next [PAGE_SIZE] commits after the ones already shown. */
    fun loadMore() {
        val st = _ui.value
        if (st.loadingMore || !st.hasMore || st.query.isNotBlank()) return
        viewModelScope.launch(io + GitTrigger("load-more")) {
            _ui.update { it.copy(loadingMore = true) }
            when (val r = session.log(max = PAGE_SIZE, path = filePath, skip = st.commits.size)) {
                is GitResult.Ok -> _ui.update {
                    val known = it.commits.mapTo(HashSet()) { c -> c.id }
                    it.copy(
                        loadingMore = false,
                        commits = it.commits + r.value.filter { c -> c.id !in known },
                        hasMore = r.value.size >= PAGE_SIZE,
                    )
                }
                is GitResult.Err -> _ui.update { it.copy(loadingMore = false, error = r.error) }
            }
        }
    }

    fun onQueryChange(q: String) {
        _ui.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.isBlank()) { reload(); return }
        searchJob = viewModelScope.launch(io) {
            delay(250)   // debounce
            when (val r = session.searchLog(q, _ui.value.mode)) {
                is GitResult.Ok -> _ui.update { it.copy(commits = r.value, hasMore = false) }
                is GitResult.Err -> _ui.update { it.copy(error = r.error) }
            }
        }
    }

    fun onModeChange(mode: GitLogSearchMode) {
        _ui.update { it.copy(mode = mode) }
        if (_ui.value.query.isNotBlank()) onQueryChange(_ui.value.query) else reload()
    }

    fun showMessage(msg: String) { _messages.value = msg }

    fun openDetail(c: GitCommitSummary) {
        _ui.update {
            it.copy(
                detail = c, files = emptyList(), filesLoading = true,
                selectedFile = null, fileDiff = null, diffLoading = false,
            )
        }
        viewModelScope.launch(io + GitTrigger("commit-files")) {
            when (val r = session.commitFiles(c.id)) {
                is GitResult.Ok -> _ui.update {
                    if (it.detail?.id == c.id) it.copy(files = r.value, filesLoading = false) else it
                }
                is GitResult.Err -> _ui.update { it.copy(filesLoading = false, error = r.error) }
            }
        }
    }

    fun dismissDetail() = _ui.update {
        it.copy(detail = null, files = emptyList(), selectedFile = null, fileDiff = null, diffLoading = false)
    }

    fun openFile(f: GitCommitFile) {
        val c = _ui.value.detail ?: return
        _ui.update { it.copy(selectedFile = f, fileDiff = null, diffLoading = true) }
        viewModelScope.launch(io + GitTrigger("commit-file-diff")) {
            when (val r = session.commitFileDiff(c.id, f.path)) {
                is GitResult.Ok -> _ui.update {
                    if (it.selectedFile == f) it.copy(fileDiff = r.value, diffLoading = false) else it
                }
                is GitResult.Err -> _ui.update { it.copy(diffLoading = false, selectedFile = null, error = r.error) }
            }
        }
    }

    fun closeFile() = _ui.update { it.copy(selectedFile = null, fileDiff = null, diffLoading = false) }

    // ---- change author ------------------------------------------------------

    fun openChangeAuthor(c: GitCommitSummary) = _ui.update { it.copy(authorTarget = c) }
    fun dismissChangeAuthor() = _ui.update { it.copy(authorTarget = null) }

    fun changeAuthor(name: String, email: String) {
        val target = _ui.value.authorTarget ?: return
        viewModelScope.launch(io + GitTrigger("change-author")) {
            _ui.update { it.copy(rewriting = true) }
            when (val r = session.changeAuthor(target.id, name, email)) {
                is GitResult.Ok -> {
                    _ui.update { it.copy(authorTarget = null) }
                    _messages.value = "Author changed"
                    reload()
                }
                is GitResult.Err -> _ui.update { it.copy(error = r.error) }
            }
            _ui.update { it.copy(rewriting = false) }
        }
    }

    fun cherryPick(id: String) {
        viewModelScope.launch(io) {
            _ui.update { it.copy(picking = true, detail = null) }
            when (val r = session.cherryPick(id)) {
                is GitResult.Ok -> _messages.value = "Cherry-picked $id"
                is GitResult.Err -> _ui.update { it.copy(error = r.error) }
            }
            _ui.update { it.copy(picking = false) }
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    // ---- reset here ---------------------------------------------------------

    fun openReset(c: GitCommitSummary) = _ui.update { it.copy(resetTarget = c, detail = null) }
    fun dismissReset() = _ui.update { it.copy(resetTarget = null, resetHardPending = false) }

    /** Mixed/soft run right away; hard needs the extra confirm below first. */
    fun resetHere(mode: GitResetMode) {
        if (mode == GitResetMode.HARD) {
            _ui.update { it.copy(resetHardPending = true) }
        } else {
            performReset(mode)
        }
    }

    fun confirmHardReset() = performReset(GitResetMode.HARD)

    private fun performReset(mode: GitResetMode) {
        val target = _ui.value.resetTarget ?: return
        viewModelScope.launch(io) {
            _ui.update { it.copy(resetting = true) }
            when (val r = session.resetTo(target.id, mode)) {
                is GitResult.Ok -> {
                    _ui.update { it.copy(resetTarget = null, resetHardPending = false) }
                    _messages.value = "Reset to ${target.shortId}"
                    reload()
                }
                is GitResult.Err -> _ui.update { it.copy(error = r.error, resetHardPending = false) }
            }
            _ui.update { it.copy(resetting = false) }
        }
    }

    companion object {
        private const val PAGE_SIZE = 50

        fun factory(projectPath: String, filePath: String?) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                GitHistoryViewModel(
                    projectPath = projectPath,
                    filePath = filePath,
                    globalIdentityFile = app.container.gitGlobalIdentityFile,
                )
            }
        }
    }
}

private val historyTimeFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())

private sealed interface HistoryRow {
    data class Header(val author: String, val count: Int) : HistoryRow
    data class Item(val commit: GitCommitSummary) : HistoryRow
}

/** Author chip: commits grouped by author (first-appearance order); other chips: flat list. */
private fun buildRows(commits: List<GitCommitSummary>, grouped: Boolean): List<HistoryRow> =
    if (!grouped) {
        commits.map { HistoryRow.Item(it) }
    } else {
        buildList {
            commits.groupBy { it.author }.forEach { (author, list) ->
                add(HistoryRow.Header(author, list.size))
                list.forEach { add(HistoryRow.Item(it)) }
            }
        }
    }

/**
 * Tap / very-short long-press detector for the hash text. Long-press fires after [timeoutMs]
 * (shorter than the system 400ms). It consumes the gesture so the row's own click / long-press
 * menu does not also fire; a quick tap is forwarded to [onTap].
 */
private fun Modifier.quickLongPress(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    timeoutMs: Long = 250L,
): Modifier = pointerInput(onTap, onLongPress) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        var finished = false
        val up = withTimeoutOrNull(timeoutMs) {
            val u = waitForUpOrCancellation()
            finished = true
            u
        }
        when {
            finished && up != null -> { up.consume(); onTap() }
            finished -> Unit // cancelled (scroll)
            else -> {
                onLongPress()
                waitForUpOrCancellation()?.consume()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GitHistoryScreen(projectPath: String, filePath: String? = null, onBack: () -> Unit) {
    val vm: GitHistoryViewModel = viewModel(
        key = "history:$projectPath:$filePath",
        factory = GitHistoryViewModel.factory(projectPath, filePath),
    )
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val hashCopiedMsg = stringResource(R.string.git_history_hash_copied)
    val rows = remember(ui.commits, ui.mode) { buildRows(ui.commits, ui.mode == GitLogSearchMode.AUTHOR) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.messages.collect { msg -> msg?.let { snackbar.showSnackbar(it); vm.messageHandled() } }
    }

    BackHandler(enabled = ui.detail != null) {
        if (ui.selectedFile != null) vm.closeFile() else vm.dismissDetail()
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (filePath != null) {
                            stringResource(R.string.git_history_title) + ": $filePath"
                        } else {
                            stringResource(R.string.git_history_title)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = ui.query,
                onValueChange = vm::onQueryChange,
                placeholder = { Text(stringResource(R.string.git_history_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                GitLogSearchMode.entries.forEach { mode ->
                    FilterChip(
                        selected = ui.mode == mode,
                        onClick = { vm.onModeChange(mode) },
                        label = {
                            Text(
                                stringResource(
                                    when (mode) {
                                        GitLogSearchMode.MESSAGE -> R.string.git_history_mode_message
                                        GitLogSearchMode.AUTHOR -> R.string.git_history_mode_author
                                        GitLogSearchMode.HASH -> R.string.git_history_mode_hash
                                    },
                                ),
                            )
                        },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(
                    rows,
                    key = { r ->
                        when (r) {
                            is HistoryRow.Header -> "author:${r.author}"
                            is HistoryRow.Item -> r.commit.id
                        }
                    },
                ) { row ->
                    when (row) {
                        is HistoryRow.Header -> Text(
                            stringResource(R.string.git_history_author_group, row.author, row.count),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
                        )
                        is HistoryRow.Item -> {
                            val c = row.commit
                            Box {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { vm.openDetail(c) },
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                menuFor = c.id
                                            },
                                        )
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            c.shortId,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontFamily = if (ui.mode == GitLogSearchMode.HASH) FontFamily.Monospace else null,
                                                fontWeight = if (ui.mode == GitLogSearchMode.HASH) FontWeight.Bold else null,
                                            ),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .quickLongPress(
                                                    onTap = { vm.openDetail(c) },
                                                    onLongPress = {
                                                        clipboard.setText(AnnotatedString(c.id))
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        vm.showMessage(hashCopiedMsg)
                                                    },
                                                )
                                                .padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            historyTimeFormat.format(Date(c.timeMs)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        c.message.lineSequence().firstOrNull().orEmpty(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        c.author,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuFor == c.id,
                                    onDismissRequest = { menuFor = null },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.git_change_author)) },
                                        leadingIcon = { Icon(XIcons.Person, contentDescription = null) },
                                        onClick = {
                                            menuFor = null
                                            vm.openChangeAuthor(c)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                if (ui.hasMore && ui.query.isBlank()) {
                    item(key = "load_more") {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = vm::loadMore, enabled = !ui.loadingMore) {
                                if (ui.loadingMore) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(stringResource(R.string.git_history_load_more))
                                }
                            }
                        }
                    }
                }
                if (!ui.loading && ui.commits.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.git_history_empty),
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    ui.detail?.let { c ->
        GitCommitDetailScreen(
            commit = c,
            files = ui.files,
            filesLoading = ui.filesLoading,
            selectedFile = ui.selectedFile,
            fileDiff = ui.fileDiff,
            diffLoading = ui.diffLoading,
            picking = ui.picking,
            onOpenFile = vm::openFile,
            onCloseFile = vm::closeFile,
            onCherryPick = { vm.cherryPick(c.id) },
            onReset = { vm.openReset(c) },
            onCancel = vm::dismissDetail,
            aiExplain = AiGate?.let { g ->
                {
                    g.ExplainDiffAi(
                        projectPath = projectPath,
                        commitId = c.id,
                        filePaths = ui.files.map { it.path },
                        app = LocalContext.current.applicationContext as android.app.Application,
                    )
                }
            },
        )
    }
    }

    ui.authorTarget?.let { target ->
        GitFieldsDialog(
            title = stringResource(R.string.git_change_author),
            fields = listOf(
                stringResource(R.string.git_author_name) to target.author,
                stringResource(R.string.git_author_email) to target.authorEmail,
            ),
            confirmLabel = stringResource(R.string.git_change_author_confirm),
            onConfirm = { values, _ ->
                val name = values[0].trim()
                val email = values[1].trim()
                if (name.isNotEmpty() && email.isNotEmpty() && !ui.rewriting) vm.changeAuthor(name, email)
            },
            onDismiss = vm::dismissChangeAuthor,
        )
    }
    ui.resetTarget?.let { target ->
        GitResetModeDialog(
            commit = target,
            resetting = ui.resetting,
            onConfirm = { mode -> vm.resetHere(mode) },
            onDismiss = vm::dismissReset,
        )
    }
    if (ui.resetHardPending) {
        GitConfirmDialog(
            title = stringResource(R.string.git_reset_hard_confirm_title),
            text = stringResource(
                R.string.git_reset_hard_confirm_text,
                ui.resetTarget?.shortId.orEmpty(),
            ),
            confirmLabel = stringResource(R.string.git_reset_confirm),
            danger = true,
            onConfirm = vm::confirmHardReset,
            onDismiss = vm::dismissReset,
        )
    }
    ui.error?.let { GitErrorDialog(details = it, onDismiss = vm::dismissError) }
}
