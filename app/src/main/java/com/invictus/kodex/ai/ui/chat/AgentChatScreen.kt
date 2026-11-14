@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.invictus.kodex.ai.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBarsPadding
import com.invictus.kodex.ai.agent.GeneratedImage
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.compose.AsyncImage
import com.invictus.kodex.ai.provider.ChatImage
import java.io.File
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
    var showMemory by remember { mutableStateOf(false) }
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
                    IconButton(onClick = { showMemory = true }) {
                        Icon(XIcons.Psychology, contentDescription = stringResource(R.string.ai_chat_memory_menu))
                    }
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
            memoryOpen = showMemory, onMemoryOpenChange = { showMemory = it },
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
    /** Same for the memory & instructions sheet. */
    memoryOpen: Boolean? = null,
    onMemoryOpenChange: ((Boolean) -> Unit)? = null,
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
    val gitConfirm by vm.gitConfirm.collectAsState()
    val status by vm.status.collectAsState()
    val contextUsed by vm.contextUsed.collectAsState()
    val changes by vm.changes.collectAsState()
    val reviewOpen by vm.reviewOpen.collectAsState()
    val applyError by vm.applyError.collectAsState()
    val index by vm.fileIndex.collectAsState()
    val chats by vm.chats.collectAsState()
    val notice by vm.notice.collectAsState()

    var input by remember { mutableStateOf("") }
    val attached = remember { mutableStateListOf<String>() }
    val images = remember { mutableStateListOf<ChatImage>() }
    var ownHistory by remember { mutableStateOf(false) }
    val showHistory = historyOpen ?: ownHistory
    val setHistory: (Boolean) -> Unit = { open ->
        if (onHistoryOpenChange != null) onHistoryOpenChange(open) else ownHistory = open
    }
    val newChatEpoch by vm.newChatEpoch.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var ownMemory by remember { mutableStateOf(false) }
    val showMemory = memoryOpen ?: ownMemory
    val setMemory: (Boolean) -> Unit = { open ->
        if (onMemoryOpenChange != null) onMemoryOpenChange(open) else ownMemory = open
    }

    // A new chat (from anywhere, e.g. the host's top bar) starts with an empty draft.
    LaunchedEffect(newChatEpoch) {
        if (newChatEpoch > 0) {
            input = ""
            attached.clear()
            images.forEach { vm.discardImage(it) }
            images.clear()
        }
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
                IconButton(onClick = { setMemory(true) }) { Icon(XIcons.Psychology, contentDescription = stringResource(R.string.ai_chat_memory_menu)) }
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
                                vm.editFrom(m.id)?.let { d ->
                                    input = d.text
                                    attached.clear()
                                    attached.addAll(d.files)
                                    images.clear()
                                    images.addAll(d.images)
                                }
                            },
                            onSaveImage = { g, svg -> vm.saveImage(g, svg) },
                            onApplyIcon = { g -> vm.applyAsAppIcon(g) },
                            onMoreLikeThis = { g ->
                                vm.send("More like this: make another version of this ${if (g.isIcon) "icon" else "image"} with the same idea and style, but a fresh take.", emptyList())
                            },
                        )
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            gitConfirm?.let { g ->
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    color = if (g.destructive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(g.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            g.lines.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 10,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(onClick = { vm.answerGit(false) }) { Text("Reject") }
                            Button(onClick = { vm.answerGit(true) }) { Text("Approve") }
                        }
                    }
                }
            }
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
            notice?.let { msg ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(XIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(20.dp))
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 10.dp),
                        )
                        IconButton(onClick = { vm.dismissNotice() }) { Icon(XIcons.Close, contentDescription = stringResource(R.string.ai_chat_dismiss)) }
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
        val scope = rememberCoroutineScope()
        val photoPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(AgentChatViewModel.MAX_IMAGES),
        ) { uris ->
            val room = AgentChatViewModel.MAX_IMAGES - images.size
            scope.launch { uris.take(room).forEach { u -> vm.importImage(u)?.let { images.add(it) } } }
        }
        var plusMenu by remember { mutableStateOf(false) }
        val cs = MaterialTheme.colorScheme
        // Like Claude: one rounded card. Photos / file chips and the text on top, then a row with + on the left
        // and the model pill right next to it, send/stop on the far right.
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = cs.surfaceContainerHigh,
            border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Column {
                if (images.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(images.toList(), key = { it.path }) { img ->
                            DraftPhoto(img, onRemove = { images.remove(img); vm.discardImage(img) })
                        }
                    }
                }
                if (attached.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
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
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(stringResource(R.string.ai_chat_ask_the_agent_to_mention), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
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
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        ComposerCircleButton(XIcons.Add, stringResource(R.string.ai_chat_attach_files)) { plusMenu = true }
                        DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                            val canPick = status.canSeeImages && images.size < AgentChatViewModel.MAX_IMAGES
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(R.string.ai_chat_photos))
                                        val hint = when {
                                            !status.canSeeImages -> stringResource(R.string.ai_chat_photos_unsupported)
                                            images.size >= AgentChatViewModel.MAX_IMAGES -> stringResource(R.string.ai_chat_photos_max, AgentChatViewModel.MAX_IMAGES)
                                            else -> null
                                        }
                                        if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                                    }
                                },
                                leadingIcon = { Icon(XIcons.Image, contentDescription = null) },
                                enabled = canPick,
                                onClick = {
                                    plusMenu = false
                                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ai_chat_attach_files)) },
                                leadingIcon = { Icon(XIcons.Article, contentDescription = null) },
                                onClick = { plusMenu = false; vm.refreshIndex(); showPicker = true },
                            )
                        }
                    }
                    if (status.loaded) {
                        Spacer(Modifier.width(8.dp))
                        ModelChip(status.modelLabel, onClick = { vm.refreshStatus(); showModels = true })
                    }
                    Spacer(Modifier.weight(1f))
                    SendStopButton(
                        busy = busy,
                        canSend = input.isNotBlank(),
                        onSend = {
                            if (vm.send(input, attached.toList(), images.toList())) {
                                input = ""
                                attached.clear()
                                images.clear()
                            }
                        },
                        onStop = { vm.stop() },
                    )
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
            onRename = { id, title -> vm.renameChat(id, title) },
            onNew = { vm.newChat(); setHistory(false) },
            onDismiss = { setHistory(false) },
        )
    }
    if (showMemory) {
        MemorySheet(docs = vm.projectDocs, onDismiss = { setMemory(false) })
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
            Triple(XIcons.Palette, "Make an app icon", "Create the best app icon for this app"),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(
    m: UiMessage,
    isLast: Boolean,
    busy: Boolean,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onSaveImage: (GeneratedImage, Boolean) -> Unit,
    onApplyIcon: (GeneratedImage) -> Unit,
    onMoreLikeThis: (GeneratedImage) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember(m.id) { mutableStateOf(false) }
    var selectOpen by remember(m.id) { mutableStateOf(false) }
    var viewerAt by remember(m.id) { mutableStateOf(-1) }
    val openMenu = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuOpen = true }
    val hasText = m.text.isNotBlank()
    val canEdit = m.role == "user" && !busy
    // Long-press menu, like Claude: Copy message / Select text (/ Edit on your own messages).
    val menu: @Composable () -> Unit = {
        if (hasText || canEdit) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (hasText) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_chat_copy_message)) },
                        leadingIcon = { Icon(XIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = { menuOpen = false; clipboard.setText(AnnotatedString(m.text)) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_chat_select_text)) },
                        leadingIcon = { Icon(XIcons.Article, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = { menuOpen = false; selectOpen = true },
                    )
                }
                if (canEdit) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_chat_edit)) },
                        leadingIcon = { Icon(XIcons.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                }
            }
        }
    }
    if (m.role == "user") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Box(contentAlignment = Alignment.TopEnd) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Photos sit above the bubble as their own cards (like Claude), tap opens them fullscreen.
                    if (m.images.isNotEmpty()) {
                        val side = if (m.images.size == 1) 190.dp else 120.dp
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.widthIn(max = 320.dp),
                        ) {
                            m.images.forEachIndexed { i, img ->
                                AsyncImage(
                                    model = File(img.path),
                                    contentDescription = stringResource(R.string.ai_chat_photo),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(side)
                                        .clip(RoundedCornerShape(20.dp))
                                        .combinedClickable(onClick = { viewerAt = i }, onLongClick = { openMenu() }),
                                )
                            }
                        }
                    }
                    if (hasText) {
                        Surface(
                            shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.pointerInput(m.id) { detectTapGestures(onLongPress = { openMenu() }) },
                        ) {
                            Text(
                                m.text,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
                menu()
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
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            // One status row, like Claude: the running step, else a cycling "Thinking…" word; tap opens the Summary sheet.
            val showSteps = m.steps.isNotEmpty() || (m.streaming && m.text.isBlank())
            if (showSteps) StepsBlock(m)
            if (hasText) {
                Box(Modifier.pointerInput(m.id) { detectTapGestures(onLongPress = { openMenu() }) }) {
                    MarkdownContent(m.text, Modifier.padding(top = if (showSteps) 8.dp else 0.dp))
                    menu()
                }
            }
            m.generated.forEach { g ->
                GeneratedImageCard(
                    g, onSave = { svg -> onSaveImage(g, svg) }, onApplyIcon = { onApplyIcon(g) },
                    onMoreLikeThis = if (isLast && !busy && g.svgPath == null) ({ onMoreLikeThis(g) }) else null,
                )
            }
            if (!m.streaming && hasText) {
                Row {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(m.text)) }) { Icon(XIcons.ContentCopy, contentDescription = stringResource(R.string.ai_chat_copy), modifier = Modifier.size(18.dp)) }
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, m.text) }
                        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Icon(XIcons.Share, contentDescription = stringResource(R.string.action_share), modifier = Modifier.size(18.dp)) }
                    if (isLast && !busy) {
                        IconButton(onClick = onRegenerate) { Icon(XIcons.Refresh, contentDescription = stringResource(R.string.ai_chat_regenerate), modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
    if (selectOpen) SelectTextSheet(m.text) { selectOpen = false }
    if (viewerAt >= 0 && m.images.isNotEmpty()) ImageViewerDialog(m.images, viewerAt.coerceAtMost(m.images.lastIndex)) { viewerAt = -1 }
}

/** "Select text": the whole message in a sheet where any part can be selected and copied. */
@Composable
private fun SelectTextSheet(text: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            stringResource(R.string.ai_chat_select_text),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
        )
        SelectionContainer(Modifier.weight(1f, fill = false)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
                    .navigationBarsPadding(),
            )
        }
    }
}

