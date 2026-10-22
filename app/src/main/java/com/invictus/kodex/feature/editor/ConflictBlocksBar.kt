package com.invictus.kodex.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.core.git.ConflictParser
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * M10 block-level conflicts, v1 (plan option (a)): a slim bar above the editor lists the
 * conflict blocks in the active file and jumps between them; "Resolve" opens a bottom
 * sheet with Accept ours / theirs / both. Editing a block by hand is always possible;
 * staging stays the drawer's job.
 */
@Composable
internal fun ConflictBlocksBar(
    blocks: List<ConflictParser.Block>,
    handle: EditorHandle,
    onEdited: () -> Unit,
) {
    if (blocks.isEmpty()) return
    var current by rememberSaveable { mutableIntStateOf(0) }
    current = current.coerceIn(0, blocks.lastIndex)
    var sheetBlock by rememberSaveable(blocks.size) { mutableIntStateOf(-1) }

    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            Text(
                stringResource(R.string.conflict_blocks_count, blocks.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = {
                current = (current - 1 + blocks.size) % blocks.size
                jumpToBlock(handle.editor, blocks[current])
            }) { Text("‹") }
            Text(
                "${'$'}{current + 1}/${'$'}{blocks.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = {
                current = (current + 1) % blocks.size
                jumpToBlock(handle.editor, blocks[current])
            }) { Text("›") }
            TextButton(onClick = { sheetBlock = current }) {
                Text(stringResource(R.string.conflict_resolve))
            }
        }
    }

    if (sheetBlock in blocks.indices) {
        ConflictBlockSheet(
            block = blocks[sheetBlock],
            onApply = { replacement ->
                applyConflictReplacement(handle.editor, blocks[sheetBlock], replacement)
                onEdited()
                sheetBlock = -1
            },
            onDismiss = { sheetBlock = -1 },
        )
    }
}

private fun jumpToBlock(editor: CodeEditor?, block: ConflictParser.Block) {
    runCatching { editor?.setSelection(block.startLine, 0) }
}

/** Replaces the marker region with the chosen side; fires ContentChangeEvent -> dirty. */
internal fun applyConflictChoice(
    editor: CodeEditor?,
    block: ConflictParser.Block,
    choice: ConflictParser.Choice,
) = applyConflictReplacement(editor, block, block.replacement(choice))

/**
 * Replaces the marker region with [repl] (a side, both, or the user's own edit), then scrolls to the
 * written code and flashes it so the person sees exactly what landed. Fires ContentChangeEvent -> dirty.
 */
internal fun applyConflictReplacement(
    editor: CodeEditor?,
    block: ConflictParser.Block,
    repl: String,
) {
    editor ?: return
    runCatching {
        val content = editor.text
        val start = content.getCharIndex(block.startLine, 0)
        val end = content.getCharIndex(
            block.endLine,
            content.getColumnCount(block.endLine),
        )
        content.delete(start, end)
        if (repl.isNotEmpty()) content.insert(block.startLine, 0, repl)
        val firstLine = block.startLine.coerceIn(0, content.lineCount - 1)
        editor.setSelection(firstLine, 0, true) // scroll there
        if (repl.isNotEmpty()) {
            val lastLine = (block.startLine + repl.count { it == '\n' }).coerceIn(firstLine, content.lineCount - 1)
            val lastCol = content.getColumnCount(lastLine)
            // After layout, so the scroll above has settled before the highlight starts.
            editor.post { editor.post { flashRegion(editor, firstLine, 0, lastLine, lastCol) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ConflictBlockSheet(
    block: ConflictParser.Block,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf(block.replacement(ConflictParser.Choice.BOTH)) }
    val onChoice = { choice: ConflictParser.Choice -> onApply(block.replacement(choice)) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.conflict_block_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.conflict_ours_label, block.labelOurs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                block.oursText.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.conflict_theirs_label, block.labelTheirs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                block.theirsText.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
            if (editing) {
                Text(
                    stringResource(R.string.conflict_result_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                OutlinedTextField(
                    value = result,
                    onValueChange = { result = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 3,
                    maxLines = 12,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { result = block.replacement(ConflictParser.Choice.OURS) }, label = { Text(stringResource(R.string.git_conflict_ours)) })
                    AssistChip(onClick = { result = block.replacement(ConflictParser.Choice.THEIRS) }, label = { Text(stringResource(R.string.git_conflict_theirs)) })
                    AssistChip(onClick = { result = block.replacement(ConflictParser.Choice.BOTH) }, label = { Text(stringResource(R.string.conflict_accept_both)) })
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { onApply(result) }) { Text(stringResource(R.string.conflict_apply)) }
                    TextButton(onClick = { editing = false }) { Text(stringResource(R.string.conflict_back)) }
                }
            } else {
                FlowRow(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { onChoice(ConflictParser.Choice.OURS) }) {
                        Text(stringResource(R.string.conflict_accept_ours))
                    }
                    Button(onClick = { onChoice(ConflictParser.Choice.THEIRS) }) {
                        Text(stringResource(R.string.conflict_accept_theirs))
                    }
                    TextButton(onClick = { onChoice(ConflictParser.Choice.BOTH) }) {
                        Text(stringResource(R.string.conflict_accept_both))
                    }
                    TextButton(onClick = { editing = true }) {
                        Text(stringResource(R.string.conflict_edit))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}
