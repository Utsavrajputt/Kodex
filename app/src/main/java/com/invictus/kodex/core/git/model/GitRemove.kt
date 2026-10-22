package com.invictus.kodex.core.git.model

/**
 * One `git rm` / `rm` command. [viaGit] is `git rm` (the deletion is also staged); plain `rm`
 * only touches the disk. [cached] is `git rm --cached` (untrack, keep the file on disk).
 * [ignoreMissing]: a pattern that matches nothing is not an error.
 */
data class GitRemoveRequest(
    val patterns: List<String>,
    val recursive: Boolean,
    val force: Boolean,
    val cached: Boolean,
    val viaGit: Boolean,
    val ignoreMissing: Boolean,
)

/** One concrete file a removal will touch. */
data class GitRemoveItem(
    val path: String,
    val deleteDisk: Boolean,
    val unstage: Boolean,
)

/** The resolved, side-effect-free result of [GitRemoveRequest]s: exactly what will be removed. */
data class GitRemovePlan(val items: List<GitRemoveItem>) {
    /** Files that will be deleted from disk (irreversible, so the person confirms first). */
    val diskDeletes: List<String> get() = items.filter { it.deleteDisk }.map { it.path }
    val needsConfirm: Boolean get() = items.any { it.deleteDisk }
}
