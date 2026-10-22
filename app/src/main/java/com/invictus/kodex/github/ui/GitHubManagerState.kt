package com.invictus.kodex.github.ui

import android.content.Context
import androidx.annotation.StringRes
import com.invictus.kodex.github.api.GhRepoRef
import com.invictus.kodex.github.api.GhRun
import com.invictus.kodex.github.api.GhWorkflow
import com.invictus.kodex.github.api.RateLimit
import com.invictus.kodex.github.prefs.GitHubPrefs

/** Text a ViewModel can emit without a Context: a string resource, or an API message verbatim. */
sealed interface GhText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : GhText
    data class Raw(val text: String) : GhText

    fun resolve(context: Context): String = when (this) {
        is Res -> context.getString(id, *args.toTypedArray())
        is Raw -> text
    }
}

/** One-shot UI effects. */
sealed interface GhEvent {
    data class Snack(val text: GhText) : GhEvent

    /** Put [text] on the clipboard (flagged sensitive), then show [confirmation]. */
    data class Copy(val label: String, val text: String, val confirmation: GhText) : GhEvent
}

/** Entry states of plan 6.1, in resolution order. */
enum class GitHubGate { Resolving, NotGit, NoGitHubRemote, NoToken, Ready }

/** Persistent top banner (plan 5.4 / 11). */
sealed interface GhBanner {
    data object TokenInvalid : GhBanner
    data object Offline : GhBanner
    data class RateLimited(val resetEpochSec: Long?) : GhBanner
    data class MissingPermission(val message: String) : GhBanner
}

data class GitHubManagerState(
    val gate: GitHubGate = GitHubGate.Resolving,
    val repo: GhRepoRef? = null,
    val login: String? = null,
    val avatarUrl: String? = null,
    val rateLimit: RateLimit? = null,
    val banner: GhBanner? = null,
    val workflows: List<GhWorkflow> = emptyList(),
    val filters: GitHubPrefs.RunFilters = GitHubPrefs.RunFilters(),
    val runs: List<GhRun> = emptyList(),
    val runsLoading: Boolean = false,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = false,
    val runsError: GhText? = null,
    val runsExpanded: Boolean = true,
    /** Runs with an action in flight (spinner on the row, actions disabled). */
    val busyRunIds: Set<Long> = emptySet(),
    /** The run that is queued / building right now (drives the "Building APK…" card). */
    val live: LiveBuild? = null,
    /** A run just finished and its artifacts / APK are still being re-checked: the APK card stays hidden so it never shows a stale build. */
    val settling: Boolean = false,
) {
    fun workflowName(run: GhRun): String =
        workflows.firstOrNull { it.id == run.workflowId }?.name ?: run.name
}
