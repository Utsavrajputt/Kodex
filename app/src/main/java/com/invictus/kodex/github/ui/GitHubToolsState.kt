package com.invictus.kodex.github.ui

import com.invictus.kodex.github.api.GhArtifact
import com.invictus.kodex.github.api.GhDeployment
import com.invictus.kodex.github.api.GhRun
import com.invictus.kodex.github.data.ApkCandidate
import com.invictus.kodex.github.data.CleanupFilters
import com.invictus.kodex.github.data.CleanupKind
import com.invictus.kodex.github.data.CleanupPlan
import com.invictus.kodex.github.data.CleanupResult
import com.invictus.kodex.github.data.DispatchInfo
import com.invictus.kodex.github.install.ApkInstallInfo
import com.invictus.kodex.github.install.InspectResult
import java.io.File
import java.time.Instant

/** What the workflow file said about manual runs (lazily fetched per workflow + ref). */
sealed interface DispatchInfoState {
    data object Unknown : DispatchInfoState
    data object Loading : DispatchInfoState
    data class Loaded(val info: DispatchInfo) : DispatchInfoState

    /** The file could not be read (missing Contents:read, offline, ...): fall back to the raw editor. */
    data class Failed(val message: GhText) : DispatchInfoState
}

/** One row of the raw key/value editor used when the inputs could not be parsed. */
data class RawInput(val key: String = "", val value: String = "")

data class RunWorkflowState(
    val selectedWorkflowId: Long? = null,
    val selectedBranch: String? = null,
    val branches: List<String> = emptyList(),
    val branchesHasMore: Boolean = false,
    val branchesLoading: Boolean = false,
    val info: DispatchInfoState = DispatchInfoState.Unknown,
    val dispatching: Boolean = false,
    /** Inputs sheet. */
    val sheetOpen: Boolean = false,
    val values: Map<String, String> = emptyMap(),
    val rawInputs: List<RawInput> = emptyList(),
    val missingRequired: Set<String> = emptySet(),
    /** The repo's existing tags, loaded when the sheet has a release-tag input. */
    val tags: List<String> = emptyList(),
)

/** Where a transfer (download of an artifact zip) is happening and what it is for. */
enum class TransferPurpose { Install, Save }

data class Transfer(
    val artifactId: Long,
    val purpose: TransferPurpose,
    /** 0..1, or null while the size is unknown. */
    val progress: Float?,
    /** True for the APK card, so the card (not a row) shows the progress. */
    val fromApkCard: Boolean,
    val downloaded: Long = 0L,
    val total: Long = 0L,
    /** Seconds left at the current speed; null = not known yet. */
    val etaSeconds: Long? = null,
    /** Current download speed in bytes/s; null = not known yet. */
    val speedBps: Long? = null,
    /** Stopped by the user; the partial file is kept and Resume continues it. */
    val paused: Boolean = false,
)

/** An APK that was downloaded, extracted and inspected, waiting for the user's tap. */
data class ReadyApk(
    val artifactId: Long,
    val artifactName: String,
    val file: File,
    val inspect: InspectResult,
    val fromApkCard: Boolean,
    /** Install was started straight from the Install button: no details dialog / inline body is shown. */
    val silent: Boolean = false,
    /** Commit the APK was built from; recorded as the installed commit once the installer succeeds. */
    val buildSha: String? = null,
)

sealed interface ApkCardState {
    data object Loading : ApkCardState
    data object None : ApkCardState
    data class AllExpired(val newestAt: Instant?) : ApkCardState
    /** [variants] = every live artifact of the same build (Light + Full arm64, ...), including the candidate. */
    data class Found(val candidate: ApkCandidate, val variants: List<GhArtifact> = listOf(candidate.artifact)) : ApkCardState
    data class Error(val message: GhText) : ApkCardState
}

/** A file waiting for the system "create document" picker (plan 8.5). */
data class PendingSave(
    val file: File,
    val suggestedName: String,
    val mimeType: String,
    /** The file is a temporary download: delete it once the copy is done or cancelled. */
    val deleteAfter: Boolean,
    /** The picker has been launched for this save (guards against a second launch on recomposition). */
    val launched: Boolean = false,
)

/** A kept APK from an earlier download, listed in the Downloaded APKs section. */
data class DownloadedApk(val artifactId: Long, val label: String, val file: File, val sizeBytes: Long, val modifiedAt: Long)

enum class InstallDialog { UnknownSources, SelfInstall }

/** What the Install button says while an install runs: Preparing -> Installing -> Installed. */
enum class InstallPhase { Idle, Preparing, Installing, Installed }

data class CleanupState(
    val open: Boolean = false,
    val filters: CleanupFilters = CleanupFilters(),
    val scanning: Boolean = false,
    val scanError: GhText? = null,
    val rawRuns: List<GhRun>? = null,
    val rawArtifacts: List<GhArtifact>? = null,
    val rawDeployments: List<GhDeployment>? = null,
    val artifactBytesByRun: Map<Long, Long> = emptyMap(),
    val plan: CleanupPlan? = null,
    val selected: Set<Long> = emptySet(),
    val running: Boolean = false,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val stopRequested: Boolean = false,
    val result: CleanupResult? = null,
) {
    val hasData: Boolean
        get() = when (filters.kind) {
            CleanupKind.Runs -> rawRuns != null
            CleanupKind.Artifacts -> rawArtifacts != null
            CleanupKind.Deployments -> rawDeployments != null
        }
}

data class GitHubToolsState(
    val run: RunWorkflowState = RunWorkflowState(),
    val apk: ApkCardState = ApkCardState.Loading,
    val transfer: Transfer? = null,
    val ready: ReadyApk? = null,
    val installing: Boolean = false,
    val installPhase: InstallPhase = InstallPhase.Idle,
    val installDialog: InstallDialog? = null,
    val pendingSave: PendingSave? = null,

    val artifacts: List<GhArtifact> = emptyList(),
    val artifactsLoading: Boolean = false,
    val artifactsHasMore: Boolean = false,
    val artifactsLoadingMore: Boolean = false,
    val artifactsError: GhText? = null,
    val artifactsExpanded: Boolean = true,
    val busyArtifactIds: Set<Long> = emptySet(),

    val deployments: List<GhDeployment> = emptyList(),
    val deploymentsLoading: Boolean = false,
    val deploymentsError: GhText? = null,
    val deploymentsExpanded: Boolean = true,
    val busyDeploymentIds: Set<Long> = emptySet(),

    val downloaded: List<DownloadedApk> = emptyList(),
    /** Install / Update / Open per downloaded artifact id (missing = not resolved yet, treated as Install). */
    val installInfo: Map<Long, ApkInstallInfo> = emptyMap(),

    val cleanup: CleanupState = CleanupState(),
)
