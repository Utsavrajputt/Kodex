@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.invictus.kodex.ai.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.invictus.kodex.ai.provider.ErrorAction
import com.invictus.kodex.ai.ui.chat.AgentChatViewModel.StepStatus
import com.invictus.kodex.ai.ui.chat.AgentChatViewModel.UiMessage
import com.invictus.kodex.ui.icons.XIcons
import java.text.DateFormat
import java.util.Date

private val MENTION_TAIL = Regex("(^|\\s)@(\\S*)$")

/** Full-screen workspace agent: top bar + the shared chat content. */
@Composable
fun AgentChatScreen(vm: AgentChatViewModel, projectName: String, onBack: () -> Unit, onOpenSettings: (() -> Unit)? = null) {
    var showHistory by remember { mutableStateOf(false) }
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
                actions = {
                    IconButton(onClick = { vm.refreshChats(); showHistory = true }) {
                        Icon(XIcons.Restore, contentDescription = "Chat history")
                    }
                    IconButton(onClick = { vm.newChat() }) { Icon(XIcons.Add, contentDescription = "New chat") }
                },
            )
        },
    ) { padding ->
        AgentChatContent(
            vm, Modifier.padding(padding).fillMaxSize().imePadding(),
            onOpenSettings = onOpenSettings,
            historyOpen = showHistory, onHistoryOpenChange = { showHistory = it },
            headerActions = false,
        )
    }
}

