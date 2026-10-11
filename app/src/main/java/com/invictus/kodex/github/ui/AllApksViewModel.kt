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
import com.invictus.kodex.github.install.ApkInstaller
import com.invictus.kodex.github.install.ArtifactZipException
import com.invictus.kodex.github.install.InstallDecision
import com.invictus.kodex.github.notify.ArmBuildWatch
import com.invictus.kodex.github.notify.GhBackground
import com.invictus.kodex.github.notify.GhNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

/** One ARM64 build in the combined list: "<App> arm64". */
data class ApkRow(
    val artifact: GhArtifact,
    val repo: GhRepoRef,
    val appName: String,
    val expired: Boolean,
)

data class AllApksState(
    val loading: Boolean = true,
    val error: Int? = null,
    val rows: List<ApkRow> = emptyList(),
    val visible: Int = PAGE,
    val loadingMore: Boolean = false,
    /** Artifact id of the row that is downloading, with 0..1 progress (null = unknown size). */
    val busyId: Long? = null,
    val progress: Float? = null,
) {
    val shown: List<ApkRow> get() = rows.take(visible)
    val hasMore: Boolean get() = visible < rows.size

    companion object { const val PAGE = 10 }
}

sealed interface AllApksEvent {
    data class Snack(val message: Int, val args: List<Any> = emptyList()) : AllApksEvent
    data class OpenSettings(val intent: Intent) : AllApksEvent
}

/**
 * Every ARM64 build of the recent projects' GitHub repos, newest first. Rows only carry the app name
 * and "arm64"; the repo is an implementation detail.
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
    private val notifier = GhNotifier(appContext)
    private var transferJob: Job? = null

    init {
        ArmBuildWatch.schedule(appContext)
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val recents = projects.recents.first().take(MAX_PROJECTS)
                val repos = coroutineScope {
                    recents.map { async(Dispatchers.IO) { resolver.resolve(it.file.path) } }.awaitAll()
                }.mapNotNull { (it as? GitHubResolution.Ready)?.repo }.distinctBy { "${it.owner}/${it.name}".lowercase() }

                val rows = coroutineScope {
                    repos.map { repo ->
                        async {
                            try {
                                repoFactory(repo).artifacts(1).items
                                    .filter { ApkResolver.isArm64(it.name) }
                                    .map { toRow(it, repo) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: GitHubError) {
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }.sortedWith(compareByDescending<ApkRow> { it.artifact.createdAt ?: Instant.EPOCH }.thenByDescending { it.artifact.id })

                rows.groupBy { it.repo }.forEach { (repo, list) -> ArmBuildWatch.markSeen(appContext, repo, list.maxOf { it.artifact.id }) }
                _state.update {
                    it.copy(loading = false, rows = rows, visible = AllApksState.PAGE, error = if (repos.isEmpty()) R.string.apks_no_repos else null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = R.string.apks_load_failed) }
            }
        }
    }

    fun showMore() = _state.update { it.copy(visible = it.visible + AllApksState.PAGE) }

    private fun toRow(a: GhArtifact, repo: GhRepoRef): ApkRow {
        return ApkRow(a, repo, apkAppName(a.name).ifEmpty { repo.name.replaceFirstChar { it.uppercase() } }, a.isExpiredAt(Instant.now()))
    }

    // ---- install -------------------------------------------------------------------------

    fun install(row: ApkRow) {
        if (_state.value.busyId != null || row.expired) return
        _state.update { it.copy(busyId = row.artifact.id, progress = 0f) }
        transferJob = GhBackground.scope.launch {
            GhBackground.begin(appContext)
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
                            _state.update { it.copy(progress = p) }
                            notifier.updateLive(title, p?.let { v -> appContext.getString(R.string.gh_notif_download_progress, (v * 100).toInt()) }.orEmpty(), p?.let { v -> (v * 100).toInt() })
                        }
                    }
                    downloader.extract(zip, row.artifact)
                }
                val inspected = withContext(Dispatchers.IO) { inspector.inspect(apk) }
                when {
                    inspected == null -> {
                        apk.delete()
                        _events.tryEmit(AllApksEvent.Snack(R.string.gh_apk_invalid))
                    }
                    InstallDecision.isBlocked(inspected.kind) -> _events.tryEmit(AllApksEvent.Snack(R.string.apks_downgrade_blocked))
                    !installer.canRequestInstalls() -> _events.tryEmit(AllApksEvent.OpenSettings(installer.unknownSourcesIntent()))
                    else -> {
                        notifier.result(
                            GhNotifier.KIND_DOWNLOAD, appContext.getString(R.string.gh_notif_download_done_title),
                            appContext.getString(R.string.gh_notif_download_done_text, row.artifact.name),
                        )
                        installer.install(apk)
                    }
                }
            } catch (e: CancellationException) {
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
                _state.update { it.copy(busyId = null, progress = null) }
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { downloader.cleanOld() }
                GhBackground.end(appContext)
            }
        }
    }

    fun cancel() { transferJob?.cancel() }

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
