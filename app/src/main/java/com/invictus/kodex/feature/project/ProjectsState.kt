package com.invictus.kodex.feature.project

import com.invictus.kodex.core.editor.ShortcutPref
import com.invictus.kodex.feature.workspace.UiText
import java.io.File

/** One row of the "Recent" list. [exists] is checked off the main thread. */
data class RecentItem(
    val file: File,
    val exists: Boolean,
    val isDeviceStorage: Boolean,
    val pinned: Boolean = false,
    val lastOpenedAt: Long = 0L,
    /** Device root / Documents / Downloads: can be opened but never deleted from the app. */
    val isProtected: Boolean = false,
)

/** A starting point in the sheet's folder browser. */
data class BrowseShortcut(val file: File, val label: UiText?)

/** A Browse shortcut plus its edit-mode flags: [enabled] = shown in Browse, [isCustom] = added by the user. */
data class ShortcutEntry(val shortcut: BrowseShortcut, val enabled: Boolean, val isCustom: Boolean)

data class RenameProject(val file: File, val error: UiText? = null)

/** Pending "delete project folder from disk" confirmation. */
data class DeleteProject(val file: File, val deleting: Boolean = false, val error: UiText? = null)

/**
 * Read-only facts about a project folder. [loading] is true until the size walk finishes;
 * [truncated] means the walk stopped at the file cap, so [sizeBytes]/[fileCount] are lower bounds.
 */
data class ProjectInfo(
    val file: File,
    val loading: Boolean = true,
    val sizeBytes: Long = 0L,
    val fileCount: Int = 0,
    val truncated: Boolean = false,
    val lastModified: Long = 0L,
    val lastOpenedAt: Long = 0L,
    val isGit: Boolean = false,
    /** Current branch, or the short commit hash when [detached]; null if unreadable. */
    val gitBranch: String? = null,
    val detached: Boolean = false,
)

data class ProjectsUiState(
    val recents: List<RecentItem> = emptyList(),
    val shortcuts: List<BrowseShortcut> = emptyList(),
    /** Every shortcut in the user's order, including hidden ones -- what the Browse edit dialog shows. */
    val shortcutEntries: List<ShortcutEntry> = emptyList(),
    /** Null = showing [shortcuts]; otherwise the folder being browsed. */
    val browseDir: File? = null,
    val browseEntries: List<File> = emptyList(),
    val browseError: UiText? = null,
    val renaming: RenameProject? = null,
    val deleting: DeleteProject? = null,
    val info: ProjectInfo? = null,
    /** Project paths with a backup running right now. */
    val backingUp: Set<String> = emptySet(),
    /** Whether the folder browser also lists dot-prefixed (hidden) folders. */
    val showHidden: Boolean = false,
    /** Error from the last New Project attempt (invalid name, already exists...). */
    val createError: UiText? = null,
)

sealed interface ProjectsEvent {
    /** Sheet became visible: re-check which recents still exist and reset the browser. */
    data object SheetOpened : ProjectsEvent
    data class Browse(val dir: File?) : ProjectsEvent
    data object BrowseUp : ProjectsEvent
    data class RemoveRecent(val path: String) : ProjectsEvent
    data class TogglePin(val path: String, val pinned: Boolean) : ProjectsEvent
    data class StartDelete(val file: File) : ProjectsEvent
    data object ConfirmDelete : ProjectsEvent
    data object DismissDelete : ProjectsEvent
    data class ShowInfo(val file: File) : ProjectsEvent
    data object DismissInfo : ProjectsEvent
    data class StartRename(val file: File) : ProjectsEvent
    data class ConfirmRename(val newName: String) : ProjectsEvent
    data object DismissRename : ProjectsEvent
    data class Backup(val file: File) : ProjectsEvent
    data object ToggleShowHidden : ProjectsEvent
    data class SaveShortcuts(val prefs: List<ShortcutPref>) : ProjectsEvent
    /** Create folder [name] inside [parent]; on success a [ProjectsEffect.ProjectCreated] follows. */
    data class CreateProject(val parent: File, val name: String, val options: NewProjectOptions = NewProjectOptions()) : ProjectsEvent
    data object DismissCreateError : ProjectsEvent
}

sealed interface ProjectsEffect {
    data class Message(val text: UiText) : ProjectsEffect

    /** A project folder was renamed on disk; the workspace follows it if it was open. */
    data class ProjectMoved(val old: File, val new: File) : ProjectsEffect

    /** A project folder was deleted from disk; the workspace leaves it if it was open. */
    data class ProjectDeleted(val dir: File) : ProjectsEffect

    /** A new project folder was created; the home screen opens it right away. */
    data class ProjectCreated(val dir: File, val open: Boolean = true) : ProjectsEffect
}
