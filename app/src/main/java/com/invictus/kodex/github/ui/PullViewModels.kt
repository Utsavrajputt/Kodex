package com.invictus.kodex.github.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.KodexApp
import com.invictus.kodex.R
import com.invictus.kodex.core.git.DiffParser
import com.invictus.kodex.core.git.model.GitCommitFile
import com.invictus.kodex.core.git.model.GitCommitFileChange
import com.invictus.kodex.core.git.model.GitCommitSummary
import com.invictus.kodex.core.git.model.GitFileDiffResult
import com.invictus.kodex.core.github.GitHubProfileRepository
import com.invictus.kodex.github.api.GhCommitDetail
import com.invictus.kodex.github.api.GhCompare
import com.invictus.kodex.github.api.GhPull
import com.invictus.kodex.github.api.GhPullCommit
import com.invictus.kodex.github.api.GhPullFile
import com.invictus.kodex.github.api.GhRepoRef
import com.invictus.kodex.github.api.GitHubError
import com.invictus.kodex.github.api.PullFilter
import com.invictus.kodex.github.api.PullMergeMethod
import com.invictus.kodex.github.auth.GitHubTokenProvider
import com.invictus.kodex.github.data.GitHubProjectResolver
import com.invictus.kodex.github.data.GitHubRepository
import com.invictus.kodex.github.data.GitHubResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Resolves the project's GitHub repo once and keeps the repository object; shared by the three pull request screens. */
internal class RepoHolder(
    private val projectPath: String,
    private val resolver: GitHubProjectResolver,
    private val repoFactory: (GhRepoRef) -> GitHubRepository,
) {
    var repository: GitHubRepository? = null
        private set
    var ref: GhRepoRef? = null
        private set

    /** Null when the project is not ready (no git / no GitHub remote / no token). */
    suspend fun get(): GitHubRepository? {
        repository?.let { return it }
        return when (val r = resolver.resolve(projectPath)) {
            is GitHubResolution.Ready -> repoFactory(r.repo).also { repository = it; ref = r.repo }
            else -> null
        }
    }
}

/** Like [describe], but a 404 names the repo and GitHub's own message so the cause can be pinned down. */
internal fun describeFor(repo: String?, e: GitHubError): GhText =
    if (e is GitHubError.NotFound && !repo.isNullOrBlank()) {
        GhText.Res(R.string.pr_err_not_found_repo, listOf(repo, e.message.orEmpty().ifBlank { "Not Found" }))
    } else describe(e)

// ---- list ----------------------------------------------------------------------------------

data class PullListState(
    val repoName: String = "",
    val filter: PullFilter = PullFilter.Open,
    val items: List<GhPull> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = false,
    val error: GhText? = null,
    /** The repo owner turned pull requests off (GitHub setting), so there is nothing to list or create. */
    val disabled: Boolean = false,
)

