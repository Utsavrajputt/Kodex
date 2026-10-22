package com.invictus.kodex.github.notify

import android.content.Context
import com.invictus.kodex.KodexApp
import com.invictus.kodex.github.api.GhRepoRef
import com.invictus.kodex.github.api.GitHubError
import com.invictus.kodex.github.data.GitHubRepository
import com.invictus.kodex.github.data.GitHubResolution
import com.invictus.kodex.github.data.RunStatusFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** Fired by the Git screen after a successful push, so the build it triggers can be followed in the background. */
object GitPushEvents {
    private val _pushed = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val pushed: SharedFlow<String> = _pushed.asSharedFlow()

    fun emit(projectPath: String) {
        _pushed.tryEmit(projectPath)
    }
}

/**
 * A push starts a GitHub Actions run a few seconds later. Look for it (without the GitHub screen being open)
 * and hand it to [RunCompletionWatch], which notifies when the build is done, even if the app is in recents.
 */
object RunWatchStarter {
    private const val HANDOFF_MS = 2_000L
    private val RETRY_DELAYS_MS = longArrayOf(4_000, 8_000, 12_000, 20_000, 30_000)

    fun afterPush(context: Context, projectPath: String) {
        val app = context.applicationContext
        val pushedAt = Instant.now().minusSeconds(30)
        GhBackground.scope.launch(Dispatchers.IO) {
            val container = (app as KodexApp).container
            // Not a GitHub project (or no token): nothing to follow, and no notification flash either.
            val ref = (container.gitHubProjectResolver.resolve(projectPath) as? GitHubResolution.Ready)?.repo ?: return@launch
            val repo = container.gitHubRepository(ref)
            // Foreground service right at push time (app still in front): without it Android freezes the
            // process once the app is backgrounded and the retry loop below never gets to notify.
            GhBackground.begin(app)
            try {
                findAndWatch(app, ref, repo, projectPath, pushedAt)
            } finally {
                // A found run's own watch takes over the service; give it a moment so the notification doesn't flicker.
                withContext(NonCancellable) {
                    delay(HANDOFF_MS)
                    GhBackground.end(app)
                }
            }
        }
    }

    private suspend fun findAndWatch(
        app: Context,
        ref: GhRepoRef,
        repo: GitHubRepository,
        projectPath: String,
        pushedAt: Instant,
    ) {
        for (wait in RETRY_DELAYS_MS) {
            delay(wait)
            val active = try {
                repo.runs(1, RunStatusFilter.All, null).items.filter { it.isActive }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                emptyList()
            }
            active.forEach { RunCompletionWatch.watch(app, ref, it, projectPath) }
            if (active.any { (it.createdAt ?: it.startedAt)?.isAfter(pushedAt) == true }) return
        }
    }
}
