package com.invictus.kodex.feature.git

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import java.io.File
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.invictus.kodex.core.git.model.GitDiffLineType
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.invictus.kodex.core.git.model.GitFileDiffResult
import com.invictus.kodex.R
import com.invictus.kodex.core.git.model.GitConflictSide
import com.invictus.kodex.core.git.model.GitPathChange
import com.invictus.kodex.core.git.model.GitStageState
import com.invictus.kodex.core.git.model.GitWorkingState
import com.invictus.kodex.ui.icons.XIcons

/** True when [change] is an unresolved merge/rebase conflict. */
internal fun GitPathChange.isConflict(): Boolean =
    staged == GitStageState.CONFLICT || unstaged == GitWorkingState.CONFLICT

private val CompactButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)

/**
 * "Rebase in progress" / "Merge in progress" card with its Continue / Skip / Abort (or Complete /
 * Abort) buttons. Shared by the drawer and the full Git screen; renders nothing when no
 * operation is running.
 */
@Composable
internal fun GitOperationBanner(state: GitViewModel.UiState, onEvent: (GitEvent) -> Unit) {
    val snapshot = state.snapshot ?: return
    val rebase = snapshot.rebaseInProgress
    val merge = snapshot.mergeInProgress
    if (!rebase && !merge) return

    val conflicts = state.status?.conflicts?.size ?: 0
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (rebase) XIcons.Rebase else XIcons.Merge,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp).graphicsLayer { scaleY = -1f },
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        stringResource(if (rebase) R.string.git_rebase_in_progress else R.string.git_merge_in_progress),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (conflicts > 0) stringResource(R.string.git_op_conflicts_left, conflicts)
                        else stringResource(R.string.git_op_all_resolved),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (conflicts > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onEvent(if (rebase) GitEvent.ContinueRebase else GitEvent.CompleteMerge) },
                    enabled = conflicts == 0,
                    contentPadding = CompactButtonPadding,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(XIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        stringResource(if (rebase) R.string.git_op_continue else R.string.git_op_complete),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (rebase) {
                    OutlinedButton(
                        onClick = { onEvent(GitEvent.SkipRebaseCommit) },
                        contentPadding = CompactButtonPadding,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(XIcons.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.git_op_skip), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedButton(
                    onClick = { onEvent(if (rebase) GitEvent.AbortRebase else GitEvent.AbortMerge) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = CompactButtonPadding,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(XIcons.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.git_op_abort), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** "Conflicts (N)" header (+ "Mark all resolved") plus one [ConflictCard] per file. Nothing when there are none. */
internal fun LazyListScope.conflictSection(
    conflicts: List<GitPathChange>,
    diffs: Map<String, GitFileDiffResult>,
    diffLoading: Set<String>,
    onEvent: (GitEvent) -> Unit,
) {
    if (conflicts.isEmpty()) return
    item(key = "conflicts:header") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 0.dp),
        ) {
            Text(
                stringResource(R.string.git_section_conflicts) + "  (${conflicts.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onEvent(GitEvent.MarkAllResolved) },
                contentPadding = CompactButtonPadding,
            ) {
                Icon(XIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(R.string.git_mark_all_resolved),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    itemsIndexed(conflicts, key = { index, change -> "conflicts:${change.repoRelativePath}:$index" }) { _, change ->
        ConflictCard(
            change = change,
            diff = diffs[change.repoRelativePath],
            diffLoading = change.repoRelativePath in diffLoading,
            onEvent = onEvent,
        )
    }
}

/**
 * One conflicted file: name + folder, and Ours / Theirs / Mark resolved.
 * Tap the name to open the file in the editor; long-press it for a full-screen diff.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConflictCard(
    change: GitPathChange,
    diff: GitFileDiffResult?,
    diffLoading: Boolean,
    onEvent: (GitEvent) -> Unit,
) {
    var showDiff by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val path = change.repoRelativePath
    val name = path.substringAfterLast('/')
    val dir = path.substringBeforeLast('/', "")
    if (showDiff) {
        GitFileDiffDialog(
            name = name,
            dir = dir,
            diff = diff,
            loading = diffLoading,
            onClose = {
                showDiff = false
                onEvent(GitEvent.CloseDiff(path))
            },
            onSaved = {
                onEvent(GitEvent.CloseDiff(path))
                onEvent(GitEvent.LoadDiff(path))
            },
        )
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 0.dp, bottom = 12.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onEvent(GitEvent.OpenFile(path)) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            showDiff = true
                            onEvent(GitEvent.LoadDiff(path))
                        },
                    )
                    .padding(end = 8.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (dir.isNotEmpty()) {
                    Text(
                        dir,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            ) {
                OutlinedButton(
                    onClick = { onEvent(GitEvent.ResolveConflict(path, GitConflictSide.OURS)) },
                    contentPadding = CompactButtonPadding,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.git_conflict_ours), maxLines = 1) }
                OutlinedButton(
                    onClick = { onEvent(GitEvent.ResolveConflict(path, GitConflictSide.THEIRS)) },
                    contentPadding = CompactButtonPadding,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.git_conflict_theirs), maxLines = 1) }
                FilledTonalButton(
                    onClick = { onEvent(GitEvent.MarkResolved(path)) },
                    contentPadding = CompactButtonPadding,
                    modifier = Modifier.weight(1.5f),
                ) {
                    Icon(XIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.git_op_resolved), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Full-screen diff for one file. The pencil icon switches to an editable view (save writes the file). */
@Composable
internal fun GitFileDiffDialog(
    name: String,
    dir: String,
    diff: GitFileDiffResult?,
    loading: Boolean,
    onClose: () -> Unit,
    onSaved: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val workFile = remember(diff?.workFilePath) { diff?.workFilePath?.let { File(it) } }
    val canEdit = diff != null && !diff.isBinary && !diff.isImage &&
        workFile != null && workFile.isFile && workFile.length() <= MAX_EDIT_BYTES

    var editing by rememberSaveable { mutableStateOf(false) }
    var original by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var added by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var fontSize by rememberSaveable { mutableStateOf(DIFF_FONT_DEFAULT) }
    // 0-based lines of the file at a change, so the editor can show just the changes (like the diff does).
    var anchors by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val dirty = editing && original != null && text != original

    // Tints come from the diff; refresh them whenever a (re)loaded diff arrives and nothing is unsaved.
    LaunchedEffect(diff) {
        if (diff != null && !dirty) {
            added = diff.rows.mapNotNull { r ->
                if (r.rightType == GitDiffLineType.ADDED) r.rightNumber?.minus(1) else null
            }.toSet()
            anchors = changeAnchors(diff.rows.map { it.rightNumber }, added)
        }
    }
    LaunchedEffect(editing, workFile) {
        if (editing && workFile != null && original == null) {
            val loaded = withContext(Dispatchers.IO) { runCatching { workFile.readText() }.getOrNull() }
            if (loaded == null) editing = false else { original = loaded; text = loaded }
        }
    }

    fun tryClose() { if (dirty) confirmDiscard = true else onClose() }
    fun save() {
        val file = workFile ?: return
        val toWrite = text
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { file.writeText(toWrite) }.isSuccess }
            if (ok) {
                original = toWrite
                onSaved()
            }
        }
    }

    Dialog(
        onDismissRequest = ::tryClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                ) {
                    IconButton(onClick = ::tryClose) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.git_diff_close))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (dir.isNotEmpty()) {
                            Text(
                                dir,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (dirty) {
                        IconButton(onClick = ::save) {
                            Icon(
                                XIcons.Save,
                                contentDescription = stringResource(R.string.action_save),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (canEdit) {
                        IconButton(onClick = {
                            when {
                                !editing -> editing = true
                                dirty -> confirmDiscard = true
                                else -> { editing = false; original = null }
                            }
                        }) {
                            Icon(
                                if (editing) XIcons.Diff else XIcons.Edit,
                                contentDescription = stringResource(R.string.git_diff_edit_file),
                            )
                        }
                    }
                }
                HorizontalDivider()
                when {
                    editing && original != null -> GitDiffHunkEditor(
                        text = text,
                        onTextChange = { text = it },
                        addedLines = added,
                        onAddedLinesChange = { added = it },
                        anchors = anchors,
                        fontSize = fontSize,
                        modifier = Modifier.weight(1f).pinchZoom { fontSize = clampDiffFont(fontSize * it) },
                    )
                    editing -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    diff != null -> GitDiffViewer(result = diff, modifier = Modifier.weight(1f))
                    loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    else -> Box(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }

    if (confirmDiscard) {
        GitConfirmDialog(
            title = stringResource(R.string.git_diff_unsaved_title),
            text = stringResource(R.string.git_diff_unsaved_message),
            confirmLabel = stringResource(R.string.action_discard),
            danger = true,
            onConfirm = {
                confirmDiscard = false
                editing = false
                original = null
                onClose()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

private const val MAX_EDIT_BYTES = 400_000L

/**
 * Lines of the new file where something changed: the added ones, plus the lines either side of a row that only
 * has an old side (a deletion), so a pure removal still gets a place in the hunk view.
 * [rightNumbers] is each diff row's 1-based new-file line number, null for a removed row.
 */
internal fun changeAnchors(rightNumbers: List<Int?>, added: Set<Int>): Set<Int> {
    val out = HashSet<Int>(added)
    var nextRight: Int? = null
    val next = arrayOfNulls<Int>(rightNumbers.size)
    for (i in rightNumbers.indices.reversed()) {
        next[i] = nextRight
        rightNumbers[i]?.let { nextRight = it }
    }
    var prevRight: Int? = null
    for (i in rightNumbers.indices) {
        val n = rightNumbers[i]
        if (n == null) {
            prevRight?.let { out.add(it - 1) }
            next[i]?.let { out.add(it - 1) }
        } else {
            prevRight = n
        }
    }
    return out
}
