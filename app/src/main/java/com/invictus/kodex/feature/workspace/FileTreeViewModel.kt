package com.invictus.kodex.feature.workspace

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.R
import com.invictus.kodex.KodexApp
import com.invictus.kodex.core.editor.EditorSettingsStore
import com.invictus.kodex.core.fs.DirectoryWatcher
import com.invictus.kodex.core.fs.FileOpenPolicy
import com.invictus.kodex.core.fs.FileOps
import com.invictus.kodex.core.fs.FsEntry
import com.invictus.kodex.core.fs.FsError
import com.invictus.kodex.core.fs.FsResult
import com.invictus.kodex.core.fs.OpenDecision
import com.invictus.kodex.core.git.GitRepoWatcher
import com.invictus.kodex.core.git.GitResult
import com.invictus.kodex.core.git.GitSession
import com.invictus.kodex.core.git.model.GitPathDecoration
import com.invictus.kodex.core.git.model.GitStageState
import com.invictus.kodex.core.git.model.GitWorkingState
import com.invictus.kodex.core.git.model.GitWorkingTreeStatus
import com.invictus.kodex.core.project.PathUtil
import com.invictus.kodex.core.project.ProjectRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the workspace tree. Single-threaded by design: all mutable state below is only
 * touched from the main thread; disk work happens inside [FileOps] on Dispatchers.IO.
 *
 * Expanded folders and their listed children are kept in [expanded] / [children] and the
 * visible rows are re-derived from them, so a refresh never loses what the user had open.
 */
