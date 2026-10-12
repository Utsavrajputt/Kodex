@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.invictus.kodex.ai.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.res.stringResource
import com.invictus.kodex.R

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
                        Text(stringResource(R.string.ai_chat_kodex_ai))
                        Text(projectName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.ai_settings_back)) } },
                actions = {
                    IconButton(onClick = { vm.refreshChats(); showHistory = true }) {
                        Icon(XIcons.Restore, contentDescription = stringResource(R.string.ai_chat_chat_history))
                    }
                    IconButton(onClick = { vm.newChat() }) { Icon(XIcons.Add, contentDescription = stringResource(R.string.ai_chat_new_chat)) }
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
                TextButton(onClick = { vm.openReview() }) { Text(stringResource(R.string.ai_chat_review, changes.size)) }
            }
            if (headerActions) {
                IconButton(onClick = { vm.refreshChats(); setHistory(true) }) { Icon(XIcons.Restore, contentDescription = stringResource(R.string.ai_chat_chat_history)) }
                IconButton(onClick = { vm.newChat() }) { Icon(XIcons.Add, contentDescription = stringResource(R.string.ai_chat_new_chat)) }
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
                                if (changes.size == 1) stringResource(R.string.ai_chat_files_changed_one) else stringResource(R.string.ai_chat_files_changed_many, changes.size),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                stringResource(R.string.ai_chat_nothing_is_written_until_you),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            )
                        }
                        TextButton(onClick = { vm.discardChanges() }) { Text(stringResource(R.string.ai_chat_discard)) }
                        Button(onClick = { vm.openReview() }) { Text(stringResource(R.string.ai_chat_review_2)) }
                    }
                }
            }
            undo?.let { u ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(XIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(20.dp))
                        Text(
                            if (u.entries.size == 1) stringResource(R.string.ai_chat_applied_one) else stringResource(R.string.ai_chat_applied_many, u.entries.size),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        TextButton(onClick = { vm.undoLast() }) { Text(stringResource(R.string.ai_chat_undo)) }
                        IconButton(onClick = { vm.dismissUndo() }) { Icon(XIcons.Close, contentDescription = stringResource(R.string.ai_chat_dismiss)) }
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
                        trailingIcon = { Icon(XIcons.Close, contentDescription = stringResource(R.string.ai_settings_remove), modifier = Modifier.size(16.dp)) },
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
                    placeholder = { Text(stringResource(R.string.ai_chat_ask_the_agent_to_mention)) },
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
                        Icon(XIcons.Add, contentDescription = stringResource(R.string.ai_chat_attach_files))
                    }
                    Spacer(Modifier.weight(1f))
                    if (status.loaded) {
                        ModelChip(status.modelLabel, onClick = { vm.refreshStatus(); showModels = true })
                        Spacer(Modifier.width(6.dp))
                    }
                    if (busy) {
                        FilledIconButton(onClick = { vm.stop() }) { Icon(XIcons.StopCircle, contentDescription = stringResource(R.string.ai_chat_stop)) }
                    } else {
                        FilledIconButton(
                            enabled = input.isNotBlank(),
                            onClick = {
                                vm.send(input, attached.toList())
                                input = ""
                                attached.clear()
                            },
                        ) { Icon(XIcons.Send, contentDescription = stringResource(R.string.ai_chat_send)) }
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
            title = { Text(stringResource(R.string.ai_chat_apply_to_file)) },
            text = {
                Column {
                    Text(stringResource(R.string.ai_chat_project_relative_path_an_existing), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = path, onValueChange = { path = it }, singleLine = true, label = { Text(stringResource(R.string.ai_chat_path)) }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = path.isNotBlank(), onClick = { vm.stageCodeBlock(path, code); applyDialog = null }) { Text(stringResource(R.string.ai_chat_review_change)) }
            },
            dismissButton = { TextButton(onClick = { applyDialog = null }) { Text(stringResource(R.string.ai_chat_cancel)) } },
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
        Text(stringResource(R.string.ai_chat_kodex_ai_agent), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text(
            stringResource(R.string.ai_chat_ask_about_your_project_or),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CapabilityPill(XIcons.Search, stringResource(R.string.ai_chat_searches_your_project))
            CapabilityPill(XIcons.Edit, stringResource(R.string.ai_chat_edits_many_files))
            CapabilityPill(XIcons.CheckCircle, stringResource(R.string.ai_chat_you_approve_every_change))
        }
        Spacer(Modifier.height(16.dp))
        listOf(
            Triple(XIcons.AccountTree, stringResource(R.string.ai_chat_explain_the_structure), stringResource(R.string.ai_chat_explain_how_this_project_is)),
            Triple(XIcons.Search, stringResource(R.string.ai_chat_hunt_for_bugs), stringResource(R.string.ai_chat_find_likely_bugs_and_list)),
            Triple(XIcons.DeleteSweep, stringResource(R.string.ai_chat_find_dead_code), stringResource(R.string.ai_chat_find_unused_code_i_can)),
            Triple(XIcons.Code, stringResource(R.string.ai_chat_write_tests), stringResource(R.string.ai_chat_write_unit_tests_for)),
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
                stringResource(R.string.ai_chat_using, status.modelLabel, status.providerName),
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
        Text(stringResource(R.string.ai_chat_set_up_ai_to_get), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        Text(
            stringResource(R.string.ai_chat_two_quick_things_and_the),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        SetupRow(status.hasKey, stringResource(R.string.ai_chat_api_key_for, status.providerName), if (status.hasKey) stringResource(R.string.ai_chat_saved) else stringResource(R.string.ai_chat_not_added_yet))
        SetupRow(status.consent, stringResource(R.string.ai_settings_allow_ai_in_projects), if (status.consent) stringResource(R.string.ai_chat_on) else stringResource(R.string.ai_chat_off_the_agent_can_t))
        if (onOpenSettings != null) {
            Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.ai_chat_open_ai_settings)) }
        } else {
            Text(stringResource(R.string.ai_chat_open_settings_ai_assistant_to), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
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
            if (!busy) TextButton(onClick = onEdit) { Text(stringResource(R.string.ai_chat_edit), style = MaterialTheme.typography.labelMedium) }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(XIcons.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(13.dp)) }
                Text(stringResource(R.string.ai_chat_kodex_ai), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
            }
            if (m.steps.isNotEmpty()) StepsBlock(m)
            if (m.text.isNotBlank()) {
                SelectionContainer { MarkdownContent(m.text, Modifier.padding(top = if (m.steps.isNotEmpty()) 8.dp else 0.dp), onApplyCode) }
            } else if (m.streaming && m.steps.none { it.status == StepStatus.RUNNING }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp)) {
                    ClaudeSpinner(size = 20.dp)
                    ThinkingLabel(seed = m.id, modifier = Modifier.padding(start = 10.dp))
                }
            }
            if (!m.streaming && m.text.isNotBlank()) {
                Row {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(m.text)) }) { Icon(XIcons.ContentCopy, contentDescription = stringResource(R.string.ai_chat_copy), modifier = Modifier.size(18.dp)) }
                    if (isLast && !busy) {
                        IconButton(onClick = onRegenerate) { Icon(XIcons.Refresh, contentDescription = stringResource(R.string.ai_chat_regenerate), modifier = Modifier.size(18.dp)) }
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

/** "Read 3 files · edited 1 file" style recap of what the agent did, for the collapsed row. */
private fun stepsSummary(steps: List<AgentChatViewModel.UiStep>): String {
    fun count(prefix: String) = steps.count { it.label.startsWith(prefix) }
    fun plural(n: Int, one: String, many: String) = if (n == 1) "$n $one" else "$n $many"
    val parts = buildList {
        count("Reading").takeIf { it > 0 }?.let { add("read " + plural(it, "file", "files")) }
        count("Searching").takeIf { it > 0 }?.let { add("searched " + plural(it, "time", "times")) }
        count("Listing").takeIf { it > 0 }?.let { add("listed " + plural(it, "folder", "folders")) }
        count("Editing").takeIf { it > 0 }?.let { add("edited " + plural(it, "file", "files")) }
        count("Creating").takeIf { it > 0 }?.let { add("created " + plural(it, "file", "files")) }
        count("Deleting").takeIf { it > 0 }?.let { add("deleted " + plural(it, "file", "files")) }
    }
    val text = if (parts.isEmpty()) plural(steps.size, "step", "steps") else parts.joinToString(", ")
    return text.replaceFirstChar { it.uppercase() }
}

/**
 * What the agent is doing, Claude style: one quiet line (clock + current action or a recap + chevron) that
 * opens into a timeline of every step on tap.
 */
@Composable
private fun StepsBlock(m: UiMessage) {
    var expanded by remember(m.id) { mutableStateOf(false) }
    val failed = m.steps.count { it.status == StepStatus.FAILED }
    val running = m.steps.lastOrNull { it.status == StepStatus.RUNNING }
    val headline = when {
        m.streaming -> running?.label ?: m.steps.lastOrNull()?.label ?: stringResource(R.string.ai_chat_working)
        else -> stepsSummary(m.steps) + if (failed > 0) stringResource(R.string.ai_chat_steps_failed, failed) else ""
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val chevron by animateFloatAsState(if (expanded) 90f else 0f, label = "stepsChevron")
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (m.streaming) ClaudeSpinner(size = 20.dp)
            else Icon(XIcons.Schedule, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
            if (m.streaming) {
                ShimmerText(headline, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            } else {
                Text(
                    headline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
            }
            Icon(
                XIcons.ChevronRight, contentDescription = if (expanded) stringResource(R.string.ai_chat_hide_steps) else stringResource(R.string.ai_chat_show_steps), tint = muted,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = chevron },
            )
        }
        if (expanded) {
            val rail = MaterialTheme.colorScheme.outlineVariant
            val bg = MaterialTheme.colorScheme.background
            Column(
                Modifier
                    .padding(start = 9.dp, bottom = 6.dp)
                    .drawBehind {
                        drawLine(rail, Offset(8.dp.toPx(), 8.dp.toPx()), Offset(8.dp.toPx(), size.height - 8.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                    },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                m.steps.forEach { st ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.background(bg).padding(vertical = 1.dp)) {
                            Icon(stepIcon(st.label), contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                        }
                        Text(
                            st.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (st.status == StepStatus.FAILED) MaterialTheme.colorScheme.error else muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        when (st.status) {
                            StepStatus.RUNNING -> ClaudeSpinner(size = 14.dp)
                            StepStatus.DONE -> Icon(XIcons.Check, contentDescription = stringResource(R.string.ai_chat_done), tint = muted, modifier = Modifier.size(14.dp))
                            StepStatus.FAILED -> Icon(XIcons.ErrorCircle, contentDescription = stringResource(R.string.ai_chat_failed), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                        }
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
                Text(stringResource(R.string.ai_chat_chats), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button(onClick = onNew) { Text(stringResource(R.string.ai_chat_new_chat)) }
            }
            if (chats.isEmpty()) Text(stringResource(R.string.ai_chat_no_saved_chats_yet), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            LazyColumn(Modifier.height(360.dp)) {
                items(chats, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title.ifBlank { stringResource(R.string.ai_chat_chat) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.updatedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDelete(c.id) }) { Icon(XIcons.Delete, contentDescription = stringResource(R.string.ai_chat_delete_chat)) }
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
        title = { Text(stringResource(R.string.ai_chat_attach_files)) },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text(stringResource(R.string.ai_chat_filter)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.height(320.dp).padding(top = 8.dp)) {
                    items(filtered) { p ->
                        Row(Modifier.fillMaxWidth().clickable { onToggle(p) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(p, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (p in selected) Icon(XIcons.Check, contentDescription = stringResource(R.string.ai_chat_attached), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_chat_done)) } },
    )
}
