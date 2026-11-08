package com.invictus.kodex.github.data

import com.invictus.kodex.github.api.GhArtifact
import com.invictus.kodex.github.api.GhDeployment
import com.invictus.kodex.github.api.GhRun
import com.invictus.kodex.github.api.GitHubError
import com.invictus.kodex.github.api.RunDisplayStatus
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class CleanupKind { Runs, Deployments, Artifacts }

/** Filters of plan 8.6. Flags that do not apply to a [kind] are ignored for it. */
data class CleanupFilters(
    val kind: CleanupKind = CleanupKind.Runs,
    /** Runs: include failed runs (on by default: failed runs are what people clean up). With [cancelled] also set the two are OR-ed; neither = all completed. */
    val failed: Boolean = true,
    val cancelled: Boolean = false,
    /** Runs only. */
    val workflowId: Long? = null,
    val olderThanDays: Int? = null,
    /** Always keep the newest N items of the kind (within the workflow filter for runs). */
    val keepLatest: Int? = null,
    /** Artifacts only. */
    val expiredOnly: Boolean = false,
)

data class CleanupItem(
    val id: Long,
    val kind: CleanupKind,
    val title: String,
    val subtitle: String,
    val sizeBytes: Long,
    val createdAt: Instant?,
    /** The newest successful build: listed but never pre-selected (plan 8.6). */
    val isLatestBuild: Boolean,
)

data class CleanupPlan(val filters: CleanupFilters, val items: List<CleanupItem>) {
    /** Everything except the latest build. */
    val defaultSelection: Set<Long> get() = items.filterNot { it.isLatestBuild }.map { it.id }.toSet()
}

/** Builds [CleanupPlan]s client-side over already-fetched lists. Pure; the clock is a parameter. */
object CleanupPlanner {

    fun planRuns(
        runs: List<GhRun>,
        filters: CleanupFilters,
        now: Instant,
        workflowName: (GhRun) -> String,
        artifactBytesByRun: Map<Long, Long> = emptyMap(),
    ): CleanupPlan {
        val scoped = runs.filter { it.isCompleted && (filters.workflowId == null || it.workflowId == filters.workflowId) }
        val latestSuccessId = runs
            .filter { it.display == RunDisplayStatus.Success }
            .maxWithOrNull(compareBy<GhRun>({ it.createdAt ?: Instant.EPOCH }, { it.id }))?.id
        val keep = newestIds(scoped, filters.keepLatest) { it.createdAt to it.id }
        val cutoff = cutoff(now, filters.olderThanDays)

        val picked = scoped.filter { r ->
            r.id !in keep &&
                statusMatches(r, filters) &&
                (cutoff == null || (r.createdAt != null && r.createdAt.isBefore(cutoff)))
        }.sortedWith(compareByDescending<GhRun> { it.createdAt ?: Instant.EPOCH }.thenByDescending { it.id })

        return CleanupPlan(
            filters,
            picked.map {
                CleanupItem(
                    id = it.id,
                    kind = CleanupKind.Runs,
                    title = "#${it.runNumber} ${workflowName(it)}",
                    subtitle = listOf(it.branch, it.shortSha, it.conclusion ?: it.status).filter { p -> p.isNotBlank() }.joinToString(" • "),
                    sizeBytes = artifactBytesByRun[it.id] ?: 0L,
                    createdAt = it.createdAt,
                    isLatestBuild = it.id == latestSuccessId,
                )
            },
        )
    }

    fun planArtifacts(artifacts: List<GhArtifact>, filters: CleanupFilters, now: Instant): CleanupPlan {
        // "Latest build" = every artifact of the newest run that still has a live artifact.
        val latestRunId = artifacts
            .filter { !it.isExpiredAt(now) && it.runId != null }
            .maxWithOrNull(compareBy<GhArtifact>({ it.createdAt ?: Instant.EPOCH }, { it.id }))?.runId
        val keep = newestIds(artifacts, filters.keepLatest) { it.createdAt to it.id }
        val cutoff = cutoff(now, filters.olderThanDays)

        val picked = artifacts.filter { a ->
            a.id !in keep &&
                (!filters.expiredOnly || a.isExpiredAt(now)) &&
                (cutoff == null || (a.createdAt != null && a.createdAt.isBefore(cutoff)))
        }.sortedWith(compareByDescending<GhArtifact> { it.createdAt ?: Instant.EPOCH }.thenByDescending { it.id })

        return CleanupPlan(
            filters,
            picked.map {
                CleanupItem(
                    id = it.id,
                    kind = CleanupKind.Artifacts,
                    title = it.name,
                    subtitle = listOf(it.branch.orEmpty(), if (it.isExpiredAt(now)) "expired" else "").filter { p -> p.isNotBlank() }.joinToString(" • "),
                    sizeBytes = it.sizeBytes,
                    createdAt = it.createdAt,
                    isLatestBuild = it.runId != null && it.runId == latestRunId && !it.isExpiredAt(now),
                )
            },
        )
    }

