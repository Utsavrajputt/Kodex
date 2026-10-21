package com.invictus.kodex.core.git

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.invictus.kodex.core.editor.EditorSettingsStore
import com.invictus.kodex.core.git.model.GitErrorDetails
import com.invictus.kodex.core.git.model.isAuthError
import com.invictus.kodex.core.security.GitCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** What the person asked to clone; kept so the screen can be reopened mid-clone. */
data class GitCloneRequest(val url: String, val parent: String, val folder: String, val branch: String)

/** Live state of the (single) clone, independent of any screen. */
data class GitCloneRun(
    val request: GitCloneRequest? = null,
    val cloning: Boolean = false,
    val cancelling: Boolean = false,
    val completed: Boolean = false,
    val logLines: List<GitCloneLogLine> = emptyList(),
)

/** A result that needs the person's attention the next time a screen is looking. */
sealed interface GitCloneOutcome {
    data class Failed(val error: GitErrorDetails) : GitCloneOutcome
    data class NeedsToken(val host: String) : GitCloneOutcome
}

/**
 * Owns the running clone in an app-level scope, so it survives leaving the Clone screen. While it
 * runs a foreground service keeps the process alive (also when the app is minimized) and shows the
 * live notification.
 */
class GitCloneManager(
    context: Context,
    private val credentialStore: GitCredentialStore,
    private val settingsStore: EditorSettingsStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val appContext = context.applicationContext
    private val notifier = GitCloneNotifier(appContext)

    private val _run = MutableStateFlow(GitCloneRun())
    val run: StateFlow<GitCloneRun> = _run.asStateFlow()

    private val _outcome = MutableStateFlow<GitCloneOutcome?>(null)
    val outcome: StateFlow<GitCloneOutcome?> = _outcome.asStateFlow()

    private val _cancelled = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fires once when a clone was cancelled; only delivered to a screen that is listening right now. */
    val cancelled: SharedFlow<Unit> = _cancelled.asSharedFlow()

    @Volatile private var cancelRequested = false

    /** Starts a clone; false when one is already running. */
    @Synchronized
    fun start(request: GitCloneRequest, target: File, username: String, token: String): Boolean {
        if (_run.value.cloning) return false
        cancelRequested = false
        _outcome.value = null
        _run.value = GitCloneRun(request = request, cloning = true)
        val repo = target.name
        startForegroundWork(repo)
        scope.launch { runClone(request, repo, target, username, token) }
        return true
    }

    /** Safe from any thread: the Cancel button and the notification's Cancel action both land here. */
    fun cancel() {
        if (!_run.value.cloning) return
        cancelRequested = true
        _run.update { it.copy(cancelling = true) }
    }

    fun consumeOutcome() { _outcome.value = null }

    /** Forget a finished/stopped run (never touches one that is still running). */
    fun reset() {
        if (_run.value.cloning) return
        _run.value = GitCloneRun()
        _outcome.value = null
    }

    private suspend fun runClone(request: GitCloneRequest, repo: String, target: File, username: String, token: String) {
        try {
            val result = GitCloner.clone(
                url = request.url,
                username = username,
                token = token,
                directory = target,
                branch = request.branch.ifBlank { null },
                isCancelled = { cancelRequested },
            ) { progress ->
                _run.update { it.copy(logLines = progress.lines) }
                notifier.progress(repo, progress.stage(), progress.overallFraction())
            }
            when (result) {
                is GitResult.Ok -> {
                    if (token.isNotBlank()) {
                        credentialStore.put(GitCredential(normalizeHost(request.url), username, token))
                    }
                    // Only a folder that just worked becomes next time's default.
                    runCatching { settingsStore.setCloneDefaultParent(request.parent) }
                    notifier.finished(repo, target.absolutePath)
                    _run.update { it.copy(completed = true) }
                }
                is GitResult.Err -> {
                    // Don't leave a half-downloaded repo behind.
                    if (target.exists()) target.deleteRecursively()
                    val host = runCatching { normalizeHost(request.url) }.getOrNull()
                    when {
                        cancelRequested -> {
                            _run.value = GitCloneRun()
                            _cancelled.tryEmit(Unit)
                        }
                        host != null && result.error.authFailure.isAuthError() ->
                            _outcome.value = GitCloneOutcome.NeedsToken(host)
                        else -> {
                            notifier.failed(repo, result.error.message)
                            _outcome.value = GitCloneOutcome.Failed(result.error)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (target.exists()) target.deleteRecursively()
            val details = GitErrorFactory.from(t, "Clone failed") { it.message ?: "Unknown error" }
            notifier.failed(repo, details.message)
            _outcome.value = GitCloneOutcome.Failed(details)
        } finally {
            _run.update { it.copy(cloning = false, cancelling = false) }
            notifier.dismiss()
            appContext.stopService(Intent(appContext, GitCloneService::class.java))
        }
    }

    private fun startForegroundWork(repo: String) {
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, GitCloneService::class.java).putExtra(GitCloneService.EXTRA_REPO, repo),
            )
        } catch (_: Exception) {
            // Not allowed right now: the clone still runs, just without process protection.
            notifier.start(repo)
        }
    }
}

/** Keeps the process (and the live notification) alive while a clone runs, even when minimized. */
class GitCloneService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = intent?.getStringExtra(EXTRA_REPO).orEmpty()
        try {
            ServiceCompat.startForeground(
                this,
                GitCloneNotifier.PROGRESS_ID,
                GitCloneNotifier(this).foregroundNotification(repo),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_REPO = "repo"
    }
}
