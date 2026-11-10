package com.invictus.kodex.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.R
import com.invictus.kodex.KodexApp
import com.invictus.kodex.core.editor.EditorSessionStore
import com.invictus.kodex.core.editor.EditorSettingsStore
import com.invictus.kodex.core.editor.EditorThemes
import com.invictus.kodex.core.editor.ExternalChange
import com.invictus.kodex.core.editor.FileTooLargeException
import com.invictus.kodex.core.editor.LoadedText
import com.invictus.kodex.core.editor.PagedEditSession
import com.invictus.kodex.core.editor.SessionState
import com.invictus.kodex.core.editor.SessionTab
import com.invictus.kodex.core.editor.TabBuffer
import com.invictus.kodex.core.editor.TextMateSupport
import com.invictus.kodex.core.editor.TextFileIo
import com.invictus.kodex.core.fs.ExternalChangeWatcher
import com.invictus.kodex.core.preview.PreviewMode
import com.invictus.kodex.core.preview.PreviewRouter
import com.invictus.kodex.feature.workspace.UiText
import io.github.rosemoe.sora.text.Content
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Owns the open tabs. Lives at activity scope so tabs survive going back to the file tree.
 * Everything mutable is touched on the main thread; disk work runs on [io].
 */
class EditorViewModel(
    val textMate: TextMateSupport,
    private val sessionStore: EditorSessionStore,
    private val settingsStore: EditorSettingsStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** Selected editor colour theme; [EditorThemes.SYSTEM_DEFAULT] follows the app light/dark; unset = [EditorThemes.DEFAULT_ID]. */
    val themeId: StateFlow<String> = settingsStore.themeId
        .stateIn(viewModelScope, SharingStarted.Eagerly, EditorThemes.DEFAULT_ID)

    fun setTheme(id: String) {
        viewModelScope.launch { settingsStore.setThemeId(id) }
    }

    /** Plan 3.2 "customizable symbol bar" -- ordered quick-insert symbols shown above the keyboard. */
    val symbolBar: StateFlow<List<String>> = settingsStore.symbolBar
        .stateIn(viewModelScope, SharingStarted.Eagerly, EditorSettingsStore.DEFAULT_SYMBOLS)

    fun setSymbolBar(symbols: List<String>) {
        viewModelScope.launch { settingsStore.setSymbolBar(symbols) }
    }

    /** Settings screen "Show symbol bar" toggle -- strip above the keyboard; hidden by default. */
    val symbolBarVisible: StateFlow<Boolean> = settingsStore.symbolBarVisible
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setSymbolBarVisible(visible: Boolean) {
        viewModelScope.launch { settingsStore.setSymbolBarVisible(visible) }
    }

    /** Settings screen "Auto-preview" toggle -- md/html tabs jump straight to SPLIT on open; off by default. */
    val autoPreviewEnabled: StateFlow<Boolean> = settingsStore.autoPreviewEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAutoPreviewEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoPreviewEnabled(enabled) }
    }

    /** Settings screen "Font size" slider; also the last size the user zoomed to anywhere. */
    val fontSizePx: StateFlow<Float> = settingsStore.fontSizePx
        .map(::clampFontPx)
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    fun setFontSizePx(px: Float) {
        viewModelScope.launch { settingsStore.setFontSizePx(clampFontPx(px)) }
    }

    /** 0 = "use built-in default"; anything else stays inside the range Sora's pinch-zoom allows. */
    private fun clampFontPx(px: Float): Float =
        if (px > 0f) px.coerceIn(MIN_PINCH_TEXT_SIZE_PX, MAX_PINCH_TEXT_SIZE_PX) else px

    /** Settings screen "Autocomplete" toggle. */
    val autocompleteEnabled: StateFlow<Boolean> = settingsStore.autocompleteEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAutocompleteEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutocompleteEnabled(enabled) }
    }

    /** Settings screen "Pair cursor" toggle. */
    val pairCursorEnabled: StateFlow<Boolean> = settingsStore.pairCursorEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setPairCursorEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setPairCursorEnabled(enabled) }
    }

    /** Settings screen "File changed outside the app" choice. */
    val autoReloadExternalChanges: StateFlow<Boolean> = settingsStore.autoReloadExternalChanges
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAutoReloadExternalChanges(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoReloadExternalChanges(enabled) }
    }

    /** Settings screen "Restore open files" toggle -- reopen last session's tabs on project open; off by default. */
    val restoreTabsOnOpen: StateFlow<Boolean> = settingsStore.restoreTabsOnOpen
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setRestoreTabsOnOpen(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setRestoreTabsOnOpen(enabled) }
    }

    /** Settings screen "Git status detection" toggle -- background status polling for the file tree stripes; off by default. */
    val gitStatusPollingEnabled: StateFlow<Boolean> = settingsStore.gitStatusPollingEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setGitStatusPollingEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setGitStatusPollingEnabled(enabled) }
    }

    val searchDefaultStringsOnly: StateFlow<Boolean> = settingsStore.searchDefaultStringsOnly
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Settings "Scroll long file names" toggle for the file tree. */
    val fileTreeHScrollLongNames: StateFlow<Boolean> = settingsStore.fileTreeHScrollLongNames
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setFileTreeHScrollLongNames(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setFileTreeHScrollLongNames(enabled) }
    }

    fun clearRecentFiles() {
        viewModelScope.launch { settingsStore.clearRecentFiles() }
    }

    val searchExtraExcludes: StateFlow<String> = settingsStore.searchExtraExcludes
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setSearchExtraExcludes(value: String) {
        viewModelScope.launch { settingsStore.setSearchExtraExcludes(value) }
    }

    val searchMaxFileMb: StateFlow<Int> = settingsStore.searchMaxFileMb
        .stateIn(viewModelScope, SharingStarted.Eagerly, EditorSettingsStore.DEFAULT_SEARCH_MAX_FILE_MB)

    fun setSearchMaxFileMb(mb: Int) {
        viewModelScope.launch { settingsStore.setSearchMaxFileMb(mb) }
    }

    fun setSearchDefaultStringsOnly(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setSearchDefaultStringsOnly(enabled) }
    }

    /** Last font size the user zoomed to anywhere, in px; 0f = editor's own built-in default. */
    private var defaultFontSizePx: Float = 0f

    /** Mirrors [autoReloadExternalChanges] for use inside the non-suspend-friendly disk-check path. */
    private var autoReloadExternal: Boolean = false

    /** Mirrors [autoPreviewEnabled] for use inside the non-suspend-friendly tab-open paths. */
    private var autoPreview: Boolean = false

    init {
        // Grammars load while the user is still browsing the tree; editors upgrade when ready.
        viewModelScope.launch { textMate.ensureLoaded() }
        viewModelScope.launch { settingsStore.fontSizePx.collect { defaultFontSizePx = clampFontPx(it) } }
        viewModelScope.launch { settingsStore.autoReloadExternalChanges.collect { autoReloadExternal = it } }
        viewModelScope.launch { settingsStore.autoPreviewEnabled.collect { autoPreview = it } }
    }

    private val buffers = LinkedHashMap<String, TabBuffer>()
    private val loading = HashSet<String>()

    /** Debounced per-path: bursts of MODIFY/CLOSE_WRITE from one save collapse to one check. */
    private val externalChangeJobs = HashMap<String, Job>()
    private val watcher = ExternalChangeWatcher { changedPath -> onDiskEvent(changedPath) }

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    /** Errors to show wherever the user currently is (file tree or editor). */
    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiText> = _messages.asSharedFlow()

    /** Fired once a file is ready, so navigation can bring the editor screen forward. */
    private val _showEditor = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** M11: code-search jump requests (path to 1-based line); consumed by the live editor view. */
    private val _jumpToLine = MutableSharedFlow<JumpRequest>(extraBufferCapacity = 1)
    val jumpToLine: SharedFlow<JumpRequest> = _jumpToLine
    val showEditor: SharedFlow<Unit> = _showEditor.asSharedFlow()

    /**
     * M5: SVG/image/video never become a [TabBuffer] -- they're binary, there's nothing to edit
     * or split against, so tapping one fires this instead of the usual tab-open path.
     */
    private val _mediaPreviewFile = MutableStateFlow<File?>(null)
    val mediaPreviewFile: StateFlow<File?> = _mediaPreviewFile.asStateFlow()
    private val _openMediaPreview = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val openMediaPreview: SharedFlow<File> = _openMediaPreview.asSharedFlow()

    /** The workspace this session belongs to; set once by [onProjectOpened]. */
    private var projectPath: String? = null
    private var restoredForProject: String? = null
    private var persistJob: Job? = null

    fun buffer(path: String): TabBuffer? = buffers[path]

    /** Exposed for HTML preview, which needs the project root to resolve relative asset paths. */
    val projectRoot: File? get() = projectPath?.let { File(it) }

    /**
     * Called when the workspace root becomes known (once at cold start, and again on every
     * project switch). Only restores a session the first time a given project is seen with no
     * tabs already open -- it never clobbers tabs the user already opened this run.
     */
    fun onProjectOpened(path: String) {
        if (projectPath == path) return
        projectPath = path
        if (restoredForProject == path || buffers.isNotEmpty()) return
        restoredForProject = path
        loading.add(SESSION_LOAD_KEY)
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            val session = withContext(io) { sessionStore.load(path) }
            // Read straight from the store (not the stateIn mirror): on cold start the mirror still
            // holds its default until DataStore emits. When off, only tabs carrying unsaved edits
            // come back, so nothing typed is lost.
            val restoreAll = settingsStore.restoreTabsOnOpen.first()
            val effective = if (restoreAll || session == null) {
                session
            } else {
                session.copy(tabs = session.tabs.filter { it.pendingEditSnapshot != null })
            }
            restoreSession(effective)
            loading.remove(SESSION_LOAD_KEY)
            _uiState.update { it.copy(loading = loading.isNotEmpty()) }
        }
    }

    fun onEvent(event: EditorEvent) {
        when (event) {
            is EditorEvent.Open -> open(event.file)
            is EditorEvent.OpenAtLine -> open(event.file, event.line, event.column, event.length)
            is EditorEvent.Select -> if (event.path in buffers) {
                _uiState.update { it.copy(activePath = event.path) }
                schedulePersist()
            }
            is EditorEvent.CloseTab -> requestClose(listOf(event.path))
            is EditorEvent.CloseOthers -> requestClose(
                _uiState.value.tabs.filter { it.path != event.path && !it.isPinned }.map { it.path },
            )
            EditorEvent.CloseAll -> requestClose(_uiState.value.tabs.filter { !it.isPinned }.map { it.path })
            is EditorEvent.Reorder -> reorder(event.from, event.to)
            is EditorEvent.TogglePin -> togglePin(event.path)
            EditorEvent.SaveActive -> _uiState.value.activePath?.let { save(listOf(it)) }
            EditorEvent.SaveAll -> save(buffers.keys.filter(::isDirty))
            EditorEvent.ConfirmSaveAndClose -> confirmSaveAndClose()
            EditorEvent.ConfirmDiscardAndClose -> {
                val pending = _uiState.value.pendingClose ?: return
                _uiState.update { it.copy(pendingClose = null) }
                closeNow(pending.paths)
            }
            EditorEvent.DismissPendingClose -> _uiState.update { it.copy(pendingClose = null) }
            is EditorEvent.ReloadFromDisk -> reloadFromDisk(event.path)
            is EditorEvent.KeepMyEdits -> clearExternalChangeUi(event.path)
            is EditorEvent.CloseDeletedTab -> closeNow(listOf(event.path))
            is EditorEvent.KeepAsNewFile -> {
                clearExternalChangeUi(event.path)
                setDirty(event.path, true) // Nothing on disk to match anymore -- next save recreates it.
            }
            is EditorEvent.ChangePage -> changePage(
                event.path,
                event.toIndex,
                PagedEditSession.PageViewState(
                    event.fromCursorLine,
                    event.fromCursorColumn,
                    event.fromScrollX,
                    event.fromScrollY,
                ),
            )
            is EditorEvent.CyclePreviewMode -> cyclePreviewMode(event.path)
            is EditorEvent.ClosePreview -> closePreview(event.path)
            is EditorEvent.SetSplitRatio -> setSplitRatio(event.path, event.ratio)
            is EditorEvent.SetHtmlJsEnabled -> setHtmlJsEnabled(event.path, event.enabled)
        }
    }

    private fun cyclePreviewMode(path: String) {
        val tab = _uiState.value.tabs.firstOrNull { it.path == path } ?: return
        if (!tab.previewType.isTextPreview) return
        _uiState.update { state ->
            state.copy(tabs = state.tabs.map { if (it.path == path) it.copy(previewMode = it.previewMode.next()) else it })
        }
    }

    private fun closePreview(path: String) {
        _uiState.update { state ->
            state.copy(tabs = state.tabs.map { if (it.path == path) it.copy(previewMode = PreviewMode.EDITOR) else it })
        }
    }

    private fun setSplitRatio(path: String, ratio: Float) {
        buffers[path]?.previewSplitRatio = ratio.coerceIn(MIN_SPLIT_RATIO, MAX_SPLIT_RATIO)
    }

    private fun setHtmlJsEnabled(path: String, enabled: Boolean) {
        val buffer = buffers[path] ?: return
        buffer.htmlJsEnabled = enabled
        buffer.htmlJsPromptShown = true
        // Bumps nothing observable -- PreviewPane reads the buffer directly on next recompose,
        // and this only ever follows a user tap on the dialog that triggered it.
    }

    override fun onCleared() {
        watcher.stop()
    }

    /** Called from the editor screen's ON_RESUME: catches changes FileObserver may have missed. */
    fun onResumeCheck() {
        viewModelScope.launch {
            for (path in buffers.keys.toList()) {
                checkForExternalChange(path)
            }
        }
    }

    /** The editor view reports every real edit (not the initial text load). */
    fun onEdited(path: String) {
        val buffer = buffers[path] ?: return
        buffer.revision++
        val tab = _uiState.value.tabs.firstOrNull { it.path == path }
        if (tab != null && !tab.dirty) setDirty(path, true)
        // Markdown/HTML preview's redraw signal -- cheap to bump unconditionally since it's only
        // ever observed by the currently visible split/preview pane (see PreviewPane).
        _uiState.update { it.copy(activeContentRevision = it.activeContentRevision + 1) }
        schedulePersist()
    }

    /** The editor view hands back cursor, zoom and scroll when a tab's view is torn down. */
    fun onViewState(path: String, line: Int, column: Int, textSizePx: Float, scrollX: Int, scrollY: Int) {
        val buffer = buffers[path] ?: return
        if (buffer.suppressNextViewStateCapture) {
            // This teardown is goToPage()'s own view swap: cursorLine/scrollX etc already hold
            // the *incoming* page's restored position, so a report of the *outgoing* page's
            // cursor here must not overwrite them. Still worth learning the zoom level from it.
            buffer.suppressNextViewStateCapture = false
            if (textSizePx > 0f && textSizePx != buffer.textSizePx) {
                buffer.textSizePx = textSizePx
                defaultFontSizePx = textSizePx
                viewModelScope.launch { settingsStore.setFontSizePx(textSizePx) }
            }
            return
        }
        buffer.cursorLine = line
        buffer.cursorColumn = column
        buffer.scrollX = scrollX
        buffer.scrollY = scrollY
        // Last size zoomed to anywhere becomes the default for the *next* newly opened tab.
        if (textSizePx > 0f && textSizePx != buffer.textSizePx) {
            buffer.textSizePx = textSizePx
            defaultFontSizePx = textSizePx
            viewModelScope.launch { settingsStore.setFontSizePx(textSizePx) }
        }
        schedulePersist()
    }

    private fun open(file: File, jumpToLine: Int? = null, matchColumn: Int = -1, matchLength: Int = 0) {
        val previewType = PreviewRouter.typeOf(file)
        if (previewType.isMedia) {
            // Binary -- never a text tab. Full-screen preview route handles it from here.
            _mediaPreviewFile.value = file
            _openMediaPreview.tryEmit(file)
            return
        }
        val path = file.path
        viewModelScope.launch { settingsStore.addRecentFile(path) } // file search: recent list
        if (path in buffers) {
            _uiState.update { it.copy(activePath = path) }
            jumpToLine?.let {
                // Both: a view that exists reacts to the live request, one that appears later reads the buffer.
                buffers[path]?.let { b ->
                    b.pendingJumpLine = (it - 1).coerceAtLeast(0)
                    b.pendingFlashColumn = matchColumn
                    b.pendingFlashLength = matchLength
                }
                _jumpToLine.tryEmit(JumpRequest(path, it, matchColumn, matchLength))
            }
            _showEditor.tryEmit(Unit)
            return
        }
        if (!loading.add(path)) return
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            val result: Result<TabBuffer> = withContext(io) {
                try {
                    Result.success(loadBuffer(file))
                } catch (e: IOException) {
                    Result.failure(e)
                } catch (e: OutOfMemoryError) {
                    Result.failure(FileTooLargeException(file.length()))
                }
            }
            loading.remove(path)
            _uiState.update { it.copy(loading = loading.isNotEmpty()) }
            result.fold(
                onSuccess = { buffer ->
                    // M11: naye tab ka initial cursor/scroll search line se set ho jayega
                    // (CodeEditorView factory buffer se restore karta hai).
                    jumpToLine?.let {
                        buffer.cursorLine = (it - 1).coerceAtLeast(0)
                        buffer.cursorColumn = 0
                        buffer.scrollY = 0
                        buffer.pendingJumpLine = (it - 1).coerceAtLeast(0)
                        buffer.pendingFlashColumn = matchColumn
                        buffer.pendingFlashLength = matchLength
                    }
                    buffers[path] = buffer
                    val session = buffer.pagedSession
                    _uiState.update {
                        it.copy(
                            tabs = it.tabs + EditorTabUi(
                                path,
                                file.name,
                                dirty = false,
                                pageIndex = session?.let { 0 },
                                pageCount = session?.pageCount,
                                previewType = previewType,
                                // Auto: markdown/html land straight in split, per M5 UX decision.
                                previewMode = if (previewType.isTextPreview && autoPreview) PreviewMode.SPLIT else PreviewMode.EDITOR,
                            ),
                            activePath = path,
                        )
                    }
                    _showEditor.tryEmit(Unit)
                    schedulePersist()
                    rewatch()
                    if (session != null) {
                        _messages.tryEmit(UiText(R.string.editor_paged_opened, listOf(session.pageCount)))
                    }
                },
                onFailure = { error ->
                    val text = if (error is FileTooLargeException) {
                        UiText(R.string.editor_err_too_large, listOf(file.name))
                    } else {
                        UiText(R.string.editor_err_read, listOf(file.name))
                    }
                    _messages.tryEmit(text)
                },
            )
        }
    }

    /**
     * Reads [file] off the calling (IO) thread and builds its [TabBuffer]. A file at or under
     * [TextFileIo.MAX_BYTES] gets the normal whole-file path; above it, this falls back to
     * [TextFileIo.readForPaging] and a [PagedEditSession] (M4 "paged large file") instead of
     * giving up. Throws [IOException] (a [FileTooLargeException] past the paging hard cap, or
     * any other read failure) or [OutOfMemoryError] -- callers already handle both.
     */
    @Throws(IOException::class)
    private fun tokensForLearning(text: String): Set<String>? =
        if (text.length <= LEARN_MAX_CHARS) textMate.userWords.tokens(text) else null

    private fun loadBuffer(file: File): TabBuffer {
        val buffer = try {
            val loaded = TextFileIo.read(file)
            TabBuffer(
                file = file,
                content = Content(loaded.text),
                charset = loaded.charset,
                hasBom = loaded.hasBom,
                lineEnding = loaded.lineEnding,
            ).apply {
                diskContentHash = EditorSessionStore.hashOf(loaded.text)
                knownTokens = tokensForLearning(loaded.text)
            }
        } catch (_: FileTooLargeException) {
            val loaded = TextFileIo.readForPaging(file)
            val session = PagedEditSession(loaded.text)
            TabBuffer(
                file = file,
                content = Content(session.textForPage(0)),
                charset = loaded.charset,
                hasBom = loaded.hasBom,
                lineEnding = loaded.lineEnding,
                pagedSession = session,
            ).apply { diskContentHash = EditorSessionStore.hashOf(loaded.text) }
        }
        buffer.lastKnownDiskModified = file.lastModified()
        if (defaultFontSizePx > 0f) buffer.textSizePx = defaultFontSizePx
        return buffer
    }

    /** Paged large file (M4): move [path] to page [toIndex], see [TabBuffer.goToPage]. */
    private fun changePage(path: String, toIndex: Int, outgoing: PagedEditSession.PageViewState) {
        val buffer = buffers[path] ?: return
        val session = buffer.pagedSession ?: return
        if (toIndex == session.currentPageIndex) return
        buffer.goToPage(toIndex, outgoing)
        // The screen keys its CodeEditorView on the tab's pageIndex, so this has to be real UI
        // state (not just live on the TabBuffer) for the swapped-in content to ever be shown.
        _uiState.update { state ->
            state.copy(tabs = state.tabs.map { if (it.path == path) it.copy(pageIndex = session.currentPageIndex) else it })
        }
        schedulePersist()
    }

    private fun isDirty(path: String): Boolean = buffers[path]?.isDirty == true

    private fun setDirty(path: String, dirty: Boolean) {
        _uiState.update { state ->
            state.copy(tabs = state.tabs.map { if (it.path == path) it.copy(dirty = dirty) else it })
        }
    }

    private fun togglePin(path: String) {
        val buffer = buffers[path] ?: return
        buffer.isPinned = !buffer.isPinned
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { if (it.path == path) it.copy(isPinned = buffer.isPinned) else it },
            )
        }
        schedulePersist()
    }

    private fun reorder(from: Int, to: Int) {
        val tabs = _uiState.value.tabs
        if (from !in tabs.indices || to !in tabs.indices || from == to) return
        val mutable = tabs.toMutableList()
        val moved = mutable.removeAt(from)
        mutable.add(to, moved)
        _uiState.update { it.copy(tabs = mutable) }
        schedulePersist()
    }

    private fun requestClose(paths: List<String>) {
        if (paths.isEmpty()) return
        val dirty = paths.filter(::isDirty)
        if (dirty.isEmpty()) {
            closeNow(paths)
        } else {
            val names = dirty.map { buffers[it]?.file?.name ?: it }
            _uiState.update { it.copy(pendingClose = PendingClose(paths, names)) }
        }
    }

    private fun confirmSaveAndClose() {
        val pending = _uiState.value.pendingClose ?: return
        _uiState.update { it.copy(pendingClose = null) }
        viewModelScope.launch {
            // If any save fails the tabs stay open, so nothing is lost.
            if (saveBuffers(pending.paths.filter(::isDirty))) closeNow(pending.paths)
        }
    }

    private fun closeNow(paths: Collection<String>) {
        val closing = paths.toSet()
        val state = _uiState.value
        var active = state.activePath
        if (active != null && active in closing) {
            // Land on the tab to the right, else the nearest one to the left.
            val index = state.tabs.indexOfFirst { it.path == active }
            active = state.tabs.drop(index + 1).firstOrNull { it.path !in closing }?.path
                ?: state.tabs.take(index).lastOrNull { it.path !in closing }?.path
        }
        closing.forEach { buffers.remove(it) }
        _uiState.update { it.copy(tabs = it.tabs.filter { tab -> tab.path !in closing }, activePath = active) }
        schedulePersist()
        rewatch()
    }

    private fun rewatch() {
        watcher.setWatchedFiles(buffers.keys)
    }

    /** FileObserver fired for some path in a watched dir; may not even be one of our tabs. */
    private fun onDiskEvent(changedPath: String) {
        if (changedPath !in buffers) return
        // Coalesce a burst (e.g. editors that write-then-touch) into a single check.
        externalChangeJobs[changedPath]?.cancel()
        externalChangeJobs[changedPath] = viewModelScope.launch {
            delay(EXTERNAL_EVENT_DEBOUNCE_MS)
            checkForExternalChange(changedPath)
        }
    }

    /**
     * The one place that decides whether a tab's file actually changed underneath it. Used by
     * both the live watcher and [onResumeCheck]'s fallback sweep, so a missed inotify event and
     * a live one are handled identically. Disk I/O runs on [io]; every [TabBuffer] field this
     * reads or writes happens back on the main thread, per [TabBuffer]'s own threading contract.
     */
    private suspend fun checkForExternalChange(path: String) {
        val file = buffers[path]?.file ?: return
        val snapshot = withContext(io) {
            if (!file.exists()) {
                null
            } else {
                val mtime = file.lastModified()
                val text = try {
                    TextFileIo.read(file).text
                } catch (_: IOException) {
                    return@withContext DiskSnapshot(mtime, null) // Unreadable mid-write; retry later.
                }
                DiskSnapshot(mtime, text)
            }
        }
        val buffer = buffers[path] ?: return // Tab closed while I/O was in flight.
        if (buffer.externalChange != ExternalChange.None) return // Already flagged, awaiting user.
        if (snapshot == null) {
            markDeleted(path)
            return
        }
        if (snapshot.text == null) return // Read failed; a later CLOSE_WRITE event retries.
        if (snapshot.mtime == buffer.lastKnownDiskModified) return // Cheap pre-filter: nothing moved.
        val diskHash = EditorSessionStore.hashOf(snapshot.text)
        if (diskHash == buffer.diskContentHash) {
            // Only mtime moved (e.g. touch, or a re-save of identical content) -- not a real change.
            buffer.lastKnownDiskModified = snapshot.mtime
            return
        }
        if (buffer.isDirty && !autoReloadExternal) {
            buffer.externalChange = ExternalChange.Modified
            setExternalChangeUi(path, ExternalChange.Modified)
        } else {
            // No unsaved edits to lose, or the user has chosen to always reload automatically
            // (settings screen "File changed outside the app") -- pull in what changed, silently.
            applyReloadedContent(path, snapshot.text, snapshot.mtime, diskHash)
        }
    }

    private class DiskSnapshot(val mtime: Long, val text: String?)

    private fun markDeleted(path: String) {
        val buffer = buffers[path] ?: return
        if (buffer.externalChange == ExternalChange.Deleted) return
        buffer.externalChange = ExternalChange.Deleted
        setExternalChangeUi(path, ExternalChange.Deleted)
    }

    private fun setExternalChangeUi(path: String, change: ExternalChange) {
        _uiState.update { state ->
            state.copy(tabs = state.tabs.map { if (it.path == path) it.copy(externalChange = change) else it })
        }
    }

    private fun clearExternalChangeUi(path: String) {
        buffers[path]?.externalChange = ExternalChange.None
        setExternalChangeUi(path, ExternalChange.None)
    }

    private fun reloadFromDisk(path: String) {
        val buffer = buffers[path] ?: return
        viewModelScope.launch {
            val loaded = withContext(io) {
                try {
                    TextFileIo.read(buffer.file)
                } catch (_: IOException) {
                    null
                }
            }
            if (loaded == null) {
                _messages.tryEmit(UiText(R.string.editor_err_read, listOf(buffer.file.name)))
                return@launch
            }
            applyReloadedContent(path, loaded.text, buffer.file.lastModified(), EditorSessionStore.hashOf(loaded.text))
        }
    }

    /** Replaces a buffer's content with what's now on disk and clears the dirty/external state. */
    private fun applyReloadedContent(path: String, text: String, mtime: Long, hash: String) {
        val buffer = buffers[path] ?: return
        buffer.content.replace(0, buffer.content.length, text)
        buffer.revision++
        buffer.savedRevision = buffer.revision
        buffer.diskContentHash = hash
        buffer.knownTokens = tokensForLearning(text) // disk text isn't "typed", so don't learn it
        buffer.lastKnownDiskModified = mtime
        buffer.externalChange = ExternalChange.None
        setDirty(path, false)
        setExternalChangeUi(path, ExternalChange.None)
    }

    private fun save(paths: List<String>) {
        if (paths.isEmpty()) return
        viewModelScope.launch { saveBuffers(paths) }
    }

    /** Saves each buffer; returns true only if every one succeeded. */
    private suspend fun saveBuffers(paths: List<String>): Boolean {
        var allOk = true
        val savedFiles = ArrayList<File>()
        for (path in paths) {
            val buffer = buffers[path] ?: continue
            // Snapshot on the main thread (the editor mutates Content there), write on IO. For a
            // paged buffer this is every page merged, not just the one currently on screen.
            val text = buffer.snapshotForSave()
            val revision = buffer.revision
            // Hashing happens alongside the write, not after: cheap at the old 32MB cap, but a
            // paged file can be up to TextFileIo.HARD_MAX_BYTES and shouldn't hash on the main
            // thread.
            val hash = withContext(io) {
                try {
                    TextFileIo.write(buffer.file, text, buffer.charset, buffer.hasBom, buffer.lineEnding)
                    // Words newly typed since the last open/save become autocomplete suggestions everywhere.
                    if (!buffer.isPaged && text.length <= LEARN_MAX_CHARS) {
                        buffer.knownTokens = textMate.userWords.learn(buffer.knownTokens, text)
                    }
                    EditorSessionStore.hashOf(text)
                } catch (_: IOException) {
                    null
                }
            }
            val ok = hash != null
            if (ok) {
                savedFiles += buffer.file
                buffer.pagedSession?.markSaved()
                buffer.savedRevision = revision
                buffer.diskContentHash = hash
                buffer.lastKnownDiskModified = buffer.file.lastModified()
                buffer.externalChange = ExternalChange.None
                clearExternalChangeUi(path)
                // Typed more while the write was running? Stay dirty.
                setDirty(path, buffer.isDirty)
            } else {
                allOk = false
                _messages.tryEmit(UiText(R.string.editor_err_save, listOf(buffer.file.name)))
            }
        }
        if (allOk) schedulePersist()
        if (savedFiles.isNotEmpty()) {
            projectPath?.let { root ->
                viewModelScope.launch(io) {
                    runCatching { com.invictus.kodex.feature.git.GitViewModel.onFilesSaved(root, savedFiles) }
                }
            }
        }
        return allOk
    }

    /** Debounced so typing doesn't write JSON to disk on every keystroke. */
    private fun schedulePersist() {
        val path = projectPath ?: return
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            val state = buildSessionState(path)
            withContext(io) { sessionStore.save(state) }
        }
    }

    private fun buildSessionState(projectPath: String): SessionState {
        val ui = _uiState.value
        val tabs = ui.tabs.mapNotNull { tabUi ->
            val buffer = buffers[tabUi.path] ?: return@mapNotNull null
            SessionTab(
                path = tabUi.path,
                name = tabUi.name,
                isPinned = buffer.isPinned,
                cursorLine = buffer.cursorLine,
                cursorColumn = buffer.cursorColumn,
                scrollX = buffer.scrollX,
                scrollY = buffer.scrollY,
                contentHash = buffer.diskContentHash,
                // A paged file's edits never ride along in the session snapshot -- unlike a
                // normal tab's content this could be hundreds of MB, so an unsaved edit to a
                // paged file doesn't survive the app being killed; only an actual save does.
                pendingEditSnapshot = if (buffer.isDirty && !buffer.isPaged) buffer.content.toString() else null,
            )
        }
        return SessionState(projectPath = projectPath, activePath = ui.activePath, tabs = tabs)
    }

    private suspend fun restoreSession(session: SessionState?) {
        if (session == null || session.tabs.isEmpty()) return
        val restoredTabs = mutableListOf<EditorTabUi>()
        var droppedUnsavedEdits = 0
        for (saved in session.tabs) {
            val file = File(saved.path)
            if (!file.exists() || !file.isFile) continue // Gone since last session: drop the tab.

            var normalLoaded: LoadedText? = null
            var pagedBuffer: TabBuffer? = null
            withContext(io) {
                try {
                    normalLoaded = TextFileIo.read(file)
                } catch (_: FileTooLargeException) {
                    // Grew past MAX_BYTES since, or was already a paged tab: same fallback as
                    // open(), just without loadBuffer()'s own try/read (already know it's this).
                    pagedBuffer = try {
                        loadBuffer(file)
                    } catch (_: IOException) {
                        null
                    } catch (_: OutOfMemoryError) {
                        null
                    }
                } catch (_: IOException) {
                    // Left both null -- unreadable, dropped below like a gone file.
                }
            }

            val buffer: TabBuffer
            val dirty: Boolean
            val loaded = normalLoaded
            when {
                pagedBuffer != null -> {
                    buffer = pagedBuffer!!
                    // Which page was open, and any unsaved edit to it, weren't persisted (see
                    // buildSessionState) -- reopens fresh at page 1 rather than guess.
                    dirty = false
                }
                loaded != null -> {
                    val currentHash = EditorSessionStore.hashOf(loaded.text)
                    val trustSnapshot = saved.pendingEditSnapshot != null && currentHash == saved.contentHash
                    if (saved.pendingEditSnapshot != null && !trustSnapshot) droppedUnsavedEdits++
                    val text = if (trustSnapshot) saved.pendingEditSnapshot!! else loaded.text
                    buffer = TabBuffer(
                        file = file,
                        content = Content(text),
                        charset = loaded.charset,
                        hasBom = loaded.hasBom,
                        lineEnding = loaded.lineEnding,
                    ).apply {
                        diskContentHash = if (trustSnapshot) saved.contentHash else currentHash
                        if (trustSnapshot) revision = 1 // savedRevision stays 0 -> dirty
                        cursorLine = saved.cursorLine
                        cursorColumn = saved.cursorColumn
                        scrollX = saved.scrollX
                        scrollY = saved.scrollY
                    }
                    dirty = trustSnapshot
                }
                else -> continue // Unreadable, or past even the paging hard cap: drop this tab.
            }
            buffer.isPinned = saved.isPinned
            if (defaultFontSizePx > 0f) buffer.textSizePx = defaultFontSizePx
            buffer.lastKnownDiskModified = file.lastModified()
            buffers[saved.path] = buffer
            val previewType = PreviewRouter.typeOf(file)
            restoredTabs += EditorTabUi(
                saved.path,
                saved.name,
                dirty = dirty,
                isPinned = saved.isPinned,
                pageIndex = buffer.pagedSession?.let { 0 },
                pageCount = buffer.pagedSession?.pageCount,
                previewType = previewType,
                previewMode = if (previewType.isTextPreview && autoPreview) PreviewMode.SPLIT else PreviewMode.EDITOR,
            )
        }
        if (restoredTabs.isEmpty()) return
        val active = session.activePath?.takeIf { p -> restoredTabs.any { it.path == p } } ?: restoredTabs.first().path
        _uiState.update { it.copy(tabs = restoredTabs, activePath = active) }
        if (droppedUnsavedEdits > 0) {
            _messages.tryEmit(UiText(R.string.editor_session_edits_stale, listOf(droppedUnsavedEdits)))
        }
        _showEditor.tryEmit(Unit)
        rewatch()
    }

    companion object {
        /** Files larger than this are skipped for word learning (tokenizing them would be slow). */
        private const val LEARN_MAX_CHARS = 500_000
        private const val SESSION_LOAD_KEY = "__session__"
        private const val PERSIST_DEBOUNCE_MS = 600L
        private const val EXTERNAL_EVENT_DEBOUNCE_MS = 400L
        private const val MIN_SPLIT_RATIO = 0.15f
        private const val MAX_SPLIT_RATIO = 0.85f

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                EditorViewModel(app.container.textMate, app.container.editorSessionStore, app.container.editorSettingsStore)
            }
        }
    }
}
