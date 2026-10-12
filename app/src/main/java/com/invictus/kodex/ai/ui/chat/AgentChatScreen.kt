@file:OptIn(ExperimentalMaterial3Api::class)

package com.invictus.kodex.ai.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.kodex.ai.ui.chat.AgentChatViewModel.StepStatus
import com.invictus.kodex.ai.ui.chat.AgentChatViewModel.UiMessage
import com.invictus.kodex.ui.icons.XIcons
import java.text.DateFormat
import java.util.Date

private val MENTION_TAIL = Regex("(^|\\s)@(\\S*)$")

/** Full-screen workspace agent: top bar + the shared chat content. */
@Composable
fun AgentChatScreen(vm: AgentChatViewModel, projectName: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Agent")
                        Text(projectName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(XIcons.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        AgentChatContent(vm, Modifier.padding(padding).fillMaxSize().imePadding())
    }
}

/** The chat itself (messages, steps, change banners, input). Shared by the workspace screen and the editor panel. */
@Composable
fun AgentChatContent(
    vm: AgentChatViewModel,
    modifier: Modifier = Modifier,
    applyPathHint: String? = null,
    title: @Composable () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
    aboveInput: @Composable () -> Unit = {},
) {
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val changes by vm.changes.collectAsState()
    val undo by vm.lastApplied.collectAsState()
    val reviewOpen by vm.reviewOpen.collectAsState()
    val applyError by vm.applyError.collectAsState()
    val index by vm.fileIndex.collectAsState()
    val chats by vm.chats.collectAsState()

    var input by remember { mutableStateOf("") }
    val attached = remember { mutableStateListOf<String>() }
    var showHistory by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var applyDialog by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { i ->
            if (i is DragInteraction.Start) follow = false
            else if (i is DragInteraction.Stop || i is DragInteraction.Cancel) follow = !listState.canScrollForward
        }
    }
    LaunchedEffect(busy) { if (busy) follow = true }
    val lastLen = messages.lastOrNull()?.text?.length ?: 0
    val lastSteps = messages.lastOrNull()?.steps?.size ?: 0
    LaunchedEffect(messages.size, lastLen, lastSteps) {
        if (follow && messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
            listState.scrollBy(1_000_000f)
        }
    }

    val mention = remember(input) { MENTION_TAIL.find(input) }
    val suggestions = remember(mention?.groupValues?.get(2), index) {
        mention?.let { m ->
            val q = m.groupValues[2].lowercase()
            index.asSequence()
                .filter { q.isEmpty() || it.lowercase().contains(q) }
                .sortedBy { if (it.substringAfterLast('/').lowercase().startsWith(q)) 0 else 1 }
                .take(6).toList()
        }.orEmpty()
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { title() }
            if (changes.isNotEmpty()) {
                TextButton(onClick = { vm.openReview() }) { Text("Review ${changes.size}") }
            }
            IconButton(onClick = { vm.refreshChats(); showHistory = true }) { Icon(XIcons.Restore, contentDescription = "Chat history") }
            IconButton(onClick = { vm.newChat(); input = ""; attached.clear() }) { Icon(XIcons.Add, contentDescription = "New chat") }
            trailing()
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyState(onPick = { input = it })
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(messages, key = { it.id }) { m ->
                        MessageItem(
                            m,
                            isLast = m.id == messages.last().id,
                            busy = busy,
                            onRegenerate = { vm.regenerate() },
                            onEdit = {
                                vm.editFrom(m.id)?.let { (t, atts) ->
                                    input = t
                                    attached.clear()
                                    attached.addAll(atts)
                                }
                            },
                            onApplyCode = { _, code -> applyDialog = code },
                        )
                    }
                }
            }
        }

        error?.let { msg ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.errorHandled() }) { Icon(XIcons.Close, contentDescription = "Dismiss") }
                }
            }
        }
        if (changes.isNotEmpty() && !busy) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${changes.size} file${if (changes.size == 1) "" else "s"} changed by AI — not applied yet",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.discardChanges() }) { Text("Discard") }
                    Button(onClick = { vm.openReview() }) { Text("Review") }
                    Spacer(Modifier.size(8.dp))
                }
            }
        }
        undo?.let { u ->
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Applied ${u.entries.size} file${if (u.entries.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.undoLast() }) { Text("Undo") }
                    IconButton(onClick = { vm.dismissUndo() }) { Icon(XIcons.Close, contentDescription = "Dismiss") }
                }
            }
        }

        aboveInput()

        if (suggestions.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(suggestions) { path ->
                    AssistChip(
                        onClick = {
                            val m = mention ?: return@AssistChip
                            val at = m.range.last - m.groupValues[2].length
                            input = input.substring(0, at) + "@" + path + " "
                            if (path !in attached) attached.add(path)
                        },
                        label = { Text(path.substringAfterLast('/'), maxLines = 1) },
                    )
                }
            }
        }
        if (attached.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(attached.toList()) { path ->
                    InputChip(
                        selected = false,
                        onClick = { attached.remove(path) },
                        label = { Text(path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Icon(XIcons.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { vm.refreshIndex(); showPicker = true }) { Icon(XIcons.Add, contentDescription = "Attach files") }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Ask the agent…  @ to mention files") },
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                IconButton(onClick = { vm.stop() }) { Icon(XIcons.StopCircle, contentDescription = "Stop") }
            } else {
                IconButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        vm.send(input, attached.toList())
                        input = ""
                        attached.clear()
                    },
                ) { Icon(XIcons.Send, contentDescription = "Send") }
            }
        }
    }

    if (reviewOpen && changes.isNotEmpty()) {
        ChangeReviewSheet(
            changes = changes,
            applyError = applyError,
            onApply = { vm.applyApproved(it) },
            onDiscard = { vm.discardChanges() },
            onDismiss = { vm.closeReview() },
        )
    }
    if (showHistory) {
        ChatHistorySheet(
            chats = chats,
            onOpen = { vm.openChat(it); showHistory = false },
            onDelete = { vm.deleteChat(it) },
            onNew = { vm.newChat(); input = ""; attached.clear(); showHistory = false },
            onDismiss = { showHistory = false },
        )
    }
    if (showPicker) {
        FilePickerDialog(index, attached.toSet(), onToggle = { p -> if (!attached.remove(p)) attached.add(p) }, onDismiss = { showPicker = false })
    }
    applyDialog?.let { code ->
        var path by remember(code) {
            mutableStateOf(applyPathHint ?: messages.lastOrNull { it.attachments.isNotEmpty() }?.attachments?.last().orEmpty())
        }
        AlertDialog(
            onDismissRequest = { applyDialog = null },
            title = { Text("Apply to file") },
            text = {
                Column {
                    Text("Project-relative path. An existing file is replaced with this code; a new path creates a file. You review the diff before anything is written.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = path, onValueChange = { path = it }, singleLine = true, label = { Text("Path") }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = path.isNotBlank(), onClick = { vm.stageCodeBlock(path, code); applyDialog = null }) { Text("Review change") }
            },
            dismissButton = { TextButton(onClick = { applyDialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyState(onPick: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(XIcons.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        Text("Kodex AI Agent", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Ask about your project. I can search and read many files, and change several at once — you review every change before it's applied.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        listOf(
            "Explain how this project is structured",
            "Find likely bugs and list them with file and line",
            "Find unused code I can safely remove",
            "Write unit tests for @",
        ).forEach { s ->
            OutlinedButton(onClick = { onPick(s) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun MessageItem(
    m: UiMessage,
    isLast: Boolean,
    busy: Boolean,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onApplyCode: (String, String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    if (m.role == "user") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Surface(shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                SelectionContainer {
                    Text(
                        m.text,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            if (m.attachments.isNotEmpty()) {
                Text(
                    m.attachments.joinToString("  ") { "📎 " + it.substringAfterLast('/') },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (!busy) TextButton(onClick = onEdit) { Text("Edit", style = MaterialTheme.typography.labelMedium) }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            if (m.steps.isNotEmpty()) StepsBlock(m)
            if (m.text.isNotBlank()) {
                SelectionContainer { MarkdownContent(m.text, Modifier.padding(top = if (m.steps.isNotEmpty()) 8.dp else 0.dp), onApplyCode) }
            } else if (m.streaming && m.steps.none { it.status == StepStatus.RUNNING }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("  Thinking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!m.streaming && m.text.isNotBlank()) {
                Row {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(m.text)) }) { Icon(XIcons.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp)) }
                    if (isLast && !busy) {
                        IconButton(onClick = onRegenerate) { Icon(XIcons.Refresh, contentDescription = "Regenerate", modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepsBlock(m: UiMessage) {
    var expanded by remember(m.id) { mutableStateOf(false) }
    val collapsible = !m.streaming && m.steps.size > 3
    val showAll = !collapsible || expanded
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
        Column(Modifier.fillMaxWidth().padding(10.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (collapsible) {
                Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                    Text("${m.steps.size} steps", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Icon(if (expanded) XIcons.KeyboardArrowUp else XIcons.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            if (showAll) m.steps.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (s.status) {
                        StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        StepStatus.DONE -> Icon(XIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        StepStatus.FAILED -> Icon(XIcons.ErrorCircle, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    }
                    Text("  " + s.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ChatHistorySheet(
    chats: List<com.invictus.kodex.ai.agent.ChatMeta>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chats", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button(onClick = onNew) { Text("New chat") }
            }
            if (chats.isEmpty()) Text("No saved chats yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            LazyColumn(Modifier.height(360.dp)) {
                items(chats, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title.ifBlank { "Chat" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.updatedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDelete(c.id) }) { Icon(XIcons.Delete, contentDescription = "Delete chat") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilePickerDialog(index: List<String>, selected: Set<String>, onToggle: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, index) {
        val q = query.trim().lowercase()
        index.asSequence().filter { q.isEmpty() || it.lowercase().contains(q) }.take(100).toList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach files") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Filter") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.height(320.dp).padding(top = 8.dp)) {
                    items(filtered) { p ->
                        Row(Modifier.fillMaxWidth().clickable { onToggle(p) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(p, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (p in selected) Icon(XIcons.Check, contentDescription = "Attached", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
