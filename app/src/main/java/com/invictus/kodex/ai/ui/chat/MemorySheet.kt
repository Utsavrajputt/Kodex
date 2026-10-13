package com.invictus.kodex.ai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.ai.agent.MemoryDocs
import com.invictus.kodex.ai.agent.ProjectMemory
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.launch

/**
 * "Memory & instructions" editor for one scope ([docs]): a single project (opened from the chat's top bar) or the
 * universal one (opened from AI settings). Two plain Markdown documents kept in the app's own storage: the memory
 * the AI keeps on its own and the rules the user wants it to follow.
 * Unsaved edits are written when the sheet is dismissed or the tab is switched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemorySheet(docs: MemoryDocs, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf(ProjectMemory.Doc.MEMORY) }
    var text by remember { mutableStateOf("") }
    var onDisk by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var justSaved by remember { mutableStateOf(false) }
    val dirty = loaded && text != onDisk

    LaunchedEffect(doc) {
        loaded = false
        justSaved = false
        val current = docs.read(doc)
        text = current
        onDisk = current
        loaded = true
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (dirty) docs.writeInBackground(doc, text)
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(XIcons.AutoAwesome, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp)) }
                Text(
                    stringResource(if (docs.isGlobal) R.string.ai_memory_title_global else R.string.ai_memory_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            val docs = listOf(
                ProjectMemory.Doc.MEMORY to R.string.ai_memory_tab_memory,
                ProjectMemory.Doc.INSTRUCTIONS to R.string.ai_memory_tab_instructions,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                docs.forEachIndexed { index, (d, label) ->
                    SegmentedButton(
                        selected = doc == d,
                        onClick = {
                            if (doc != d) {
                                if (dirty) docs.writeInBackground(doc, text)
                                doc = d
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, docs.size),
                    ) { Text(stringResource(label)) }
                }
            }

            Text(
                stringResource(
                    when {
                        docs.isGlobal && doc == ProjectMemory.Doc.MEMORY -> R.string.ai_memory_desc_memory_global
                        docs.isGlobal -> R.string.ai_memory_desc_instructions_global
                        doc == ProjectMemory.Doc.MEMORY -> R.string.ai_memory_desc_memory
                        else -> R.string.ai_memory_desc_instructions
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )

            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(docs.limit(doc)); justSaved = false },
                enabled = loaded,
                minLines = 8,
                maxLines = 12,
                placeholder = { Text(stringResource(R.string.ai_memory_empty)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ai_memory_chars, text.length),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (doc == ProjectMemory.Doc.MEMORY && text.isNotBlank()) {
                    TextButton(onClick = { text = ""; justSaved = false }) { Text(stringResource(R.string.ai_memory_clear)) }
                }
                Button(
                    enabled = dirty,
                    onClick = {
                        scope.launch {
                            if (docs.write(doc, text)) {
                                onDisk = text
                                justSaved = true
                            }
                        }
                    },
                ) { Text(stringResource(if (justSaved && !dirty) R.string.ai_memory_saved else R.string.ai_memory_save)) }
            }
        }
    }
}