class FileTreeViewModel(
    private val fileOps: FileOps,
    private val openPolicy: FileOpenPolicy,
    private val projects: ProjectRepository,
    private val storageRoot: File,
    private val settingsStore: EditorSettingsStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** The open project folder. Starts on device storage until the last project is restored. */
    private var root: File = storageRoot

    private val expanded = linkedSetOf(root.path)
    private val children = HashMap<String, List<FsEntry>>()
    private val loading = HashSet<String>()
    private val loadJobs = HashMap<String, Job>()

    private val _uiState = MutableStateFlow(
        FileTreeUiState(
            root = root,
            rootName = null,
        ),
    )
    val uiState: StateFlow<FileTreeUiState> = _uiState.asStateFlow()

    private val _effects = Channel<FileTreeEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val watchEvents = Channel<String>(Channel.UNLIMITED)
    private val watcher = DirectoryWatcher { dir -> watchEvents.trySend(dir.path) }

    /** Git-internal changes (Termux, another app) — refs/HEAD/index moving outside the app. */
    private val gitChangeEvents = Channel<Unit>(Channel.UNLIMITED)
    private val gitRepoWatcher = GitRepoWatcher { gitChangeEvents.trySend(Unit) }

    /** False until the last project is restored, so the tree never flashes the wrong root. */
    private var ready = false

    private val rootPath = MutableStateFlow(root.path)
    private val pinTick = MutableStateFlow(0)
    private var gitSession: GitSession? = null

    /** Mirrors the Settings "Git status detection" toggle for use in non-suspend call sites. */
    private var gitStatusPollingEnabled: Boolean = false

    init {
        viewModelScope.launch { debounceWatcherEvents() }
        viewModelScope.launch { debounceGitEvents() }
        viewModelScope.launch { observePins() }
        viewModelScope.launch { restoreLastProject() }
        viewModelScope.launch {
            settingsStore.fileTreeShowHidden.collect { on -> publish { copy(showHidden = on) } }
        }
        viewModelScope.launch {
            settingsStore.searchDefaultStringsOnly.collect { on -> publish { copy(searchDefaultStringsOnly = on) } }
        }
        viewModelScope.launch {
            settingsStore.fileTreeHScrollLongNames.collect { on -> publish { copy(hScrollLongNames = on) } }
        }
        viewModelScope.launch {
            settingsStore.gitStatusPollingEnabled.collect { enabled ->
                val turnedOn = enabled && !gitStatusPollingEnabled
                val turnedOff = !enabled && gitStatusPollingEnabled
                gitStatusPollingEnabled = enabled
                when {
                    turnedOff -> {
                        gitRepoWatcher.stop()
                        if (_uiState.value.gitDecorations.isNotEmpty()) publish { copy(gitDecorations = emptyMap()) }
                    }
                    turnedOn -> {
                        gitRepoWatcher.start(File(root, GIT_DIR))
                        refreshGitDecorations()
                    }
                }
            }
        }
    }

    fun onEvent(event: FileTreeEvent) {
        when (event) {
            is FileTreeEvent.RowClicked -> onRowClicked(event.file, event.isDirectory)
            FileTreeEvent.Refresh -> refreshAll()
            FileTreeEvent.CollapseAll -> collapseAll()
            is FileTreeEvent.SetShowHidden -> {
                publish { copy(showHidden = event.value) }
                viewModelScope.launch { settingsStore.setFileTreeShowHidden(event.value) }
            }
            is FileTreeEvent.SetShowGitFolder -> publish { copy(showGitFolder = event.value) }

            is FileTreeEvent.StartCreate ->
                publish { copy(dialog = TreeDialog.Create(event.parent, event.isFolder), renaming = null) }
            is FileTreeEvent.ConfirmCreate -> confirmCreate(event.name)
            FileTreeEvent.DismissDialog -> publish { copy(dialog = null) }

            is FileTreeEvent.StartRename -> startRename(event.file)
            is FileTreeEvent.CommitRename -> commitRename(event.newName)
            FileTreeEvent.CancelRename -> publish { if (renaming != null) copy(renaming = null) else this }

            is FileTreeEvent.RequestDelete -> requestDelete(event.file, event.isDirectory)
            FileTreeEvent.ConfirmDelete -> confirmDelete()

            is FileTreeEvent.Duplicate -> duplicate(event.file)
            is FileTreeEvent.Cut -> setClipboard(event.file, isCut = true)
            is FileTreeEvent.Copy -> setClipboard(event.file, isCut = false)
            is FileTreeEvent.PasteInto -> paste(event.dir)
            FileTreeEvent.ClearClipboard -> publish { copy(clipboard = null) }

            is FileTreeEvent.ConfirmOpen -> {
                publish { copy(dialog = null) }
                _effects.trySend(FileTreeEffect.OpenFile(event.file))
            }

            is FileTreeEvent.OpenProject -> openProject(event.dir)
            is FileTreeEvent.ProjectMoved -> onProjectMoved(event.old, event.new)
            is FileTreeEvent.ProjectDeleted -> onProjectDeleted(event.dir)
            is FileTreeEvent.TogglePin -> togglePin(event.file, event.isDirectory)
            is FileTreeEvent.Reveal -> reveal(event.file, event.isDirectory)
            is FileTreeEvent.StartUpload -> _effects.trySend(FileTreeEffect.PickUploadFiles(event.dir))
            is FileTreeEvent.UploadFinished -> onUploadFinished(event.dir, event.files)
            is FileTreeEvent.SetDeleteAfterExtract -> publish {
                val d = dialog
                if (d is TreeDialog.ExtractZip) copy(dialog = d.copy(deleteAfter = event.value)) else this
            }
            FileTreeEvent.ConfirmExtract -> confirmExtract()
            FileTreeEvent.SkipExtract -> nextZipOrClose()
        }
    }

    // region project / pins

    private suspend fun restoreLastProject() {
        // A project just opened from Home / All Projects is handed over directly (no wait for the recents write).
        val handed = handoffPath?.also { handoffPath = null }?.let(::File)?.takeIf { withContext(io) { it.isDirectory && it.canRead() } }
        val last = handed ?: projects.latestRecent()?.takeIf { withContext(io) { it.isDirectory && it.canRead() } }
        switchRoot(last ?: storageRoot, record = false)
    }

    private fun openProject(dir: File) {
        viewModelScope.launch { openProjectAndWait(dir) }
    }

    /**
     * Opens [dir] and returns true once it is the tree's root. The workspace screen builds a fresh tree and
     * restores the project from [handoffPath] (set here, instantly) so it never reopens the previously opened
     * project while the recents write is still running; the write itself stays in the background.
     * False when the folder is not available.
     */
    suspend fun openProjectAndWait(dir: File): Boolean {
        val ok = withContext(io) { dir.isDirectory && dir.canRead() }
        if (!ok) {
            _effects.send(FileTreeEffect.Message(UiText(R.string.msg_folder_unavailable)))
            return false
        }
        handoffPath = dir.path
        if (dir.path != root.path) switchRoot(dir, record = true) else viewModelScope.launch { projects.recordOpened(dir) }
        return true
    }

    private fun onProjectMoved(old: File, new: File) {
        if (!PathUtil.isSameOrUnder(root.path, old.path)) return
        switchRoot(File(PathUtil.rebase(root.path, old.path, new.path)), record = false)
    }

    /** The open project's folder is gone: fall back to the device root rather than a dead path. */
    private fun onProjectDeleted(dir: File) {
        if (!PathUtil.isSameOrUnder(root.path, dir.path)) return
        switchRoot(storageRoot, record = false)
    }

    /** Points the tree at [newRoot], dropping the previous project's expansion state. */
    private fun switchRoot(newRoot: File, record: Boolean) {
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()
        loading.clear()
        children.clear()
        expanded.clear()
        root = newRoot
        ready = true
        expanded += newRoot.path
        rootPath.value = newRoot.path
        publish {
            copy(
                root = newRoot,
                rootName = if (newRoot.path == storageRoot.path) null else newRoot.name.ifEmpty { newRoot.path },
                renaming = null,
                dialog = null,
                rootError = null,
            )
        }
        loadDir(newRoot)
        syncWatcher()
        gitSession = null
        if (gitStatusPollingEnabled) {
            gitRepoWatcher.start(File(newRoot, GIT_DIR))
            refreshGitDecorations()
        } else {
            gitRepoWatcher.stop()
        }
        if (record) viewModelScope.launch { projects.recordOpened(newRoot) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observePins() {
        combine(rootPath.flatMapLatest { projects.observePins(it) }, pinTick) { pins, _ -> pins }
            // Pins whose file was deleted/moved outside the app are hidden, not shown as dead rows.
            .mapLatest { pins -> withContext(io) { pins.filter { it.file.exists() } } }
            .collect { pins ->
                publish { copy(pins = pins, pinnedPaths = pins.mapTo(HashSet()) { it.file.path }) }
            }
    }

    private fun togglePin(file: File, isDirectory: Boolean) {
        if (file.path == root.path) return
        val pinned = file.path !in _uiState.value.pinnedPaths
        viewModelScope.launch { projects.setPinned(root.path, file, isDirectory, pinned) }
    }

    private fun reveal(file: File, isDirectory: Boolean) {
        if (!PathUtil.isSameOrUnder(file.path, root.path)) return
        viewModelScope.launch {
            val chain = generateSequence(file.parentFile) { it.parentFile }
                .takeWhile { PathUtil.isSameOrUnder(it.path, root.path) }
                .toList()
                .asReversed() + if (isDirectory) listOf(file) else emptyList()
            for (dir in chain) {
                if (expanded.add(dir.path)) loadDir(dir)
                loadJobs[dir.path]?.join()
            }
            publish()
            syncWatcher()
            _effects.send(FileTreeEffect.ScrollTo(file.path))
        }
    }

    // endregion

    // region tree state

    private fun onRowClicked(file: File, isDirectory: Boolean) {
        if (isDirectory) {
            toggleDir(file)
            return
        }
        viewModelScope.launch {
            when (val decision = openPolicy.check(file)) {
                OpenDecision.Open -> _effects.send(FileTreeEffect.OpenFile(file))
                else -> publish { copy(dialog = TreeDialog.OpenWarning(file, decision)) }
            }
        }
    }

    private fun toggleDir(dir: File) {
        if (!expanded.remove(dir.path)) {
            expanded += dir.path
            loadDir(dir)
        }
        publish()
        syncWatcher()
    }

    private fun collapseAll() {
        expanded.clear()
        expanded += root.path
        publish()
        syncWatcher()
    }

    private fun refreshAll() {
        expanded.toList().forEach { loadDir(File(it)) }
        pinTick.update { it + 1 }
        refreshGitDecorations()
    }

    /** M7: refresh the git status stripe map for the open project (no-op outside a repo, or when disabled). */
    private fun refreshGitDecorations() {
        if (!gitStatusPollingEnabled) return
        val root = this.root
        viewModelScope.launch(io) {
            if (!File(root, ".git").isDirectory) {
                if (_uiState.value.gitDecorations.isNotEmpty()) {
                    publish { copy(gitDecorations = emptyMap()) }
                }
                return@launch
            }
            val session = gitSession ?: GitSession(root).also { gitSession = it }
            val status = (session.status() as? GitResult.Ok)?.value ?: return@launch
            val decorations = toDecorations(status)
            publish { copy(gitDecorations = decorations) }
        }
    }

    /** File + folder stripes; a folder shows the highest-priority state inside it. */
    private fun toDecorations(status: GitWorkingTreeStatus): Map<String, GitPathDecoration> {
        val map = HashMap<String, GitPathDecoration>()
        fun put(rel: String, kind: GitPathDecoration.Kind) {
            val existing = map[rel]
            if (existing == null || kind.priority > existing.kind.priority) {
                map[rel] = GitPathDecoration(rel, rel, kind)
            }
        }
        status.changes.forEach { change ->
            val kind = when {
                change.staged == GitStageState.CONFLICT ||
                    change.unstaged == GitWorkingState.CONFLICT -> GitPathDecoration.Kind.CONFLICT
                change.unstaged == GitWorkingState.UNTRACKED -> GitPathDecoration.Kind.UNTRACKED
                change.staged == GitStageState.ADDED -> GitPathDecoration.Kind.ADDED
                change.staged == GitStageState.DELETED ||
                    change.unstaged == GitWorkingState.DELETED -> GitPathDecoration.Kind.DELETED
                else -> GitPathDecoration.Kind.MODIFIED
            }
            put(change.repoRelativePath, kind)
            // Aggregate up to every parent folder (but not the root itself).
            var parent = change.repoRelativePath.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                put(parent, kind)
                parent = parent.substringBeforeLast('/', "")
            }
        }
        return map
    }

    private fun reloadIfExpanded(dir: File) {
        if (dir.path in expanded) loadDir(dir)
    }

    private fun loadDir(dir: File) {
        val path = dir.path
        loadJobs.remove(path)?.cancel()
        loading += path
        loadJobs[path] = viewModelScope.launch {
            when (val result = fileOps.list(dir)) {
                is FsResult.Ok -> {
                    children[path] = result.value
                    if (path == root.path) publish { copy(rootError = null) }
                }
                is FsResult.Err -> onListFailed(dir, result)
            }
            loading -= path
            loadJobs.remove(path)
            publish()
        }
        publish()
    }

    private fun onListFailed(dir: File, err: FsResult.Err) {
        if (dir.path == root.path) {
            children.remove(dir.path)
            publish { copy(rootError = err.toUiText()) }
            return
        }
        forget(dir)
        if (err.error == FsError.NOT_FOUND) {
            dir.parentFile?.let(::reloadIfExpanded)
        } else {
            _effects.trySend(FileTreeEffect.Message(err.toUiText()))
        }
        syncWatcher()
    }

    /** Drops [dir] and everything below it from the expanded set and the children cache. */
    private fun forget(dir: File) {
        val path = dir.path
        val below = path + File.separator
        expanded.removeAll { it == path || it.startsWith(below) }
        children.keys.removeAll { it == path || it.startsWith(below) }
        loadJobs.keys.filter { it == path || it.startsWith(below) }.forEach { key ->
            loadJobs.remove(key)?.cancel()
            loading -= key
        }
    }

    /** A folder was renamed or moved: keep whatever was expanded under it expanded at the new path. */
    private fun remapExpanded(old: File, new: File) {
        val oldPath = old.path
        val below = oldPath + File.separator
        val remapped = expanded.filter { it == oldPath || it.startsWith(below) }
            .map { new.path + it.removePrefix(oldPath) }
        forget(old)
        expanded += remapped
        remapped.forEach { loadDir(File(it)) }
    }

    private fun syncWatcher() {
        watcher.setWatched(expanded.map { File(it) })
    }

    private suspend fun debounceWatcherEvents() {
        val pending = LinkedHashSet<String>()
        while (true) {
            pending += watchEvents.receive()
            delay(WATCH_DEBOUNCE_MS)
            while (true) {
                val next = watchEvents.tryReceive().getOrNull() ?: break
                pending += next
            }
            val batch = pending.toList()
            pending.clear()
            batch.filter { it in expanded }.forEach { loadDir(File(it)) }
        }
    }

    /** A `git` op run outside the app (Termux, etc.) — refresh the tree + decorations once. */
    private suspend fun debounceGitEvents() {
        while (true) {
            gitChangeEvents.receive()
            delay(WATCH_DEBOUNCE_MS)
            while (gitChangeEvents.tryReceive().isSuccess) { /* drain the rest of the burst */ }
            refreshAll()
        }
    }

    // endregion

    // region create / rename / delete

    private fun confirmCreate(name: String) {
        val pending = _uiState.value.dialog as? TreeDialog.Create ?: return
        viewModelScope.launch {
            val result = if (pending.isFolder) {
                fileOps.createFolder(pending.parent, name)
            } else {
                fileOps.createFile(pending.parent, name)
            }
            when (result) {
                is FsResult.Ok -> {
                    expanded += pending.parent.path
                    publish { copy(dialog = null) }
                    loadDir(pending.parent)
                    syncWatcher()
                }
                is FsResult.Err -> publish { copy(dialog = pending.copy(error = result.toUiText())) }
            }
        }
    }

    private fun startRename(file: File) {
        if (file.path == root.path) return
        publish { copy(renaming = RenameState(file), dialog = null) }
    }

    private fun commitRename(newName: String) {
        val state = _uiState.value.renaming ?: return
        if (newName.trim() == state.file.name) {
            publish { copy(renaming = null) }
            return
        }
        viewModelScope.launch {
            when (val result = fileOps.rename(state.file, newName)) {
                is FsResult.Ok -> {
                    projects.onPathMoved(state.file, result.value)
                    remapExpanded(state.file, result.value)
                    publish { copy(renaming = null) }
                    state.file.parentFile?.let(::reloadIfExpanded)
                    syncWatcher()
                }
                is FsResult.Err -> publish { copy(renaming = state.copy(error = result.toUiText())) }
            }
        }
    }

    private fun requestDelete(file: File, isDirectory: Boolean) {
        if (file.path == root.path) return
        publish { copy(dialog = TreeDialog.ConfirmDelete(file, isDirectory), renaming = null) }
    }

    private fun confirmDelete() {
        val pending = _uiState.value.dialog as? TreeDialog.ConfirmDelete ?: return
        publish { copy(dialog = null) }
        viewModelScope.launch {
            when (val result = fileOps.delete(pending.file)) {
                is FsResult.Ok -> {
                    projects.onPathDeleted(pending.file)
                    forget(pending.file)
                    val below = pending.file.path + File.separator
                    publish {
                        val clip = clipboard
                        val clipGone = clip != null &&
                            (clip.file.path == pending.file.path || clip.file.path.startsWith(below))
                        if (clipGone) copy(clipboard = null) else this
                    }
                    pending.file.parentFile?.let(::reloadIfExpanded)
                    syncWatcher()
                }
                is FsResult.Err -> _effects.send(FileTreeEffect.Message(result.toUiText()))
            }
        }
    }

    // endregion

    // region duplicate / clipboard

    private fun duplicate(file: File) {
        if (file.path == root.path) return
        viewModelScope.launch {
            when (val result = fileOps.duplicate(file)) {
                is FsResult.Ok -> file.parentFile?.let(::reloadIfExpanded)
                is FsResult.Err -> _effects.send(FileTreeEffect.Message(result.toUiText()))
            }
        }
    }

    private fun setClipboard(file: File, isCut: Boolean) {
        if (file.path == root.path) return
        publish { copy(clipboard = Clipboard(file, isCut)) }
    }

    private fun paste(dir: File) {
        val clip = _uiState.value.clipboard ?: return
        viewModelScope.launch {
            val result = if (clip.isCut) fileOps.moveInto(clip.file, dir) else fileOps.copyInto(clip.file, dir)
            when (result) {
                is FsResult.Ok -> {
                    if (clip.isCut) {
                        projects.onPathMoved(clip.file, result.value)
                        remapExpanded(clip.file, result.value)
                        publish { copy(clipboard = null) }
                        clip.file.parentFile?.let(::reloadIfExpanded)
                    }
                    expanded += dir.path
                    loadDir(dir)
                    syncWatcher()
                    val messageRes = if (clip.isCut) R.string.msg_moved else R.string.msg_pasted
                    _effects.send(FileTreeEffect.Message(UiText(messageRes, listOf(result.value.name))))
                }
                is FsResult.Err -> _effects.send(FileTreeEffect.Message(result.toUiText()))
            }
        }
    }

    // endregion

    // region upload / extract

    private fun onUploadFinished(dir: File, files: List<File>) {
        if (files.isEmpty()) {
            _effects.trySend(FileTreeEffect.Message(UiText(R.string.msg_upload_failed)))
            return
        }
        expanded += dir.path
        loadDir(dir)
        syncWatcher()
        _effects.trySend(FileTreeEffect.Message(UiText(R.string.msg_uploaded, listOf(files.size))))
        val zips = files.filter { it.extension.equals("zip", ignoreCase = true) }
        if (zips.isNotEmpty()) {
            publish { copy(dialog = TreeDialog.ExtractZip(zips.first(), dir, zips.drop(1))) }
        }
    }

    /** After Done / a finished extraction: ask about the next uploaded zip, or close the dialog. */
    private fun nextZipOrClose() {
        val d = _uiState.value.dialog as? TreeDialog.ExtractZip ?: return
        val next = d.queue.firstOrNull()
        publish { copy(dialog = next?.let { TreeDialog.ExtractZip(it, d.targetDir, d.queue.drop(1)) }) }
    }

    private fun confirmExtract() {
        val d = _uiState.value.dialog as? TreeDialog.ExtractZip ?: return
        if (d.busy) return
        publish { copy(dialog = d.copy(busy = true)) }
        viewModelScope.launch {
            val result = withContext(io) { runCatching { UploadOps.extractZip(d.zip, d.targetDir) } }
            result.onSuccess { extracted ->
                if (d.deleteAfter) withContext(io) { d.zip.delete() }
                expanded += d.targetDir.path
                loadDir(d.targetDir)
                syncWatcher()
                _effects.send(
                    FileTreeEffect.Message(UiText(R.string.msg_extracted, listOf(extracted.folder.name, extracted.fileCount))),
                )
            }.onFailure {
                _effects.send(FileTreeEffect.Message(UiText(R.string.msg_extract_failed, listOf(d.zip.name))))
            }
            nextZipOrClose()
        }
    }

    // endregion

    // region row building

    private fun publish(transform: FileTreeUiState.() -> FileTreeUiState = { this }) {
        _uiState.update { current ->
            val next = current.transform()
            next.copy(rows = if (ready) buildRows(next) else emptyList())
        }
    }

    private fun buildRows(state: FileTreeUiState): List<TreeRow> {
        val rows = ArrayList<TreeRow>()

        fun isVisible(entry: FsEntry): Boolean = when {
            entry.name == GIT_DIR -> state.showGitFolder
            entry.name.startsWith(".") -> state.showHidden
            else -> true
        }

        fun walk(dir: File, depth: Int) {
            val listed = children[dir.path] ?: return
            val visible = listed.filter(::isVisible)
            if (visible.isEmpty()) {
                rows += TreeRow.Empty(dir.path, depth)
                return
            }
            for (entry in visible) {
                val isOpen = entry.isDirectory && entry.file.path in expanded
                rows += TreeRow.Entry(
                    file = entry.file,
                    depth = depth,
                    isDirectory = entry.isDirectory,
                    isExpanded = isOpen,
                    isLoading = entry.file.path in loading && entry.file.path !in children,
                    isRoot = false,
                )
                if (isOpen) walk(entry.file, depth + 1)
            }
        }

        if (state.pins.isNotEmpty()) {
            rows += TreeRow.PinnedHeader
            state.pins.forEach { pin ->
                val parent = pin.file.parentFile?.path.orEmpty()
                val label = if (PathUtil.isSameOrUnder(parent, root.path)) {
                    parent.removePrefix(root.path).trimStart(File.separatorChar)
                } else {
                    parent
                }
                rows += TreeRow.Pinned(pin, label)
            }
            rows += TreeRow.Divider
        }

        val rootOpen = root.path in expanded
        rows += TreeRow.Entry(
            file = root,
            depth = 0,
            isDirectory = true,
            isExpanded = rootOpen,
            isLoading = root.path in loading && root.path !in children,
            isRoot = true,
        )
        if (rootOpen) walk(root, 1)
        return rows
    }

    // endregion

    override fun onCleared() {
        watcher.stop()
        gitRepoWatcher.stop()
    }

    companion object {
        /** Project the next workspace tree should open first (see [openProjectAndWait]). */
        @Volatile private var handoffPath: String? = null

        private const val GIT_DIR = ".git"
        private const val WATCH_DEBOUNCE_MS = 250L

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                FileTreeViewModel(
                    fileOps = app.container.fileOps,
                    openPolicy = app.container.fileOpenPolicy,
                    projects = app.container.projectRepository,
                    storageRoot = Environment.getExternalStorageDirectory(),
                    settingsStore = app.container.editorSettingsStore,
                )
            }
        }
    }
}
