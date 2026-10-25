package com.invictus.kodex.feature.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.core.git.model.GitCommitFile
import com.invictus.kodex.core.git.model.GitCommitFileChange
import com.invictus.kodex.core.git.model.GitCommitSummary
import com.invictus.kodex.core.git.model.GitDiffLineType
import com.invictus.kodex.core.git.model.GitFileDiffResult
import com.invictus.kodex.ui.icons.XIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val detailTimeFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())

/**
 * Full-screen commit detail: message/author header, changed-file list and (after tapping a file)
 * that file's diff. The old dialog actions now live in a bottom bar: Cherry-pick on the far left,
 * Cancel + Reset here on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitCommitDetailScreen(
    commit: GitCommitSummary,
    files: List<GitCommitFile>,
    filesLoading: Boolean,
    selectedFile: GitCommitFile?,
    fileDiff: GitFileDiffResult?,
    diffLoading: Boolean,
    totalAdded: Int? = null,
    totalRemoved: Int? = null,
    picking: Boolean,
    onOpenFile: (GitCommitFile) -> Unit,
    onCloseFile: () -> Unit,
    onCherryPick: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    aiExplain: (@Composable () -> Unit)? = null,   // M20
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        selectedFile?.path ?: commit.shortId,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selectedFile != null) onCloseFile() else onCancel() }) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    // Open file -> that file's +/- (from its diff rows), otherwise the whole commit's.
                    if (selectedFile != null) {
                        fileDiff?.let { d ->
                            LineStats(
                                added = d.rows.count { it.rightType == GitDiffLineType.ADDED },
                                removed = d.rows.count { it.leftType == GitDiffLineType.REMOVED },
                            )
                        }
                    } else if (totalAdded != null && totalRemoved != null) {
                        LineStats(totalAdded, totalRemoved)
                    }
                    aiExplain?.invoke()   // M20
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onCherryPick, enabled = !picking) {
                            Text(stringResource(R.string.git_cherry_pick))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        TextButton(onClick = onReset) {
                            Icon(XIcons.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.git_reset_here))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (selectedFile != null) {
                when {
                    diffLoading || fileDiff == null ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    else -> GitDiffViewer(result = fileDiff, modifier = Modifier.fillMaxSize())
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "header") { CommitHeader(commit) }
                    item(key = "files_title") {
                        Text(
                            stringResource(R.string.git_commit_changed_files, files.size),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    if (filesLoading) {
                        item(key = "files_loading") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    } else if (files.isEmpty()) {
                        item(key = "files_empty") {
                            Text(
                                stringResource(R.string.git_commit_no_files),
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(files, key = { "${it.change}:${it.path}" }) { f ->
                            FileRow(f, onClick = { onOpenFile(f) })
                        }
                    }
                }
            }
        }
    }
}

private val AddedGreen = Color(0xFF3FB950)

/** "+120 −45" chip shown in the top bar, left of the AI button. */
@Composable
private fun LineStats(added: Int, removed: Int) {
    Row(
        Modifier.padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "+$added",
            color = AddedGreen,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
        )
        Text(
            "−$removed",
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun CommitHeader(c: GitCommitSummary) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(c.message.trim(), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(6.dp))
        Text(
            "${c.author} · ${detailTimeFormat.format(Date(c.timeMs))}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            c.id,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
private fun FileRow(f: GitCommitFile, onClick: () -> Unit) {
    val (letter, color) = when (f.change) {
        GitCommitFileChange.ADDED -> "A" to MaterialTheme.colorScheme.primary
        GitCommitFileChange.DELETED -> "D" to MaterialTheme.colorScheme.error
        GitCommitFileChange.RENAMED -> "R" to MaterialTheme.colorScheme.tertiary
        GitCommitFileChange.COPIED -> "C" to MaterialTheme.colorScheme.tertiary
        GitCommitFileChange.MODIFIED -> "M" to MaterialTheme.colorScheme.secondary
    }
    val name = f.path.substringAfterLast('/')
    val dir = f.path.substringBeforeLast('/', "")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(letter, color = color, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            val sub = buildString {
                if (f.oldPath != null) append(f.oldPath).append(" → ")
                if (dir.isNotEmpty()) append(dir)
            }
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
