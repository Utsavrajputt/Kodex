package com.invictus.kodex.github.notify

import android.content.Context
import android.os.SystemClock
import com.invictus.kodex.KodexApp
import com.invictus.kodex.R
import com.invictus.kodex.github.api.GhRepoRef
import com.invictus.kodex.github.api.GhRun
import com.invictus.kodex.github.api.GitHubError
import com.invictus.kodex.github.api.RunDisplayStatus
import com.invictus.kodex.github.data.ApkResolver
import com.invictus.kodex.github.ui.apkBuildName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches a queued / running workflow run and notifies the moment it finishes ("Build #N complete,
 * <app>-arm64-<sha> is ready to download"). Polls every few seconds from the process-wide
 * [GhBackground] scope (kept alive by the foreground service while a run is watched), so it does not
 * depend on the slow periodic [ArmBuildWatch] job.
 */
object RunCompletionWatch {
    private const val POLL_MS = 15_000L
    private const val MAX_WATCH_MS = 45L * 60 * 1000
    private const val MAX_POLL_FAILURES = 8

    private val watching: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Idempotent: calling it again for a run that is already watched (or finished) does nothing. */
    fun watch(context: Context, repoRef: GhRepoRef, run: GhRun, projectPath: String? = null) {
        if (!run.isActive || !watching.add(run.id)) return
        val app = context.applicationContext
        GhBackground.scope.launch(Dispatchers.IO) {
            GhBackground.begin(app)
            val notifier = GhNotifier(app)
            notifier.updateLive(
                app.getString(R.string.gh_notif_run_title, run.runNumber),
                app.getString(R.string.gh_notif_run_text),
                null,
            )
            try {
                val repo = (app as KodexApp).container.gitHubRepository(repoRef)
                val deadline = SystemClock.elapsedRealtime() + MAX_WATCH_MS
                var failures = 0
                while (isActive && SystemClock.elapsedRealtime() < deadline) {
                    delay(POLL_MS)
                    val latest = try {
                        repo.run(run.id).also { failures = 0 }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: GitHubError) {
                        if (++failures >= MAX_POLL_FAILURES) break
                        continue
                    }
                    if (!latest.isCompleted) continue
                    finished(app, repo, repoRef, latest, notifier, projectPath)
                    break
                }
            } finally {
                watching.remove(run.id)
                GhBackground.end(app)
            }
        }
    }

    private suspend fun finished(
        app: Context,
        repo: com.invictus.kodex.github.data.GitHubRepository,
        repoRef: GhRepoRef,
        run: GhRun,
        notifier: GhNotifier,
        projectPath: String?,
    ) {
        when (run.display) {
            RunDisplayStatus.Success -> {
                val now = Instant.now()
                val artifact = try {
                    repo.artifacts(1).items.filter { ApkResolver.isArm64(it.name) && !it.isExpiredAt(now) }.maxByOrNull { it.id }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GitHubError) {
                    null
                }
                // The periodic watcher must not announce this same build a second time.
                artifact?.let { ArmBuildWatch.markSeen(app, repoRef, it.id) }
                notifier.buildDone(
                    run.id,
                    app.getString(R.string.gh_notif_done_title, run.runNumber),
                    artifact?.let { app.getString(R.string.gh_notif_done_text, apkBuildName(it.name, it.shortSha)) }
                        ?: app.getString(R.string.gh_notif_done_text_plain),
                    ok = true,
                    projectPath = projectPath,
                )
            }
            RunDisplayStatus.Failed -> notifier.buildDone(
                run.id,
                app.getString(R.string.gh_notif_failed_title, run.runNumber),
                app.getString(R.string.gh_notif_failed_text),
                ok = false,
                projectPath = projectPath,
            )
            else -> Unit // cancelled / skipped: the user already knows
        }
    }
}
