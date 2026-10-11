package com.invictus.kodex.github.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.R
import com.invictus.kodex.KodexApp
import com.invictus.kodex.core.github.GitHubProfileRepository
import com.invictus.kodex.github.api.GhRepoRef
import com.invictus.kodex.github.api.GhRun
import com.invictus.kodex.github.api.GitHubError
import com.invictus.kodex.github.auth.GitHubTokenProvider
import com.invictus.kodex.github.data.GitHubProjectResolver
import com.invictus.kodex.github.data.GitHubRepository
import com.invictus.kodex.github.data.GitHubResolution
import com.invictus.kodex.github.data.RunStatusFilter
import com.invictus.kodex.github.notify.ArmBuildWatch
import com.invictus.kodex.github.notify.RunCompletionWatch
import com.invictus.kodex.github.prefs.GitHubPrefs
import kotlinx.coroutines.CancellationException
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
import java.io.File

/** State holder for the per-project GitHub Manager (plan section 3 / 6). */
class GitHubManagerViewModel(
    val projectPath: String,
    private val resolver: GitHubProjectResolver,
    private val repoFactory: (GhRepoRef) -> GitHubRepository,
    private val prefs: GitHubPrefs,
    private val tokens: GitHubTokenProvider,
    private val profiles: GitHubProfileRepository,
    private val appContext: Context,
    /** The "all runs" screen: only runs + workflows, none of the APK / artifact / profile loading. */
    private val lite: Boolean = false,
) : ViewModel() {

    private val _state = MutableStateFlow(GitHubManagerState())
    val state: StateFlow<GitHubManagerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GhEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<GhEvent> = _events.asSharedFlow()

    private var repository: GitHubRepository? = null

    /** M16: Run Workflow, APK, artifacts, deployments, cleanup. */
    val tools = GitHubToolsController(
        scope = viewModelScope,
        appContext = appContext.applicationContext,
        projectPath = projectPath,
        prefs = prefs,
        repoProvider = { repository },
        repoRefProvider = { _state.value.repo },
        workflowsProvider = { _state.value.workflows },
        emit = { _events.tryEmit(it) },
        onError = { applyBanner(it) },
        onRunsChanged = { loadRuns(reset = true) },
    )

    private var runsJob: Job? = null
    private var page = 1
    private var resumed = false

    init {
        if (!lite) ArmBuildWatch.schedule(appContext)
        viewModelScope.launch {
            prefs.sectionExpanded(projectPath, SECTION_RUNS).collect { v -> _state.update { it.copy(runsExpanded = v) } }
        }
        load()
    }

    /** Re-detect on every return to the screen (Remotes / Settings may have changed the answer). */
    fun onResume() {
        if (!lite) tools.onResume()
        if (!resumed) { resumed = true; return }
        if (_state.value.gate != GitHubGate.Resolving) load()
    }

    fun load() {
        viewModelScope.launch {
            val resolution = resolver.resolve(projectPath)
            when (resolution) {
                GitHubResolution.NotGit -> setGate(GitHubGate.NotGit)
                GitHubResolution.NoGitHubRemote -> setGate(GitHubGate.NoGitHubRemote)
                GitHubResolution.NoToken -> setGate(GitHubGate.NoToken)
                is GitHubResolution.Ready -> {
                    val changed = _state.value.repo?.fullName != resolution.repo.fullName
                    // Returning from Remotes/Settings with nothing changed: keep what is on screen.
                    if (!changed && repository != null && _state.value.gate == GitHubGate.Ready) return@launch
                    if (changed || repository == null) repository = repoFactory(resolution.repo)
                    // Filters live only on the "all runs" screen and are not remembered between visits.
                    val filters = if (changed || _state.value.gate != GitHubGate.Ready) GitHubPrefs.RunFilters()
                    else _state.value.filters
                    _state.update {
                        it.copy(
                            gate = GitHubGate.Ready,
                            repo = if (changed) resolution.repo else it.repo ?: resolution.repo,
                            filters = filters,
                            banner = null,
                        )
                    }
                    if (!lite) loadProfile()
                    loadOverview()
                    loadRuns(reset = true)
                    if (!lite) tools.onRepoReady(changed)
                }
            }
        }
    }

    private fun setGate(gate: GitHubGate) {
        repository = null
        tools.onGateLost()
        _state.update { GitHubManagerState(gate = gate, runsExpanded = it.runsExpanded) }
    }

    private fun loadProfile() {
        val cached = profiles.cached(GitHubTokenProvider.HOST)
        _state.update { it.copy(login = cached?.login, avatarUrl = cached?.avatarUrl) }
        if (cached == null || profiles.isStale(cached)) {
            val token = tokens.token() ?: return
            viewModelScope.launch {
                profiles.fetch(GitHubTokenProvider.HOST, token)?.let { p ->
                    _state.update { it.copy(login = p.login, avatarUrl = p.avatarUrl) }
                }
            }
        }
    }

    private fun loadOverview() {
        val repo = repository ?: return
        viewModelScope.launch {
            try {
                val info = repo.repoInfo()
                val wf = repo.workflows()
                _state.update { it.copy(repo = info, workflows = wf, rateLimit = repo.rateLimit) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                applyBanner(e)
            }
        }
    }

    /** Pull-to-refresh. */
    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        loadOverview()
        loadRuns(reset = true)
        if (!lite) tools.refresh()
    }

    fun loadMore() {
        val s = _state.value
        if (s.hasMore && !s.loadingMore && !s.runsLoading) loadRuns(reset = false)
    }

    fun retryRuns() = loadRuns(reset = true)

    private fun loadRuns(reset: Boolean) {
        val repo = repository ?: return
        runsJob?.cancel()
        if (reset) page = 1
        val filters = _state.value.filters
        _state.update {
            if (reset) it.copy(runsLoading = it.runs.isEmpty(), runsError = null)
            else it.copy(loadingMore = true)
        }
        runsJob = viewModelScope.launch {
            try {
                val result = repo.runs(page, filters.status, filters.workflowId)
                page = result.page + 1
                // Announce the moment any queued / running build finishes (also when the app is in the background).
                _state.value.repo?.let { ref -> result.items.filter { it.isActive }.forEach { RunCompletionWatch.watch(appContext, ref, it) } }
                _state.update {
                    it.copy(
                        runs = if (reset) result.items else (it.runs + result.items).distinctBy { r -> r.id },
                        hasMore = result.hasMore,
                        runsLoading = false, loadingMore = false, refreshing = false, runsError = null,
                        rateLimit = repo.rateLimit,
                        banner = if (it.banner is GhBanner.Offline) null else it.banner,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                applyBanner(e)
                _state.update {
                    it.copy(
                        runsLoading = false, loadingMore = false, refreshing = false,
                        runsError = if (e is GitHubError.Offline && it.runs.isNotEmpty()) null else describe(e),
                    )
                }
            }
        }
    }

    fun setStatusFilter(status: RunStatusFilter) = updateFilters(_state.value.filters.copy(status = status))

    fun setWorkflowFilter(workflowId: Long?) = updateFilters(_state.value.filters.copy(workflowId = workflowId))

    private fun updateFilters(f: GitHubPrefs.RunFilters) {
        if (f == _state.value.filters) return
        _state.update { it.copy(filters = f, runs = emptyList(), hasMore = false) }
        loadRuns(reset = true)
    }

    fun setRunsExpanded(expanded: Boolean) {
        viewModelScope.launch { prefs.setSectionExpanded(projectPath, SECTION_RUNS, expanded) }
    }

    // ---- run actions (plan 6.4) -------------------------------------------------

    fun cancelRun(run: GhRun) = action(run, success = R.string.gh_cancel_requested) {
        if (!run.canCancel) return@action
        it.cancel(run.id)
    }

    fun rerun(run: GhRun, failedOnly: Boolean) = action(run, success = R.string.gh_rerun_started) {
        if (!run.canRerun) return@action
        if (failedOnly) it.rerunFailed(run.id) else it.rerunAll(run.id)
    }

    /** Optimistic: the row disappears at once and comes back (same position) if GitHub refuses. */
    fun deleteRun(run: GhRun) {
        val repo = repository ?: return
        if (!run.canDelete) return
        val index = _state.value.runs.indexOfFirst { it.id == run.id }.takeIf { it >= 0 } ?: return
        _state.update { it.copy(runs = it.runs.filterNot { r -> r.id == run.id }) }
        viewModelScope.launch {
            try {
                repo.delete(run.id)
                _events.tryEmit(GhEvent.Snack(GhText.Res(R.string.gh_run_deleted, listOf(run.runNumber))))
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update {
                    val list = it.runs.toMutableList()
                    list.add(index.coerceAtMost(list.size), run)
                    it.copy(runs = list)
                }
                applyBanner(e)
                _events.tryEmit(GhEvent.Snack(describe(e)))
            }
        }
    }

    private fun action(run: GhRun, success: Int, block: suspend (GitHubRepository) -> Unit) {
        val repo = repository ?: return
        if (run.id in _state.value.busyRunIds) return
        _state.update { it.copy(busyRunIds = it.busyRunIds + run.id) }
        viewModelScope.launch {
            try {
                block(repo)
                _events.tryEmit(GhEvent.Snack(GhText.Res(success)))
                delay(REFRESH_AFTER_MS) // the API needs a moment to reflect the new state
                loadRuns(reset = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                applyBanner(e)
                _events.tryEmit(GhEvent.Snack(describe(e)))
            } finally {
                _state.update { it.copy(busyRunIds = it.busyRunIds - run.id) }
            }
        }
    }

    /** Long-press on a failed run: copy only the failure excerpt (plan 6.4). */
    fun copyError(run: GhRun) {
        val repo = repository ?: return
        if (run.id in _state.value.busyRunIds) return
        _state.update { it.copy(busyRunIds = it.busyRunIds + run.id) }
        viewModelScope.launch {
            var file: File? = null
            try {
                val log = repo.loadRunLog(run, _state.value.workflowName(run))
                file = log.file
                _events.tryEmit(GhEvent.Copy(COPY_LABEL, log.excerpt, GhText.Res(R.string.gh_error_copied)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                applyBanner(e)
                _events.tryEmit(GhEvent.Snack(describe(e)))
            } finally {
                file?.delete()
                _state.update { it.copy(busyRunIds = it.busyRunIds - run.id) }
            }
        }
    }

    private fun applyBanner(e: GitHubError) {
        val banner = when (e) {
            is GitHubError.Unauthorized -> GhBanner.TokenInvalid
            is GitHubError.Offline -> GhBanner.Offline
            is GitHubError.RateLimited -> GhBanner.RateLimited(e.resetEpochSec)
            is GitHubError.MissingPermission -> GhBanner.MissingPermission(e.message.orEmpty())
            else -> return
        }
        _state.update { it.copy(banner = banner, rateLimit = repository?.rateLimit ?: it.rateLimit) }
    }

    override fun onCleared() {
        runsJob?.cancel()
        tools.onCleared()
    }

    companion object {
        private const val SECTION_RUNS = "runs"
        private const val REFRESH_AFTER_MS = 1_500L
        const val COPY_LABEL = "GitHub run error"

        fun factory(projectPath: String, lite: Boolean = false) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                val c = app.container
                GitHubManagerViewModel(
                    projectPath = projectPath,
                    resolver = c.gitHubProjectResolver,
                    repoFactory = c::gitHubRepository,
                    prefs = c.gitHubPrefs,
                    tokens = c.gitHubTokenProvider,
                    profiles = c.gitHubProfileRepository,
                    appContext = app.applicationContext,
                    lite = lite,
                )
            }
        }
    }
}

/** User-facing text for a failure (plan section 11). */
fun describe(e: GitHubError): GhText = when (e) {
    is GitHubError.Unauthorized -> GhText.Res(R.string.gh_err_unauthorized)
    is GitHubError.MissingPermission -> GhText.Res(R.string.gh_err_permission)
    is GitHubError.RateLimited -> GhText.Res(R.string.gh_err_rate_limited)
    is GitHubError.NotFound -> GhText.Res(R.string.gh_err_not_found)
    is GitHubError.Gone -> GhText.Res(R.string.gh_err_gone)
    is GitHubError.Offline -> GhText.Res(R.string.gh_err_offline)
    is GitHubError.BadDownload -> GhText.Res(
        if (e.reason == GitHubError.BadDownload.Reason.TooLarge) R.string.gh_download_too_large else R.string.gh_download_incomplete,
    )
    is GitHubError.Conflict, is GitHubError.Validation -> GhText.Raw(e.message.orEmpty())
    is GitHubError.Http -> GhText.Res(R.string.gh_err_http, listOf(e.code))
}