class PullListViewModel(
    projectPath: String,
    resolver: GitHubProjectResolver,
    repoFactory: (GhRepoRef) -> GitHubRepository,
    private val profiles: GitHubProfileRepository,
    private val tokens: GitHubTokenProvider,
) : ViewModel() {

    private val holder = RepoHolder(projectPath, resolver, repoFactory)
    private val _state = MutableStateFlow(PullListState())
    val state: StateFlow<PullListState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GhEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<GhEvent> = _events.asSharedFlow()

    private var job: Job? = null
    private var nextPage = 1
    private var login: String? = null
    private var resumed = false

    init { load(reset = true) }

    /** Back from a detail / create screen: the list may have changed (merged, closed, new PR). */
    fun onResume() {
        if (!resumed) { resumed = true; return }
        load(reset = true)
    }

    fun setFilter(f: PullFilter) {
        if (f == _state.value.filter) return
        _state.update { it.copy(filter = f, items = emptyList(), hasMore = false) }
        load(reset = true)
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(reset = true)
    }

    fun loadMore() {
        val s = _state.value
        if (s.hasMore && !s.loadingMore && !s.loading) load(reset = false)
    }

    fun retry() = load(reset = true)

    private fun load(reset: Boolean) {
        job?.cancel()
        val filter = _state.value.filter
        _state.update {
            if (reset) it.copy(loading = it.items.isEmpty(), error = null) else it.copy(loadingMore = true)
        }
        job = viewModelScope.launch {
            try {
                val repo = holder.get() ?: run { fail(GhText.Res(R.string.gh_err_not_ready)); return@launch }
                _state.update { it.copy(repoName = holder.ref?.fullName.orEmpty()) }
                if (filter == PullFilter.Mine && login == null) {
                    login = profiles.cached(GitHubTokenProvider.HOST)?.login
                        ?: tokens.token()?.let { profiles.fetch(GitHubTokenProvider.HOST, it)?.login }
                }
                if (reset) {
                    // GitHub answers 404 for the pulls of a repo with pull requests switched off: ask the repo itself first.
                    val info = try { repo.repoInfo() } catch (e: GitHubError) { null }
                    if (info != null && !info.pullsEnabled) {
                        _state.update {
                            it.copy(loading = false, loadingMore = false, refreshing = false, items = emptyList(), hasMore = false, error = null, disabled = true)
                        }
                        return@launch
                    }
                    if (_state.value.disabled) _state.update { it.copy(disabled = false) }
                }
                val result = repo.pulls(if (reset) 1 else nextPage, filter, login)
                nextPage = result.page + 1
                _state.update {
                    it.copy(
                        items = if (reset) result.items else (it.items + result.items).distinctBy { p -> p.number },
                        hasMore = result.hasMore,
                        loading = false, loadingMore = false, refreshing = false, error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                if (_state.value.items.isNotEmpty()) {
                    _state.update { it.copy(loading = false, loadingMore = false, refreshing = false) }
                    _events.tryEmit(GhEvent.Snack(describeFor(holder.ref?.fullName, e)))
                } else {
                    fail(describeFor(holder.ref?.fullName, e))
                }
            }
        }
    }

    private fun fail(text: GhText) = _state.update { it.copy(loading = false, loadingMore = false, refreshing = false, error = text) }

    companion object {
        fun factory(projectPath: String) = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as KodexApp).container
                PullListViewModel(projectPath, c.gitHubProjectResolver, c::gitHubRepository, c.gitHubProfileRepository, c.gitHubTokenProvider)
            }
        }
    }
}

// ---- detail --------------------------------------------------------------------------------

enum class PullTab { Overview, Commits, Files }

data class PullDetailState(
    val loading: Boolean = true,
    val error: GhText? = null,
    val pull: GhPull? = null,
    val tab: PullTab = PullTab.Overview,
    val commits: List<GhPullCommit>? = null,
    val commitsLoading: Boolean = false,
    val commitsError: GhText? = null,
    val files: List<GhPullFile>? = null,
    val filesLoading: Boolean = false,
    val filesError: GhText? = null,
    /** A merge / close / reopen request is in flight. */
    val acting: Boolean = false,
    val mergeDialog: Boolean = false,
    val closeDialog: Boolean = false,
    val mergeMethod: PullMergeMethod = PullMergeMethod.Merge,
    val deleteBranch: Boolean = false,
    /** Commit opened from the Commits tab (same detail screen as git history); null = list. */
    val commitView: PullCommitView? = null,
)

/** Remote twin of the git-history commit detail state: header, changed files, and the open file's diff. */
data class PullCommitView(
    val summary: GitCommitSummary,
    val loading: Boolean = true,
    val files: List<GitCommitFile> = emptyList(),
    val patches: Map<String, GhPullFile> = emptyMap(),
    val added: Int? = null,
    val removed: Int? = null,
    val selected: GitCommitFile? = null,
    val diff: GitFileDiffResult? = null,
    val diffLoading: Boolean = false,
)

