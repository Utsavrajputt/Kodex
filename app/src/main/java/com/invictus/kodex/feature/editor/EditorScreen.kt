package com.invictus.kodex.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.invictus.kodex.R
import com.invictus.kodex.core.editor.EditorSettingsStore
import com.invictus.kodex.feature.git.GitEvent
import com.invictus.kodex.feature.git.GitViewModel
import com.invictus.kodex.feature.git.SourceControlDrawerSheet
import java.io.File
import com.invictus.kodex.core.preview.PreviewMode
import com.invictus.kodex.core.preview.PreviewType
import com.invictus.kodex.feature.preview.PreviewPane
import com.invictus.kodex.feature.preview.SplitDragHandle
import com.invictus.kodex.ui.components.FileTypeIcon
import com.invictus.kodex.ui.icons.XIcons
import com.invictus.kodex.ai.AiGate
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val handle = remember { EditorHandle() }
    val context = LocalContext.current
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val highlightReady by viewModel.textMate.ready.collectAsStateWithLifecycle()
    val themeId by viewModel.themeId.collectAsStateWithLifecycle()
    val autocompleteEnabled by viewModel.autocompleteEnabled.collectAsStateWithLifecycle()
    val pairCursorEnabled by viewModel.pairCursorEnabled.collectAsStateWithLifecycle()
    var showSymbolCustomize by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var showQuickActions by remember { mutableStateOf(false) }
    // M17: AI panel in a bottom split; gate is null in the normal flavor.
    var showAi by remember { mutableStateOf(false) }
    val findState = remember { FindReplaceState() }
    val symbolBar by viewModel.symbolBar.collectAsStateWithLifecycle()
    val symbolBarVisible by viewModel.symbolBarVisible.collectAsStateWithLifecycle()

    // M7 Source Control drawer: shares the project root with the editor session.
    val projectRoot = viewModel.projectRoot
    val gitViewModel: GitViewModel? = projectRoot
        ?.takeIf { File(it, ".git").isDirectory }
        ?.let { root ->
            androidx.lifecycle.viewmodel.compose.viewModel(
                key = "git-drawer:${root.path}",
                factory = GitViewModel.factory(root.path),
            )
        }
    // Fallback flow keeps collectAsStateWithLifecycle unconditional (composition safety
    // if the project root flips between null/non-null across recompositions).
    val gitStateFlow = gitViewModel?.uiState
        ?: kotlinx.coroutines.flow.MutableStateFlow(GitViewModel.UiState())
    val gitState by gitStateFlow.collectAsStateWithLifecycle()
    var showGitSheet by remember { mutableStateOf(false) }
    var showFileSearch by remember { mutableStateOf(false) }
    // Bumped whenever the editor content/undo stack changes, so undo/redo enabled-state recomputes.
    var historyTick by remember { mutableIntStateOf(0) }

    val density = LocalDensity.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it.resolve(context)) }
    }
    LaunchedEffect(gitViewModel) {
        gitViewModel?.effects?.collect { effect ->
            when (effect) {
                is GitViewModel.Effect.OpenFile -> {
                    showGitSheet = false
                    viewModel.onEvent(EditorEvent.Open(effect.file))
                }
                is GitViewModel.Effect.Message ->
                    snackbarHostState.showSnackbar(effect.text.resolve(context))
            }
        }
    }
    // Last tab closed: nothing left to show here.
    LaunchedEffect(state.tabs.isEmpty()) {
        if (state.tabs.isEmpty()) onBack()
    }
    // Switching tabs closes the finder rather than trying to carry it to a different buffer,
    // (the top bar is fixed and never collapses).
    LaunchedEffect(state.activePath) {
        showFind = false
        findState.reset()
    }

    // A save from Termux/git or another app while Kodex was backgrounded may not have raised a
    // FileObserver event (Android 11+ FUSE/SAF can miss inotify) -- resume always double-checks.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumeCheck() }

    val active = state.tabs.firstOrNull { it.path == state.activePath }
    val activePath = state.activePath
    val activeBuffer = activePath?.let { viewModel.buffer(it) }
    val canUndo = remember(historyTick, activePath) { handle.editor?.canUndo() == true }
    val canRedo = remember(historyTick, activePath) { handle.editor?.canRedo() == true }

    // Keeps Sora's live search and the panel's independent match count both in sync with the
    // query/options/edits, without polling on every recomposition.
    LaunchedEffect(activePath, showFind, findState.query, findState.caseSensitive, findState.useRegex, findState.editEpoch) {
        if (!showFind || activePath == null) return@LaunchedEffect
        val query = findState.query
        if (query.isEmpty()) {
            findState.matchCount = 0
            handle.editor?.searcher?.stopSearch()
            return@LaunchedEffect
        }
        // Debounce: every keystroke cancels this effect, so a large file is searched once typing
        // pauses instead of on each character (that pile-up is what froze/crashed big files).
        delay(FIND_DEBOUNCE_MS)
        val caseSensitive = findState.caseSensitive
        val useRegex = findState.useRegex
        // Content is main-thread only, so snapshot here; the heavy regex scan runs off-thread.
        // Past the limit the independent count is skipped -- Sora's own searcher still highlights.
        val snapshot = activeBuffer?.content?.takeIf { it.length <= FIND_COUNT_MAX_CHARS }?.toString()
        val count: Int? = if (snapshot == null) {
            0
        } else {
            withContext(Dispatchers.Default) {
                try {
                    countMatches(snapshot, query, caseSensitive, useRegex, FIND_COUNT_CAP)
                } catch (_: Throwable) {
                    null
                }
            }
        }
        findState.matchCount = count
        val editor = handle.editor ?: return@LaunchedEffect
        if (count == null) {
            editor.searcher.stopSearch()
        } else {
            try {
                editor.searcher.search(
                    query,
                    EditorSearcher.SearchOptions(!caseSensitive, useRegex),
                )
            } catch (_: Throwable) {
                // Independent countMatches() above already flags a bad pattern to the user.
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            EditorTopBar(
                onBack = onBack,
                actions = {
                    HistoryButton(
                        enabled = canUndo,
                        onClick = { handle.editor?.undo() },
                        icon = XIcons.Undo,
                        description = stringResource(R.string.action_undo),
                    )
                    HistoryButton(
                        enabled = canRedo,
                        onClick = { handle.editor?.redo() },
                        icon = XIcons.Redo,
                        description = stringResource(R.string.action_redo),
                    )
                    if (active?.previewType?.isTextPreview == true) {
                        IconButton(onClick = { viewModel.onEvent(EditorEvent.CyclePreviewMode(active.path)) }) {
                            val (icon, description) = when (active.previewMode) {
                                PreviewMode.EDITOR -> XIcons.Code to R.string.preview_mode_editor
                                PreviewMode.SPLIT -> XIcons.HorizontalSplit to R.string.preview_mode_split
                                PreviewMode.PREVIEW -> XIcons.Visibility to R.string.preview_mode_preview
                            }
                            Icon(icon, contentDescription = stringResource(description))
                        }
                    }
                    Box {
                        IconButton(onClick = { showQuickActions = true }, enabled = activePath != null) {
                            Icon(XIcons.Tune, contentDescription = stringResource(R.string.editor_quick_actions))
                        }
                        QuickActionsMenu(
                            expanded = showQuickActions,
                            handle = handle,
                            activePath = activePath,
                            onDismiss = { showQuickActions = false },
                        )
                    }
                },
                pinned = {
                    IconButton(
                        onClick = {
                            showFind = !showFind
                            if (!showFind) {
                                handle.editor?.searcher?.stopSearch()
                                findState.reset()
                            }
                        },
                    ) {
                        Icon(XIcons.Search, contentDescription = stringResource(R.string.find_action))
                    }
                    IconButton(
                        onClick = { viewModel.onEvent(EditorEvent.SaveActive) },
                        enabled = active?.dirty == true,
                    ) {
                        Icon(XIcons.Save, contentDescription = stringResource(R.string.action_save))
                    }
                },
                overflow = {
                    EditorOverflowMenu(
                        activePath = state.activePath,
                        anyDirty = state.tabs.any { it.dirty },
                        onEvent = viewModel::onEvent,
                        onOpenSettings = onOpenSettings,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            TabBar(state = state, onEvent = viewModel::onEvent)
            active?.let { tab ->
                ExternalChangeBanner(tab = tab, onEvent = viewModel::onEvent)
            }
            val activeText = handle.editor?.text
                ?.takeIf { it.length <= FIND_COUNT_MAX_CHARS } // skip full-text copy on huge files
                ?.toString()
            val conflictBlocks = remember(active?.dirty, activePath, activeText) {
                activeText
                    ?.takeIf { active?.previewType == PreviewType.NONE }
                    ?.let { text ->
                        runCatching { com.invictus.kodex.core.git.ConflictParser.parse(text) }
                            .onFailure {
                                com.invictus.kodex.core.diagnostics.CrashHandler.logCaught(
                                    context, "ConflictParser.parse", it,
                                )
                            }
                            .getOrNull()
                    }
                    .orEmpty()
            }
            ConflictBlocksBar(
                blocks = conflictBlocks,
                handle = handle,
                onEdited = { activePath?.let { viewModel.onEdited(it) } },
            )
            val activePageIndex = active?.pageIndex
            val activePageCount = active?.pageCount
            if (activePath != null && activePageIndex != null && activePageCount != null) {
                PagedFileBar(
                    currentPage = activePageIndex,
                    pageCount = activePageCount,
                    handle = handle,
                    onChangePage = { toIndex, line, column, scrollX, scrollY ->
                        viewModel.onEvent(
                            EditorEvent.ChangePage(activePath, toIndex, line, column, scrollX, scrollY),
                        )
                    },
                )
            }
            if (showFind) {
                FindReplacePanel(
                    state = findState,
                    onQueryChange = { findState.query = it },
                    onFindNext = { try { handle.editor?.searcher?.gotoNext() } catch (_: Exception) {} },
                    onFindPrevious = { try { handle.editor?.searcher?.gotoPrevious() } catch (_: Exception) {} },
                    onReplace = {
                        try {
                            handle.editor?.searcher?.replaceCurrentMatch(findState.replacement)
                        } catch (_: Exception) {
                        }
                    },
                    onReplaceAll = {
                        try {
                            handle.editor?.searcher?.replaceAll(findState.replacement)
                        } catch (_: Exception) {
                        }
                    },
                    onClose = {
                        showFind = false
                        handle.editor?.searcher?.stopSearch()
                        findState.reset()
                    },
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val path = activePath
                val buffer = activeBuffer
                if (path != null && buffer != null) {
                    key(path, activePageIndex ?: 0) {
                        val previewType = active?.previewType ?: PreviewType.NONE
                        val previewMode = active?.previewMode ?: PreviewMode.EDITOR
                        val editorContent: @Composable (Modifier) -> Unit = { m ->
                            CodeEditorView(
                                buffer = buffer,
                                path = path,
                                jumpToLine = viewModel.jumpToLine,
                                darkTheme = darkTheme,
                                textMate = viewModel.textMate,
                                highlightReady = highlightReady,
                                themeId = themeId,
                                autocompleteEnabled = autocompleteEnabled,
                                handle = handle,
                                onEdited = {
                                    viewModel.onEdited(path)
                                    if (showFind) findState.editEpoch++
                                },
                                onViewState = { line, column, size, scrollX, scrollY ->
                                    viewModel.onViewState(path, line, column, size, scrollX, scrollY)
                                },
                                onHistoryChanged = { historyTick++ },
                                modifier = m,
                            )
                        }
                        // Preview never applies to a paged large file -- PreviewRouter only routes
                        // markdown/html, and a file that size is never one of those in practice,
                        // but the guard keeps the two M4/M5 features from ever fighting over `key`.
                        val aiGate = AiGate
                        if (showAi && aiGate != null && (previewType == PreviewType.NONE || previewMode == PreviewMode.EDITOR || buffer.isPaged)) {
                            // AI split: editor on top, drag handle, AI panel below (mirrors the M5 preview split).
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                val totalHeightPx = with(density) { maxHeight.toPx() }
                                var aiRatio by remember(path) { mutableFloatStateOf(0.5f) }
                                Column(Modifier.fillMaxSize()) {
                                    editorContent(Modifier.weight(aiRatio).fillMaxWidth())
                                    SplitDragHandle(onClose = { showAi = false }, onDragDeltaPx = { deltaY ->
                                        if (totalHeightPx > 0f) {
                                            aiRatio = (aiRatio + deltaY / totalHeightPx).coerceIn(0.2f, 0.8f)
                                        }
                                    })
                                    Box(Modifier.weight(1f - aiRatio).fillMaxWidth()) {
                                        aiGate.AiEditorPanel(viewModel, handle, onClose = { showAi = false })
                                    }
                                }
                            }
                        } else if (previewType == PreviewType.NONE || previewMode == PreviewMode.EDITOR || buffer.isPaged) {
                            editorContent(Modifier.fillMaxSize())
                        } else if (previewMode == PreviewMode.PREVIEW) {
                            PreviewPane(
                                buffer = buffer,
                                previewType = previewType,
                                revision = state.activeContentRevision,
                                darkTheme = darkTheme,
                                projectRoot = viewModel.projectRoot,
                                onSetHtmlJsEnabled = { viewModel.onEvent(EditorEvent.SetHtmlJsEnabled(path, it)) },
                                onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else { // SPLIT: editor on top, drag handle, preview below (M5 decision).
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                val totalHeightPx = with(density) { maxHeight.toPx() }
                                var ratio by remember(path) { mutableFloatStateOf(buffer.previewSplitRatio) }
                                Column(Modifier.fillMaxSize()) {
                                    editorContent(Modifier.weight(ratio).fillMaxWidth())
                                    SplitDragHandle(onClose = { viewModel.onEvent(EditorEvent.ClosePreview(path)) }, onDragDeltaPx = { deltaY ->
                                        if (totalHeightPx > 0f) {
                                            ratio = (ratio + deltaY / totalHeightPx).coerceIn(0.15f, 0.85f)
                                            viewModel.onEvent(EditorEvent.SetSplitRatio(path, ratio))
                                        }
                                    })
                                    PreviewPane(
                                        buffer = buffer,
                                        previewType = previewType,
                                        revision = state.activeContentRevision,
                                        darkTheme = darkTheme,
                                        projectRoot = viewModel.projectRoot,
                                        onSetHtmlJsEnabled = { viewModel.onEvent(EditorEvent.SetHtmlJsEnabled(path, it)) },
                                        onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                                        modifier = Modifier.weight(1f - ratio).fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
                // Floating actions (replace the old top-bar AI icon): AI on top, Source Control below.
                // The AI button hides while the AI panel is open (the panel has its own close handle)
                // and whenever AI is switched off in Settings > Behavior.
                val showAiFab = AiGate != null && activePath != null && !showAi
                val showGitFab = gitViewModel != null && activePath != null
                if (showAiFab || showGitFab) {
                    Column(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (showAiFab) {
                            SmallFloatingActionButton(
                                onClick = { showAi = true },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ) {
                                Icon(XIcons.AutoAwesome, contentDescription = "AI Assistant")
                            }
                        }
                        if (showGitFab) {
                            val changedCount = gitState.status?.changes?.size ?: 0
                            BadgedBox(badge = { if (changedCount > 0) Badge { Text("$changedCount") } }) {
                                SmallFloatingActionButton(
                                    onClick = { showGitSheet = true },
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ) {
                                    Icon(XIcons.Commit, contentDescription = stringResource(R.string.git_title))
                                }
                            }
                        }
                    }
                }
            }
            if (activePath != null && symbolBarVisible) {
                SymbolBar(
                    handle = handle,
                    symbols = symbolBar,
                    pairCursorEnabled = pairCursorEnabled,
                    onCustomize = { showSymbolCustomize = true },
                )
            }
        }
    }

    if (showFileSearch && projectRoot != null) {
        com.invictus.kodex.feature.search.FileSearchOverlay(
            root = projectRoot,
            onOpen = { file ->
                showFileSearch = false
                viewModel.onEvent(EditorEvent.Open(file))
            },
            onDismiss = { showFileSearch = false },
            onLocateInTree = { file ->
                // Same hand-off as code search: the workspace reveals it when we pop back to it.
                showFileSearch = false
                com.invictus.kodex.feature.search.SearchBus.requestReveal(file)
                onBack()
            },
        )
        androidx.activity.compose.BackHandler { showFileSearch = false }
    }

    if (showGitSheet && gitViewModel != null && projectRoot != null) {
        ModalBottomSheet(
            onDismissRequest = { showGitSheet = false },
            // Open at the content's height instead of half-way: the half-open state left the
            // changes list running off the bottom edge of the screen.
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SourceControlDrawerSheet(
                state = gitState,
                projectRoot = projectRoot,
                onEvent = gitViewModel::onEvent,
            )
        }
    }

    state.pendingClose?.let { pending ->
        UnsavedChangesDialog(pending = pending, onEvent = viewModel::onEvent)
    }
    if (showSymbolCustomize) {
        SymbolBarCustomizeDialog(
            current = symbolBar,
            onSave = { viewModel.setSymbolBar(it); showSymbolCustomize = false },
            onResetDefault = { viewModel.setSymbolBar(EditorSettingsStore.DEFAULT_SYMBOLS); showSymbolCustomize = false },
            onDismiss = { showSymbolCustomize = false },
        )
    }
}

/**
 * Fixed-height top bar (never collapses on scroll/zoom). Back, search, save and the overflow menu
 * stay pinned; the other action icons swipe horizontally when they don't all fit.
 */
@Composable
private fun EditorTopBar(
    onBack: () -> Unit,
    actions: @Composable () -> Unit,
    pinned: @Composable () -> Unit,
    overflow: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) { actions() }
            pinned()
            overflow()
        }
    }
}

/** Undo/redo: plain icon, dimmed while there is nothing to undo/redo (no background highlight). */
@Composable
private fun HistoryButton(
    enabled: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

@Composable
private fun EditorOverflowMenu(
    activePath: String?,
    anyDirty: Boolean,
    onEvent: (EditorEvent) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(XIcons.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_save_all)) },
                leadingIcon = { Icon(XIcons.Save, contentDescription = null) },
                enabled = anyDirty,
                onClick = {
                    open = false
                    onEvent(EditorEvent.SaveAll)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_close_tab)) },
                leadingIcon = { Icon(XIcons.Close, contentDescription = null) },
                enabled = activePath != null,
                onClick = {
                    open = false
                    activePath?.let { onEvent(EditorEvent.CloseTab(it)) }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_close_others)) },
                leadingIcon = { Icon(XIcons.CloseOthers, contentDescription = null) },
                enabled = activePath != null,
                onClick = {
                    open = false
                    activePath?.let { onEvent(EditorEvent.CloseOthers(it)) }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_close_all)) },
                leadingIcon = { Icon(XIcons.ClearAll, contentDescription = null) },
                onClick = {
                    open = false
                    onEvent(EditorEvent.CloseAll)
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_settings)) },
                leadingIcon = { Icon(XIcons.Settings, contentDescription = null) },
                onClick = {
                    open = false
                    onOpenSettings()
                },
            )
        }
    }
}