/** Fullscreen photo viewer: swipe between photos, pinch or double-tap to zoom. */
@Composable
private fun ImageViewerDialog(images: List<ChatImage>, startIndex: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = startIndex) { images.size }
        var zoomed by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableImage(images[page], onZoomChange = { zoomed = it })
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
                Icon(XIcons.Close, contentDescription = stringResource(R.string.action_close_dialog), tint = Color.White)
            }
            if (images.size > 1) {
                Text(
                    "${pager.currentPage + 1} / ${images.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(img: ChatImage, onZoomChange: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale > 1f) { onZoomChange(scale > 1f) }
    AsyncImage(
        model = File(img.path),
        contentDescription = stringResource(R.string.ai_chat_photo),
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Two fingers always zoom; one finger only pans once zoomed in, so the pager can still swipe.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val next = (scale * zoom).coerceIn(1f, 5f)
                            scale = next
                            val maxX = size.width * (next - 1f) / 2f
                            val maxY = size.height * (next - 1f) / 2f
                            offset = if (next > 1f) Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY)) else Offset.Zero
                            if (zoom != 1f || pan != Offset.Zero) event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f })
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    )
}

private fun stepIcon(label: String): ImageVector = when {
    label.startsWith("Reading") -> XIcons.Article
    label.startsWith("Searching") -> XIcons.Search
    label.startsWith("Listing") -> XIcons.Folder
    label.startsWith("Editing") -> XIcons.Edit
    label.startsWith("Creating") -> XIcons.NoteAdd
    label.startsWith("Deleting") -> XIcons.Delete
    label.startsWith("Generating") || label.startsWith("Drawing") -> XIcons.Palette
    else -> XIcons.Bolt
}