private fun GhPullFile.toCommitFile() = GitCommitFile(
    path = filename,
    oldPath = previousFilename,
    change = when (status) {
        "added" -> GitCommitFileChange.ADDED
        "removed" -> GitCommitFileChange.DELETED
        "renamed" -> GitCommitFileChange.RENAMED
        "copied" -> GitCommitFileChange.COPIED
        else -> GitCommitFileChange.MODIFIED
    },
)

private fun GhCommitDetail.toSummary() = GitCommitSummary(
    id = sha, shortId = sha.take(7), message = message, author = author, timeMs = dateMs, authorEmail = authorEmail,
)

class PullDetailViewModel(
    projectPath: String,
    private val number: Int,
    resolver: GitHubProjectResolver,
    repoFactory: (GhRepoRef) -> GitHubRepository,
) : ViewModel() {

    private val holder = RepoHolder(projectPath, resolver, repoFactory)
    private val _state = MutableStateFlow(PullDetailState())
    val state: StateFlow<PullDetailState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GhEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<GhEvent> = _events.asSharedFlow()

    init { load() }

    fun retry() = load()

    fun refresh() {
        load(silent = true)
        _state.update { it.copy(commits = null, files = null, commitsError = null, filesError = null) }
        ensureTabLoaded(_state.value.tab)
    }

    private fun load(silent: Boolean = false) {
        if (!silent) _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val repo = holder.get() ?: run { fail(GhText.Res(R.string.gh_err_not_ready)); return@launch }
                var pull = repo.pull(number)
                _state.update { it.copy(loading = false, error = null, pull = pull) }
                if (pull.isOpen && pull.mergeable == null) {
                    // GitHub computes mergeability lazily; ask once more a moment later.
                    delay(2_000)
                    pull = repo.pull(number)
                    _state.update { it.copy(pull = pull) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                if (silent && _state.value.pull != null) _events.tryEmit(GhEvent.Snack(describeFor(holder.ref?.fullName, e))) else fail(describeFor(holder.ref?.fullName, e))
            }
        }
    }

    private fun fail(text: GhText) = _state.update { it.copy(loading = false, error = text) }

    fun setTab(tab: PullTab) {
        _state.update { it.copy(tab = tab) }
        ensureTabLoaded(tab)
    }

    private fun ensureTabLoaded(tab: PullTab) {
        val s = _state.value
        when (tab) {
            PullTab.Commits -> if (s.commits == null && !s.commitsLoading) loadCommits()
            PullTab.Files -> if (s.files == null && !s.filesLoading) loadFiles()
            PullTab.Overview -> Unit
        }
    }

    fun retryTab() = when (_state.value.tab) {
        PullTab.Commits -> loadCommits()
        PullTab.Files -> loadFiles()
        PullTab.Overview -> load()
    }

    private fun loadCommits() {
        _state.update { it.copy(commitsLoading = true, commitsError = null) }
        viewModelScope.launch {
            try {
                val repo = holder.get() ?: run { _state.update { it.copy(commitsLoading = false, commitsError = GhText.Res(R.string.gh_err_not_ready)) }; return@launch }
                val list = repo.pullCommits(number)
                _state.update { it.copy(commits = list, commitsLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update { it.copy(commitsLoading = false, commitsError = describeFor(holder.ref?.fullName, e)) }
            }
        }
    }

    private fun loadFiles() {
        _state.update { it.copy(filesLoading = true, filesError = null) }
        viewModelScope.launch {
            try {
                val repo = holder.get() ?: run { _state.update { it.copy(filesLoading = false, filesError = GhText.Res(R.string.gh_err_not_ready)) }; return@launch }
                val list = repo.pullFiles(number)
                _state.update { it.copy(files = list, filesLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update { it.copy(filesLoading = false, filesError = describeFor(holder.ref?.fullName, e)) }
            }
        }
    }

    // ---- commit detail (tap a commit in the Commits tab) ----

    fun openCommit(c: GhPullCommit) {
        _state.update {
            it.copy(
                commitView = PullCommitView(
                    GitCommitSummary(c.sha, c.shortSha, c.message, c.author, c.date?.toEpochMilli() ?: 0L),
                ),
            )
        }
        viewModelScope.launch {
            try {
                val repo = holder.get() ?: run {
                    _state.update { it.copy(commitView = null) }
                    _events.tryEmit(GhEvent.Snack(GhText.Res(R.string.gh_err_not_ready)))
                    return@launch
                }
                val d = repo.commit(c.sha)
                _state.update { st ->
                    val cur = st.commitView
                    if (cur == null || cur.summary.id != c.sha) st
                    else st.copy(
                        commitView = cur.copy(
                            summary = d.toSummary(),
                            loading = false,
                            files = d.files.map { f -> f.toCommitFile() },
                            patches = d.files.associateBy { f -> f.filename },
                            added = d.additions,
                            removed = d.deletions,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update { it.copy(commitView = null) }
                _events.tryEmit(GhEvent.Snack(describeFor(holder.ref?.fullName, e)))
            }
        }
    }

    fun openCommitFile(f: GitCommitFile) {
        val v = _state.value.commitView ?: return
        _state.update { it.copy(commitView = v.copy(selected = f, diff = null, diffLoading = true)) }
        viewModelScope.launch {
            val patch = v.patches[f.path]?.patch
            val diff = withContext(Dispatchers.Default) {
                // No patch = binary or too large for GitHub to inline: the viewer shows its binary notice.
                DiffParser.parse(patch ?: "Binary files differ\n", f.path, "").copy(workFilePath = null)
            }
            _state.update { st ->
                val cur = st.commitView
                if (cur == null || cur.selected != f) st else st.copy(commitView = cur.copy(diff = diff, diffLoading = false))
            }
        }
    }

    fun closeCommitFile() = _state.update { st -> st.copy(commitView = st.commitView?.copy(selected = null, diff = null, diffLoading = false)) }

    fun closeCommit() = _state.update { it.copy(commitView = null) }

    // ---- merge / close / reopen ----

    fun openMergeDialog() = _state.update { it.copy(mergeDialog = true, mergeMethod = PullMergeMethod.Merge, deleteBranch = false) }
    fun openCloseDialog() = _state.update { it.copy(closeDialog = true) }
    fun dismissDialogs() = _state.update { it.copy(mergeDialog = false, closeDialog = false) }
    fun setMethod(m: PullMergeMethod) = _state.update { it.copy(mergeMethod = m) }
    fun setDeleteBranch(v: Boolean) = _state.update { it.copy(deleteBranch = v) }

    fun confirmMerge() {
        val s = _state.value
        val pull = s.pull ?: return
        if (s.acting) return
        _state.update { it.copy(acting = true, mergeDialog = false) }
        viewModelScope.launch {
            try {
                val repo = holder.get() ?: return@launch
                repo.mergePull(number, s.mergeMethod)
                var branchLeft = false
                if (s.deleteBranch && pull.canDeleteBranch) {
                    try {
                        repo.deleteBranch(pull.headRef)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: GitHubError) {
                        branchLeft = true
                    }
                }
                _events.tryEmit(GhEvent.Snack(GhText.Res(if (branchLeft) R.string.pr_merged_branch_kept else R.string.pr_merged_ok)))
                _state.update { it.copy(acting = false) }
                load(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update { it.copy(acting = false) }
                _events.tryEmit(GhEvent.Snack(describeFor(holder.ref?.fullName, e)))
            }
        }
    }

    fun confirmClose() = changeState(close = true)
    fun reopen() = changeState(close = false)

    private fun changeState(close: Boolean) {
        if (_state.value.acting) return
        _state.update { it.copy(acting = true, closeDialog = false) }
        viewModelScope.launch {
            try {
                val repo = holder.get() ?: return@launch
                if (close) repo.closePull(number) else repo.reopenPull(number)
                _events.tryEmit(GhEvent.Snack(GhText.Res(if (close) R.string.pr_closed_ok else R.string.pr_reopened_ok)))
                _state.update { it.copy(acting = false) }
                load(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update { it.copy(acting = false) }
                _events.tryEmit(GhEvent.Snack(describeFor(holder.ref?.fullName, e)))
            }
        }
    }

    companion object {
        fun factory(projectPath: String, number: Int) = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as KodexApp).container
                PullDetailViewModel(projectPath, number, c.gitHubProjectResolver, c::gitHubRepository)
            }
        }
    }
}

// ---- create --------------------------------------------------------------------------------

enum class BranchPickerTarget { Repo, Base, Head }

/** Compare = choose repo + branches; Details = title, description and the file changes. */
enum class CreateStep { Compare, Details }

data class CreatePullState(
    val loading: Boolean = true,
    val error: GhText? = null,
    val repoName: String = "",
    /** Branches of this (possibly forked) repository: always the compare/head side. */
    val branches: List<String> = emptyList(),
    /** Upstream (parent) repo `owner/name` when this repo is a fork with readable branches; else null. */
    val upstreamName: String? = null,
    val upstreamBranches: List<String> = emptyList(),
    /** True = the pull request is opened against [upstreamName] instead of this repo. */
    val useUpstream: Boolean = false,
    val ownDefaultBase: String = "",
    val upstreamDefaultBase: String = "",
    val base: String = "",
    val head: String = "",
    val title: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val autoFilling: Boolean = false,
    val creating: Boolean = false,
    val picker: BranchPickerTarget? = null,
    /** Inline validation / API message shown above the button. */
    val formError: GhText? = null,
    val step: CreateStep = CreateStep.Compare,
    /** Commits + files between the chosen base and compare branch; null until both are chosen. */
    val cmp: GhCompare? = null,
    val cmpLoading: Boolean = false,
    val cmpError: GhText? = null,
    val ownPullsEnabled: Boolean = true,
    val upstreamPullsEnabled: Boolean = true,
) {
    val basePullsEnabled: Boolean get() = if (useUpstream) upstreamPullsEnabled else ownPullsEnabled
    val sameBranch: Boolean get() = !useUpstream && head.isNotEmpty() && head == base

    /** The Compare button may continue: both branches chosen, they differ, and there is something to open a PR for. */
    val canCompare: Boolean get() = head.isNotEmpty() && base.isNotEmpty() && !sameBranch && basePullsEnabled &&
        !cmpLoading && cmp != null && !cmp.isEmpty

    val baseBranches: List<String> get() = if (useUpstream) upstreamBranches else branches
    val baseRepoName: String get() = if (useUpstream && upstreamName != null) upstreamName else repoName
}

class CreatePullViewModel(
    private val projectPath: String,
    resolver: GitHubProjectResolver,
    private val repoFactory: (GhRepoRef) -> GitHubRepository,
) : ViewModel() {

    private val holder = RepoHolder(projectPath, resolver, repoFactory)
    private val _state = MutableStateFlow(CreatePullState())
    val state: StateFlow<CreatePullState> = _state.asStateFlow()

    /** Emits the number of the pull request that was just created. */
    private val _created = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val created: SharedFlow<Int> = _created.asSharedFlow()

    /** Emits the web URL of a pull request opened in the upstream repo (the app can't browse it as a fork PR). */
    private val _createdUpstream = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val createdUpstream: SharedFlow<String> = _createdUpstream.asSharedFlow()

    private var upstreamRef: GhRepoRef? = null
    private var titleEdited = false
    private var fillJob: Job? = null

    init { load() }

    fun retry() = load()

    private fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val repo = holder.get() ?: run { _state.update { it.copy(loading = false, error = GhText.Res(R.string.gh_err_not_ready)) }; return@launch }
                val info = repo.repoInfo()
                val branches = repo.allBranches()
                val base = info.defaultBranch?.takeIf { it in branches } ?: branches.firstOrNull().orEmpty()
                val parent = info.parent
                val upstreamBranches = parent?.let {
                    try { repoFactory(it).allBranches() } catch (e: GitHubError) { null } // best effort: fork-only mode
                }.orEmpty()
                val upstreamPulls = parent?.let {
                    try { repoFactory(it).repoInfo().pullsEnabled } catch (e: GitHubError) { true } // unknown = let GitHub decide
                } ?: true
                upstreamRef = parent?.takeIf { upstreamBranches.isNotEmpty() }
                val upstreamBase = parent?.defaultBranch?.takeIf { it in upstreamBranches } ?: upstreamBranches.firstOrNull().orEmpty()
                // Default compare branch: the branch checked out in the project, when GitHub has it.
                val head = localBranch()?.takeIf { it in branches && it != base }.orEmpty()
                _state.update {
                    it.copy(loading = false, repoName = holder.ref?.fullName.orEmpty(), branches = branches, base = base, head = head,
                        upstreamName = upstreamRef?.fullName, upstreamBranches = upstreamBranches,
                        ownDefaultBase = base, upstreamDefaultBase = upstreamBase,
                        ownPullsEnabled = info.pullsEnabled, upstreamPullsEnabled = upstreamPulls,
                        // Own PRs are off but the fork's upstream accepts them: start on the upstream.
                        useUpstream = !info.pullsEnabled && upstreamRef != null && upstreamPulls,
                    ).let { st -> if (st.useUpstream) st.copy(base = upstreamBase) else st }
                }
                if (head.isNotEmpty()) autoFillTitle(head)
                runCompare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update { it.copy(loading = false, error = describeFor(holder.ref?.fullName, e)) }
            }
        }
    }

    /** Branch name from `.git/HEAD` (null when detached or unreadable). */
    private fun localBranch(): String? = runCatching {
        File(projectPath, ".git/HEAD").readText().trim().removePrefix("ref: refs/heads/").takeIf { !it.startsWith("ref:") && it.isNotEmpty() && it.length < 200 && !it.matches(Regex("[0-9a-f]{40}")) }
    }.getOrNull()

    fun openPicker(target: BranchPickerTarget) = _state.update { it.copy(picker = target) }
    fun dismissPicker() = _state.update { it.copy(picker = null) }

    fun pickBranch(name: String) {
        val target = _state.value.picker ?: return
        _state.update {
            when (target) {
                BranchPickerTarget.Repo -> {
                    val up = name == it.upstreamName
                    if (up == it.useUpstream) it.copy(picker = null)
                    else it.copy(
                        useUpstream = up, base = if (up) it.upstreamDefaultBase else it.ownDefaultBase,
                        picker = null, formError = null,
                    )
                }
                BranchPickerTarget.Base -> it.copy(base = name, picker = null, formError = null)
                BranchPickerTarget.Head -> it.copy(head = name, picker = null, formError = null)
            }
        }
        if (target == BranchPickerTarget.Head && !titleEdited) autoFillTitle(name)
        runCompare()
    }

    private var cmpJob: Job? = null

    /** Loads what a pull request between the chosen branches would contain (same call the AI uses). */
    private fun runCompare() {
        cmpJob?.cancel()
        val s = _state.value
        if (s.head.isEmpty() || s.base.isEmpty() || s.sameBranch) {
            _state.update { it.copy(cmp = null, cmpLoading = false, cmpError = null) }
            return
        }
        _state.update { it.copy(cmp = null, cmpLoading = true, cmpError = null) }
        cmpJob = viewModelScope.launch {
            try {
                val own = holder.get() ?: return@launch
                val upstream = if (s.useUpstream) upstreamRef else null
                val result = if (upstream != null) {
                    repoFactory(upstream).compare(s.base, "${holder.ref?.owner.orEmpty()}:${s.head}")
                } else own.compare(s.base, s.head)
                _state.update { it.copy(cmp = result, cmpLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update { it.copy(cmpLoading = false, cmpError = describeFor(s.baseRepoName, e)) }
            }
        }
    }

    fun goDetails() {
        if (_state.value.canCompare) _state.update { it.copy(step = CreateStep.Details, formError = null) }
    }

    fun backToCompare() = _state.update { it.copy(step = CreateStep.Compare, formError = null) }

    /** After a pull request was created: back to an empty compare panel. */
    fun reset() {
        titleEdited = false
        fillJob?.cancel()
        cmpJob?.cancel()
        _state.update {
            it.copy(step = CreateStep.Compare, title = "", body = "", draft = false, creating = false, formError = null,
                head = "", cmp = null, cmpLoading = false, cmpError = null, autoFilling = false)
        }
    }

    /** AI result: replaces title + description and keeps the commit-message auto-fill from overriding it. */
    fun applyAiText(title: String, body: String) {
        titleEdited = true
        fillJob?.cancel()
        _state.update { it.copy(title = title, body = body, autoFilling = false, formError = null) }
    }

    /** Commits + diff the new pull request would contain, as AI prompt text (paths in [excludeGlobs] are left out). */
    suspend fun aiChanges(excludeGlobs: String): String {
        val s = _state.value
        val own = holder.get() ?: throw IllegalStateException("GitHub is not ready")
        val upstream = if (s.useUpstream) upstreamRef else null
        val cmp = if (upstream != null) {
            repoFactory(upstream).compare(s.base, "${holder.ref?.owner.orEmpty()}:${s.head}")
        } else {
            own.compare(s.base, s.head)
        }
        if (cmp.isEmpty) return ""
        return cmp.toPromptText(skip = { com.invictus.kodex.ai.Redactor.isExcluded(it, excludeGlobs) })
    }

    fun setTitle(v: String) { titleEdited = true; _state.update { it.copy(title = v, formError = null) } }
    fun setBody(v: String) = _state.update { it.copy(body = v) }
    fun setDraft(v: Boolean) = _state.update { it.copy(draft = v) }

    /** Title from the newest commit on [head] (first line), unless the user already typed one. */
    private fun autoFillTitle(head: String) {
        fillJob?.cancel()
        _state.update { it.copy(autoFilling = true) }
        fillJob = viewModelScope.launch {
            try {
                val msg = holder.get()?.tipCommitMessage(head).orEmpty()
                val first = msg.lineSequence().firstOrNull().orEmpty().trim()
                _state.update { if (!titleEdited && first.isNotEmpty()) it.copy(title = first, autoFilling = false) else it.copy(autoFilling = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update { it.copy(autoFilling = false) } // best effort: the user can type the title
            }
        }
    }

    fun create() {
        val s = _state.value
        if (s.creating) return
        val problem = when {
            !s.basePullsEnabled -> GhText.Res(R.string.pr_base_disabled, listOf(s.baseRepoName))
            s.head.isEmpty() -> GhText.Res(R.string.pr_err_no_head)
            !s.useUpstream && s.head == s.base -> GhText.Res(R.string.pr_err_same_branch)
            s.title.isBlank() -> GhText.Res(R.string.pr_err_no_title)
            else -> null
        }
        if (problem != null) { _state.update { it.copy(formError = problem) }; return }
        _state.update { it.copy(creating = true, formError = null) }
        viewModelScope.launch {
            try {
                val repo = holder.get() ?: return@launch
                val upstream = if (s.useUpstream) upstreamRef else null
                if (upstream != null) {
                    // Cross-repo PR: head is "forkOwner:branch", the PR lives in the upstream repo.
                    val headRef = "${holder.ref?.owner.orEmpty()}:${s.head}"
                    val pull = repoFactory(upstream).createPull(s.title.trim(), s.body.trim(), headRef, s.base, s.draft)
                    _state.update { it.copy(creating = false) }
                    _createdUpstream.tryEmit(pull.htmlUrl)
                } else {
                    val pull = repo.createPull(s.title.trim(), s.body.trim(), s.head, s.base, s.draft)
                    _state.update { it.copy(creating = false) }
                    _created.tryEmit(pull.number)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update { it.copy(creating = false, formError = describeFor(holder.ref?.fullName, e)) }
            }
        }
    }

    companion object {
        fun factory(projectPath: String) = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as KodexApp).container
                CreatePullViewModel(projectPath, c.gitHubProjectResolver, c::gitHubRepository)
            }
        }
    }
}