/**
 * Drag-to-reorder tab strip. Long-press then drag reorders (a plain tap still just switches
 * tabs; long-press and release without moving opens the tab menu). The dragged tab follows the
 * finger and swaps past a neighbor once it crosses that neighbor's midpoint.
 *
 * Two things used to break this: the tab's own long-click handler consumed the gesture before
 * the drag detector could use it, and the drag lambda kept reading the tab list from the
 * composition it was created in, so after the first swap every later swap used stale indices.
 */
@Composable
private fun TabBar(state: EditorUiState, onEvent: (EditorEvent) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.activePath) {
        val index = state.tabs.indexOfFirst { it.path == state.activePath }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.animateScrollToItem(index)
        }
    }

    var draggingPath by remember { mutableStateOf<String?>(null) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var menuPath by remember { mutableStateOf<String?>(null) }
    val tabsNow by rememberUpdatedState(state.tabs)
    val moveSlopPx = with(LocalDensity.current) { 8.dp.toPx() }
    // Filled in by each tab's onGloballyPositioned; used to size neighbor swap thresholds.
    val itemWidths = remember { mutableMapOf<String, Float>() }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.Bottom,
    ) {
        itemsIndexed(state.tabs, key = { _, tab -> tab.path }) { _, tab ->
            val isDragging = tab.path == draggingPath
            EditorTab(
                tab = tab,
                selected = tab.path == state.activePath,
                onSelect = { onEvent(EditorEvent.Select(tab.path)) },
                menuOpen = menuPath == tab.path,
                onDismissMenu = { menuPath = null },
                onClose = { onEvent(EditorEvent.CloseTab(tab.path)) },
                onTogglePin = { onEvent(EditorEvent.TogglePin(tab.path)) },
                onCloseOthers = { onEvent(EditorEvent.CloseOthers(tab.path)) },
                onCloseAll = { onEvent(EditorEvent.CloseAll) },
                modifier = Modifier
                    .onGloballyPositioned { coords -> itemWidths[tab.path] = coords.size.width.toFloat() }
                    .zIndex(if (isDragging) 1f else 0f)
                    .let { m ->
                        if (isDragging) m.offset { IntOffset(dragOffsetX.roundToInt(), 0) }
                        else m.animateItem()
                    }
                    .pointerInput(tab.path) {
                        var totalMove = 0f
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingPath = tab.path
                                dragOffsetX = 0f
                                totalMove = 0f
                                onEvent(EditorEvent.Select(tab.path))
                            },
                            onDragEnd = {
                                // Held without moving: behave like the old long-press menu.
                                if (totalMove < moveSlopPx) menuPath = tab.path
                                draggingPath = null
                                dragOffsetX = 0f
                            },
                            onDragCancel = {
                                draggingPath = null
                                dragOffsetX = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffsetX += amount.x
                                totalMove += kotlin.math.abs(amount.x)
                                val tabs = tabsNow
                                val myWidth = itemWidths[tab.path] ?: return@detectDragGesturesAfterLongPress
                                val fromIndex = tabs.indexOfFirst { it.path == tab.path }
                                if (fromIndex < 0) return@detectDragGesturesAfterLongPress
                                if (dragOffsetX > 0) {
                                    val next = tabs.getOrNull(fromIndex + 1) ?: return@detectDragGesturesAfterLongPress
                                    val nextWidth = itemWidths[next.path] ?: myWidth
                                    if (dragOffsetX > nextWidth / 2) {
                                        onEvent(EditorEvent.Reorder(fromIndex, fromIndex + 1))
                                        dragOffsetX -= nextWidth
                                    }
                                } else {
                                    val prev = if (fromIndex > 0) tabs[fromIndex - 1] else null
                                    if (prev != null) {
                                        val prevWidth = itemWidths[prev.path] ?: myWidth
                                        if (-dragOffsetX > prevWidth / 2) {
                                            onEvent(EditorEvent.Reorder(fromIndex, fromIndex - 1))
                                            dragOffsetX += prevWidth
                                        }
                                    }
                                }
                            },
                        )
                    },
            )
        }
    }
}