/** "Read 3 files · edited 1 file" style recap of what the agent did, for the collapsed row. */
private fun stepsSummary(allSteps: List<AgentChatViewModel.UiStep>): String {
    val steps = allSteps.filter { !it.thought }
    if (steps.isEmpty()) return "Thought"
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

/** "**Heading**" runs in a reasoning summary become semi-bold. */
private fun thoughtText(raw: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val bold = Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)
    for (m in bold.findAll(raw)) {
        append(raw.substring(i, m.range.first))
        pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
        append(m.groupValues[1])
        pop()
        i = m.range.last + 1
    }
    append(raw.substring(i))
}

/**
 * What the agent is doing, Claude style: ONE quiet status line (spinner + the running step, or a cycling
 * "Pondering…" word while it thinks; a recap + clock once it is done) that opens into a timeline of every
 * step and, where the model sends them, its reasoning summaries.
 */
@Composable
private fun StepsBlock(m: UiMessage) {
    var showSheet by remember(m.id) { mutableStateOf(false) }
    val tools = m.steps.filter { !it.thought }
    val failed = tools.count { it.status == StepStatus.FAILED }
    val running = tools.lastOrNull { it.status == StepStatus.RUNNING }
    val working = m.streaming && (running != null || m.text.isBlank())
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val expandable = m.steps.isNotEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = expandable) { showSheet = true }
            .padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (working) ClaudeSpinner(size = 20.dp)
        else Icon(XIcons.Schedule, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
        when {
            working && running != null ->
                ShimmerText(running.label, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            working ->
                Box(Modifier.weight(1f).padding(horizontal = 10.dp)) { ThinkingLabel(seed = m.id) }
            else -> Text(
                stepsSummary(m.steps) + if (failed > 0) stringResource(R.string.ai_chat_steps_failed, failed) else "",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
        }
        if (expandable) {
            Icon(XIcons.ChevronRight, contentDescription = stringResource(R.string.ai_chat_show_steps), tint = muted, modifier = Modifier.size(20.dp))
        }
    }
    // Reads [m] directly, so the timeline keeps filling in live while the agent works.
    if (showSheet && expandable) StepsSheet(m) { showSheet = false }
}

/** Claude-style "Summary" sheet: close button, centred title, then the step timeline. */
@Composable
private fun StepsSheet(m: UiMessage, onDismiss: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val rail = MaterialTheme.colorScheme.outlineVariant
    val sheetBg = MaterialTheme.colorScheme.surfaceContainerLow
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = sheetBg,
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(XIcons.Close, contentDescription = stringResource(R.string.action_close_dialog))
            }
            Text(
                stringResource(R.string.ai_chat_summary),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = 26.dp, end = 20.dp, top = 16.dp, bottom = 24.dp)
                .navigationBarsPadding()
                .drawBehind {
                    drawLine(rail, Offset(8.dp.toPx(), 8.dp.toPx()), Offset(8.dp.toPx(), size.height - 8.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            m.steps.forEach { st ->
                if (st.thought) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.background(sheetBg).padding(vertical = 2.dp)) {
                            Icon(XIcons.AutoAwesome, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                        }
                        Text(
                            thoughtText(st.label.trim()),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(start = 16.dp),
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.background(sheetBg).padding(vertical = 2.dp)) {
                            Icon(stepIcon(st.label), contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                        }
                        Text(
                            st.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (st.status == StepStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
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

/** Chat history: a sheet that hugs its content (no dead space), one flat newest-first list of soft cards with search. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatHistorySheet(
    chats: List<com.invictus.kodex.ai.agent.ChatMeta>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var renaming by remember { mutableStateOf<com.invictus.kodex.ai.agent.ChatMeta?>(null) }
    var query by remember { mutableStateOf("") }
    val shown = remember(chats, query) {
        val q = query.trim()
        chats.sortedByDescending { it.updatedAt }.filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(XIcons.Comment, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp)) }
                Text(
                    stringResource(R.string.ai_chat_chats),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                Button(onClick = onNew) {
                    Icon(XIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.ai_chat_new_chat), modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (chats.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 44.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(cs.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) { Icon(XIcons.Comment, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(26.dp)) }
                    Text(
                        stringResource(R.string.ai_chat_no_saved_chats_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.ai_chat_search_chats)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(XIcons.Close, contentDescription = null) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
                    item(key = "rename_hint") {
                        Text(
                            stringResource(R.string.ai_chat_rename_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
                        )
                    }
                    if (shown.isEmpty()) {
                        item(key = "no_matches") {
                            Text(
                                stringResource(R.string.ai_chat_no_matching_chats),
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp, top = 16.dp),
                            )
                        }
                    }
                    items(shown, key = { it.id }) { c ->
                        val whenText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(c.updatedAt))
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = cs.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(18.dp)).combinedClickable(
                                onClick = { onOpen(c.id) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    renaming = c
                                },
                            ),
                        ) {
                            Row(
                                Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape).background(cs.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(XIcons.Comment, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(
                                        c.title.ifBlank { stringResource(R.string.ai_chat_chat) },
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(whenText, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                                }
                                IconButton(onClick = { onDelete(c.id) }) {
                                    Icon(
                                        XIcons.Delete,
                                        contentDescription = stringResource(R.string.ai_chat_delete_chat),
                                        tint = cs.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.ai_chat_rename_chat)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.ai_chat_rename_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { onRename(target.id, name); renaming = null },
                ) { Text(stringResource(R.string.ai_chat_rename_confirm)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** Outlined round button (the + in the input card). */
@Composable
private fun ComposerCircleButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Send (arrow up) while idle, Stop (a small rounded square in a filled circle, with a slowly turning ring)
 * while the answer is streaming. Muted and inert when there is nothing to send.
 */
@Composable
private fun SendStopButton(busy: Boolean, canSend: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val active = busy || canSend
    val stopLabel = stringResource(R.string.ai_chat_stop)
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 2.dp, color = cs.primary, trackColor = Color.Transparent)
        Surface(
            onClick = if (busy) onStop else onSend,
            enabled = active,
            shape = CircleShape,
            color = if (active) cs.primary else cs.surfaceVariant,
            modifier = Modifier.size(if (busy) 32.dp else 40.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (busy) {
                    Box(
                        Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(cs.onPrimary)
                            .semantics { contentDescription = stopLabel },
                    )
                } else {
                    Icon(
                        XIcons.ArrowUpward,
                        contentDescription = stringResource(R.string.ai_chat_send),
                        tint = if (canSend) cs.onPrimary else cs.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** A picture the agent made: small preview beside its actions: Use as app icon, download PNG (and SVG when it is vector art). */
@Composable
private fun GeneratedImageCard(g: GeneratedImage, onSave: (svg: Boolean) -> Unit, onApplyIcon: () -> Unit, onMoreLikeThis: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val tight = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cs.surfaceContainerHigh,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(
                model = File(g.path),
                contentDescription = g.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(if (g.isIcon) 24.dp else 12.dp))
                    .background(cs.surfaceContainerHighest),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(g.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (g.isIcon) {
                        Button(onClick = onApplyIcon, contentPadding = tight, modifier = Modifier.height(36.dp)) {
                            Text("Use as app icon", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (onMoreLikeThis != null) {
                        OutlinedButton(onClick = onMoreLikeThis, contentPadding = tight, modifier = Modifier.height(36.dp)) {
                            Text("More like this", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    OutlinedButton(onClick = { onSave(false) }, contentPadding = tight, modifier = Modifier.height(36.dp)) {
                        Icon(XIcons.Download, contentDescription = "Download PNG", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("PNG", style = MaterialTheme.typography.labelLarge)
                    }
                    if (g.svgPath != null) {
                        OutlinedButton(onClick = { onSave(true) }, contentPadding = tight, modifier = Modifier.height(36.dp)) {
                            Icon(XIcons.Download, contentDescription = "Download SVG", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("SVG", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

/** A photo waiting in the input card, with a small remove button in its corner. */
@Composable
private fun DraftPhoto(img: ChatImage, onRemove: () -> Unit) {
    Box(Modifier.size(92.dp)) {
        AsyncImage(
            model = File(img.path),
            contentDescription = stringResource(R.string.ai_chat_photo),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)),
        )
        Surface(
            onClick = onRemove,
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.TopStart).padding(5.dp).size(22.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(XIcons.Close, contentDescription = stringResource(R.string.ai_chat_remove_photo), tint = Color.White, modifier = Modifier.size(12.dp))
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
