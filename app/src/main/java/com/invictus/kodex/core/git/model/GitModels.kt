package com.invictus.kodex.core.git.model

import java.io.File

/** What we know about the repository at HEAD, without walking history. */
data class GitRepoSnapshot(
    val gitRoot: File,
    val hasCommits: Boolean,
    val headId: String?,
    val headName: String?,
    val trackingInfo: GitTrackingInfo?,
    val mergeInProgress: Boolean = false,
    val rebaseInProgress: Boolean = false,
)

/** Upstream tracking: which remote/branch HEAD follows, and how far it has diverged. */
data class GitTrackingInfo(
    val remote: String,
    val branch: String,
    val ahead: Int,
    val behind: Int,
)

/** Full working-tree status, derived from JGit's Status in one pass. */
data class GitWorkingTreeStatus(
    val changes: List<GitPathChange>,
    val hasUnmerged: Boolean,
) {
    val isClean: Boolean get() = changes.isEmpty()

    val staged: List<GitPathChange> get() = changes.filter { it.staged != GitStageState.NONE }
    val unstaged: List<GitPathChange> get() = changes.filter { it.unstaged != GitWorkingState.NONE }
    val untracked: List<GitPathChange> get() = changes.filter { it.unstaged == GitWorkingState.UNTRACKED }
    val conflicts: List<GitPathChange> get() = changes.filter {
        it.staged == GitStageState.CONFLICT || it.unstaged == GitWorkingState.CONFLICT
    }

    /**
     * Swaps in freshly computed state for just [touched] paths (a path-filtered status),
     * leaving every other entry as-is. A [touched] path absent from [fresh] is now clean
     * and drops out. This is what lets resolve/commit skip a whole-repo status walk.
     */
    fun patched(touched: Set<String>, fresh: List<GitPathChange>): GitWorkingTreeStatus {
        val merged = (changes.filter { it.repoRelativePath !in touched } + fresh)
            .distinctBy { it.repoRelativePath }
            .sortedBy { it.repoRelativePath }
        return GitWorkingTreeStatus(
            changes = merged,
            hasUnmerged = merged.any {
                it.staged == GitStageState.CONFLICT || it.unstaged == GitWorkingState.CONFLICT
            },
        )
    }

    /** What the status looks like right after a commit: the index now equals HEAD. */
    fun afterCommit(): GitWorkingTreeStatus = GitWorkingTreeStatus(
        changes = changes.mapNotNull {
            if (it.unstaged == GitWorkingState.NONE) null
            else it.copy(staged = GitStageState.NONE)
        },
        hasUnmerged = false,
    )
}

/** Result of a path-filtered status: [fresh] holds only entries for [touched] that still differ. */
data class GitStatusPatch(val touched: Set<String>, val fresh: List<GitPathChange>)

/** One repo-relative path and how it differs from HEAD/index in both directions. */
data class GitPathChange(
    val repoRelativePath: String,
    val staged: GitStageState,
    val unstaged: GitWorkingState,
)

enum class GitStageState { NONE, ADDED, MODIFIED, DELETED, CONFLICT }
enum class GitWorkingState { NONE, MODIFIED, DELETED, UNTRACKED, CONFLICT }

data class GitRemoteInfo(
    val name: String,
    val url: String,
)

data class GitBranchInfo(
    val name: String,
    val isCurrent: Boolean,
)

data class GitCommitSummary(
    val id: String,
    val shortId: String,
    val message: String,
    val author: String,
    val timeMs: Long,
    val authorEmail: String = "",
)

/** How a file changed inside one commit (commit-detail file list). */
enum class GitCommitFileChange { ADDED, MODIFIED, DELETED, RENAMED, COPIED }

/** One changed file of a commit; [oldPath] is only set for renames/copies. */
data class GitCommitFile(
    val path: String,
    val oldPath: String?,
    val change: GitCommitFileChange,
    /** Lines added / removed in this file (0 for binary files). */
    val added: Int = 0,
    val removed: Int = 0,
)

/** What went wrong, decided from the exception *type* (release builds rename classes, so names can't be matched). */
enum class GitErrorKind { OTHER, PUSH_REJECTED, UNFETCHED_COMMITS, EMPTY_COMMIT, IDENTITY_MISSING }

/** Everything the interactive error dialog shows; one-click copy serializes this. */
data class GitErrorDetails(
    val title: String,
    val message: String,
    val exceptionClass: String,
    val stackTrace: String,
    val timestamp: Long = System.currentTimeMillis(),
    val authFailure: GitAuthFailureType = GitAuthFailureType.NONE,
    val kind: GitErrorKind = GitErrorKind.OTHER,
) {
    fun toCopyableText(): String = buildString {
        appendLine(title)
        appendLine(message)
        appendLine()
        appendLine(exceptionClass)
        append(stackTrace)
    }
}

