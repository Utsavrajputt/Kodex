package com.invictus.kodex.github.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.invictus.kodex.R
import com.invictus.kodex.github.api.GhArtifact
import com.invictus.kodex.github.api.GhDeployment
import com.invictus.kodex.github.api.GhRepoRef
import com.invictus.kodex.github.api.GhRun
import com.invictus.kodex.github.api.GhWorkflow
import com.invictus.kodex.github.api.GitHubError
import com.invictus.kodex.github.data.ApkCandidate
import com.invictus.kodex.github.data.ApkResolution
import com.invictus.kodex.github.data.ApkResolver
import com.invictus.kodex.github.data.CleanupFilters
import com.invictus.kodex.github.data.CleanupItem
import com.invictus.kodex.github.data.CleanupKind
import com.invictus.kodex.github.data.CleanupPlanner
import com.invictus.kodex.github.data.CleanupResult
import com.invictus.kodex.github.data.CleanupRunner
import com.invictus.kodex.github.data.DispatchInfo
import com.invictus.kodex.github.data.GitHubRepository
import com.invictus.kodex.github.data.WorkflowInputParser
import com.invictus.kodex.github.install.ApkDownloader
import com.invictus.kodex.github.notify.ArmBuildWatch
import com.invictus.kodex.github.notify.GhBackground
import com.invictus.kodex.github.notify.GhNotifier
import com.invictus.kodex.github.install.ApkInspector
import com.invictus.kodex.github.install.ApkInstaller
import com.invictus.kodex.github.install.ArtifactZip
import com.invictus.kodex.github.install.ArtifactZipException
import com.invictus.kodex.github.install.InstallDecision
import com.invictus.kodex.github.install.InstallEvents
import com.invictus.kodex.github.install.InstallResult
import com.invictus.kodex.github.prefs.GitHubPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * M16 logic of the GitHub Manager (plan 8.2-8.6): Run Workflow, APK card, artifacts,
 * deployments and cleanup. Owned by [GitHubManagerViewModel], which supplies the repository and
 * the hooks; keeping it separate keeps the M15 view model small. All state is in [state].
 */
