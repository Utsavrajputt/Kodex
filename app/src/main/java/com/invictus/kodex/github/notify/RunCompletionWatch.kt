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
import com.invictus.kodex.github.ui.RunProgress
import com.invictus.kodex.github.ui.StepState
import com.invictus.kodex.github.ui.apkBuildName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
    private const val POLL_MS = 10_000L
    private const val MAX_WATCH_MS = 45L * 60 * 1000
    private const val MAX_POLL_FAILURES = 8

    private val watching: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val jobs = ConcurrentHashMap<Long, Job>()

    /** Ends every running watch (the notification's Stop button). */
    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
    }

    /** When the run started (queued time while it still waits): drives the notification's elapsed-time chronometer. */
    private fun startedMs(run: GhRun): Long? = (run.startedAt ?: run.createdAt)?.toEpochMilli()

    /** Idempotent: calling it again for a run that is already watched (or finished) does nothing. */
    fun watch(context: Context, repoRef: GhRepoRef, run: GhRun, projectPath: String? = null) {
        if (!run.isActive || !watching.add(run.id)) return
        val app = context.applicationContext
        val title = app.getString(R.string.gh_notif_run_title, run.runNumber)
        val job = GhBackground.scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val notifier = GhNotifier(app)
            // Post the real text first, so the foreground service that starts next shows it instead of a generic one.
            notifier.updateLive(title, app.getString(R.string.gh_notif_run_waiting, repoRef.name), null, projectPath, run.id, stoppable = true, startedAtMs = startedMs(run))
            GhBackground.begin(app)
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
                    if (!latest.isCompleted) {
                        val progress = try {
                            RunProgress.of(repo.jobs(run.id))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: GitHubError) {
                            null
                        }
                        if (progress != null && progress.hasSteps) {
                            val step = progress.steps.firstOrNull { it.state == StepState.Current }?.name
                                ?: progress.steps.firstOrNull { it.state == StepState.Pending }?.name
                            notifier.updateLive(
                                title, listOfNotNull(repoRef.name, step).joinToString(" \u2022 "),
                                progress.percent, projectPath, run.id, stoppable = true, startedAtMs = startedMs(latest),
                            )
                        }
                        continue
                    }
                    finished(app, repo, repoRef, latest, notifier, projectPath)
                    break
                }
            } finally {
                watching.remove(run.id)
                jobs.remove(run.id)
                GhBackground.end(app)
            }
        }
        jobs[run.id] = job
        job.start()
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