    fun planDeployments(deployments: List<GhDeployment>, filters: CleanupFilters, now: Instant): CleanupPlan {
        val keep = newestIds(deployments, filters.keepLatest) { it.createdAt to it.id }
        val cutoff = cutoff(now, filters.olderThanDays)
        val picked = deployments.filter { d ->
            d.id !in keep && (cutoff == null || (d.createdAt != null && d.createdAt.isBefore(cutoff)))
        }.sortedWith(compareByDescending<GhDeployment> { it.createdAt ?: Instant.EPOCH }.thenByDescending { it.id })

        return CleanupPlan(
            filters,
            picked.map {
                CleanupItem(
                    id = it.id,
                    kind = CleanupKind.Deployments,
                    title = it.environment.ifBlank { "deployment" },
                    subtitle = listOf(it.ref, it.shortSha, it.latestState.orEmpty()).filter { p -> p.isNotBlank() }.joinToString(" • "),
                    sizeBytes = 0L,
                    createdAt = it.createdAt,
                    isLatestBuild = false,
                )
            },
        )
    }

    private fun statusMatches(r: GhRun, f: CleanupFilters): Boolean {
        if (!f.failed && !f.cancelled) return true
        return (f.failed && r.display == RunDisplayStatus.Failed) || (f.cancelled && r.display == RunDisplayStatus.Cancelled)
    }

    private fun cutoff(now: Instant, days: Int?): Instant? = days?.let { now.minus(it.toLong(), ChronoUnit.DAYS) }

    private fun <T> newestIds(items: List<T>, n: Int?, key: (T) -> Pair<Instant?, Long>): Set<Long> {
        if (n == null || n <= 0) return emptySet()
        return items
            .sortedWith(compareByDescending<T> { key(it).first ?: Instant.EPOCH }.thenByDescending { key(it).second })
            .take(n)
            .map { key(it).second }
            .toSet()
    }
}

data class CleanupFailure(val item: CleanupItem, val reason: String)

data class CleanupResult(
    val deleted: Int,
    val failed: List<CleanupFailure>,
    /** Left untouched because the run was cancelled or stopped by the rate limit. */
    val notAttempted: List<CleanupItem> = emptyList(),
)

/**
 * Sequential, rate-limit-aware deleter (plan 7.7 / 8.6). One call at a time with [spacingMs]
 * between mutating calls. A 404 counts as already deleted. Stops gracefully via [shouldStop].
 */
class CleanupRunner(
    private val spacingMs: Long = 400L,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
    private val maxRateWaitSec: Long = 90L,
) {
    suspend fun run(
        items: List<CleanupItem>,
        shouldStop: () -> Boolean,
        delete: suspend (CleanupItem) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit,
    ): CleanupResult {
        var deleted = 0
        val failed = mutableListOf<CleanupFailure>()
        var index = 0
        onProgress(0, items.size)
        while (index < items.size && !shouldStop()) {
            val item = items[index]
            var retried = false
            var consumed = true   // the item was attempted (success or recorded failure)
            var halt = false      // stop the whole run
            while (true) {
                try {
                    delete(item)
                    deleted++
                } catch (e: GitHubError.NotFound) {
                    deleted++ // already gone
                } catch (e: GitHubError.RateLimited) {
                    val wait = e.resetEpochSec?.minus(nowEpochSec())
                    if (!retried && wait != null && wait in 0..maxRateWaitSec) {
                        retried = true
                        sleep((wait + 1) * 1000)
                        if (shouldStop()) { consumed = false; halt = true } else continue
                    } else {
                        failed += CleanupFailure(item, e.message.orEmpty())
                        halt = true
                    }
                } catch (e: GitHubError.Unauthorized) {
                    failed += CleanupFailure(item, e.message.orEmpty())
                    halt = true
                } catch (e: GitHubError) {
                    failed += CleanupFailure(item, e.message.orEmpty())
                }
                break
            }
            if (consumed) index++
            if (halt) break
            onProgress(deleted + failed.size, items.size)
            if (index < items.size && !shouldStop()) sleep(spacingMs)
        }
        return CleanupResult(deleted, failed, items.drop(index))
    }
}