class GitHubToolsController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val projectPath: String,
    private val prefs: GitHubPrefs,
    private val repoProvider: () -> GitHubRepository?,
    private val repoRefProvider: () -> GhRepoRef?,
    private val workflowsProvider: () -> List<GhWorkflow>,
    private val emit: (GhEvent) -> Unit,
    private val onError: (GitHubError) -> Unit,
    private val onRunsChanged: () -> Unit,
    private val clock: () -> Instant = { Instant.now() },
    /** Long jobs (download, cleanup, dispatch) run here so they outlive the screen. */
    private val jobScope: CoroutineScope = GhBackground.scope,
) {
    private val _state = MutableStateFlow(GitHubToolsState())
    val state: StateFlow<GitHubToolsState> = _state.asStateFlow()

    private val downloader = ApkDownloader(appContext.filesDir)
    private val notifier = GhNotifier(appContext)
    private val inspector = ApkInspector(appContext)
    private val installer = ApkInstaller(appContext)

    private val dispatchCache = HashMap<String, DispatchInfo>()
    private var activeInfoKey: String? = null
    private var branchPage = 1
    private var artifactsPage = 1

    private var infoJob: Job? = null
    private var pollJob: Job? = null
    private var artifactsJob: Job? = null
    private var deploymentsJob: Job? = null
    private var transferJob: Job? = null
    private var scanJob: Job? = null
    private var cleanupJob: Job? = null

    init {
        scope.launch {
            prefs.sectionExpanded(projectPath, SECTION_ARTIFACTS).collect { v -> _state.update { it.copy(artifactsExpanded = v) } }
        }
        scope.launch {
            prefs.sectionExpanded(projectPath, SECTION_DEPLOYMENTS).collect { v -> _state.update { it.copy(deploymentsExpanded = v) } }
        }
        scope.launch {
            InstallEvents.results.collect { onInstallResult(it) }
        }
        // Retention (last 3 APKs + 24 h) and stale temp files; then show what is kept.
        scope.launch(Dispatchers.IO) { downloader.cleanOld(); refreshDownloadedNow() }
    }

    /** Runs [block] in the process-wide scope with the foreground service held, so it survives leaving the screen. */
    private fun launchTracked(tracked: Boolean = true, block: suspend CoroutineScope.() -> Unit): Job = jobScope.launch {
        if (tracked) GhBackground.begin(appContext)
        try { block() } finally { if (tracked) GhBackground.end(appContext) }
    }

    private fun str(res: Int, vararg args: Any): String = appContext.getString(res, *args)

    // ---- downloaded APKs (kept on disk) --------------------------------------------------

    private fun refreshDownloadedNow() {
        val list = downloader.listCached().map { DownloadedApk(it.artifactId, it.label, it.file, it.sizeBytes, it.modifiedAt) }
        _state.update { it.copy(downloaded = list) }
    }

    fun refreshDownloaded() { scope.launch(Dispatchers.IO) { refreshDownloadedNow() } }

    fun installDownloaded(d: DownloadedApk) {
        val s = _state.value
        if (s.transfer != null || s.installing || s.pendingSave != null) { emit(GhEvent.Snack(GhText.Res(R.string.gh_downloaded_install_busy))); return }
        scope.launch {
            val inspected = withContext(Dispatchers.IO) { inspector.inspect(d.file) }
            if (inspected == null) {
                emit(GhEvent.Snack(GhText.Res(R.string.gh_apk_invalid)))
                return@launch
            }
            if (InstallDecision.isBlocked(inspected.kind)) {
                emit(GhEvent.Snack(GhText.Res(R.string.gh_ready_downgrade)))
                return@launch
            }
            // Straight to the system installer: no details dialog. The system still asks for its own confirmation.
            _state.update { it.copy(ready = ReadyApk(d.artifactId, d.label, d.file, inspected, fromApkCard = false, silent = true)) }
            if (!installer.canRequestInstalls()) _state.update { it.copy(installDialog = InstallDialog.UnknownSources) }
            else startInstall()
        }
    }

    fun saveDownloaded(d: DownloadedApk) {
        val s = _state.value
        if (s.transfer != null || s.installing || s.pendingSave != null) { emit(GhEvent.Snack(GhText.Res(R.string.gh_downloaded_install_busy))); return }
        _state.update { it.copy(pendingSave = PendingSave(d.file, "${safeFileName(d.label)}.apk", MIME_APK, deleteAfter = false)) }
    }

    fun deleteDownloaded(d: DownloadedApk) {
        if (_state.value.installing) return
        scope.launch(Dispatchers.IO) {
            downloader.delete(d.file)
            _state.update { st -> if (st.ready?.file == d.file) st.copy(ready = null) else st }
            refreshDownloadedNow()
            emit(GhEvent.Snack(GhText.Res(R.string.gh_downloaded_deleted, listOf(d.label))))
        }
    }

    fun deleteAllDownloaded() {
        if (_state.value.installing) return
        scope.launch(Dispatchers.IO) {
            downloader.deleteAllApks()
            _state.update { it.copy(ready = null) }
            refreshDownloadedNow()
        }
    }

    // ---- lifecycle -------------------------------------------------------------------

    /** The project resolved to a ready repo (first time, or its repo changed). */
    fun onRepoReady(changed: Boolean) {
        if (changed) {
            cancelJobs()
            dispatchCache.clear()
            _state.update { GitHubToolsState(artifactsExpanded = it.artifactsExpanded, deploymentsExpanded = it.deploymentsExpanded) }
            refreshDownloaded()
        }
        loadBranches(reset = true)
        loadArtifactsAndApk()
        loadDeployments()
    }

    /** The gate left Ready (not git / no remote / no token). */
    fun onGateLost() {
        cancelJobs()
        _state.update { GitHubToolsState(artifactsExpanded = it.artifactsExpanded, deploymentsExpanded = it.deploymentsExpanded) }
    }

    fun refresh() {
        loadBranches(reset = true)
        loadArtifactsAndApk()
        loadDeployments()
    }

    /** Back from system settings / the installer. */
    fun onResume() {
        val s = _state.value
        refreshDownloaded()
        if (s.installing) _state.update { it.copy(installing = false) }
        if (s.installDialog == InstallDialog.UnknownSources && installer.canRequestInstalls()) {
            _state.update { it.copy(installDialog = null) }
            onInstallTapped()
        }
    }

    /** The screen is gone. Downloads are kept on disk and long jobs keep running in the background. */
    fun onCleared() = Unit

    private fun cancelJobs() {
        listOf(infoJob, pollJob, artifactsJob, deploymentsJob, scanJob).forEach { it?.cancel() }
    }

    fun setArtifactsExpanded(v: Boolean) { scope.launch { prefs.setSectionExpanded(projectPath, SECTION_ARTIFACTS, v) } }
    fun setDeploymentsExpanded(v: Boolean) { scope.launch { prefs.setSectionExpanded(projectPath, SECTION_DEPLOYMENTS, v) } }

    // ---- run workflow (plan 8.2) ---------------------------------------------------------

    private fun updateRun(block: (RunWorkflowState) -> RunWorkflowState) = _state.update { it.copy(run = block(it.run)) }

    fun loadBranches(reset: Boolean) {
        val repo = repoProvider() ?: return
        if (reset) branchPage = 1
        updateRun { it.copy(branchesLoading = true) }
        scope.launch {
            try {
                val page = repo.branches(branchPage)
                branchPage = page.page + 1
                updateRun {
                    it.copy(
                        branches = if (reset) page.items else (it.branches + page.items).distinct(),
                        branchesHasMore = page.hasMore,
                        branchesLoading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                updateRun { it.copy(branchesLoading = false) }
                onError(e)
            }
        }
    }

    fun loadMoreBranches() {
        val r = _state.value.run
        if (r.branchesHasMore && !r.branchesLoading) loadBranches(reset = false)
    }

    fun selectWorkflow(id: Long) = updateRun { it.copy(selectedWorkflowId = id, info = DispatchInfoState.Unknown) }

    fun selectBranch(name: String) = updateRun { it.copy(selectedBranch = name, info = DispatchInfoState.Unknown) }

    /** The workflow the row shows: the chosen one, else the first active one. */
    fun effectiveWorkflow(workflows: List<GhWorkflow>, selectedId: Long?): GhWorkflow? =
        workflows.firstOrNull { it.id == selectedId }
            ?: workflows.firstOrNull { it.state == "active" }
            ?: workflows.firstOrNull()

    /** Reads (once per workflow + ref) what the workflow file says about manual runs. */
    fun ensureDispatchInfo(workflow: GhWorkflow, ref: String) {
        val key = "${workflow.id}@$ref"
        activeInfoKey = key
        dispatchCache[key]?.let { updateRun { r -> r.copy(info = DispatchInfoState.Loaded(it)) }; return }
        val repo = repoProvider() ?: return
        updateRun { it.copy(info = DispatchInfoState.Loading) }
        infoJob?.cancel()
        infoJob = scope.launch {
            try {
                val info = WorkflowInputParser.parse(repo.workflowFile(workflow, ref))
                dispatchCache[key] = info
                if (activeInfoKey == key) updateRun { it.copy(info = DispatchInfoState.Loaded(info)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                if (activeInfoKey == key) updateRun { it.copy(info = DispatchInfoState.Failed(describe(e))) }
                if (e is GitHubError.Unauthorized || e is GitHubError.RateLimited) onError(e)
            }
        }
    }

    /** The play button: dispatch directly, open the inputs sheet, or explain why not. */
    fun onRunPressed(workflow: GhWorkflow, ref: String) {
        val r = _state.value.run
        if (r.dispatching) return
        when (val info = r.info) {
            is DispatchInfoState.Loaded -> when {
                !info.info.supported -> emit(GhEvent.Snack(GhText.Res(R.string.gh_run_no_manual_trigger)))
                info.info.inputs.isEmpty() && !info.info.parseFailed -> dispatch(workflow, ref, emptyMap())
                else -> openInputsSheet(info.info)
            }
            is DispatchInfoState.Failed -> openInputsSheet(null)
            else -> Unit // still loading: the button is disabled in the UI
        }
    }

    private fun openInputsSheet(info: DispatchInfo?) {
        val defaults = info?.inputs.orEmpty().associate { i ->
            i.name to (i.default ?: if (i.type == "boolean") "false" else if (i.type == "choice") i.options.firstOrNull().orEmpty() else "")
        }
        val raw = if (info == null || info.parseFailed) listOf(RawInput()) else emptyList()
        updateRun { it.copy(sheetOpen = true, values = defaults, rawInputs = raw, missingRequired = emptySet()) }
    }

    fun closeInputsSheet() = updateRun { it.copy(sheetOpen = false, missingRequired = emptySet()) }

    fun setInput(name: String, value: String) =
        updateRun { it.copy(values = it.values + (name to value), missingRequired = it.missingRequired - name) }

    fun setRawInput(index: Int, raw: RawInput) = updateRun {
        it.copy(rawInputs = it.rawInputs.toMutableList().also { l -> if (index in l.indices) l[index] = raw })
    }

    fun addRawInput() = updateRun { it.copy(rawInputs = it.rawInputs + RawInput()) }

    fun removeRawInput(index: Int) = updateRun {
        it.copy(rawInputs = it.rawInputs.filterIndexed { i, _ -> i != index })
    }

    fun submitInputs(workflow: GhWorkflow, ref: String) {
        val r = _state.value.run
        val info = (r.info as? DispatchInfoState.Loaded)?.info
        val typed = info?.inputs.orEmpty()
        val missing = typed.filter { it.required && r.values[it.name].isNullOrBlank() }.map { it.name }.toSet()
        if (missing.isNotEmpty()) {
            updateRun { it.copy(missingRequired = missing) }
            return
        }
        val inputs = LinkedHashMap<String, String>()
        typed.forEach { i -> r.values[i.name]?.takeIf { it.isNotEmpty() }?.let { inputs[i.name] = it } }
        r.rawInputs.filter { it.key.isNotBlank() }.forEach { inputs[it.key.trim()] = it.value }
        dispatch(workflow, ref, inputs)
    }

    private fun dispatch(workflow: GhWorkflow, ref: String, inputs: Map<String, String>) {
        val repo = repoProvider() ?: return
        updateRun { it.copy(dispatching = true) }
        // Quick action: no notification and no foreground service for it.
        jobScope.launch {
            // Remember the newest runs first: a dispatch returns no run id, so the new run is "the one not seen before".
            val before: Set<Long> = try {
                repo.dispatchRuns(workflow.id, ref).map { it.id }.toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                emptySet()
            }
            try {
                repo.dispatch(workflow.id, ref, inputs)
                updateRun { it.copy(dispatching = false, sheetOpen = false) }
                emit(GhEvent.Snack(GhText.Res(R.string.gh_dispatch_ok)))
                pollForNewRun(repo, workflow.id, ref, before)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                updateRun { it.copy(dispatching = false) }
                onError(e)
                emit(GhEvent.Snack(if (e is GitHubError.NotFound) GhText.Res(R.string.gh_dispatch_not_found) else describe(e)))
            }
        }
    }

    /** ~15 s (5 x 3 s) until the new run shows up, then refresh the Runs section. */
    private fun pollForNewRun(repo: GitHubRepository, workflowId: Long, ref: String, before: Set<Long>) {
        pollJob?.cancel()
        pollJob = scope.launch {
            repeat(POLL_TRIES) {
                delay(POLL_INTERVAL_MS)
                val found = try {
                    repo.dispatchRuns(workflowId, ref).any { r -> r.id !in before }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GitHubError) {
                    false
                }
                if (found) { onRunsChanged(); return@launch }
            }
            onRunsChanged()
        }
    }

    // ---- artifacts + APK card (plan 8.3 / 8.5) -------------------------------------------------

    fun loadArtifactsAndApk() {
        val repo = repoProvider() ?: return
        artifactsPage = 1
        _state.update {
            it.copy(
                artifactsLoading = it.artifacts.isEmpty(),
                artifactsError = null,
                apk = if (it.apk is ApkCardState.Found) it.apk else ApkCardState.Loading,
            )
        }
        artifactsJob?.cancel()
        artifactsJob = scope.launch {
            try {
                val page = repo.artifacts(1)
                artifactsPage = 2
                _state.update { it.copy(artifacts = page.items, artifactsHasMore = page.hasMore, artifactsLoading = false) }
                repoRefProvider()?.let { ref ->
                    page.items.filter { ApkResolver.isArm64(it.name) }.maxOfOrNull { it.id }
                        ?.let { ArmBuildWatch.markSeen(appContext, ref, it) }
                }
                resolveApk(repo, page.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                onError(e)
                _state.update {
                    it.copy(
                        artifactsLoading = false,
                        artifactsError = if (e is GitHubError.Offline && it.artifacts.isNotEmpty()) null else describe(e),
                        apk = if (it.apk is ApkCardState.Loading) ApkCardState.Error(describe(e)) else it.apk,
                    )
                }
            }
        }
    }

    fun loadMoreArtifacts() {
        val repo = repoProvider() ?: return
        val s = _state.value
        if (!s.artifactsHasMore || s.artifactsLoadingMore) return
        _state.update { it.copy(artifactsLoadingMore = true) }
        scope.launch {
            try {
                val page = repo.artifacts(artifactsPage)
                artifactsPage = page.page + 1
                _state.update {
                    it.copy(
                        artifacts = (it.artifacts + page.items).distinctBy { a -> a.id },
                        artifactsHasMore = page.hasMore,
                        artifactsLoadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update { it.copy(artifactsLoadingMore = false) }
                onError(e)
                emit(GhEvent.Snack(describe(e)))
            }
        }
    }

    private suspend fun resolveApk(repo: GitHubRepository, artifacts: List<GhArtifact>) {
        try {
            val resolution = ApkResolver.resolve(artifacts, repoRefProvider()?.defaultBranch, clock()) { runId ->
                try {
                    repo.run(runId).conclusion == "success"
                } catch (e: GitHubError.NotFound) {
                    false // the run was deleted; its artifact is not a trusted build
                }
            }
            _state.update {
                it.copy(
                    apk = when (resolution) {
                        is ApkResolution.Found -> ApkCardState.Found(
                            resolution.candidate,
                            ApkResolver.siblingsOf(resolution.candidate, artifacts, clock()),
                        )
                        is ApkResolution.AllExpired -> ApkCardState.AllExpired(resolution.newestAt)
                        ApkResolution.None -> ApkCardState.None
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubError) {
            onError(e)
            _state.update { it.copy(apk = ApkCardState.Error(describe(e))) }
        }
    }

    // ---- transfers: download -> verify -> install / save ---------------------------------

    fun downloadApk(candidate: ApkCandidate) = startTransfer(candidate.artifact, TransferPurpose.Install, fromApkCard = true)
    fun installFromArtifact(artifact: GhArtifact) = startTransfer(artifact, TransferPurpose.Install, fromApkCard = false)
    fun saveArtifact(artifact: GhArtifact) = startTransfer(artifact, TransferPurpose.Save, fromApkCard = false)

    /** What a paused transfer needs to continue. */
    private class PausedTransfer(val artifact: GhArtifact, val purpose: TransferPurpose, val fromApkCard: Boolean)

    private var pausedTransfer: PausedTransfer? = null
    private var pauseRequested = false

    /** Stop the running download but keep the partial file; [resumeTransfer] continues it. */
    fun pauseTransfer() {
        val t = _state.value.transfer ?: return
        if (t.paused || (t.progress ?: 0f) >= 1f) return
        pauseRequested = true
        transferJob?.cancel()
    }

    fun resumeTransfer() {
        val p = pausedTransfer ?: return
        startTransfer(p.artifact, p.purpose, p.fromApkCard)
    }

    /** Cancel: a running download is stopped, a paused one is dropped; the partial file is deleted either way. */
    fun cancelTransfer() {
        val t = _state.value.transfer ?: return
        if (t.paused) {
            pausedTransfer = null
            _state.update { it.copy(transfer = null) }
            scope.launch(Dispatchers.IO) { downloader.discardPartial(t.artifactId) }
        } else {
            pauseRequested = false
            transferJob?.cancel()
        }
    }

    private fun startTransfer(artifact: GhArtifact, purpose: TransferPurpose, fromApkCard: Boolean) {
        val repo = repoProvider() ?: return
        val s = _state.value
        // A paused transfer does not block: it is resumed (same artifact) or simply left with its partial file.
        if ((s.transfer != null && !s.transfer.paused) || s.installing || s.pendingSave != null) return
        pausedTransfer = null
        pauseRequested = false
        val partial = downloader.partialBytes(artifact.id)
        val startProgress = if (artifact.sizeBytes > 0 && partial in 1 until artifact.sizeBytes) partial.toFloat() / artifact.sizeBytes else 0f
        _state.update {
            it.copy(
                transfer = Transfer(artifact.id, purpose, startProgress, fromApkCard, downloaded = partial, total = artifact.sizeBytes),
                ready = null,
            )
        }
        // Already on disk (a quick list of one small folder): nothing long-running, so no notification.
        val alreadyDownloaded = downloader.cachedApk(artifact.id) != null
        transferJob = launchTracked(tracked = !alreadyDownloaded) {
            var zip: File? = null
            var paused = false
            val meter = DownloadMeter()
            val title = str(R.string.gh_notif_download_title, artifact.name)
            try {
                // Already downloaded earlier (e.g. before a self-install restarted the app): no new download.
                val cached = withContext(Dispatchers.IO) { downloader.cachedApk(artifact.id) }
                if (cached != null) {
                    when (purpose) {
                        TransferPurpose.Install -> prepareInstallFromApk(cached, artifact, fromApkCard)
                        TransferPurpose.Save -> _state.update {
                            it.copy(pendingSave = PendingSave(cached, "${safeFileName(artifact.name)}.apk", MIME_APK, deleteAfter = false))
                        }
                    }
                    return@launchTracked
                }
                notifier.updateLive(title, str(R.string.gh_notif_download_progress, 0), 0)
                var last = -1f
                val file = downloader.downloadZip(repo, artifact) { bytes ->
                    val p = if (artifact.sizeBytes > 0) (bytes.toFloat() / artifact.sizeBytes).coerceIn(0f, 1f) else null
                    if (p == null || p - last >= 0.01f) {
                        last = p ?: last
                        val eta = meter.etaSeconds(bytes, artifact.sizeBytes)
                        _state.update { st ->
                            st.transfer?.takeIf { t -> !t.paused }
                                ?.let { t -> st.copy(transfer = t.copy(progress = p, downloaded = bytes, total = artifact.sizeBytes, etaSeconds = eta)) }
                                ?: st
                        }
                        notifier.updateLive(title, p?.let { str(R.string.gh_notif_download_progress, (it * 100).toInt()) } ?: "", p?.let { (it * 100).toInt() })
                    }
                }
                zip = file
                notifier.updateLive(title, str(R.string.gh_notif_download_preparing), null)
                when (purpose) {
                    TransferPurpose.Install -> prepareInstall(file, artifact, fromApkCard)
                    TransferPurpose.Save -> prepareSave(file, artifact)
                }
                if (purpose == TransferPurpose.Install) {
                    notifier.result(GhNotifier.KIND_DOWNLOAD, str(R.string.gh_notif_download_done_title), str(R.string.gh_notif_download_done_text, artifact.name))
                }
            } catch (e: CancellationException) {
                if (pauseRequested && zip == null) {
                    // Paused: the .part file stays so Resume continues from there.
                    paused = true
                    pausedTransfer = PausedTransfer(artifact, purpose, fromApkCard)
                    _state.update { st -> st.transfer?.let { t -> st.copy(transfer = t.copy(paused = true, etaSeconds = null)) } ?: st }
                } else {
                    zip?.delete()
                    withContext(NonCancellable + Dispatchers.IO) { downloader.discardPartial(artifact.id) }
                }
                pauseRequested = false
                throw e
            } catch (e: GitHubError) {
                zip?.delete()
                onError(e)
                emit(GhEvent.Snack(transferError(e)))
                notifier.result(GhNotifier.KIND_DOWNLOAD, str(R.string.gh_notif_download_failed_title), transferError(e).resolve(appContext), ok = false)
            } catch (e: ArtifactZipException) {
                zip?.delete()
                emit(GhEvent.Snack(GhText.Res(R.string.gh_apk_invalid)))
                notifier.result(GhNotifier.KIND_DOWNLOAD, str(R.string.gh_notif_download_failed_title), str(R.string.gh_apk_invalid), ok = false)
            } catch (e: IOException) {
                zip?.delete()
                emit(GhEvent.Snack(GhText.Res(R.string.gh_disk_error)))
                notifier.result(GhNotifier.KIND_DOWNLOAD, str(R.string.gh_notif_download_failed_title), str(R.string.gh_disk_error), ok = false)
            } finally {
                if (!paused) _state.update { it.copy(transfer = null) }
                withContext(NonCancellable + Dispatchers.IO) { downloader.cleanOld(); refreshDownloadedNow() }
            }
        }
    }

    private fun transferError(e: GitHubError): GhText = when (e) {
        is GitHubError.Gone, is GitHubError.NotFound -> GhText.Res(R.string.gh_artifact_expired)
        is GitHubError.BadDownload -> GhText.Res(
            if (e.reason == GitHubError.BadDownload.Reason.TooLarge) R.string.gh_download_too_large else R.string.gh_download_incomplete,
        )
        else -> describe(e)
    }

    private suspend fun prepareInstall(zip: File, artifact: GhArtifact, fromApkCard: Boolean) {
        prepareInstallFromApk(downloader.extract(zip, artifact), artifact, fromApkCard)
    }

    private suspend fun prepareInstallFromApk(apk: File, artifact: GhArtifact, fromApkCard: Boolean) {
        val inspected = withContext(Dispatchers.IO) { inspector.inspect(apk) }
        if (inspected == null) {
            apk.delete()
            emit(GhEvent.Snack(GhText.Res(R.string.gh_apk_invalid)))
            return
        }
        _state.update { it.copy(ready = ReadyApk(artifact.id, artifact.name, apk, inspected, fromApkCard)) }
    }

    private suspend fun prepareSave(zip: File, artifact: GhArtifact) {
        val base = safeFileName(artifact.name)
        val pending = if (withContext(Dispatchers.IO) { ArtifactZip.containsApk(zip) }) {
            PendingSave(downloader.extract(zip, artifact), "$base.apk", MIME_APK, deleteAfter = false)
        } else {
            PendingSave(zip, "$base.zip", MIME_ZIP, deleteAfter = true)
        }
        _state.update { it.copy(pendingSave = pending) }
    }

    /** "Save to folder" from the APK card's ready state (the file is also needed for Install, so it is kept). */
    fun saveReadyApk() {
        val r = _state.value.ready ?: return
        if (_state.value.pendingSave != null) return
        _state.update {
            it.copy(pendingSave = PendingSave(r.file, "${safeFileName(r.artifactName)}.apk", MIME_APK, deleteAfter = false))
        }
    }

    fun onSaveLaunched() = _state.update { s -> s.copy(pendingSave = s.pendingSave?.copy(launched = true)) }

    /** Result of the system "create document" picker: [uri] is null when the user cancelled. */
    fun completeSave(uri: Uri?) {
        val pending = _state.value.pendingSave ?: return
        _state.update { it.copy(pendingSave = null) }
        if (uri == null) {
            if (pending.deleteAfter) pending.file.delete()
            return
        }
        jobScope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    val out = appContext.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("no output stream")
                    out.use { o -> pending.file.inputStream().use { it.copyTo(o) } }
                    displayName(uri)
                }
                emit(GhEvent.Snack(GhText.Res(R.string.gh_saved, listOf(name ?: pending.suggestedName))))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A half-written document must not stay behind.
                withContext(Dispatchers.IO) {
                    try { DocumentsContract.deleteDocument(appContext.contentResolver, uri) } catch (ignored: Exception) { }
                }
                emit(GhEvent.Snack(GhText.Res(R.string.gh_save_failed)))
            } finally {
                if (pending.deleteAfter) pending.file.delete()
            }
        }
    }

    private fun displayName(uri: Uri): String? =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun safeFileName(name: String): String = name.replace(Regex("""[\\/:*?"<>|\s]"""), "_").ifBlank { "artifact" }

    // ---- install ------------------------------------------------------------------------

    fun dismissReady() {
        _state.update { it.copy(ready = null) }
    }

    /** Install button. Never installs without this tap and the system's own confirmation. */
    fun onInstallTapped() {
        val s = _state.value
        val r = s.ready ?: return
        if (s.installing || InstallDecision.isBlocked(r.inspect.kind)) return
        when {
            !installer.canRequestInstalls() -> _state.update { it.copy(installDialog = InstallDialog.UnknownSources) }
            r.inspect.isSelf -> _state.update { it.copy(installDialog = InstallDialog.SelfInstall) }
            else -> startInstall()
        }
    }

    fun confirmSelfInstall() {
        _state.update { it.copy(installDialog = null) }
        startInstall()
    }

    fun dismissInstallDialog() = _state.update { it.copy(installDialog = null) }

    fun unknownSourcesIntent(): Intent = installer.unknownSourcesIntent()

    private fun startInstall() {
        val r = _state.value.ready ?: return
        _state.update { it.copy(installing = true) }
        scope.launch {
            try {
                installer.install(r.file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(installing = false) }
                emit(GhEvent.Snack(GhText.Res(R.string.gh_install_failed, listOf(e.message.orEmpty()))))
            }
        }
    }

    private fun onInstallResult(result: InstallResult) {
        val s = _state.value
        if (!s.installing && s.ready == null) return
        _state.update { it.copy(installing = false) }
        when (result) {
            InstallResult.Success -> {
                _state.update { it.copy(ready = null) }
                emit(GhEvent.Snack(GhText.Res(R.string.gh_install_ok)))
            }
            InstallResult.Cancelled -> emit(GhEvent.Snack(GhText.Res(R.string.gh_install_cancelled)))
            is InstallResult.Failed -> emit(GhEvent.Snack(GhText.Res(R.string.gh_install_failed, listOf(result.message.orEmpty()))))
        }
    }

    // ---- artifact delete -----------------------------------------------------------------

    fun deleteArtifact(a: GhArtifact) {
        val repo = repoProvider() ?: return
        if (a.id in _state.value.busyArtifactIds) return
        val index = _state.value.artifacts.indexOfFirst { it.id == a.id }.takeIf { it >= 0 } ?: return
        _state.update { it.copy(artifacts = it.artifacts.filterNot { x -> x.id == a.id }, busyArtifactIds = it.busyArtifactIds + a.id) }
        scope.launch {
            try {
                repo.deleteArtifact(a.id)
                emit(GhEvent.Snack(GhText.Res(R.string.gh_artifact_deleted, listOf(a.name))))
                resolveApk(repo, _state.value.artifacts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update {
                    val list = it.artifacts.toMutableList()
                    list.add(index.coerceAtMost(list.size), a)
                    it.copy(artifacts = list)
                }
                onError(e)
                emit(GhEvent.Snack(describe(e)))
            } finally {
                _state.update { it.copy(busyArtifactIds = it.busyArtifactIds - a.id) }
            }
        }
    }

    // ---- deployments (plan 7.4) ------------------------------------------------------------

    fun loadDeployments() {
        val repo = repoProvider() ?: return
        _state.update { it.copy(deploymentsLoading = it.deployments.isEmpty(), deploymentsError = null) }
        deploymentsJob?.cancel()
        deploymentsJob = scope.launch {
            try {
                val page = repo.deployments(1)
                _state.update { it.copy(deployments = page.items, deploymentsLoading = false) }
                // Status chips for the rows that are visible by default (best effort).
                val states = repo.deploymentStates(page.items.take(DEPLOYMENT_STATES).map { it.id })
                _state.update { s ->
                    s.copy(deployments = s.deployments.map { d -> if (d.id in states) d.copy(latestState = states[d.id]) else d })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                onError(e)
                _state.update {
                    it.copy(
                        deploymentsLoading = false,
                        deploymentsError = if (e is GitHubError.Offline && it.deployments.isNotEmpty()) null else describe(e),
                    )
                }
            }
        }
    }

    fun deleteDeployment(d: GhDeployment) {
        val repo = repoProvider() ?: return
        if (d.id in _state.value.busyDeploymentIds) return
        val index = _state.value.deployments.indexOfFirst { it.id == d.id }.takeIf { it >= 0 } ?: return
        _state.update { it.copy(deployments = it.deployments.filterNot { x -> x.id == d.id }, busyDeploymentIds = it.busyDeploymentIds + d.id) }
        scope.launch {
            try {
                repo.deleteDeployment(d.id)
                emit(GhEvent.Snack(GhText.Res(R.string.gh_deployment_deleted, listOf(d.environment))))
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update {
                    val list = it.deployments.toMutableList()
                    list.add(index.coerceAtMost(list.size), d)
                    it.copy(deployments = list)
                }
                onError(e)
                emit(GhEvent.Snack(describe(e)))
            } finally {
                _state.update { it.copy(busyDeploymentIds = it.busyDeploymentIds - d.id) }
            }
        }
    }

    // ---- cleanup (plan 8.6) ----------------------------------------------------------------

    private fun updateCleanup(block: (CleanupState) -> CleanupState) = _state.update { it.copy(cleanup = block(it.cleanup)) }

    fun openCleanup() {
        // A cleanup running in the background: just show its progress again.
        if (_state.value.cleanup.running) { updateCleanup { it.copy(open = true) }; return }
        updateCleanup { CleanupState(open = true) }
        scanCleanup()
    }

    /** Swipe / back / Done. A running cleanup keeps going in the background (see notification). */
    fun closeCleanup() {
        if (_state.value.cleanup.running) {
            updateCleanup { it.copy(open = false) }
            return
        }
        scanJob?.cancel()
        updateCleanup { CleanupState() }
    }

    fun setCleanupKind(kind: CleanupKind) {
        if (_state.value.cleanup.running) return
        updateCleanup { it.copy(filters = CleanupFilters(kind = kind), result = null, scanError = null) }
        recomputePlan()
        if (!_state.value.cleanup.hasData) scanCleanup()
    }

    fun updateCleanupFilters(filters: CleanupFilters) {
        updateCleanup { it.copy(filters = filters, result = null) }
        recomputePlan()
    }

    fun toggleCleanupItem(id: Long) = updateCleanup {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun selectAllCleanup() = updateCleanup { c -> c.copy(selected = c.plan?.items?.map { it.id }?.toSet().orEmpty()) }

    fun selectNoneCleanup() = updateCleanup { it.copy(selected = emptySet()) }

    /** Fetches the list of the current kind once; later filter changes are computed client-side. */
    fun scanCleanup() {
        val repo = repoProvider() ?: return
        val kind = _state.value.cleanup.filters.kind
        updateCleanup { it.copy(scanning = true, scanError = null) }
        scanJob?.cancel()
        scanJob = scope.launch {
            try {
                when (kind) {
                    CleanupKind.Runs -> {
                        val runs = repo.scanCompletedRuns()
                        val bytes = try {
                            (_state.value.cleanup.rawArtifacts ?: repo.scanArtifacts())
                                .filter { it.runId != null }
                                .groupBy { it.runId!! }
                                .mapValues { (_, v) -> v.sumOf { a -> a.sizeBytes } }
                        } catch (e: GitHubError) {
                            emptyMap() // sizes are decoration; the run list is what matters
                        }
                        updateCleanup { it.copy(rawRuns = runs, artifactBytesByRun = bytes) }
                    }
                    CleanupKind.Artifacts -> { val a = repo.scanArtifacts(); updateCleanup { it.copy(rawArtifacts = a) } }
                    CleanupKind.Deployments -> { val d = repo.scanDeployments(); updateCleanup { it.copy(rawDeployments = d) } }
                }
                updateCleanup { it.copy(scanning = false) }
                recomputePlan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                onError(e)
                updateCleanup { it.copy(scanning = false, scanError = describe(e)) }
            }
        }
    }

    private fun workflowName(run: GhRun): String =
        workflowsProvider().firstOrNull { it.id == run.workflowId }?.name ?: run.name

    private fun recomputePlan() {
        _state.update { s ->
            val c = s.cleanup
            val now = clock()
            val plan = when (c.filters.kind) {
                CleanupKind.Runs -> c.rawRuns?.let { CleanupPlanner.planRuns(it, c.filters, now, this::workflowName, c.artifactBytesByRun) }
                CleanupKind.Artifacts -> c.rawArtifacts?.let { CleanupPlanner.planArtifacts(it, c.filters, now) }
                CleanupKind.Deployments -> c.rawDeployments?.let { CleanupPlanner.planDeployments(it, c.filters, now) }
            }
            s.copy(cleanup = c.copy(plan = plan, selected = plan?.defaultSelection.orEmpty()))
        }
    }

    fun runCleanup() {
        val c = _state.value.cleanup
        val items = c.plan?.items.orEmpty().filter { it.id in c.selected }
        executeCleanup(items)
    }

    fun retryFailedCleanup() {
        val failed = _state.value.cleanup.result?.failed.orEmpty().map { it.item }
        executeCleanup(failed)
    }

    fun stopCleanup() = updateCleanup { it.copy(stopRequested = true) }

    private fun executeCleanup(items: List<CleanupItem>) {
        val repo = repoProvider() ?: return
        if (items.isEmpty() || _state.value.cleanup.running) return
        val kind = _state.value.cleanup.filters.kind
        updateCleanup { it.copy(running = true, stopRequested = false, progressDone = 0, progressTotal = items.size, result = null) }
        // Only a big cleanup gets a notification / foreground service; a few deletes are over in seconds.
        val big = items.size >= BIG_CLEANUP
        cleanupJob = launchTracked(tracked = big) {
            val title = str(R.string.gh_notif_cleanup_title, appContext.getString(kindName(kind)).lowercase())
            if (big) notifier.updateLive(title, str(R.string.gh_notif_cleanup_progress, 0, items.size), 0)
            val result = CleanupRunner().run(
                items = items,
                shouldStop = { _state.value.cleanup.stopRequested },
                delete = { item ->
                    when (item.kind) {
                        CleanupKind.Runs -> repo.delete(item.id)
                        CleanupKind.Artifacts -> repo.deleteArtifact(item.id)
                        CleanupKind.Deployments -> repo.deleteDeployment(item.id)
                    }
                },
                onProgress = { done, total ->
                    updateCleanup { it.copy(progressDone = done, progressTotal = total) }
                    if (big) notifier.updateLive(title, str(R.string.gh_notif_cleanup_progress, done, total), if (total > 0) done * 100 / total else null)
                },
            )
            finishCleanup(kind, items, result, notify = big)
        }
    }

    private fun finishCleanup(kind: CleanupKind, attempted: List<CleanupItem>, result: CleanupResult, notify: Boolean) {
        val untouched = (result.failed.map { it.item.id } + result.notAttempted.map { it.id }).toSet()
        val gone = attempted.map { it.id }.filter { it !in untouched }.toSet()
        updateCleanup { c ->
            c.copy(
                running = false,
                stopRequested = false,
                result = result,
                rawRuns = if (kind == CleanupKind.Runs) c.rawRuns?.filterNot { it.id in gone } else c.rawRuns,
                rawArtifacts = if (kind == CleanupKind.Artifacts) c.rawArtifacts?.filterNot { it.id in gone } else c.rawArtifacts,
                rawDeployments = if (kind == CleanupKind.Deployments) c.rawDeployments?.filterNot { it.id in gone } else c.rawDeployments,
            )
        }
        recomputePlan()
        emit(GhEvent.Snack(GhText.Res(R.string.gh_cleanup_done, listOf(result.deleted, result.failed.size))))
        if (notify) notifier.result(
            GhNotifier.KIND_CLEANUP, str(R.string.gh_notif_cleanup_done_title),
            str(R.string.gh_notif_cleanup_done_text, result.deleted, result.failed.size), ok = result.failed.isEmpty(),
        )
        when (kind) {
            CleanupKind.Runs -> onRunsChanged()
            CleanupKind.Artifacts -> loadArtifactsAndApk()
            CleanupKind.Deployments -> loadDeployments()
        }
    }

    private fun kindName(k: CleanupKind): Int = when (k) {
        CleanupKind.Runs -> R.string.gh_kind_runs
        CleanupKind.Deployments -> R.string.gh_kind_deployments
        CleanupKind.Artifacts -> R.string.gh_kind_artifacts
    }

    companion object {
        private const val BIG_CLEANUP = 5
        private const val SECTION_ARTIFACTS = "artifacts"
        private const val SECTION_DEPLOYMENTS = "deployments"
        private const val POLL_TRIES = 5
        private const val POLL_INTERVAL_MS = 3_000L
        private const val DEPLOYMENT_STATES = 5
        const val MIME_APK = "application/vnd.android.package-archive"
        const val MIME_ZIP = "application/zip"
    }
}
