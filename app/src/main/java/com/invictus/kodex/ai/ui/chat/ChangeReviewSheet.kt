@file:OptIn(ExperimentalMaterial3Api::class)

package com.invictus.kodex.ai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.kodex.ai.agent.ApprovedChange
import com.invictus.kodex.ai.agent.ChangeKind
import com.invictus.kodex.ai.agent.FileChange
import com.invictus.kodex.ai.diff.DiffEngine
import com.invictus.kodex.ai.diff.DiffHunk
import com.invictus.kodex.ai.diff.DiffLine
import com.invictus.kodex.ai.diff.LineType
import com.invictus.kodex.ui.icons.XIcons
import androidx.compose.ui.res.stringResource
import com.invictus.kodex.R

private const val MAX_LINES_SHOWN = 400

private class FileReview(val change: FileChange) {
    val hunks: List<DiffHunk> = when (change.kind) {
        ChangeKind.EDIT -> DiffEngine.diff(change.baseText.orEmpty(), change.newText.orEmpty())
        ChangeKind.CREATE -> DiffEngine.diff("", change.newText.orEmpty())
        ChangeKind.DELETE -> emptyList()
    }
    var included by mutableStateOf(true)
    var expanded by mutableStateOf(false)
    val hunkOn = mutableStateListOf<Boolean>().also { l -> repeat(hunks.size) { l.add(true) } }

    val added = hunks.sumOf { h -> h.lines.count { it.type == LineType.ADD } }
    val removed = hunks.sumOf { h -> h.lines.count { it.type == LineType.REMOVE } }

    fun approved(): ApprovedChange? {
        if (!included) return null
        return when (change.kind) {
            ChangeKind.CREATE -> ApprovedChange(change.path, ChangeKind.CREATE, null, change.newText)
            ChangeKind.DELETE -> ApprovedChange(change.path, ChangeKind.DELETE, change.baseText, null)
            ChangeKind.EDIT -> {
                if (hunkOn.none { it }) return null
                val base = change.baseText.orEmpty()
                val final = DiffEngine.applySelected(base, hunks.mapIndexed { i, h -> h.copy(included = hunkOn[i]) })
                if (final == base) null else ApprovedChange(change.path, ChangeKind.EDIT, base, final)
            }
        }
    }
}

private sealed interface Row2 {
    data class Hunk(val file: FileReview, val index: Int) : Row2
    data class Line(val line: DiffLine) : Row2
    data class Note(val text: String) : Row2
}

/**
 * Review every file the AI changed in one place. Per-file and per-hunk checkboxes; nothing is written
 * until "Apply selected". Unchecked files/hunks are skipped.
 */
@Composable
fun ChangeReviewSheet(
    changes: List<FileChange>,
    applyError: String?,
    onApply: (List<ApprovedChange>) -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val reviews = remember(changes) { changes.map { FileReview(it) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(if (changes.size == 1) stringResource(R.string.ai_review_title_one) else stringResource(R.string.ai_review_title_many, changes.size), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.ai_review_nothing_is_written_until_you),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            applyError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            LazyColumn(Modifier.heightIn(max = 480.dp).padding(vertical = 8.dp)) {
                reviews.forEach { r ->
                    item(key = "f:${r.change.path}") { FileHeader(r) }
                    if (r.expanded) {
                        val rows = rowsFor(r)
                        items(rows.size) { n ->
                            when (val row = rows[n]) {
                                is Row2.Hunk -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp)) {
                                    Checkbox(checked = r.hunkOn[row.index], onCheckedChange = { r.hunkOn[row.index] = it })
                                    Text(stringResource(R.string.ai_diff_hunk, row.index + 1), style = MaterialTheme.typography.labelMedium)
                                }
                                is Row2.Line -> DiffLineRow(row.line)
                                is Row2.Note -> Text(row.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.ai_review_discard_all)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onApply(reviews.mapNotNull { it.approved() }) }, enabled = reviews.any { it.included }) {
                    Text(stringResource(R.string.ai_review_apply_selected))
                }
            }
        }
    }
}

private fun rowsFor(r: FileReview): List<Row2> {
    if (r.change.kind == ChangeKind.DELETE) return listOf(Row2.Note("This file will be deleted."))
    val out = ArrayList<Row2>()
    var shown = 0
    for ((i, h) in r.hunks.withIndex()) {
        if (r.change.kind == ChangeKind.EDIT) out += Row2.Hunk(r, i)
        for (l in h.lines) {
            if (shown >= MAX_LINES_SHOWN) { out += Row2.Note("… diff preview truncated; the full change is applied."); return out }
            out += Row2.Line(l)
            shown++
        }
    }
    return out
}

@Composable
private fun FileHeader(r: FileReview) {
    Row(
        Modifier.fillMaxWidth().clickable { r.expanded = !r.expanded }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = r.included, onCheckedChange = { r.included = it })
        Column(Modifier.weight(1f)) {
            Text(r.change.path, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val label = when (r.change.kind) {
                ChangeKind.EDIT -> "Modified  +${r.added} −${r.removed}"
                ChangeKind.CREATE -> "New file  +${r.added}"
                ChangeKind.DELETE -> "Deleted"
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (r.expanded) XIcons.KeyboardArrowUp else XIcons.KeyboardArrowDown, contentDescription = null)
    }
}

@Composable
private fun DiffLineRow(l: DiffLine) {
    val (glyph, color) = when (l.type) {
        LineType.ADD -> "+" to MaterialTheme.colorScheme.primaryContainer
        LineType.REMOVE -> "−" to MaterialTheme.colorScheme.errorContainer
        LineType.CONTEXT -> " " to MaterialTheme.colorScheme.surface
    }
    Text(
        "$glyph ${l.text}",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().background(color).padding(horizontal = 8.dp, vertical = 1.dp),
    )
}
