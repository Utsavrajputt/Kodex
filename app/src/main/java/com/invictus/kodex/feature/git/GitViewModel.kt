package com.invictus.kodex.feature.git

import com.invictus.kodex.github.notify.GitPushEvents
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.invictus.kodex.core.git.GitTrigger
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.R
import com.invictus.kodex.KodexApp
import com.invictus.kodex.core.git.GitCredential
import com.invictus.kodex.core.editor.EditorSettingsStore
import com.invictus.kodex.core.git.GitRepoWatcher
import com.invictus.kodex.core.git.GitResult
import com.invictus.kodex.core.git.GitSessionRegistry
import com.invictus.kodex.core.git.GitTokenCredentialsProvider
import com.invictus.kodex.core.git.normalizeHost
import com.invictus.kodex.core.git.model.GitAuthFailureType
import com.invictus.kodex.core.git.model.GitConflictSide
import com.invictus.kodex.core.git.model.MergeOutcome
import com.invictus.kodex.core.git.model.GitErrorDetails
import com.invictus.kodex.core.git.model.GitErrorKind
import com.invictus.kodex.core.git.model.GitFileDiffResult
import com.invictus.kodex.core.git.model.GitCommitSummary
import com.invictus.kodex.core.git.model.GitPathChange
import com.invictus.kodex.core.git.model.GitPendingAction
import com.invictus.kodex.core.git.model.GitRemoteInfo
import com.invictus.kodex.core.git.model.GitRepoSnapshot
import com.invictus.kodex.core.git.model.GitResetMode
import com.invictus.kodex.core.git.model.GitStageState
import com.invictus.kodex.core.git.model.GitStatusPatch
import com.invictus.kodex.core.git.model.GitTrackingInfo
import com.invictus.kodex.core.git.model.GitWorkingState
import com.invictus.kodex.core.git.model.GitWorkingTreeStatus
import com.invictus.kodex.core.git.model.RebaseOutcome
import com.invictus.kodex.core.security.GitCredentialStore
import com.invictus.kodex.feature.workspace.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.eclipse.jgit.transport.CredentialsProvider
import java.io.File

/**
 * Source Control screen state (M6): snapshot + status after every op, network ops
 * gated through [runNetwork] which resolves per-host tokens and surfaces an
 * auth-retry dialog on 401/403 instead of a dead end.
 */
/** A text change this long in one go is treated as a paste, not typing. */
private const val PASTE_MIN_CHARS = 6

