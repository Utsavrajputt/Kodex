@file:OptIn(ExperimentalMaterial3Api::class)

package com.invictus.kodex.ai.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.kodex.ai.agent.EditorIo
import com.invictus.kodex.feature.editor.EditorHandle
import com.invictus.kodex.feature.editor.EditorViewModel
import com.invictus.kodex.ui.icons.XIcons
import java.io.File

/**
 * The editor's AI panel, now on the same agent engine as the workspace screen: the open file and selection
 * ride along with every message, the agent can still read/search/edit the rest of the project, and open
 * tabs are read and written through their buffers so unsaved text is never clobbered.
 */
@Composable
fun EditorAiPanel(vm: AgentChatViewModel, editor: EditorViewModel, handle: EditorHandle, onClose: () -> Unit) {
    val ui by editor.uiState.collectAsState()
    val activePath = ui.activePath

    LaunchedEffect(vm, editor) {
        vm.bindIo(EditorIo(editor))
        vm.refreshIndex()
    }
    DisposableEffect(vm, editor, handle) {
        vm.contextProvider = provider@{
            val path = editor.uiState.value.activePath ?: return@provider null
            val text = editor.buffer(path)?.takeIf { !it.isPaged }?.content?.toString()
            val e = handle.editor
            val selection = if (text != null && e != null) {
                val l = e.cursor.left.coerceIn(0, text.length)
                val r = e.cursor.right.coerceIn(0, text.length)
                if (l != r) text.substring(minOf(l, r), maxOf(l, r)) else null
            } else null
            AgentChatViewModel.EditorContext(path, text, selection)
        }
        onDispose { vm.contextProvider = null }
    }

    val relPath = activePath?.let { runCatching { File(it).relativeTo(File(vm.projectPath)).path }.getOrNull() }
        ?.takeIf { !it.startsWith("..") }
    val selectionLen = handle.editor?.let { e ->
        val l = e.cursor.left
        val r = e.cursor.right
        if (l != r) kotlin.math.abs(r - l) else null
    }
    var editInstruction by remember { mutableStateOf<String?>(null) }

    AgentChatContent(
        vm = vm,
        modifier = Modifier.fillMaxSize(),
        applyPathHint = relPath,
        title = {
            Text(
                relPath?.let { "AI · ${it.substringAfterLast('/')}" } ?: "AI Assistant",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = { IconButton(onClick = onClose) { Icon(XIcons.Close, contentDescription = "Close AI panel") } },
        aboveInput = {
            if (selectionLen != null) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Selection: $selectionLen chars", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editInstruction = "" }) { Text("Edit with AI") }
                    }
                }
            }
        },
    )

    editInstruction?.let { draft ->
        AlertDialog(
            onDismissRequest = { editInstruction = null },
            title = { Text("Edit selection with AI") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { editInstruction = it },
                    label = { Text("What should change?") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        vm.send("Edit the selected code in the open file: ${draft.trim()}", emptyList())
                        editInstruction = null
                    },
                ) { Text("Edit") }
            },
            dismissButton = { TextButton(onClick = { editInstruction = null }) { Text("Cancel") } },
        )
    }
}