/** The chat itself (messages, steps, status cards, input). Shared by the workspace screen and the editor panel. */
@Composable
fun AgentChatContent(
    vm: AgentChatViewModel,
    modifier: Modifier = Modifier,
    applyPathHint: String? = null,
    onOpenSettings: (() -> Unit)? = null,
    /** Hoisted history-sheet state (the full screen opens it from its top bar); null = the chat owns it. */
    historyOpen: Boolean? = null,
    onHistoryOpenChange: ((Boolean) -> Unit)? = null,
    /** History / New chat buttons in the chat's own header row (off when the host puts them in its top bar). */
    headerActions: Boolean = true,
    title: @Composable () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
    aboveInput: @Composable () -> Unit = {},
) {
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val failure by vm.failure.collectAsState()
    val rateLimit by vm.rateLimit.collectAsState()
    val status by vm.status.collectAsState()
    val contextUsed by vm.contextUsed.collectAsState()
    val changes by vm.changes.collectAsState()
    val undo by vm.lastApplied.collectAsState()
    val reviewOpen by vm.reviewOpen.collectAsState()
    val applyError by vm.applyError.collectAsState()
    val index by vm.fileIndex.collectAsState()
    val chats by vm.chats.collectAsState()

    var input by remember { mutableStateOf("") }
    val attached = remember { mutableStateListOf<String>() }
    var ownHistory by remember { mutableStateOf(false) }
    val showHistory = historyOpen ?: ownHistory
    val setHistory: (Boolean) -> Unit = { open ->
        if (onHistoryOpenChange != null) onHistoryOpenChange(open) else ownHistory = open
    }
    val newChatEpoch by vm.newChatEpoch.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var applyDialog by remember { mutableStateOf<String?>(null) }

    // A new chat (from anywhere, e.g. the host's top bar) starts with an empty draft.
    LaunchedEffect(newChatEpoch) {
        if (newChatEpoch > 0) { input = ""; attached.clear() }
    }

    // Provider, model, key and consent are edited in Settings; pick the change up when we come back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStatus() }

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
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { title() }
            if (changes.isNotEmpty()) {
                TextButton(onClick = { vm.openReview() }) { Text("Review ${changes.size}") }
            }
            if (headerActions) {
                IconButton(onClick = { vm.refreshChats(); setHistory(true) }) { Icon(XIcons.Restore, contentDescription = "Chat history") }
                IconButton(onClick = { vm.newChat() }) { Icon(XIcons.Add, contentDescription = "New chat") }
            }
            trailing()
        }
        ContextMeter(contextUsed, status.contextTokens)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                if (status.loaded && !status.ready) SetupCard(status, onOpenSettings)
                else EmptyState(status, onPick = { input = it })
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
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

        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val rl = rateLimit
            if (rl != null) {
                RateLimitCard(
                    info = rl,
                    onRetryNow = { vm.retryNow() },
                    onSwitchModel = { vm.refreshStatus(); showModels = true },
                    onStop = { vm.stop() },
                )
            } else {
                failure?.let { f ->
                    AiErrorCard(
                        info = f,
                        canOpenSettings = onOpenSettings != null,
                        onDismiss = { vm.errorHandled() },
                        onAction = { a ->
                            when (a) {
                                ErrorAction.RETRY -> { vm.errorHandled(); vm.regenerate() }
                                ErrorAction.SWITCH_MODEL -> { vm.refreshStatus(); showModels = true }
                                ErrorAction.OPEN_SETTINGS -> onOpenSettings?.invoke()
                            }
                        },
                    )
                }
                error?.let { msg -> SimpleErrorCard(msg, onDismiss = { vm.errorHandled() }) }
            }
            if (changes.isNotEmpty() && !busy) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(XIcons.Diff, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(
                                "${changes.size} file${if (changes.size == 1) "" else "s"} changed by AI",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                "Nothing is written until you approve",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            )
                        }
                        TextButton(onClick = { vm.discardChanges() }) { Text("Discard") }
                        Button(onClick = { vm.openReview() }) { Text("Review") }
                    }
                }
            }
            undo?.let { u ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(XIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(20.dp))
                        Text(
                            "Applied ${u.entries.size} file${if (u.entries.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        TextButton(onClick = { vm.undoLast() }) { Text("Undo") }
                        IconButton(onClick = { vm.dismissUndo() }) { Icon(XIcons.Close, contentDescription = "Dismiss") }
                    }
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
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            // Like Claude: the text on top, then one row with attach on the left and model + send on the right.
            Column {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Ask the agent…  @ to mention files") },
                    maxLines = 6,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.refreshIndex(); showPicker = true }) {
                        Icon(XIcons.Add, contentDescription = "Attach files")
                    }
                    Spacer(Modifier.weight(1f))
                    if (status.loaded) {
                        ModelChip(status.modelLabel, onClick = { vm.refreshStatus(); showModels = true })
                        Spacer(Modifier.width(6.dp))
                    }
                    if (busy) {
                        FilledIconButton(onClick = { vm.stop() }) { Icon(XIcons.StopCircle, contentDescription = "Stop") }
                    } else {
                        FilledIconButton(
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
            onOpen = { vm.openChat(it); setHistory(false) },
            onDelete = { vm.deleteChat(it) },
            onNew = { vm.newChat(); setHistory(false) },
            onDismiss = { setHistory(false) },
        )
    }
    if (showModels) {
        ModelSwitchSheet(
            status = status,
            onPick = { providerId, model -> vm.switchModel(providerId, model) },
            onOpenSettings = onOpenSettings,
            onDismiss = { showModels = false },
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
private fun HeroBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(XIcons.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun CapabilityPill(icon: ImageVector, text: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun EmptyState(status: AgentChatViewModel.AiStatus, onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HeroBadge()
        Text("Kodex AI Agent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Ask about your project, or tell me what to change.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CapabilityPill(XIcons.Search, "Searches your project")
            CapabilityPill(XIcons.Edit, "Edits many files")
            CapabilityPill(XIcons.CheckCircle, "You approve every change")
        }
        Spacer(Modifier.height(16.dp))
        listOf(
            Triple(XIcons.AccountTree, "Explain the structure", "Explain how this project is structured"),
            Triple(XIcons.Search, "Hunt for bugs", "Find likely bugs and list them with file and line"),
            Triple(XIcons.DeleteSweep, "Find dead code", "Find unused code I can safely remove"),
            Triple(XIcons.Code, "Write tests", "Write unit tests for @"),
        ).forEach { (icon, label, prompt) ->
            Surface(
                onClick = { onPick(prompt) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp)) }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        Text(prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (status.loaded) {
            Text(
                "Using ${status.modelLabel} · ${status.providerName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/** First-run card: what is still missing before the agent can answer, with a way to fix it. */
@Composable
private fun SetupCard(status: AgentChatViewModel.AiStatus, onOpenSettings: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HeroBadge()
        Text("Set up AI to get started", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Two quick things and the agent is ready. Your key stays encrypted on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        SetupRow(status.hasKey, "API key for ${status.providerName}", if (status.hasKey) "Saved" else "Not added yet")
        SetupRow(status.consent, "Allow AI in projects", if (status.consent) "On" else "Off — the agent can't read your files")
        if (onOpenSettings != null) {
            Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 16.dp)) { Text("Open AI settings") }
        } else {
            Text("Open Settings → AI Assistant to finish.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun SetupRow(done: Boolean, title: String, detail: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (done) XIcons.CheckCircle else XIcons.ErrorCircle,
                contentDescription = null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
            Surface(shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                SelectionContainer {
                    Text(
                        m.text,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            if (m.attachments.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    m.attachments.forEach { AiTag(it.substringAfterLast('/')) }
                }
            }
            if (!busy) TextButton(onClick = onEdit) { Text("Edit", style = MaterialTheme.typography.labelMedium) }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(XIcons.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(13.dp)) }
                Text("Kodex AI", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
            }
            if (m.steps.isNotEmpty()) StepsBlock(m)
            if (m.text.isNotBlank()) {
                SelectionContainer { MarkdownContent(m.text, Modifier.padding(top = if (m.steps.isNotEmpty()) 8.dp else 0.dp), onApplyCode) }
            } else if (m.streaming && m.steps.none { it.status == StepStatus.RUNNING }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
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

private fun stepIcon(label: String): ImageVector = when {
    label.startsWith("Reading") -> XIcons.Article
    label.startsWith("Searching") -> XIcons.Search
    label.startsWith("Listing") -> XIcons.Folder
    label.startsWith("Editing") -> XIcons.Edit
    label.startsWith("Creating") -> XIcons.NoteAdd
    label.startsWith("Deleting") -> XIcons.Delete
    else -> XIcons.Bolt
}

@Composable
private fun StepsBlock(m: UiMessage) {
    var expanded by remember(m.id) { mutableStateOf(false) }
    val collapsible = !m.streaming && m.steps.size > 3
    val showAll = !collapsible || expanded
    val failed = m.steps.count { it.status == StepStatus.FAILED }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (if (m.streaming) "Working · " else "") + "${m.steps.size} step${if (m.steps.size == 1) "" else "s"}" +
                        if (failed > 0) " · $failed failed" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (collapsible) {
                    Icon(if (expanded) XIcons.KeyboardArrowUp else XIcons.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            if (showAll) m.steps.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(stepIcon(s.label), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                    Text(s.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    when (s.status) {
                        StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        StepStatus.DONE -> Icon(XIcons.CheckCircle, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        StepStatus.FAILED -> Icon(XIcons.ErrorCircle, contentDescription = "Failed", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    }
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
