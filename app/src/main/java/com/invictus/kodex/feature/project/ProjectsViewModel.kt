package com.invictus.kodex.feature.project

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.R
import com.invictus.kodex.KodexApp
import com.invictus.kodex.core.editor.EditorSettingsStore
import com.invictus.kodex.core.fs.FileOps
import com.invictus.kodex.core.fs.FsResult
import com.invictus.kodex.core.project.ProjectBackup
import com.invictus.kodex.core.project.ProjectRepository
import com.invictus.kodex.feature.workspace.UiText
import com.invictus.kodex.feature.workspace.toUiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.invictus.kodex.core.editor.ShortcutPref
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Drives the Open Project sheet: recents, the folder browser, and the per-recent actions
 * (backup ZIP, rename on disk, remove from recents). Opening a project is not done here:
 * the workspace owns the tree, so the UI forwards that to it.
 */
class ProjectsViewModel(
    private val projects: ProjectRepository,
    private val fileOps: FileOps,
    private val backup: ProjectBackup,
    private val storageRoot: File,
    private val settingsStore: EditorSettingsStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val existsTick = MutableStateFlow(0)
    private var browseJob: Job? = null
    private var infoJob: Job? = null

    /** Built-in Browse shortcuts that exist on this device, in default order. */
    private var defaultShortcuts: List<BrowseShortcut> = emptyList()

    /** The user's saved arrangement; empty until they edit it. */
    private var savedShortcuts: List<ShortcutPref> = emptyList()

    private val _uiState = MutableStateFlow(ProjectsUiState())
    val uiState: StateFlow<ProjectsUiState> = _uiState.asStateFlow()

    private val _effects = Channel<ProjectsEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch { observeRecents() }
        viewModelScope.launch { loadShortcuts() }
        viewModelScope.launch {
            settingsStore.browseShortcuts.collect { saved ->
                savedShortcuts = saved
                rebuildShortcuts()
            }
        }
        viewModelScope.launch {
            val stored = settingsStore.projectSheetShowHidden.first()
            _uiState.update { it.copy(showHidden = stored) }
        }
    }

    fun onEvent(event: ProjectsEvent) {
        when (event) {
            ProjectsEvent.SheetOpened -> {
                existsTick.update { it + 1 }
                browse(null)
            }
            is ProjectsEvent.Browse -> browse(event.dir)
            ProjectsEvent.BrowseUp -> browseUp()
            is ProjectsEvent.RemoveRecent -> viewModelScope.launch { projects.removeRecent(event.path) }
            is ProjectsEvent.TogglePin ->
                viewModelScope.launch { projects.setRecentPinned(event.path, event.pinned) }
            is ProjectsEvent.StartDelete -> startDelete(event.file)
            ProjectsEvent.ConfirmDelete -> confirmDelete()
            ProjectsEvent.DismissDelete -> _uiState.update { s ->
                // Mid-delete the dialog stays up; the result decides what happens to it.
                if (s.deleting?.deleting == true) s else s.copy(deleting = null)
            }
            is ProjectsEvent.ShowInfo -> showInfo(event.file)
            ProjectsEvent.DismissInfo -> {
                infoJob?.cancel()
                _uiState.update { it.copy(info = null) }
            }
            is ProjectsEvent.StartRename ->
                _uiState.update { it.copy(renaming = RenameProject(event.file)) }
            is ProjectsEvent.ConfirmRename -> confirmRename(event.newName)
            ProjectsEvent.DismissRename -> _uiState.update { it.copy(renaming = null) }
            is ProjectsEvent.Backup -> runBackup(event.file)
            ProjectsEvent.ToggleShowHidden -> toggleShowHidden()
            is ProjectsEvent.SaveShortcuts ->
                viewModelScope.launch { settingsStore.setBrowseShortcuts(event.prefs) }
            is ProjectsEvent.CreateProject -> createProject(event.parent, event.name, event.options)
            ProjectsEvent.DismissCreateError -> _uiState.update { it.copy(createError = null) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeRecents() {
        combine(projects.recents, existsTick) { list, _ -> list }
            .mapLatest { list ->
                withContext(io) {
                    val protectedPaths = protectedPaths()
                    list.map {
                        RecentItem(
                            file = it.file,
                            exists = it.file.isDirectory,
                            isDeviceStorage = it.file.path == storageRoot.path,
                            pinned = it.pinned,
                            lastOpenedAt = it.lastOpenedAt,
                            isProtected = it.file.path in protectedPaths,
                        )
                    }
                }
            }
            .collect { items -> _uiState.update { it.copy(recents = items) } }
    }

    private suspend fun loadShortcuts() {
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val projectsDir = File(storageRoot, "Projects")
        val shortcuts = withContext(io) {
            buildList {
                add(BrowseShortcut(storageRoot, UiText(R.string.workspace_root_internal)))
                if (docs.isDirectory) add(BrowseShortcut(docs, null))
                if (downloads.isDirectory) add(BrowseShortcut(downloads, null))
                if (projectsDir.isDirectory) add(BrowseShortcut(projectsDir, null))
            }
        }
        defaultShortcuts = shortcuts
        rebuildShortcuts()
    }

    /** Merges built-in shortcuts with the saved order/visibility/custom folders into UI state. */
    private suspend fun rebuildShortcuts() {
        val defaults = defaultShortcuts
        val saved = savedShortcuts
        val entries = withContext(io) {
            val defaultPaths = defaults.map { it.file.path }.toSet()
            val byPath = defaults.associateBy { it.file.path }
            val order = ArrayList<ShortcutPref>(saved)
            // New built-ins (or a never-edited list) fall in after the user's own entries, visible.
            defaults.forEach { d -> if (order.none { it.path == d.file.path }) order.add(ShortcutPref(d.file.path, true)) }
            order.mapNotNull { pref ->
                val shortcut = byPath[pref.path] ?: File(pref.path).takeIf { it.isDirectory }?.let { BrowseShortcut(it, null) }
                shortcut?.let { ShortcutEntry(it, pref.enabled, isCustom = pref.path !in defaultPaths) }
            }
        }
        _uiState.update { it.copy(shortcutEntries = entries, shortcuts = entries.filter { e -> e.enabled }.map { e -> e.shortcut }) }
    }

    private fun browse(dir: File?) {
        browseJob?.cancel()
        if (dir == null) {
            _uiState.update { it.copy(browseDir = null, browseEntries = emptyList(), browseError = null) }
            return
        }
        browseJob = viewModelScope.launch {
            when (val result = fileOps.list(dir)) {
                is FsResult.Ok -> _uiState.update {
                    it.copy(
                        browseDir = dir,
                        browseEntries = result.value
                            .filter { entry ->
                                entry.isDirectory && (_uiState.value.showHidden || !entry.name.startsWith("."))
                            }
                            .map { entry -> entry.file },
                        browseError = null,
                    )
                }
                is FsResult.Err -> _uiState.update {
                    it.copy(browseDir = dir, browseEntries = emptyList(), browseError = result.toUiText())
                }
            }
        }
    }

    private fun toggleShowHidden() {
        val next = !_uiState.value.showHidden
        _uiState.update { it.copy(showHidden = next) }
        _uiState.value.browseDir?.let { browse(it) }
        viewModelScope.launch { settingsStore.setProjectSheetShowHidden(next) }
    }

    private fun browseUp() {
        val current = _uiState.value.browseDir ?: return
        val atShortcutRoot = _uiState.value.shortcuts.any { it.file.path == current.path } ||
            current.path == android.os.Environment.getExternalStorageDirectory().path
        browse(if (atShortcutRoot) null else current.parentFile)
    }

    private fun confirmRename(newName: String) {
        val pending = _uiState.value.renaming ?: return
        if (newName.trim() == pending.file.name) {
            _uiState.update { it.copy(renaming = null) }
            return
        }
        viewModelScope.launch {
            when (val result = fileOps.rename(pending.file, newName)) {
                is FsResult.Ok -> {
                    projects.onPathMoved(pending.file, result.value)
                    _uiState.update { it.copy(renaming = null) }
                    val renamed = result.value
                    _effects.send(ProjectsEffect.ProjectMoved(pending.file, renamed))
                    _effects.send(ProjectsEffect.Message(UiText(R.string.msg_project_renamed, listOf(renamed.name))))
                }
                is FsResult.Err ->
                    _uiState.update { it.copy(renaming = pending.copy(error = result.toUiText())) }
            }
        }
    }

    /** Folders the app must never delete: the device root and the standard Documents/Downloads. */
    private fun protectedPaths(): Set<String> = setOf(
        storageRoot.path,
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).path,
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path,
    )

    private fun startDelete(file: File) {
        if (file.path in protectedPaths()) return
        _uiState.update { it.copy(deleting = DeleteProject(file)) }
    }

    private fun confirmDelete() {
        val pending = _uiState.value.deleting ?: return
        if (pending.deleting || pending.file.path in protectedPaths()) return
        _uiState.update { it.copy(deleting = pending.copy(deleting = true, error = null)) }
        viewModelScope.launch {
            when (val result = fileOps.delete(pending.file)) {
                is FsResult.Ok -> {
                    projects.onProjectDeleted(pending.file)
                    _uiState.update { it.copy(deleting = null) }
                    _effects.send(ProjectsEffect.ProjectDeleted(pending.file))
                    _effects.send(ProjectsEffect.Message(UiText(R.string.msg_project_deleted, listOf(pending.file.name))))
                }
                is FsResult.Err ->
                    _uiState.update { it.copy(deleting = pending.copy(deleting = false, error = result.toUiText())) }
            }
        }
    }

    private fun showInfo(file: File) {
        infoJob?.cancel()
        val openedAt = _uiState.value.recents.firstOrNull { it.file.path == file.path }?.lastOpenedAt ?: 0L
        _uiState.update { it.copy(info = ProjectInfo(file = file, lastOpenedAt = openedAt)) }
        infoJob = viewModelScope.launch {
            val job = coroutineContext[Job]
            val computed = withContext(io) { readProjectInfo(file, job) }
            _uiState.update { s ->
                if (s.info?.file?.path == file.path) s.copy(info = computed.copy(lastOpenedAt = openedAt)) else s
            }
        }
    }

    /** Walks [dir] once (symlinks not followed) for size, file count and newest modification time. */
    private fun readProjectInfo(dir: File, job: Job?): ProjectInfo {
        var size = 0L
        var files = 0
        var newest = 0L
        var truncated = false
        try {
            Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (job?.isActive == false) return FileVisitResult.TERMINATE
                    if (attrs.isRegularFile) {
                        files++
                        size += attrs.size()
                        newest = maxOf(newest, attrs.lastModifiedTime().toMillis())
                    }
                    if (files >= INFO_FILE_CAP) {
                        truncated = true
                        return FileVisitResult.TERMINATE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            })
        } catch (_: IOException) {
            // Report whatever was counted before the failure.
        }
        val gitEntry = File(dir, ".git")
        var branch: String? = null
        var detached = false
        if (gitEntry.isDirectory) {
            val head = runCatching { File(gitEntry, "HEAD").readText().trim() }.getOrNull()
            when {
                head == null -> Unit
                head.startsWith(HEAD_REF_PREFIX) -> branch = head.removePrefix(HEAD_REF_PREFIX)
                head.isNotEmpty() -> {
                    branch = head.take(7)
                    detached = true
                }
            }
        }
        return ProjectInfo(
            file = dir,
            loading = false,
            sizeBytes = size,
            fileCount = files,
            truncated = truncated,
            lastModified = if (newest > 0L) newest else dir.lastModified(),
            isGit = gitEntry.exists(),
            gitBranch = branch,
            detached = detached,
        )
    }

    private fun createProject(parent: File, name: String, options: NewProjectOptions) {
        viewModelScope.launch {
            // The default location (storage/Projects) may not exist yet.
            withContext(io) { if (!parent.exists()) parent.mkdirs() }
            when (val result = fileOps.createFolder(parent, name)) {
                is FsResult.Ok -> {
                    _uiState.update { it.copy(createError = null) }
                    withContext(io) { ProjectScaffold.write(result.value, name, options) }
                    if (!options.open) _effects.send(ProjectsEffect.Message(UiText(R.string.msg_project_created, listOf(result.value.name))))
                    _effects.send(ProjectsEffect.ProjectCreated(result.value, options.open))
                }
                is FsResult.Err -> _uiState.update { it.copy(createError = result.toUiText()) }
            }
        }
    }

    private fun runBackup(project: File) {
        if (project.path in _uiState.value.backingUp) return
        _uiState.update { it.copy(backingUp = it.backingUp + project.path) }
        viewModelScope.launch {
            try {
                val text = when (val result = backup.backup(project)) {
                    is FsResult.Ok -> UiText(R.string.msg_backup_done, listOf(result.value.path))
                    is FsResult.Err -> UiText(R.string.msg_backup_failed, listOf(project.name))
                }
                _effects.send(ProjectsEffect.Message(text))
            } finally {
                _uiState.update { it.copy(backingUp = it.backingUp - project.path) }
            }
        }
    }

    companion object {
        /** Stop the info walk here so a huge folder can't spin forever; the UI shows "N+". */
        private const val INFO_FILE_CAP = 200_000
        private const val HEAD_REF_PREFIX = "ref: refs/heads/"

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                ProjectsViewModel(
                    projects = app.container.projectRepository,
                    fileOps = app.container.fileOps,
                    backup = app.container.projectBackup,
                    storageRoot = Environment.getExternalStorageDirectory(),
                    settingsStore = app.container.editorSettingsStore,
                )
            }
        }
    }
}