enum class GitAuthMethod { TOKEN }

enum class GitAuthFailureType { NONE, AUTH_REQUIRED, INVALID_CREDENTIALS, EXPIRED_TOKEN, PERMISSION_DENIED, NETWORK_ERROR, UNKNOWN }

/** True for any failure a fresh token could fix — the trigger for an auth-retry dialog. */
fun GitAuthFailureType.isAuthError(): Boolean =
    this == GitAuthFailureType.AUTH_REQUIRED ||
        this == GitAuthFailureType.INVALID_CREDENTIALS ||
        this == GitAuthFailureType.EXPIRED_TOKEN ||
        this == GitAuthFailureType.PERMISSION_DENIED

/** Which network operation to retry once the user supplies a fresh token. */
enum class GitPendingAction { PUSH, PULL, FETCH, CLONE }

// ---- M7: diff + file-tree decoration models --------------------------------

/** Line classification for one side of a diff row. PADDING = that side has no line. */
/** [HUNK] rows carry the raw "@@ -a,b +c,d @@ section" header in leftText/rightText. */
enum class GitDiffLineType { CONTEXT, ADDED, REMOVED, PADDING, HUNK }

/**
 * One aligned row of a side-by-side diff. Adds/deletes pair up inside a hunk; a leftover
 * line on one side gets PADDING on the other. [intralineLeft]/[intralineRight] hold the
 * changed character ranges inside a paired line for the soft highlight.
 */
data class GitDiffRow(
    val leftNumber: Int?,
    val leftText: String?,
    val leftType: GitDiffLineType,
    val rightNumber: Int?,
    val rightText: String?,
    val rightType: GitDiffLineType,
    val intralineLeft: List<IntRange> = emptyList(),
    val intralineRight: List<IntRange> = emptyList(),
)

/**
 * Parsed diff for one file, render-ready (no JGit on the UI thread).
 * [oldImageBytes] is only filled for image files so the diff UI can show "before".
 * [workFilePath] is the absolute worktree path, used as the "after" image source.
 */
data class GitFileDiffResult(
    val path: String,
    val oldLabel: String,
    val newLabel: String,
    val isBinary: Boolean,
    val isImage: Boolean,
    val rows: List<GitDiffRow>,
    val truncated: Boolean = false,
    val oldImageBytes: ByteArray? = null,
    val workFilePath: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is GitFileDiffResult && other.path == path && other.rows == rows)

    override fun hashCode(): Int = 31 * path.hashCode() + rows.hashCode()
}

/** File-tree status stripe for one repo-relative path (plan 3.4 GitPathDecoration). */
data class GitPathDecoration(
    val repoRelativePath: String,
    val label: String,
    val kind: Kind,
) {
    /** [priority] orders aggregation when a folder contains mixed states. */
    enum class Kind(val priority: Int) {
        CONFLICT(4), DELETED(3), MODIFIED(2), ADDED(1), UNTRACKED(0),
    }
}

// ---- M8: advanced ops models ------------------------------------------------

/** Branch with tracking info (upstream + divergence) for the branches screen. */
data class GitBranchDetail(
    val name: String,
    val isCurrent: Boolean,
    val upstream: String?,   // "origin/main", null when no tracking config
    /** null until the background divergence pass has computed it (names load first, counts fill in). */
    val ahead: Int? = null,
    val behind: Int? = null,
    /** true for a remote-tracking branch with no local branch yet; [name] is then "origin/foo". */
    val isRemote: Boolean = false,
)

data class GitTagInfo(
    val name: String,
    val commitId: String,
    val message: String?,
    val timeMs: Long,
)

data class GitStashInfo(
    val ref: String,     // "stash@{0}"
    val index: Int,
    val message: String,
    val timeMs: Long,
)

/** git reset mode: how far back the index/working tree follow HEAD. */
enum class GitResetMode { SOFT, MIXED, HARD }

enum class GitLogSearchMode { MESSAGE, AUTHOR, HASH }

// ---- M10: merge + conflict models -------------------------------------------

enum class GitConflictSide { OURS, THEIRS }

enum class MergeOutcome { FAST_FORWARD, MERGED, ALREADY_UP_TO_DATE, CONFLICTS }

// ---- M11: rebase -------------------------------------------------------------

enum class RebaseOutcome { FAST_FORWARD, OK, ALREADY_UP_TO_DATE, CONFLICTS }
