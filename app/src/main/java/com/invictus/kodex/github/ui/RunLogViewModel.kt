package com.invictus.kodex.github.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.R
import com.invictus.kodex.KodexApp
import com.invictus.kodex.github.api.GhJob
import com.invictus.kodex.github.api.GhRepoRef
import com.invictus.kodex.github.api.GhRun
import com.invictus.kodex.github.api.GitHubError
import com.invictus.kodex.github.data.GitHubProjectResolver
import com.invictus.kodex.github.data.GitHubRepository
import com.invictus.kodex.github.data.GitHubResolution
import com.invictus.kodex.github.data.LogFiles
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

enum class LogMode { Error, Full }

enum class RunTab { Steps, Log }

data class RunLogState(
    val loading: Boolean = true,
    val error: GhText? = null,
    val repoName: String = "",
    val run: GhRun? = null,
    val workflowName: String = "",
    val mode: LogMode = LogMode.Error,
    /** Only filled when more than one job failed (plan 6.3). */
    val failedJobs: List<GhJob> = emptyList(),
    val selectedJobId: Long? = null,
    val excerptLines: List<String> = emptyList(),
    val fullLines: List<String>? = null,
    val fullLoading: Boolean = false,
    /** The visible full log starts mid-file (only the last 5 MB is shown). */
    val fullCutAtStart: Boolean = false,
    val hasLogFile: Boolean = false,
    /** Every job of the run with its steps (refreshed live while the run is active). */
    val jobs: List<GhJob> = emptyList(),
    val progress: RunProgress = RunProgress.Empty,
    val tab: RunTab = RunTab.Steps,
)

/** Run log screen (plan 6.3): error excerpt + full log, both copyable. */
class RunLogViewModel(
    private val projectPath: String,
    private val runId: Long,
    private val resolver: GitHubProjectResolver,
    private val repoFactory: (GhRepoRef) -> GitHubRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RunLogState())
    val state: StateFlow<RunLogState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GhEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<GhEvent> = _events.asSharedFlow()

    private var repository: GitHubRepository? = null
    private var logFile: File? = null
    private var excerptText: String = ""
    private var pollJob: Job? = null

    init { load(jobId = null) }

    fun retry() = load(_state.value.selectedJobId)

    fun selectJob(jobId: Long) {
        if (jobId == _state.value.selectedJobId) return
        _state.update { it.copy(fullLines = null) }
        load(jobId)
    }

    private fun load(jobId: Long?) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val repo = repository ?: when (val r = resolver.resolve(projectPath)) {
                    is GitHubResolution.Ready -> repoFactory(r.repo).also { repository = it; _state.update { s -> s.copy(repoName = r.repo.fullName) } }
                    else -> { fail(GhText.Res(R.string.gh_err_not_ready)); return@launch }
                }
                val run = repo.run(runId)
                val workflowName = _state.value.workflowName.ifEmpty {
                    runCatching { repo.workflows().firstOrNull { it.id == run.workflowId }?.name }.getOrNull() ?: run.name
                }
                if (run.isActive) {
                    // GitHub only serves a job's log once it has finished: show live steps and wait.
                    val jobs = repo.jobs(run.id)
                    _state.update {
                        it.copy(
                            loading = false, error = null, run = run, workflowName = workflowName,
                            jobs = jobs, progress = RunProgress.of(jobs),
                            excerptLines = emptyList(), hasLogFile = false, fullLines = null,
                        )
                    }
                    startPolling(repo)
                    return@launch
                }
                val log = repo.loadRunLog(run, workflowName, jobId)
                logFile?.delete()
                logFile = log.file
                excerptText = log.excerpt
                val failed = log.jobs.filter { it.isFailed }
                _state.update {
                    it.copy(
                        loading = false, error = null, run = run, workflowName = workflowName,
                        jobs = log.jobs, progress = RunProgress.of(log.jobs),
                        failedJobs = if (failed.size > 1) failed else emptyList(),
                        selectedJobId = log.job?.id,
                        excerptLines = log.excerpt.lines(),
                        hasLogFile = log.file != null,
                        fullLines = null, fullCutAtStart = log.truncatedOnDisk,
                    )
                }
                if (_state.value.mode == LogMode.Full) loadFull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                fail(
                    when (e) {
                        is GitHubError.NotFound, is GitHubError.Gone -> GhText.Res(R.string.gh_log_unavailable)
                        else -> describe(e)
                    },
                )
            }
        }
    }

    /** Refreshes status and steps every few seconds while the run is queued / running; loads the log when it ends. */
    private fun startPolling(repo: GitHubRepository) {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                delay(POLL_MS)
                try {
                    val run = repo.run(runId)
                    val jobs = repo.jobs(runId)
                    _state.update { it.copy(run = run, jobs = jobs, progress = RunProgress.of(jobs)) }
                    if (run.isCompleted) {
                        pollJob = null
                        load(_state.value.selectedJobId)
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GitHubError) {
                    // Offline / rate limited for a moment: keep trying.
                }
            }
        }
    }

    fun setTab(tab: RunTab) = _state.update { it.copy(tab = tab) }

    fun cancelRun() {
        val repo = repository ?: return
        viewModelScope.launch {
            try {
                repo.cancel(runId)
                _events.tryEmit(GhEvent.Snack(GhText.Res(R.string.gh_cancel_requested)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _events.tryEmit(GhEvent.Snack(describe(e)))
            }
        }
    }

    private fun fail(text: GhText) = _state.update { it.copy(loading = false, error = text) }

    fun setMode(mode: LogMode) {
        _state.update { it.copy(mode = mode) }
        if (mode == LogMode.Full) loadFull()
    }

    private fun loadFull() {
        val file = logFile ?: return
        val s = _state.value
        if (s.fullLines != null || s.fullLoading) return
        _state.update { it.copy(fullLoading = true) }
        viewModelScope.launch {
            val tail = withContext(Dispatchers.IO) { LogFiles.readTail(file) }
            _state.update { it.copy(fullLoading = false, fullLines = tail.lines, fullCutAtStart = it.fullCutAtStart || tail.cutAtStart) }
        }
    }

    fun copyError() {
        if (excerptText.isEmpty()) return
        _events.tryEmit(GhEvent.Copy(GitHubManagerViewModel.COPY_LABEL, excerptText, GhText.Res(R.string.gh_error_copied)))
    }

    fun copyFull() {
        val file = logFile
        if (file == null) { _events.tryEmit(GhEvent.Snack(GhText.Res(R.string.gh_log_unavailable))); return }
        viewModelScope.launch {
            val (text, cut) = withContext(Dispatchers.IO) { LogFiles.clipboardText(file) }
            _events.tryEmit(
                GhEvent.Copy(
                    "GitHub run log", text,
                    GhText.Res(if (cut) R.string.gh_log_copied_partial else R.string.gh_log_copied),
                ),
            )
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        logFile?.delete() // temp file lives only while the screen is open (plan 6.3)
    }

    companion object {
        private const val POLL_MS = 5_000L

        fun factory(projectPath: String, runId: Long) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                val c = app.container
                RunLogViewModel(projectPath, runId, c.gitHubProjectResolver, c::gitHubRepository)
            }
        }
    }
}
