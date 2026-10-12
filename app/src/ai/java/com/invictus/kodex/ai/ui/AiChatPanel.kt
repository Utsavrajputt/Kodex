package com.invictus.kodex.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.invictus.kodex.ai.diff.DiffEngine
import com.invictus.kodex.feature.editor.EditorHandle
import com.invictus.kodex.feature.editor.EditorViewModel
import com.invictus.kodex.ui.icons.XIcons
import java.io.File

/**
 * Editor-side AI panel (plan 9.4) — fills the bottom half of the EditorScreen AI split.
 * Markdown-lite chat (code fences as copyable blocks); M19 edit flow via the selection bar.
 */
@Composable
fun AiChatPanel(vm: AiChatViewModel, editor: EditorViewModel, handle: EditorHandle, onClose: () -> Unit) {
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val pending by vm.pending.collectAsState()
    val attachLabel by vm.attachLabel.collectAsState()
    val ui by editor.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    var editInstruction by remember { mutableStateOf<String?>(null) }
    var applyError by remember { mutableStateOf<String?>(null) }
    var explainDiff by remember { mutableStateOf(false) }

    val activePath = ui.activePath
    val buffer = activePath?.let { editor.buffer(it) }

    // Live selection from the Sora editor (same handle the top bar uses).
    val selection: Pair<Int, Int>? = handle.editor?.let { e ->
        // Sora's Cursor.left/right are already char indices (left()/right() return CharPosition).
        val l = e.cursor.left
        val r = e.cursor.right
        if (l != r) minOf(l, r) to maxOf(l, r) else null
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AI Assistant", style = MaterialTheme.typography.titleSmall)
            attachLabel?.let {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            if (busy) TextButton(onClick = { vm.cancel() }) { Text("Stop") }
            IconButton(onClick = onClose) { Icon(XIcons.Close, contentDescription = "Close AI panel") }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(messages.size) { i -> ChatBubble(messages[i]) { code -> clipboard.setText(AnnotatedString(code)) } }
            error?.let { e ->
                item {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.errorHandled() }) { Text("Dismiss") }
                    }
                }
            }
        }

        selection?.let { (selStart, selEnd) ->
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Selection: ${selEnd - selStart} chars", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { editInstruction = "" }) { Text("Edit with AI") }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Ask about the code…") },
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.padding(4.dp))
            Button(
                onClick = {
                    vm.send(input, activePath?.let { File(it).name }, buffer?.content?.toString())
                    input = ""
                },
                enabled = !busy && input.isNotBlank(),
            ) { Icon(XIcons.Send, contentDescription = "Send") }
        }
    }

    editInstruction?.let { draft ->
        AlertDialog(
            onDismissRequest = { editInstruction = null },
            title = { Text("Edit selection with AI") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { editInstruction = it },
                    placeholder = { Text("e.g. add null checks, rename x to y, simplify…") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = !busy && draft.isNotBlank() && activePath != null,
                    onClick = {
                        val path = activePath!!
                        val text = buffer?.content?.toString() ?: File(path).takeIf { it.exists() }?.readText()
                        if (text != null && selection != null) {
                            applyError = null
                            vm.proposeEdit(path, text, selection.first, selection.second, draft)
                        } else {
                            applyError = "Open the file in the editor first"
                        }
                        editInstruction = null
                    },
                ) { Text("Generate edit") }
            },
            dismissButton = { TextButton(onClick = { editInstruction = null }) { Text("Cancel") } },
        )
    }

    pending?.let { p ->
        val hunks = remember(p) { DiffEngine.diff(p.baseText, p.newText) }
        DiffReviewSheet(
            explanation = p.explanation,
            fileName = File(p.filePath).name,
            hunks = hunks,
            applyError = applyError,
            onApply = { selected ->
                val current = buffer?.content?.toString()
                    ?: File(p.filePath).takeIf { it.exists() }?.readText()
                    ?: return@DiffReviewSheet
                val newText = DiffEngine.applySelected(p.baseText, selected)
                val err = vm.applyPending(newText, current)
                if (err != null) {
                    applyError = err
                } else {
                    applyError = null
                    val b = buffer
                    if (b != null) {
                        // Single Content.replace over the whole range = one undo step (plan 9.5).
                        val c = b.content
                        c.replace(0, 0, c.lineCount - 1, c.getColumnCount(c.lineCount - 1), newText)
                    } else {
                        File(p.filePath).writeText(newText)
                    }
                }
            },
            onReject = { vm.rejectPending(); applyError = null },
            onRegenerate = { extra -> vm.regenerate(extra); applyError = null },
            onDismiss = { vm.rejectPending(); applyError = null },
            onExplain = { explainDiff = true },
        )
    }

    // M20: explain the proposed diff straight from the review sheet
    if (explainDiff) {
        val p = pending
        if (p == null) {
            explainDiff = false
        } else {
            val context = androidx.compose.ui.platform.LocalContext.current
            com.invictus.kodex.ai.ui.AiStreamSheet(
                title = "Explain changes",
                runtime = remember {
                    com.invictus.kodex.ai.AiRuntime(context.applicationContext as android.app.Application)
                },
                onClose = { explainDiff = false },
            ) {
                val (provider, model, key) = resolve()
                com.invictus.kodex.ai.tasks.ExplainDiffTask(provider, key, model)
                    .stream(DiffEngine.toUnifiedText(DiffEngine.diff(p.baseText, p.newText)))
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: AiChatViewModel.ChatMessage, onCopyCode: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        if (msg.role == "user") {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.align(Alignment.End)) {
                Text(msg.text, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val parts = msg.text.split("```")
            parts.forEachIndexed { i, part ->
                if (i % 2 == 0) {
                    if (part.isNotBlank()) Text(part.trim(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
                } else {
                    val code = part.substringAfter('\n')
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            Row {
                                Text("code", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                Text("Copy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { onCopyCode(code) }.padding(4.dp))
                            }
                            Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (msg.streaming) Text("▍", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