@Composable
private fun EditorTab(
    tab: EditorTabUi,
    selected: Boolean,
    onSelect: () -> Unit,
    menuOpen: Boolean,
    onDismissMenu: () -> Unit,
    onClose: () -> Unit,
    onTogglePin: () -> Unit,
    onCloseOthers: () -> Unit,
    onCloseAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        modifier = modifier
            .height(40.dp)
            .clickable(onClick = onSelect),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 2.dp),
        ) {
            if (tab.isPinned) {
                Icon(
                    imageVector = XIcons.Pin,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp).padding(end = 4.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            FileTypeIcon(name = tab.name, isDirectory = false, size = 18.dp)
            Text(
                text = tab.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).widthIn(max = 160.dp),
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tab.dirty) {
                Box(
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = XIcons.Close,
                    contentDescription = stringResource(R.string.editor_close_tab),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (tab.isPinned) R.string.editor_unpin_tab else R.string.editor_pin_tab,
                        ),
                    )
                },
                leadingIcon = { Icon(XIcons.Pin, contentDescription = null) },
                onClick = { onDismissMenu(); onTogglePin() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_close_others)) },
                leadingIcon = { Icon(XIcons.CloseOthers, contentDescription = null) },
                onClick = { onDismissMenu(); onCloseOthers() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_close_all)) },
                leadingIcon = { Icon(XIcons.ClearAll, contentDescription = null) },
                onClick = { onDismissMenu(); onCloseAll() },
            )
        }
    }
}

@Composable
private fun UnsavedChangesDialog(pending: PendingClose, onEvent: (EditorEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(EditorEvent.DismissPendingClose) },
        title = { Text(stringResource(R.string.editor_unsaved_title)) },
        text = {
            Text(
                if (pending.dirtyNames.size == 1) {
                    stringResource(R.string.editor_unsaved_one, pending.dirtyNames.first())
                } else {
                    stringResource(R.string.editor_unsaved_many, pending.dirtyNames.size)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onEvent(EditorEvent.ConfirmSaveAndClose) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onEvent(EditorEvent.DismissPendingClose) }) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(onClick = { onEvent(EditorEvent.ConfirmDiscardAndClose) }) {
                    Text(stringResource(R.string.action_discard))
                }
            }
        },
    )
}

private const val FIND_DEBOUNCE_MS = 300L
private const val FIND_COUNT_MAX_CHARS = 2_000_000
private const val FIND_COUNT_CAP = 10_000
