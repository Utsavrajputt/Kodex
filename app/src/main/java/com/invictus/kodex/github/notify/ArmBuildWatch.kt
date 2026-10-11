package com.invictus.kodex.github.notify

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.invictus.kodex.KodexApp
import com.invictus.kodex.R
import com.invictus.kodex.github.api.GhArtifact
import com.invictus.kodex.github.api.GhRepoRef
import com.invictus.kodex.github.api.GitHubError
import com.invictus.kodex.github.data.ApkResolver
import com.invictus.kodex.github.data.GitHubResolution
import com.invictus.kodex.github.ui.apkTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Tells the user when a new ARM64 build shows up for one of the recent projects' repos.
 * A periodic [JobScheduler] job (no extra dependency, ~every 20 min while the device has network)
 * compares the newest ARM64 artifact per repo with the last one seen. The first run only records a
 * baseline, so nothing is announced for builds that already existed.
 */
object ArmBuildWatch {
    private const val JOB_ID = 7301
    private const val PERIOD_MS = 20L * 60 * 1000
    private const val PREFS = "gh_arm_watch"
    private const val MAX_PROJECTS = 12

    /** Idempotent: safe to call every time a GitHub screen opens. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val js = app.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID) != null) return
        val info = JobInfo.Builder(JOB_ID, ComponentName(app, ArmBuildJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(PERIOD_MS)
            .build()
        runCatching { js.schedule(info) }
    }

    private fun key(repo: GhRepoRef) = "${repo.owner}/${repo.name}".lowercase()

    /** The user has seen builds up to [artifactId] (e.g. by opening the list): do not announce those. */
    fun markSeen(context: Context, repo: GhRepoRef, artifactId: Long) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(repo)
        if (artifactId > p.getLong(k, 0L)) p.edit().putLong(k, artifactId).apply()
    }

    internal suspend fun check(context: Context) {
        val app = context.applicationContext
        val container = (app as KodexApp).container
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val recents = container.projectRepository.recents.first().take(MAX_PROJECTS)
        val repos = coroutineScope {
            recents.map { async(Dispatchers.IO) { container.gitHubProjectResolver.resolve(it.file.path) } }.awaitAll()
        }.mapNotNull { (it as? GitHubResolution.Ready)?.repo }.distinctBy(::key)

        val now = Instant.now()
        for (repo in repos) {
            val newest: GhArtifact = try {
                container.gitHubRepository(repo).artifacts(1).items
                    .filter { ApkResolver.isArm64(it.name) && !it.isExpiredAt(now) }
                    .maxByOrNull { it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                null
            } ?: continue
            val k = key(repo)
            val last = prefs.getLong(k, -1L)
            if (last == -1L) {
                prefs.edit().putLong(k, newest.id).apply() // baseline, no notification
            } else if (newest.id > last) {
                prefs.edit().putLong(k, newest.id).apply()
                GhNotifier(app).newBuild(app.getString(R.string.gh_notif_new_build_title), app.getString(R.string.gh_notif_new_build_text, apkTitle(newest.name)))
            }
        }
    }
}

class ArmBuildJobService : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = GhBackground.scope.launch(Dispatchers.IO) {
            try {
                ArmBuildWatch.check(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort: the next period tries again.
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }
}
