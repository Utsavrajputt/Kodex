package com.invictus.kodex.github.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.KodexApp
import com.invictus.kodex.R
import com.invictus.kodex.core.project.ProjectRepository
import com.invictus.kodex.github.api.GhArtifact
import com.invictus.kodex.github.api.GhRepoRef
import com.invictus.kodex.github.api.GitHubError
import com.invictus.kodex.github.data.ApkResolver
import com.invictus.kodex.github.data.GitHubProjectResolver
import com.invictus.kodex.github.data.GitHubRepository
import com.invictus.kodex.github.data.GitHubResolution
import com.invictus.kodex.github.install.ApkDownloader
import com.invictus.kodex.github.install.ApkInspector
import com.invictus.kodex.github.install.ApkInstallInfo
import com.invictus.kodex.github.install.ApkInstaller
import com.invictus.kodex.github.install.BuildSha
import com.invictus.kodex.github.install.InstallEvents
import com.invictus.kodex.github.install.InstallResult
import com.invictus.kodex.github.install.InstalledApps
import com.invictus.kodex.github.install.InstalledBuilds
import com.invictus.kodex.github.install.ArtifactZipException
import com.invictus.kodex.github.install.InstallDecision
import com.invictus.kodex.github.notify.ArmBuildWatch
import com.invictus.kodex.github.notify.GhBackground
import com.invictus.kodex.github.notify.GhNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

private val ABI_MARKERS = listOf("-arm64-v8a", "-armeabi-v7a", "-universal", "-x86_64", "-x86")

/** "kodex-arm64-v8a-3514671" -> "Kodex". */
internal fun apkAppName(artifactName: String): String {
    var base = artifactName.removeSuffix(".apk")
    val cut = ABI_MARKERS.map { base.indexOf(it) }.filter { it > 0 }.minOrNull()
    if (cut != null) base = base.substring(0, cut)
    return base.replace('-', ' ').replace('_', ' ').trim().replaceFirstChar { it.uppercase() }
}

/** "kodex-arm64-v8a-3514671" -> "Kodex arm64" (app name, then the ABI). */
internal fun apkTitle(artifactName: String): String {
    val abi = when {
        "-arm64-v8a" in artifactName -> "arm64"
        "-armeabi-v7a" in artifactName -> "armeabi"
        "-universal" in artifactName -> "universal"
        else -> ""
    }
    return "${apkAppName(artifactName)} $abi".trim()
}

/** "kodex-arm64-v8a-3514671" + sha -> "Kodex-arm64-92886fe" (app name, ABI, commit). */
internal fun apkBuildName(artifactName: String, shortSha: String): String =
    listOf(apkTitle(artifactName).replace(' ', '-'), shortSha).filter { it.isNotBlank() }.joinToString("-")

/** One ARM64 variant (Light, Full, ...) of a repo's latest build: "<App> arm64". */
data class ApkRow(
    val artifact: GhArtifact,
    val repo: GhRepoRef,
    val appName: String,
    val expired: Boolean,
    /** Project folder of this repo (for the app icon). */
    val projectPath: String = "",
)

/** The latest build of one repo: every ARM64 artifact it produced. */
data class RepoBuild(val repo: GhRepoRef, val projectPath: String, val variants: List<ApkRow>) {
    val key: String get() = "${repo.owner}/${repo.name}".lowercase()
    val newestAt: Instant get() = variants.maxOfOrNull { it.artifact.createdAt ?: Instant.EPOCH } ?: Instant.EPOCH
}

/** The download in progress (or paused) on the Builds screen. */
data class RowTransfer(
    val artifactId: Long,
    val downloaded: Long = 0L,
    val total: Long = 0L,
    val etaSeconds: Long? = null,
    val speedBps: Long? = null,
    val paused: Boolean = false,
)

data class AllApksState(
    val loading: Boolean = true,
    val error: Int? = null,
    val builds: List<RepoBuild> = emptyList(),
    val transfer: RowTransfer? = null,
    /** Artifact whose install was handed to the system installer and has not reported back yet. */
    val installingId: Long? = null,
    val installPhase: InstallPhase = InstallPhase.Idle,
    /** Artifact ids whose APK is already on the device (row shows Install + Delete instead of Download). */
    val downloaded: Set<Long> = emptySet(),
    /** Install / Update / Open for each downloaded artifact id. */
    val installInfo: Map<Long, ApkInstallInfo> = emptyMap(),
) {
    /** A running download blocks the other buttons; a paused one does not. */
    val anyBusy: Boolean get() = transfer?.paused == false
}