class GitViewModel(
    private val projectPath: String,
    private val credentialStore: GitCredentialStore,
    globalIdentityFile: File,
    private val settingsStore: EditorSettingsStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val session = GitSessionRegistry.acquire(File(projectPath), globalIdentityFile, io)

    data class IdentityDialogState(
        val name: String,
        val email: String,
        val isLocal: Boolean,
    )

    data class TokenDialogState(
        val host: String,
        val username: String,
        /** Which op to re-run once a token is saved; null = just store it. */
        val pending: GitPendingAction?,
        /**
         * The exact push/pull variant the person had asked for before the auth
         * prompt interrupted them — force push, rebase pull, or the pull-then-push
         * retry from the push-rejected dialog — so saving the token replays that,
         * not a plain push/pull. Ignored when [pending] isn't PUSH or PULL.
         */
        val mode: PendingNetworkMode = PendingNetworkMode.Plain,
    )

    /** Carries push/pull's force/rebase/continuation intent through a token-save detour. */
    sealed interface PendingNetworkMode {
        data object Plain : PendingNetworkMode
        data object Force : PendingNetworkMode
        data object Rebase : PendingNetworkMode
        /** The "Pull" choice on the push-rejected dialog: pull, then retry the push. */
        data object PullThenPush : PendingNetworkMode
    }

    data class ConflictPreviewState(
        val path: String,
        val side: GitConflictSide,
        val content: String,
    )

    /** A reset (mode + target) waiting on the "this discards changes" confirmation. */
    data class PendingReset(val ref: String, val label: String, val mode: GitResetMode)

    data class UiState(
        val loading: Boolean = true,
        val notARepo: Boolean = false,
        val snapshot: GitRepoSnapshot? = null,
        val status: GitWorkingTreeStatus? = null,
        val remotes: List<GitRemoteInfo> = emptyList(),
        val networkOp: GitPendingAction? = null,
        /** Generic "working…" dialog for long local git ops; null when idle. */
        val opProgress: GitOpProgress? = null,
        val committing: Boolean = false,
        val progressTask: String = "",
        val commitMessage: String = "",
        val amend: Boolean = false,
        val identity: IdentityDialogState? = null,
        val tokenDialog: TokenDialogState? = null,
        val error: GitErrorDetails? = null,
        // M7 drawer
        val expandedSections: Set<String> = setOf(SECTION_CONFLICTS, SECTION_STAGED, SECTION_CHANGES),
        val diffs: Map<String, GitFileDiffResult> = emptyMap(),
        val diffLoading: Set<String> = emptySet(),
        // M10 merge + conflicts
        val mergeDialog: Boolean = false,
        val mergeCandidates: List<String> = emptyList(),
        val merging: Boolean = false,
        val abortConfirm: Boolean = false,
        val completeMergeDialog: Boolean = false,
        val completeMergeMessage: String = "",
        val completingMerge: Boolean = false,
        val conflictPreview: ConflictPreviewState? = null,
        /** Long-press "Discard changes" confirmation, pending [GitEvent.ConfirmDiscard]. */
        val discardConfirm: DiscardConfirmState? = null,
        // Reset (soft/mixed/hard)
        val resetSheetOpen: Boolean = false,
        val resetCommits: List<GitCommitSummary> = emptyList(),
        val resetCommitsLoading: Boolean = false,
        /** Every local/remote branch that really exists (main/master pinned first), for reset targets. */
        val resetBranches: List<String> = emptyList(),
        val resetBranchesLoaded: Boolean = false,
        val resetHardConfirm: PendingReset? = null,
        val resetting: Boolean = false,
        // M11: rebase
        val abortRebaseConfirm: Boolean = false,
        val rebasePicker: Boolean = false,
        val rebaseCandidates: List<String> = emptyList(),
        val rebasing: Boolean = false,
        val rebaseLoading: Boolean = false,
        /** Shown when a plain (non-force) push is rejected — remote has commits we don't. */
        val pushRejected: Boolean = false,
        /** Remote whose unfetched commits made a force push refuse: offer Fetch. */
        val fetchFirstRemote: String? = null,
        // Quick branch switcher dropdown
        val branchMenuOpen: Boolean = false,
        val branchMenuItems: List<String> = emptyList(),
    )

    data class GitOpProgress(@StringRes val titleRes: Int, val done: Int = 0, val total: Int = 0)

    data class DiscardConfirmState(val path: String, val isUntracked: Boolean)

    sealed interface Effect {
        data class Message(val text: UiText) : Effect
        /** The drawer asked to open a repo-relative path in the editor. */
        data class OpenFile(val file: java.io.File) : Effect
    }

    private var lastConflictCount = 0

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** External changes (Termux, another app) picked up by [repoWatcher]; refreshed debounced. */
    private val externalChangeEvents = Channel<Unit>(Channel.UNLIMITED)
    private val repoWatcher = GitRepoWatcher { externalChangeEvents.trySend(Unit) }

    init {
        if (!session.isRepo) {
            _uiState.update { it.copy(loading = false, notARepo = true) }
        } else {
            val memCached = statusCache[projectPath]
            if (memCached != null) {
                applyCached(memCached)
            } else {
                // No in-process cache (first visit this run, or process was killed) —
                // fall back to the small on-disk snapshot from the last successful
                // refresh, if any, so a cold start also shows something instantly.
                viewModelScope.launch(io) {
                    loadDiskCache(projectPath)?.let { disk ->
                        statusCache[projectPath] = disk
                        if (_uiState.value.snapshot == null) applyCached(disk)
                    }
                }
            }
            refresh("screen-open")
            viewModelScope.launch { debounceExternalChanges() }
            // Editor saves while this screen/drawer is alive: show the change right away.
            viewModelScope.launch {
                statusPatches.collect { (path, patch) -> if (path == projectPath) applyStatusPatch(patch) }
            }
            // Follows Settings > Advanced > "Git status detection": OFF = no .git watcher at all.
            viewModelScope.launch {
                settingsStore.gitStatusPollingEnabled.collect { enabled ->
                    if (enabled) repoWatcher.start(File(projectPath, ".git")) else repoWatcher.stop()
                }
            }
        }
    }

    private fun applyCached(cached: CachedStatus) {
        _uiState.update {
            it.copy(
                loading = false,
                snapshot = cached.snapshot,
                status = cached.status,
                remotes = cached.remotes,
            )
        }
    }

    /** Mirrors FileTreeViewModel's debounceWatcherEvents: coalesce a burst into one refresh. */
    private suspend fun debounceExternalChanges() {
        while (true) {
            externalChangeEvents.receive()
            delay(EXTERNAL_CHANGE_DEBOUNCE_MS)
            while (externalChangeEvents.tryReceive().isSuccess) { /* drain the rest of the burst */ }
            // Our own index/ref writes fire the watcher too; the op already patched the UI itself.
            if (System.currentTimeMillis() < ownActivityUntil || _uiState.value.opProgress != null) continue
            refresh("watcher")
        }
    }

    override fun onCleared() {
        repoWatcher.stop()
        GitSessionRegistry.release(File(projectPath))
    }

    fun onEvent(event: GitEvent) {
        when (event) {
            GitEvent.Refresh -> refresh("user")
            GitEvent.ResumeRefresh -> refresh("resume")
            GitEvent.StageAll -> stage(null)
            GitEvent.UnstageAll -> unstage(null)
            is GitEvent.Stage -> stage(event.path)
            is GitEvent.Unstage -> unstage(event.path)
            is GitEvent.RequestDiscard ->
                _uiState.update { it.copy(discardConfirm = DiscardConfirmState(event.path, event.isUntracked)) }
            GitEvent.ConfirmDiscard -> discard()
            GitEvent.DismissDiscard -> _uiState.update { it.copy(discardConfirm = null) }
            is GitEvent.CommitMessageChange -> onCommitMessageChange(event.text)
            is GitEvent.PasteCommitText -> onPasteCommitText(event.text)
            GitEvent.ToggleAmend -> toggleAmend()
            GitEvent.Commit -> commit()
            GitEvent.Push -> push(force = false)
            GitEvent.ForcePushWithLease -> push(force = true)
            GitEvent.Pull -> pull(rebase = false)
            GitEvent.PullRebase -> pull(rebase = true)
            GitEvent.Fetch -> fetch()
            GitEvent.DismissPushRejected -> _uiState.update { it.copy(pushRejected = false) }
            GitEvent.DismissFetchFirst -> _uiState.update { it.copy(fetchFirstRemote = null) }
            GitEvent.PullThenRetryPush -> {
                _uiState.update { it.copy(pushRejected = false) }
                pull(rebase = false, thenPush = true)
            }
            is GitEvent.ToggleSection -> _uiState.update {
                it.copy(
                    expandedSections = if (event.name in it.expandedSections)
                        it.expandedSections - event.name else it.expandedSections + event.name,
                )
            }
            is GitEvent.LoadDiff -> loadDiff(event.path)
            is GitEvent.CloseDiff -> _uiState.update { it.copy(diffs = it.diffs - event.path) }
            is GitEvent.OpenFile ->
                _effects.trySend(Effect.OpenFile(java.io.File(projectPath, event.path)))
            GitEvent.OpenIdentity -> openIdentity()
            is GitEvent.SaveIdentity -> saveIdentity(event.name, event.email, event.isLocal)
            GitEvent.DismissIdentity -> _uiState.update { it.copy(identity = null) }
            is GitEvent.SaveToken -> saveToken(event.host, event.username, event.token)
            GitEvent.DismissToken -> _uiState.update { it.copy(tokenDialog = null) }
            GitEvent.DismissError -> _uiState.update { it.copy(error = null) }
            // M10: merge + conflicts
            GitEvent.OpenMerge -> openMergeDialog()
            GitEvent.DismissMerge ->
                _uiState.update { it.copy(mergeDialog = false, mergeCandidates = emptyList()) }
            is GitEvent.Merge -> merge(event.branch)
            is GitEvent.ResolveConflict -> resolveConflict(event.path, event.side)
            is GitEvent.MarkResolved -> markResolved(event.path)
            GitEvent.MarkAllResolved -> markAllResolved()
            is GitEvent.PreviewConflictSide -> previewConflictSide(event.path, event.side)
            GitEvent.DismissConflictPreview ->
                _uiState.update { it.copy(conflictPreview = null) }
            GitEvent.AbortMerge -> _uiState.update { it.copy(abortConfirm = true) }
            GitEvent.ConfirmAbortMerge -> abortMerge()
            GitEvent.DismissAbortMerge -> _uiState.update { it.copy(abortConfirm = false) }
            GitEvent.CompleteMerge -> openCompleteMerge()
            GitEvent.ConfirmCompleteMerge -> completeMerge()
            is GitEvent.CompleteMergeMessageChange ->
                _uiState.update { it.copy(completeMergeMessage = event.text) }
            GitEvent.DismissCompleteMerge ->
                _uiState.update { it.copy(completeMergeDialog = false) }
            // ---- reset ----
            GitEvent.OpenReset -> {
                _uiState.update { it.copy(resetSheetOpen = true, resetBranchesLoaded = false) }
                loadResetBranches()
            }
            GitEvent.DismissReset -> _uiState.update { it.copy(resetSheetOpen = false) }
            GitEvent.LoadResetCommits -> loadResetCommits()
            is GitEvent.ResetTo -> requestReset(event.ref, event.label, event.mode)
            GitEvent.ConfirmHardReset -> confirmHardReset()
            GitEvent.DismissHardResetConfirm ->
                _uiState.update { it.copy(resetHardConfirm = null) }
            // ---- rebase (continue/skip/abort) ----
            GitEvent.ContinueRebase -> continueRebase()
            GitEvent.SkipRebaseCommit -> skipRebaseCommit()
            GitEvent.AbortRebase -> _uiState.update { it.copy(abortRebaseConfirm = true) }
            GitEvent.ConfirmAbortRebase -> abortRebase()
            GitEvent.DismissAbortRebase -> _uiState.update { it.copy(abortRebaseConfirm = false) }
            GitEvent.OpenRebasePicker -> openRebasePicker()
            GitEvent.DismissRebasePicker ->
                _uiState.update { it.copy(rebasePicker = false, rebaseLoading = false, rebaseCandidates = emptyList()) }
            is GitEvent.RebaseOnto -> rebaseOnto(event.branch)
            // ---- quick branch switcher ----
            GitEvent.OpenBranchMenu -> openBranchMenu()
            GitEvent.DismissBranchMenu ->
                _uiState.update { it.copy(branchMenuOpen = false) }
            is GitEvent.CheckoutBranch -> checkoutBranch(event.name)
        }
    }

    // ---- refresh -----------------------------------------------------------

    @Volatile private var ownActivityUntil = 0L
    @Volatile private var lastFullStatusAt = 0L
    @Volatile private var refreshQueued = false
    @Volatile private var refreshJob: Job? = null

    private fun touchOwnActivity() {
        ownActivityUntil = System.currentTimeMillis() + OWN_ACTIVITY_GRACE_MS
    }

    /**
     * Full status walks are the slow part on big repos (16-50s in the logs), so they are
     * coalesced: resume/screen-open show the cached status immediately and only re-walk when
     * the last walk is older than [PASSIVE_REFRESH_MIN_MS]; a refresh never stacks behind
     * another one — an op/watcher/user request that arrives mid-walk queues exactly one re-run.
     */
    private fun refresh(trigger: String = "after-op") {
        val passive = trigger == "resume" || trigger == "screen-open"
        if (passive && _uiState.value.status != null &&
            System.currentTimeMillis() - lastFullStatusAt < PASSIVE_REFRESH_MIN_MS
        ) return
        if (refreshJob?.isActive == true) {
            if (!passive) refreshQueued = true
            return
        }
        val job = viewModelScope.launch(io + GitTrigger(trigger)) { doRefresh() }
        refreshJob = job
        job.invokeOnCompletion {
            if (refreshQueued) {
                refreshQueued = false
                refresh("after-op")
            }
        }
    }

    private suspend fun doRefresh() {
        run {
            _uiState.update { it.copy(loading = it.snapshot == null) }
            when (val result = session.refreshFull()) {
                is GitResult.Ok -> {
                    val (snapshot, status, remotes) = result.value
                    val cached = CachedStatus(snapshot, status, remotes)
                    statusCache[projectPath] = cached
                    saveDiskCache(projectPath, cached)
                    lastFullStatusAt = System.currentTimeMillis()
                    val conflicts = status.conflicts.size
                    _uiState.update {
                        it.copy(
                            loading = false,
                            snapshot = snapshot,
                            status = status,
                            remotes = remotes,
                        )
                    }
                    if ((snapshot.mergeInProgress || snapshot.rebaseInProgress) &&
                        lastConflictCount > 0 && conflicts == 0
                    ) {
                        message(R.string.git_msg_all_conflicts_resolved)
                    }
                    lastConflictCount = conflicts
                }
                is GitResult.Err -> _uiState.update { it.copy(loading = false, error = result.error) }
            }
        }
    }

    // ---- op progress + path-scoped status patches ---------------------------------

    private suspend fun <T> withOpProgress(@StringRes titleRes: Int, block: suspend () -> T): T {
        _uiState.update { it.copy(opProgress = GitOpProgress(titleRes)) }
        try {
            return block()
        } finally {
            _uiState.update { it.copy(opProgress = null) }
            touchOwnActivity()
        }
    }

    private fun reportProgress(done: Int, total: Int) {
        _uiState.update { s -> s.opProgress?.let { s.copy(opProgress = it.copy(done = done, total = total)) } ?: s }
    }

    /** Folds a path-filtered status into the shown status: no whole-repo walk. */
    private fun applyStatusPatch(patch: GitStatusPatch) {
        if (_uiState.value.status == null) {
            refresh()
            return
        }
        _uiState.update { st -> st.copy(status = st.status?.patched(patch.touched, patch.fresh)) }
        val conflicts = _uiState.value.status?.conflicts?.size ?: 0
        val snap = _uiState.value.snapshot
        if (snap != null && (snap.mergeInProgress || snap.rebaseInProgress) &&
            lastConflictCount > 0 && conflicts == 0
        ) {
            message(R.string.git_msg_all_conflicts_resolved)
        }
        lastConflictCount = conflicts
        persistCache()
    }

    /** After a commit the index equals HEAD: derive the status locally, re-read only the snapshot. */
    private suspend fun applyCommitted() {
        _uiState.update { st -> st.status?.let { st.copy(status = it.afterCommit()) } ?: st }
        lastConflictCount = 0
        refreshSnapshotOnly()
    }

    /** Push/fetch/commit never change the working tree, so skip the status walk entirely. */
    private suspend fun refreshSnapshotOnly() {
        when (val r = session.snapshot()) {
            is GitResult.Ok -> {
                _uiState.update { it.copy(snapshot = r.value) }
                persistCache()
            }
            is GitResult.Err -> refresh()
        }
    }

    private fun persistCache() {
        val st = _uiState.value
        val snap = st.snapshot ?: return
        val status = st.status ?: return
        val cached = CachedStatus(snap, status, st.remotes)
        statusCache[projectPath] = cached
        saveDiskCache(projectPath, cached)
    }

    // ---- stage ---------------------------------------------------------------

    /**
     * Stage/unstage never triggers a full [refresh] anymore, "all" included:
     * [GitSession.stageAll] / [GitSession.unstageAll] only ever touch the index (never HEAD,
     * upstream tracking, or remotes config), so there's nothing in [refresh]'s
     * snapshot/remotes half that either could possibly change. JGit's `status().call()` walk
     * is what makes this feel slow, and since every path's before/after state is something
     * we already know from the status we're already showing, "all" can flip every currently
     * changed path locally in one pass — same idea as the single-path case below, just
     * applied to the whole list instead of one entry.
     */
    private fun stage(path: String?) {
        viewModelScope.launch(io) {
            if (path == null) {
                // Stage exactly what the Changes list shows via one batched index write; only
                // fall back to the whole-tree `git add .` when there's no status to go on yet.
                val pending = _uiState.value.status?.changes
                    ?.filter { it.unstaged != GitWorkingState.NONE }
                    ?.map { it.repoRelativePath }
                if (pending != null && pending.isEmpty()) return@launch
                val staged = withOpProgress(R.string.git_progress_staging) {
                    if (pending == null) session.stageAll()
                    else session.stagePaths(pending) { done, total -> reportProgress(done, total) }
                }
                when (val result = staged) {
                    is GitResult.Ok -> applyLocalStageAll()
                    is GitResult.Err -> _uiState.update { it.copy(error = result.error) }
                }
                return@launch
            }
            when (val result = session.stage(path)) {
                is GitResult.Ok -> applyLocalStage(path)
                is GitResult.Err -> _uiState.update { it.copy(error = result.error) }
            }
        }
    }

    private fun unstage(path: String?) {
        viewModelScope.launch(io) {
            if (path == null) {
                when (val result = withOpProgress(R.string.git_progress_staging) { session.unstageAll() }) {
                    is GitResult.Ok -> applyLocalUnstageAll()
                    is GitResult.Err -> _uiState.update { it.copy(error = result.error) }
                }
                return@launch
            }
            when (val result = session.unstage(path)) {
                is GitResult.Ok -> applyLocalUnstage(path)
                is GitResult.Err -> _uiState.update { it.copy(error = result.error) }
            }
        }
    }

    /** Flips [path]'s staged state locally to mirror what [GitSession.stage] just did. */
    private fun applyLocalStage(path: String) {
        _uiState.update { state ->
            val status = state.status ?: return@update state
            val changes = status.changes.map { change ->
                if (change.repoRelativePath != path) return@map change
                val staged = when (change.unstaged) {
                    GitWorkingState.UNTRACKED -> GitStageState.ADDED
                    GitWorkingState.DELETED -> GitStageState.DELETED
                    GitWorkingState.MODIFIED, GitWorkingState.NONE, GitWorkingState.CONFLICT ->
                        GitStageState.MODIFIED
                }
                // Staging clears the working-tree side too: `git add` snapshots the file
                // into the index, so there's nothing left un-added about it until it
                // changes again post-stage.
                change.copy(staged = staged, unstaged = GitWorkingState.NONE)
            }
            state.copy(status = status.copy(changes = changes))
        }
    }

    /** Flips [path]'s staged state back to NONE locally to mirror [GitSession.unstage]. */
    private fun applyLocalUnstage(path: String) {
        _uiState.update { state ->
            val status = state.status ?: return@update state
            val changes = status.changes.mapNotNull { change ->
                if (change.repoRelativePath != path) return@mapNotNull change
                // If the file was already showing further un-added changes on top of the
                // staged snapshot (staged MODIFIED + additionally edited since), that
                // working-tree state is unaffected by unstaging and must be kept as-is
                // rather than recomputed from `staged` alone.
                val unstaged = if (change.unstaged != GitWorkingState.NONE) {
                    change.unstaged
                } else when (change.staged) {
                    GitStageState.ADDED -> GitWorkingState.UNTRACKED
                    GitStageState.DELETED -> GitWorkingState.DELETED
                    GitStageState.MODIFIED, GitStageState.NONE, GitStageState.CONFLICT ->
                        GitWorkingState.MODIFIED
                }
                change.copy(staged = GitStageState.NONE, unstaged = unstaged)
            }
            state.copy(status = status.copy(changes = changes))
        }
    }

    /** [applyLocalStage] for every currently-unstaged path at once, to mirror [GitSession.stageAll]. */
    private fun applyLocalStageAll() {
        _uiState.update { state ->
            val status = state.status ?: return@update state
            val changes = status.changes.map { change ->
                if (change.unstaged == GitWorkingState.NONE) return@map change
                val staged = when (change.unstaged) {
                    GitWorkingState.UNTRACKED -> GitStageState.ADDED
                    GitWorkingState.DELETED -> GitStageState.DELETED
                    GitWorkingState.MODIFIED, GitWorkingState.NONE, GitWorkingState.CONFLICT ->
                        GitStageState.MODIFIED
                }
                change.copy(staged = staged, unstaged = GitWorkingState.NONE)
            }
            state.copy(status = status.copy(changes = changes))
        }
    }

    /** [applyLocalUnstage] for every currently-staged path at once, to mirror [GitSession.unstageAll]. */
    private fun applyLocalUnstageAll() {
        _uiState.update { state ->
            val status = state.status ?: return@update state
            val changes = status.changes.map { change ->
                if (change.staged == GitStageState.NONE) return@map change
                val unstaged = if (change.unstaged != GitWorkingState.NONE) {
                    change.unstaged
                } else when (change.staged) {
                    GitStageState.ADDED -> GitWorkingState.UNTRACKED
                    GitStageState.DELETED -> GitWorkingState.DELETED
                    GitStageState.MODIFIED, GitStageState.NONE, GitStageState.CONFLICT ->
                        GitWorkingState.MODIFIED
                }
                change.copy(staged = GitStageState.NONE, unstaged = unstaged)
            }
            state.copy(status = status.copy(changes = changes))
        }
    }

    private fun discard() {
        val target = _uiState.value.discardConfirm ?: return
        _uiState.update { it.copy(discardConfirm = null) }
        viewModelScope.launch(io) {
            when (val result = session.discard(target.path, target.isUntracked)) {
                is GitResult.Ok -> refresh()
                is GitResult.Err -> _uiState.update { it.copy(error = result.error) }
            }
        }
    }

    // ---- commit ---------------------------------------------------------------

    /** Amend ON pre-fills HEAD's message (editable, message-only reword stays possible). */
    private fun toggleAmend() {
        val turningOn = !_uiState.value.amend
        _uiState.update { it.copy(amend = turningOn) }
        if (!turningOn) return
        viewModelScope.launch(io) {
            when (val result = session.headCommitMessage()) {
                is GitResult.Ok -> result.value?.let { headMessage ->
                    _uiState.update {
                        // Only prefill if the box is still empty/untouched — don't clobber
                        // something the user already started typing before the toggle.
                        if (it.amend && it.commitMessage.isBlank()) {
                            it.copy(commitMessage = headMessage.trimEnd('\n'))
                        } else it
                    }
                }
                is GitResult.Err -> Unit
            }
        }
    }

    private fun commit() {
        val message = _uiState.value.commitMessage.trim()
        val amend = _uiState.value.amend
        // Amend allows an empty box through as a message-only reword of HEAD's own
        // message; a fresh (non-amend) commit still needs real text.
        if (message.isEmpty() && !amend) return
        viewModelScope.launch(io) {
            _uiState.update { it.copy(committing = true) }
            doCommit(message, amend)
            _uiState.update { it.copy(committing = false) }
        }
    }

    /** One commit attempt with the usual messages/dialogs; true only when the commit was made. */
    private suspend fun doCommit(message: String, amend: Boolean): Boolean {
        when (val result = withOpProgress(R.string.git_progress_committing) { session.commit(message, amend) }) {
            is GitResult.Ok -> {
                _uiState.update { it.copy(commitMessage = "", amend = false) }
                message(R.string.git_msg_committed)
                applyCommitted()
                return true
            }
            is GitResult.Err -> {
                when {
                    result.error.kind == GitErrorKind.EMPTY_COMMIT ->
                        message(R.string.git_err_nothing_to_commit)
                    result.error.kind == GitErrorKind.IDENTITY_MISSING ->
                        openIdentity()
                    else -> _uiState.update { it.copy(error = result.error) }
                }
                return false
            }
        }
    }

    // ---- pasted "git add / commit / push" ----------------------------------------------

    /**
     * Typing is always plain text. A single insertion of several characters at once (keyboard
     * clipboard chip, long-press paste) that holds a `git commit -m` snippet runs it instead.
     */
    private fun onCommitMessageChange(text: String) {
        val old = _uiState.value.commitMessage
        val script = if (text.length - old.length >= PASTE_MIN_CHARS) parseGitScript(text) else null
        if (script != null) runGitScript(script) else _uiState.update { it.copy(commitMessage = text) }
    }

    private fun onPasteCommitText(text: String) {
        val script = parseGitScript(text)
        if (script != null) runGitScript(script) else _uiState.update { it.copy(commitMessage = it.commitMessage + text) }
    }

    /** Same order as the shell: add -> commit -> push, each step only if the previous one worked. */
    private fun runGitScript(script: GitCommandScript) {
        if (_uiState.value.committing) return
        _uiState.update { it.copy(commitMessage = script.message, amend = false, committing = true) }
        viewModelScope.launch(io) {
            var ok = true
            if (script.stageAll) {
                when (val r = withOpProgress(R.string.git_progress_staging) { session.stageAll() }) {
                    is GitResult.Ok -> applyLocalStageAll()
                    is GitResult.Err -> {
                        _uiState.update { it.copy(error = r.error) }
                        ok = false
                    }
                }
            }
            val committed = ok && doCommit(script.message, amend = false)
            _uiState.update { it.copy(committing = false) }
            if (committed && script.push) push(force = false)
        }
    }

    private fun loadDiff(path: String) {
        if (_uiState.value.diffs.containsKey(path) || path in _uiState.value.diffLoading) return
        viewModelScope.launch(io) {
            _uiState.update { it.copy(diffLoading = it.diffLoading + path) }
            when (val result = session.fileDiff(path)) {
                is GitResult.Ok -> _uiState.update {
                    it.copy(
                        diffs = it.diffs + (path to result.value),
                        diffLoading = it.diffLoading - path,
                    )
                }
                is GitResult.Err -> _uiState.update {
                    it.copy(diffLoading = it.diffLoading - path, error = result.error)
                }
            }
        }
    }

    // ---- network ---------------------------------------------------------------

    private fun push(force: Boolean) {
        val snapshot = _uiState.value.snapshot ?: return
        if (!snapshot.hasCommits) return
        val remote = snapshot.trackingInfo?.remote
            ?: _uiState.value.remotes.firstOrNull { it.name == "origin" }?.name
            ?: _uiState.value.remotes.firstOrNull()?.name
        if (remote == null) {
            message(R.string.git_err_no_remote)
            return
        }
        val mode = if (force) PendingNetworkMode.Force else PendingNetworkMode.Plain
        viewModelScope.launch(io) {
            runNetwork(GitPendingAction.PUSH, remote, mode) { cp ->
                session.push(remote, cp, force = force) { p -> _uiState.update { it.copy(progressTask = p.task) } }
            }
        }
    }

    /**
     * [thenPush] re-runs a plain push right after a successful pull — used by the
     * "Pull then push" choice on the push-rejected dialog, so the person doesn't
     * have to tap Push again themselves. If a token prompt interrupts this pull,
     * [PendingNetworkMode.PullThenPush] carries that intent through so saving the
     * token still chains into the push instead of stopping at a plain pull.
     */
    private fun pull(rebase: Boolean, thenPush: Boolean = false) {
        val snapshot = _uiState.value.snapshot ?: return
        if (!snapshot.hasCommits) return
        val tracking = snapshot.trackingInfo
        val remote = tracking?.remote ?: _uiState.value.remotes.singleOrNull()?.name
        if (remote == null) {
            message(R.string.git_err_no_remote)
            return
        }
        val branch = tracking?.branch ?: snapshot.headName
        val mode = when {
            thenPush -> PendingNetworkMode.PullThenPush
            rebase -> PendingNetworkMode.Rebase
            else -> PendingNetworkMode.Plain
        }
        viewModelScope.launch(io) {
            val pulled = runNetwork(GitPendingAction.PULL, remote, mode) { cp ->
                session.pull(remote, branch, cp, rebase = rebase) { p -> _uiState.update { it.copy(progressTask = p.task) } }
            }
            if (thenPush && pulled) push(force = false)
        }
    }

    /** Last successful fetch of all remotes; lets the merge/rebase pickers skip a redundant fetch. */
    private var lastFetchAt = 0L

    private fun fetch() {
        val snapshot = _uiState.value.snapshot ?: return
        if (!snapshot.hasCommits) return
        // Saare remotes (origin + upstream...) fetch hote hain, tracking wala pehle.
        val tracking = snapshot.trackingInfo?.remote
        val remotes = (listOfNotNull(tracking) + _uiState.value.remotes.map { it.name }).distinct()
        if (remotes.isEmpty()) {
            message(R.string.git_err_no_remote)
            return
        }
        viewModelScope.launch(io) {
            var allOk = true
            for (remote in remotes) {
                val ok = runNetwork(GitPendingAction.FETCH, remote, PendingNetworkMode.Plain, announce = false) { cp ->
                    session.fetch(remote, cp) { p -> _uiState.update { it.copy(progressTask = p.task) } }
                }
                if (!ok) { allOk = false; break }
            }
            if (allOk) {
                lastFetchAt = System.currentTimeMillis()
                message(R.string.git_msg_fetched)
                refreshSnapshotOnly()
            }
        }
    }

    /** Returns true when the op succeeded — used by [pull]'s thenPush chaining. */
    private suspend fun runNetwork(
        action: GitPendingAction,
        remote: String,
        mode: PendingNetworkMode,
        announce: Boolean = true,
        call: suspend (CredentialsProvider?) -> GitResult<Unit>,
    ): Boolean {
        val url = session.remoteUrl(remote)
        val host = url?.let(::normalizeHost)
        val credential = host?.let { credentialStore.get(it) }
        if (host != null && credential == null) {
            _uiState.update { it.copy(tokenDialog = TokenDialogState(host, DEFAULT_USERNAME, action, mode)) }
            return false
        }
        val provider = credential?.let { GitTokenCredentialsProvider(it.username, it.token) }
        _uiState.update { it.copy(networkOp = action, progressTask = "") }
        val ok = when (val result = call(provider)) {
            is GitResult.Ok -> {
                if (action == GitPendingAction.PUSH) GitPushEvents.emit(projectPath)
                if (announce) {
                    message(
                        when (action) {
                            GitPendingAction.PUSH -> R.string.git_msg_pushed
                            GitPendingAction.PULL -> R.string.git_msg_pulled
                            else -> R.string.git_msg_fetched
                        },
                    )
                    if (action == GitPendingAction.PUSH) refreshSnapshotOnly() else refresh()
                }
                true
            }
            is GitResult.Err -> {
                if (host != null && result.error.authFailure.isAuthError()) {
                    _uiState.update {
                        it.copy(
                            tokenDialog = TokenDialogState(
                                host,
                                credential?.username ?: DEFAULT_USERNAME,
                                action,
                                mode,
                            ),
                        )
                    }
                } else if (action == GitPendingAction.PUSH &&
                    result.error.kind == GitErrorKind.PUSH_REJECTED
                ) {
                    _uiState.update { it.copy(pushRejected = true) }
                } else if (action == GitPendingAction.PUSH &&
                    result.error.kind == GitErrorKind.UNFETCHED_COMMITS
                ) {
                    _uiState.update { it.copy(fetchFirstRemote = remote) }
                } else {
                    _uiState.update { it.copy(error = result.error) }
                }
                false
            }
        }
        _uiState.update { it.copy(networkOp = null, progressTask = "") }
        touchOwnActivity()
        return ok
    }

    private fun GitAuthFailureType.isAuthError(): Boolean =
        this == GitAuthFailureType.AUTH_REQUIRED ||
            this == GitAuthFailureType.INVALID_CREDENTIALS ||
            this == GitAuthFailureType.EXPIRED_TOKEN ||
            this == GitAuthFailureType.PERMISSION_DENIED

    // ---- M10: merge + conflicts ------------------------------------------------

    private fun openMergeDialog() {
        val snapshot = _uiState.value.snapshot ?: return
        if (!snapshot.hasCommits || snapshot.mergeInProgress) return
        viewModelScope.launch(io) {
            _uiState.update { it.copy(mergeDialog = true, mergeCandidates = emptyList()) }
            backgroundPruneFetch()
            when (val result = session.mergeCandidates()) {
                is GitResult.Ok -> _uiState.update { it.copy(mergeCandidates = result.value) }
                is GitResult.Err -> _uiState.update {
                    it.copy(mergeDialog = false, error = result.error)
                }
            }
        }
    }

    private fun merge(branch: String) {
        viewModelScope.launch(io) {
            _uiState.update { it.copy(merging = true, mergeDialog = false) }
            when (val result = withOpProgress(R.string.git_merging) { session.mergeBranch(branch) }) {
                is GitResult.Ok -> message(
                    when (result.value) {
                        MergeOutcome.FAST_FORWARD -> R.string.git_msg_merged_ff
                        MergeOutcome.MERGED -> R.string.git_msg_merged
                        MergeOutcome.ALREADY_UP_TO_DATE -> R.string.git_msg_merge_uptodate
                        MergeOutcome.CONFLICTS -> R.string.git_msg_merge_conflicts
                    },
                )
                is GitResult.Err -> _uiState.update { it.copy(error = result.error) }
            }
            _uiState.update { it.copy(merging = false, mergeCandidates = emptyList()) }
            refresh()
        }
    }

    private fun resolveConflict(path: String, side: GitConflictSide) = resolvePaths(listOf(path), side)

    private fun markResolved(path: String) = resolvePaths(listOf(path), null)

    private fun markAllResolved() {
        val paths = _uiState.value.status?.conflicts
            ?.map { it.repoRelativePath }?.distinct().orEmpty()
        if (paths.isEmpty()) return
        resolvePaths(paths, null)
    }

    /** One batched index rewrite + path-filtered status; progress dialog while it runs. */
    private fun resolvePaths(paths: List<String>, side: GitConflictSide?) {
        viewModelScope.launch(io) {
            val result = withOpProgress(R.string.git_progress_resolving) {
                session.resolveConflicts(paths, side) { done, total -> reportProgress(done, total) }
            }
            when (result) {
                is GitResult.Ok -> applyStatusPatch(result.value)
                is GitResult.Err -> {
                    _uiState.update { it.copy(error = result.error) }
                    refresh()
                }
            }
        }
    }

    private fun previewConflictSide(path: String, side: GitConflictSide) {
        viewModelScope.launch(io) {
            when (val result = session.conflictSideContent(path, side)) {
                is GitResult.Ok -> _uiState.update {
                    it.copy(conflictPreview = ConflictPreviewState(path, side, result.value))
                }
                is GitResult.Err -> _uiState.update { it.copy(error = result.error) }
            }
        }
    }

    private fun abortMerge() {
        viewModelScope.launch(io) {
            _uiState.update { it.copy(abortConfirm = false) }
            when (val result = withOpProgress(R.string.git_progress_aborting) { session.abortMerge() }) {
                is GitResult.Ok -> {
                    message(R.string.git_msg_merge_aborted)
                    refresh()
                }
                is GitResult.Err -> _uiState.update { it.copy(error = result.error) }
            }
        }
    }

    private fun openCompleteMerge() {
        viewModelScope.launch(io) {
            val saved = (session.mergeMessage() as? GitResult.Ok)?.value
            val head = _uiState.value.snapshot?.headName.orEmpty()
            _uiState.update {
                it.copy(
                    completeMergeDialog = true,
                    completeMergeMessage = saved ?: "Merge branch '$head'",
                )
            }
        }
    }

    private fun completeMerge() {
        val message = _uiState.value.completeMergeMessage.trim()
        if (message.isEmpty()) return
        viewModelScope.launch(io) {
            _uiState.update { it.copy(completingMerge = true) }
            when (val result = withOpProgress(R.string.git_progress_committing) { session.completeMerge(message) }) {
                is GitResult.Ok -> {
                    _uiState.update { it.copy(completeMergeDialog = false) }
                    message(R.string.git_msg_committed)
                    applyCommitted()
                }
                is GitResult.Err -> {
                    when {
                        result.error.kind == GitErrorKind.IDENTITY_MISSING -> {
                            _uiState.update { it.copy(completeMergeDialog = false) }
                            openIdentity()
                        }
                        else -> _uiState.update { it.copy(error = result.error) }
                    }
                }
            }
            _uiState.update { it.copy(completingMerge = false) }
        }
    }

    // ---- M11: rebase (continue/skip/abort) --------------------------------------

    private fun continueRebase() {
        viewModelScope.launch(io) {
            when (val result = withOpProgress(R.string.git_progress_continuing) { session.continueRebase() }) {
                is GitResult.Ok -> {
                    message(rebaseOutcomeMessage(result.value))
                    refresh()
                }
                is GitResult.Err -> {
                    if (result.error.kind == GitErrorKind.IDENTITY_MISSING) {
                        openIdentity()
                    } else {
                        _uiState.update { it.copy(error = result.error) }
                    }
                }
            }
        }
    }

    private fun skipRebaseCommit() {
        viewModelScope.launch(io) {
            when (val result = withOpProgress(R.string.git_progress_continuing) { session.skipRebaseCommit() }) {
                is GitResult.Ok -> {
                    message(rebaseOutcomeMessage(result.value))
                    refresh()
                }
                is GitResult.Err -> _uiState.update { it.copy(error = result.error) }
            }
        }
    }

    private fun abortRebase() {
        viewModelScope.launch(io) {
            _uiState.update { it.copy(abortRebaseConfirm = false) }
            when (val result = withOpProgress(R.string.git_progress_aborting) { session.abortRebase() }) {
                is GitResult.Ok -> {
                    message(R.string.git_msg_rebase_aborted)
                    refresh()
                }
                is GitResult.Err -> _uiState.update { it.copy(error = result.error) }
            }
        }
    }

    /** Started from the top bar: pick a branch to move the current branch's commits onto. */
    private fun openRebasePicker() {
        val snapshot = _uiState.value.snapshot ?: return
        if (!snapshot.hasCommits || snapshot.rebaseInProgress || snapshot.mergeInProgress) return
        viewModelScope.launch(io) {
            _uiState.update {
                it.copy(rebasePicker = true, rebaseLoading = true, rebaseCandidates = emptyList())
            }
            // No network fetch here: the picker lists what's already fetched so it opens instantly.
            when (val result = session.mergeCandidates()) {
                is GitResult.Ok -> _uiState.update {
                    it.copy(rebaseCandidates = result.value, rebaseLoading = false)
                }
                is GitResult.Err -> _uiState.update {
                    it.copy(rebasePicker = false, rebaseLoading = false, error = result.error)
                }
            }
        }
    }

    /**
     * Best-effort, silent fetch (with prune) so the merge/rebase branch picker
     * isn't showing a stale list — a deleted remote branch lingering, or one
     * created since the last real fetch, missing. Never surfaces an auth prompt
     * or error: an expired token or offline device just means the picker falls
     * back to whatever was already fetched, same as before this existed.
     */
    private suspend fun backgroundPruneFetch() {
        // Abhi hi (manual ya picker se) fetch ho chuka hai to dobara network hit nahi.
        if (System.currentTimeMillis() - lastFetchAt < FETCH_FRESH_MS) return
        val tracking = _uiState.value.snapshot?.trackingInfo?.remote
        val remotes = (listOfNotNull(tracking) + _uiState.value.remotes.map { it.name }).distinct()
        for (remote in remotes) {
            val host = session.remoteUrl(remote)?.let(::normalizeHost)
            val credential = host?.let { credentialStore.get(it) }
            if (host != null && credential == null) continue
            val provider = credential?.let { GitTokenCredentialsProvider(it.username, it.token) }
            session.fetch(remote, provider)
        }
        lastFetchAt = System.currentTimeMillis()
    }

    private fun rebaseOnto(branch: String) {
        viewModelScope.launch(io) {
            _uiState.update { it.copy(rebasing = true) }
            when (val result = withOpProgress(R.string.git_rebasing) { session.rebaseOnto(branch) }) {
                is GitResult.Ok -> message(rebaseOutcomeMessage(result.value))
                is GitResult.Err -> _uiState.update { it.copy(error = result.error) }
            }
            _uiState.update {
                it.copy(rebasing = false, rebasePicker = false, rebaseCandidates = emptyList())
            }
            refresh()
        }
    }

    private fun rebaseOutcomeMessage(outcome: RebaseOutcome): Int = when (outcome) {
        RebaseOutcome.FAST_FORWARD -> R.string.git_msg_rebase_ff
        RebaseOutcome.OK -> R.string.git_msg_rebase_ok
        RebaseOutcome.ALREADY_UP_TO_DATE -> R.string.git_msg_rebase_uptodate
        RebaseOutcome.CONFLICTS -> R.string.git_msg_rebase_conflicts
    }

    // ---- reset -------------------------------------------------------------

    private fun loadResetBranches() {
        viewModelScope.launch(io) {
            val names = (session.mergeCandidates() as? GitResult.Ok)?.value.orEmpty()
            _uiState.update { it.copy(resetBranches = names, resetBranchesLoaded = true) }
        }
    }

    private fun loadResetCommits() {
        if (_uiState.value.resetCommits.isNotEmpty() || _uiState.value.resetCommitsLoading) return
        viewModelScope.launch(io) {
            _uiState.update { it.copy(resetCommitsLoading = true) }
            when (val result = session.log(max = 100)) {
                is GitResult.Ok -> _uiState.update {
                    it.copy(resetCommits = result.value, resetCommitsLoading = false)
                }
                is GitResult.Err -> _uiState.update {
                    it.copy(resetCommitsLoading = false, error = result.error)
                }
            }
        }
    }

    /** Hard needs an explicit "this discards changes" confirm first; soft/mixed run right away. */
    private fun requestReset(ref: String, label: String, mode: GitResetMode) {
        if (mode == GitResetMode.HARD) {
            _uiState.update { it.copy(resetHardConfirm = PendingReset(ref, label, mode)) }
        } else {
            performReset(ref, label, mode)
        }
    }

    private fun confirmHardReset() {
        val pending = _uiState.value.resetHardConfirm ?: return
        _uiState.update { it.copy(resetHardConfirm = null) }
        performReset(pending.ref, pending.label, pending.mode)
    }

    private fun performReset(ref: String, label: String, mode: GitResetMode) {
        viewModelScope.launch(io) {
            _uiState.update { it.copy(resetting = true) }
            when (val result = withOpProgress(R.string.git_progress_resetting) { session.resetTo(ref, mode) }) {
                is GitResult.Ok -> {
                    _uiState.update {
                        it.copy(resetSheetOpen = false, resetCommits = emptyList(), resetBranches = emptyList(), resetBranchesLoaded = false)
                    }
                    message(R.string.git_reset_success, mode.label(), label)
                    refresh()
                }
                is GitResult.Err -> _uiState.update { it.copy(error = result.error) }
            }
            _uiState.update { it.copy(resetting = false) }
        }
    }

    private fun GitResetMode.label(): String = when (this) {
        GitResetMode.SOFT -> "soft"
        GitResetMode.MIXED -> "mixed"
        GitResetMode.HARD -> "hard"
    }

    // ---- identity / token dialogs ----------------------------------------------

    private fun openIdentity() {
        viewModelScope.launch(io) {
            val local = (session.getIdentity(local = true) as? GitResult.Ok)?.value
            val global = (session.getIdentity(local = false) as? GitResult.Ok)?.value
            val chosen = local ?: global
            _uiState.update {
                it.copy(
                    identity = IdentityDialogState(
                        name = chosen?.name ?: "",
                        email = chosen?.email ?: "",
                        isLocal = local != null,
                    ),
                )
            }
        }
    }

    private fun saveIdentity(name: String, email: String, isLocal: Boolean) {
        viewModelScope.launch(io) {
            when (val result = session.setIdentity(name, email, isLocal)) {
                is GitResult.Ok -> {
                    _uiState.update { it.copy(identity = null) }
                    message(R.string.git_msg_identity_saved)
                    refresh()
                }
                is GitResult.Err -> _uiState.update { it.copy(identity = null, error = result.error) }
            }
        }
    }

    private fun saveToken(host: String, username: String, token: String) {
        credentialStore.put(GitCredential(host, username.ifBlank { DEFAULT_USERNAME }, token))
        val dialog = _uiState.value.tokenDialog
        _uiState.update { it.copy(tokenDialog = null) }
        message(R.string.git_msg_token_saved, host)
        when (dialog?.pending) {
            GitPendingAction.PUSH -> push(force = dialog.mode == PendingNetworkMode.Force)
            GitPendingAction.PULL -> when (dialog.mode) {
                PendingNetworkMode.Rebase -> pull(rebase = true)
                PendingNetworkMode.PullThenPush -> pull(rebase = false, thenPush = true)
                else -> pull(rebase = false)
            }
            GitPendingAction.FETCH -> fetch()
            else -> Unit
        }
    }

    private fun openBranchMenu() {
        _uiState.update { it.copy(branchMenuOpen = true) }
        viewModelScope.launch(io) {
            when (val result = session.listBranches()) {
                is GitResult.Ok -> _uiState.update {
                    it.copy(branchMenuItems = result.value.map { b -> b.name }.sorted())
                }
                is GitResult.Err -> _uiState.update {
                    it.copy(branchMenuOpen = false, error = result.error)
                }
            }
        }
    }

    private fun checkoutBranch(name: String) {
        _uiState.update { it.copy(branchMenuOpen = false) }
        if (name == _uiState.value.snapshot?.headName) return
        viewModelScope.launch(io) {
            when (val result = session.checkoutBranch(name, create = false)) {
                is GitResult.Ok -> {
                    message(R.string.git_msg_switched_branch, name)
                    refresh()
                }
                is GitResult.Err -> _uiState.update { it.copy(error = result.error) }
            }
        }
    }

    private fun message(res: Int, vararg args: Any) {
        _effects.trySend(Effect.Message(UiText(res, args.toList())))
    }

    companion object {
        private const val DEFAULT_USERNAME = "x-access-token"
        private const val EXTERNAL_CHANGE_DEBOUNCE_MS = 400L
        private const val PASSIVE_REFRESH_MIN_MS = 5_000L

        private val statusPatches =
            kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, GitStatusPatch>>(extraBufferCapacity = 16)

        /**
         * Called by the editor after it saves [files]: patches just those paths into the cached
         * status (and any live Git screen), so opening Source Control shows the edit instantly
         * instead of only after the full status walk finishes. No-op outside a git repo.
         */
        suspend fun onFilesSaved(projectPath: String, files: List<File>) {
            val root = File(projectPath)
            val rels = files.mapNotNull { f ->
                runCatching { f.absoluteFile.relativeTo(root.absoluteFile).invariantSeparatorsPath }.getOrNull()
                    ?.takeIf { it.isNotEmpty() && !it.startsWith("..") && !it.startsWith(".git/") }
            }
            if (rels.isEmpty() || !File(root, ".git").exists()) return
            val session = GitSessionRegistry.acquire(root)
            try {
                val result = session.statusFor(rels)
                if (result !is GitResult.Ok) return
                val patch = result.value
                statusCache[projectPath]?.let { cached ->
                    val updated = cached.copy(status = cached.status.patched(patch.touched, patch.fresh))
                    statusCache[projectPath] = updated
                    saveDiskCache(projectPath, updated)
                }
                statusPatches.tryEmit(projectPath to patch)
            } finally {
                GitSessionRegistry.release(root)
            }
        }
        private const val OWN_ACTIVITY_GRACE_MS = 1_500L

        /** A fetch newer than this is considered fresh; merge/rebase pickers skip re-fetching. */
        private const val FETCH_FRESH_MS = 5 * 60 * 1000L

        const val SECTION_CONFLICTS = "conflicts"
        const val SECTION_STAGED = "staged"
        const val SECTION_CHANGES = "changes"
        const val SECTION_UNTRACKED = "untracked"

        /**
         * In-memory, per-process cache of each project's last known Source Control state.
         * [GitViewModel] gets a fresh instance every time its owning back-stack entry is
         * torn down — i.e. leaving the project and coming back — and building that state
         * from scratch (`refresh()`'s `session.refreshFull()`) costs exactly as much as
         * the very first load. Caching it here means a revisit shows the last-known state
         * immediately while a real [refresh] quietly catches up underneath.
         */
        private val statusCache =
            java.util.concurrent.ConcurrentHashMap<String, CachedStatus>()

        private data class CachedStatus(
            val snapshot: GitRepoSnapshot,
            val status: GitWorkingTreeStatus,
            val remotes: List<GitRemoteInfo>,
        )

        /** Where the on-disk snapshot for [projectPath] lives — next to git's own metadata. */
        private fun diskCacheFile(projectPath: String): File =
            File(projectPath, ".git/kodex-sc-cache.json")

        /**
         * Best-effort disk snapshot of the last known status, read on a cold start (fresh
         * app process, so [statusCache] is empty) before the real [GitSession.refreshFull]
         * lands. Any read/parse failure — missing file, corrupt JSON, schema mismatch after
         * an app update — is swallowed and just means no seed, never a crash.
         */
        private fun loadDiskCache(projectPath: String): CachedStatus? = runCatching {
            val file = diskCacheFile(projectPath)
            if (!file.isFile) return null
            val root = org.json.JSONObject(file.readText())

            val snapJson = root.getJSONObject("snapshot")
            val trackingJson = snapJson.optJSONObject("tracking")
            val snapshot = GitRepoSnapshot(
                gitRoot = File(projectPath),
                hasCommits = snapJson.getBoolean("hasCommits"),
                headId = snapJson.optString("headId", null),
                headName = snapJson.optString("headName", null),
                trackingInfo = trackingJson?.let {
                    GitTrackingInfo(
                        remote = it.getString("remote"),
                        branch = it.getString("branch"),
                        ahead = it.getInt("ahead"),
                        behind = it.getInt("behind"),
                    )
                },
                mergeInProgress = snapJson.getBoolean("mergeInProgress"),
                rebaseInProgress = snapJson.getBoolean("rebaseInProgress"),
            )

            val statusJson = root.getJSONObject("status")
            val changesJson = statusJson.getJSONArray("changes")
            val changes = (0 until changesJson.length()).map { i ->
                val c = changesJson.getJSONObject(i)
                GitPathChange(
                    repoRelativePath = c.getString("path"),
                    staged = GitStageState.valueOf(c.getString("staged")),
                    unstaged = GitWorkingState.valueOf(c.getString("unstaged")),
                )
            }
            val status = GitWorkingTreeStatus(
                changes = changes,
                hasUnmerged = statusJson.getBoolean("hasUnmerged"),
            )

            val remotesJson = root.getJSONArray("remotes")
            val remotes = (0 until remotesJson.length()).map { i ->
                val r = remotesJson.getJSONObject(i)
                GitRemoteInfo(name = r.getString("name"), url = r.getString("url"))
            }

            CachedStatus(snapshot, status, remotes)
        }.getOrNull()

        /** Mirror of [loadDiskCache]; failures are swallowed — losing the cache is fine. */
        private fun saveDiskCache(projectPath: String, cached: CachedStatus) {
            runCatching {
                val snapJson = org.json.JSONObject().apply {
                    put("hasCommits", cached.snapshot.hasCommits)
                    put("headId", cached.snapshot.headId)
                    put("headName", cached.snapshot.headName)
                    cached.snapshot.trackingInfo?.let {
                        put("tracking", org.json.JSONObject().apply {
                            put("remote", it.remote)
                            put("branch", it.branch)
                            put("ahead", it.ahead)
                            put("behind", it.behind)
                        })
                    }
                    put("mergeInProgress", cached.snapshot.mergeInProgress)
                    put("rebaseInProgress", cached.snapshot.rebaseInProgress)
                }

                val changesJson = org.json.JSONArray()
                cached.status.changes.forEach { c ->
                    changesJson.put(org.json.JSONObject().apply {
                        put("path", c.repoRelativePath)
                        put("staged", c.staged.name)
                        put("unstaged", c.unstaged.name)
                    })
                }
                val statusJson = org.json.JSONObject().apply {
                    put("changes", changesJson)
                    put("hasUnmerged", cached.status.hasUnmerged)
                }

                val remotesJson = org.json.JSONArray()
                cached.remotes.forEach { r ->
                    remotesJson.put(org.json.JSONObject().apply {
                        put("name", r.name)
                        put("url", r.url)
                    })
                }

                val root = org.json.JSONObject().apply {
                    put("snapshot", snapJson)
                    put("status", statusJson)
                    put("remotes", remotesJson)
                }
                diskCacheFile(projectPath).writeText(root.toString())
            }
        }

        fun factory(projectPath: String) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                GitViewModel(
                    projectPath = projectPath,
                    credentialStore = app.container.gitCredentialStore,
                    globalIdentityFile = app.container.gitGlobalIdentityFile,
                    settingsStore = app.container.editorSettingsStore,
                )
            }
        }
    }
}