sealed interface AllApksEvent {
    data class Snack(val message: Int, val args: List<Any> = emptyList()) : AllApksEvent
    data class OpenSettings(val intent: Intent) : AllApksEvent
}

/**
 * The latest build of every recent project's GitHub repo (all its ARM64 variants), newest first.
 */
class AllApksViewModel(
    private val projects: ProjectRepository,
    private val resolver: GitHubProjectResolver,
    private val repoFactory: (GhRepoRef) -> GitHubRepository,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(AllApksState())
    val state: StateFlow<AllApksState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AllApksEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AllApksEvent> = _events.asSharedFlow()

    private val downloader = ApkDownloader(appContext.filesDir)
    private val inspector = ApkInspector(appContext)
    private val installer = ApkInstaller(appContext)
    private val installedBuilds = InstalledBuilds(appContext)
    private val installedApps = InstalledApps(appContext)
    private val notifier = GhNotifier(appContext)
    private var transferJob: Job? = null

    init {
        ArmBuildWatch.schedule(appContext)
        refreshDownloaded()
        // An install finished (Install -> Open) while this screen is open.
        viewModelScope.launch {
            InstallEvents.results.collect { result ->
                if (result == InstallResult.Success && _state.value.installingId != null) {
                    // "Installed" stays on the button for a moment, then it turns into Open.
                    _state.update { it.copy(installPhase = InstallPhase.Installed) }
                    refreshDownloaded()
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(1_500)
                        _state.update { it.copy(installingId = null, installPhase = InstallPhase.Idle) }
                    }
                } else {
                    _state.update { it.copy(installingId = null, installPhase = InstallPhase.Idle) }
                    refreshDownloaded()
                }
            }
        }
        load()
    }

    /** Also called from the screen on resume: the app may have been installed or updated elsewhere. */
    fun refreshDownloaded() {
        viewModelScope.launch(Dispatchers.IO) {
            val cached = downloader.listCached()
            val rows = _state.value.builds.flatMap { it.variants }.associateBy { it.artifact.id }
            val info = cached.associate { c ->
                val sha = rows[c.artifactId]?.artifact?.sha?.takeIf { it.isNotBlank() } ?: BuildSha.fromArtifactName(c.label)
                c.artifactId to installedApps.resolve(c.file, sha, installedBuilds)
            }
            _state.update { it.copy(downloaded = cached.map { c -> c.artifactId }.toSet(), installInfo = info) }
        }
    }

    /** The Open button of a row. */
    fun open(row: ApkRow) {
        val pkg = _state.value.installInfo[row.artifact.id]?.packageName
        if (pkg == null || !installedApps.open(pkg)) _events.tryEmit(AllApksEvent.Snack(R.string.gh_open_failed))
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val recents = projects.recents.first().take(MAX_PROJECTS)
                val found = coroutineScope {
                    recents.map { async(Dispatchers.IO) { it.file.path to resolver.resolve(it.file.path) } }.awaitAll()
                }.mapNotNull { (path, r) -> (r as? GitHubResolution.Ready)?.repo?.let { it to path } }
                    .distinctBy { (repo, _) -> "${repo.owner}/${repo.name}".lowercase() }
                val repos = found.map { it.first }
                val pathOf = found.associate { (repo, path) -> repo to path }

                val builds = coroutineScope {
                    repos.map { repo ->
                        async {
                            try {
                                val latest = ApkResolver.latestBuild(repoFactory(repo).artifacts(1).items)
                                if (latest.isEmpty()) null
                                else RepoBuild(repo, pathOf[repo].orEmpty(), latest.map { toRow(it, repo, pathOf[repo].orEmpty()) })
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: GitHubError) {
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }.sortedByDescending { it.newestAt }

                builds.forEach { b -> ArmBuildWatch.markSeen(appContext, b.repo, b.variants.maxOf { it.artifact.id }) }
                _state.update {
                    it.copy(loading = false, builds = builds, error = if (repos.isEmpty()) R.string.apks_no_repos else null)
                }
                refreshDownloaded() // now that the builds' commits are known
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = R.string.apks_load_failed) }
            }
        }
    }

    private fun toRow(a: GhArtifact, repo: GhRepoRef, projectPath: String): ApkRow {
        return ApkRow(a, repo, apkAppName(a.name).ifEmpty { repo.name.replaceFirstChar { it.uppercase() } }, a.isExpiredAt(Instant.now()), projectPath)
    }

    // ---- install -------------------------------------------------------------------------

    /** Download only: the APK is kept on the device and the row switches to Install / Delete. */
    fun download(row: ApkRow) = start(row, installAfter = false)

    fun install(row: ApkRow) = start(row, installAfter = true)

    fun delete(row: ApkRow) {
        if (_state.value.anyBusy) return
        viewModelScope.launch(Dispatchers.IO) {
            downloader.cachedApk(row.artifact.id)?.let { downloader.delete(it) }
            refreshDownloaded()
            _events.tryEmit(AllApksEvent.Snack(R.string.gh_downloaded_deleted, listOf(apkBuildName(row.artifact.name, row.artifact.shortSha))))
        }
    }

    private class PausedRow(val row: ApkRow, val installAfter: Boolean)

    private var pausedRow: PausedRow? = null
    private var pauseRequested = false

    /** Stop the running download but keep the partial file; [resume] continues it. */
    fun pause() {
        val t = _state.value.transfer ?: return
        if (t.paused || (t.total > 0 && t.downloaded >= t.total)) return
        pauseRequested = true
        transferJob?.cancel()
    }

    fun resume() {
        val p = pausedRow ?: return
        start(p.row, p.installAfter)
    }

    /** Cancel: a running download is stopped, a paused one is dropped; the partial file is deleted either way. */
    fun cancel() {
        val t = _state.value.transfer ?: return
        if (t.paused) {
            pausedRow = null
            _state.update { it.copy(transfer = null) }
            viewModelScope.launch(Dispatchers.IO) { downloader.discardPartial(t.artifactId) }
        } else {
            pauseRequested = false
            transferJob?.cancel()
        }
    }

    /** Safety net: if the system installer never reports back (e.g. its prompt was ignored), the button comes back. */
    private fun armInstallingTimeout(id: Long) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(180_000)
            _state.update { if (it.installingId == id) it.copy(installingId = null, installPhase = InstallPhase.Idle) else it }
        }
    }

    private fun start(row: ApkRow, installAfter: Boolean) {
        val current = _state.value.transfer
        // A paused download does not block: it is resumed (same row) or simply left with its partial file.
        if ((current != null && !current.paused) || row.expired) return
        pausedRow = null
        pauseRequested = false
        val partial = downloader.partialBytes(row.artifact.id)
        _state.update {
            it.copy(transfer = RowTransfer(row.artifact.id, downloaded = partial.takeIf { p -> p < row.artifact.sizeBytes } ?: 0L, total = row.artifact.sizeBytes))
        }
        transferJob = GhBackground.scope.launch {
            GhBackground.begin(appContext)
            var paused = false
            var downloadDone = false
            val meter = DownloadMeter()
            val title = appContext.getString(R.string.gh_notif_download_title, row.artifact.name)
            try {
                val cached = withContext(Dispatchers.IO) { downloader.cachedApk(row.artifact.id) }
                val apk = cached ?: run {
                    val repo = repoFactory(row.repo)
                    var last = -1f
                    notifier.updateLive(title, "", 0)
                    val zip = downloader.downloadZip(repo, row.artifact) { bytes ->
                        val size = row.artifact.sizeBytes
                        val p = if (size > 0) (bytes.toFloat() / size).coerceIn(0f, 1f) else null
                        if (p == null || p - last >= 0.01f) {
                            last = p ?: last
                            val eta = meter.etaSeconds(bytes, size)
                            _state.update { st ->
                                st.transfer?.takeIf { t -> !t.paused }
                                    ?.let { t -> st.copy(transfer = t.copy(downloaded = bytes, total = size, etaSeconds = eta, speedBps = meter.speedBytesPerSec)) }
                                    ?: st
                            }
                            notifier.updateLive(title, p?.let { v -> appContext.getString(R.string.gh_notif_download_progress, (v * 100).toInt()) }.orEmpty(), p?.let { v -> (v * 100).toInt() })
                        }
                    }
                    downloadDone = true
                    notifier.updateLive(title, appContext.getString(R.string.gh_notif_download_preparing), null)
                    downloader.extract(zip, row.artifact)
                }
                val inspected = withContext(Dispatchers.IO) { inspector.inspect(apk) }
                when {
                    inspected == null -> {
                        apk.delete()
                        _events.tryEmit(AllApksEvent.Snack(R.string.gh_apk_invalid))
                    }
                    // Download only: tapping the notification opens the system installer for this APK.
                    !installAfter -> notifier.downloadComplete(apk, row.artifact.name)
                    InstallDecision.isBlocked(inspected.kind) -> _events.tryEmit(AllApksEvent.Snack(R.string.apks_downgrade_blocked))
                    !installer.canRequestInstalls() -> _events.tryEmit(AllApksEvent.OpenSettings(installer.unknownSourcesIntent()))
                    else -> {
                        notifier.result(
                            GhNotifier.KIND_DOWNLOAD, appContext.getString(R.string.gh_notif_download_done_title),
                            appContext.getString(R.string.gh_notif_download_done_text, row.artifact.name),
                        )
                        // The package installer may replace this very app (killing the process): leave no stale bar behind.
                        notifier.clearLive()
                        installedBuilds.begin(inspected.info.packageName, row.artifact.sha?.takeIf { it.isNotBlank() } ?: BuildSha.fromArtifactName(row.artifact.name))
                        notifier.clearDownloadComplete()
                        _state.update { it.copy(transfer = null, installingId = row.artifact.id, installPhase = InstallPhase.Preparing) }
                        try {
                            installer.install(apk)
                        } catch (e: Exception) {
                            _state.update { it.copy(installingId = null, installPhase = InstallPhase.Idle) }
                            throw e
                        }
                        _state.update {
                            if (it.installingId == row.artifact.id && it.installPhase == InstallPhase.Preparing) it.copy(installPhase = InstallPhase.Installing) else it
                        }
                        armInstallingTimeout(row.artifact.id)
                    }
                }
            } catch (e: CancellationException) {
                if (pauseRequested && !downloadDone) {
                    // Paused: the .part file stays so Resume continues from there.
                    paused = true
                    pausedRow = PausedRow(row, installAfter)
                    _state.update { st -> st.transfer?.let { t -> st.copy(transfer = t.copy(paused = true, etaSeconds = null, speedBps = null)) } ?: st }
                } else {
                    withContext(NonCancellable + Dispatchers.IO) { downloader.discardPartial(row.artifact.id) }
                }
                pauseRequested = false
                throw e
            } catch (e: GitHubError) {
                _events.tryEmit(AllApksEvent.Snack(R.string.gh_download_incomplete))
                notifier.result(GhNotifier.KIND_DOWNLOAD, appContext.getString(R.string.gh_notif_download_failed_title), describe(e).resolve(appContext), ok = false)
            } catch (e: ArtifactZipException) {
                _events.tryEmit(AllApksEvent.Snack(R.string.gh_apk_invalid))
            } catch (e: IOException) {
                _events.tryEmit(AllApksEvent.Snack(R.string.gh_disk_error))
            } catch (e: Exception) {
                _events.tryEmit(AllApksEvent.Snack(R.string.gh_install_failed, listOf(e.message.orEmpty())))
            } finally {
                if (!paused) _state.update { it.copy(transfer = null) }
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { downloader.cleanOld() }
                refreshDownloaded()
                GhBackground.end(appContext)
            }
        }
    }

    companion object {
        private const val MAX_PROJECTS = 12

        val Factory: androidx.lifecycle.ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                val c = app.container
                AllApksViewModel(c.projectRepository, c.gitHubProjectResolver, c::gitHubRepository, app.applicationContext)
            }
        }
    }
}
