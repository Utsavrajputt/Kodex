package com.invictus.kodex.github.data

import com.invictus.kodex.github.api.GhArtifact
import com.invictus.kodex.github.api.GhDeployment
import com.invictus.kodex.github.api.GhJob
import com.invictus.kodex.github.api.GhRepoRef
import com.invictus.kodex.github.api.GhRun
import com.invictus.kodex.github.api.GhWorkflow
import com.invictus.kodex.github.api.GitHubApi
import com.invictus.kodex.github.api.GitHubError
import com.invictus.kodex.github.api.Page
import com.invictus.kodex.github.api.RateLimit
import java.io.File

/** What the Runs section filters by (plan 6.1 card 4). */
enum class RunStatusFilter(val apiStatus: String?) {
    All(null), Success("success"), Running("in_progress"), Failed("failure"), Cancelled("cancelled")
}

/** Result of loading a run's logs (plan 6.3 / 8.1). */
data class RunLog(
    val jobs: List<GhJob>,
    val job: GhJob?,
    /** Whole log on disk; null when the run has no jobs. Owned by the caller (delete when done). */
    val file: File?,
    /** The log was larger than the download cap and its start was dropped. */
    val truncatedOnDisk: Boolean,
    val excerpt: String,
)

/** Domain operations on top of [GitHubApi] (plan section 3). */
class GitHubRepository(
    private val api: GitHubApi,
    private val repo: GhRepoRef,
    private val logDir: File,
) {
    val rateLimit: RateLimit? get() = api.rateLimit

    suspend fun repoInfo(): GhRepoRef = api.getRepo()
    suspend fun workflows(): List<GhWorkflow> = api.listWorkflows()

    suspend fun runs(page: Int, filter: RunStatusFilter, workflowId: Long?): Page<GhRun> =
        api.listRuns(page = page, status = filter.apiStatus, workflowId = workflowId)

    suspend fun run(runId: Long): GhRun = api.getRun(runId)
    suspend fun jobs(runId: Long): List<GhJob> = api.listJobs(runId)

    suspend fun cancel(runId: Long) = api.cancel(runId)
    suspend fun forceCancel(runId: Long) = api.forceCancel(runId)
    suspend fun rerunAll(runId: Long) = api.rerun(runId)
    suspend fun rerunFailed(runId: Long) = api.rerunFailedJobs(runId)
    suspend fun delete(runId: Long) = api.deleteRun(runId)

    // ---- M16: dispatch ---------------------------------------------------------------

    suspend fun branches(page: Int): Page<String> = api.listBranches(page)

    suspend fun tags(): List<String> = api.listTags()

    suspend fun workflowFile(workflow: GhWorkflow, ref: String?): String = api.getWorkflowFileText(workflow.path, ref)

    suspend fun dispatch(workflowId: Long, ref: String, inputs: Map<String, String>) =
        api.dispatch(workflowId, ref, inputs)

    /** Latest dispatch-triggered runs of [workflowId] on [ref]; used to spot the run a dispatch created. */
    suspend fun dispatchRuns(workflowId: Long, ref: String): List<GhRun> =
        api.listRuns(page = 1, perPage = 5, workflowId = workflowId, event = "workflow_dispatch", branch = ref).items

    // ---- M16: artifacts --------------------------------------------------------------

    suspend fun artifacts(page: Int): Page<GhArtifact> = api.listArtifacts(page)

    suspend fun downloadArtifact(artifact: GhArtifact, dest: File, keepPartial: Boolean, onProgress: (Long) -> Unit) =
        api.downloadArtifactZip(artifact, dest, MAX_ARTIFACT_BYTES, keepPartial, onProgress)

    suspend fun deleteArtifact(id: Long) = api.deleteArtifact(id)

    // ---- M16: deployments ------------------------------------------------------------

    suspend fun deployments(page: Int): Page<GhDeployment> = api.listDeployments(page)

    /** Latest status per deployment id; ids whose lookup fails are simply missing. */
    suspend fun deploymentStates(ids: List<Long>): Map<Long, String?> {
        val out = LinkedHashMap<Long, String?>()
        for (id in ids) {
            try {
                out[id] = api.latestDeploymentState(id)
            } catch (e: GitHubError.Offline) {
                throw e
            } catch (e: GitHubError) {
                // best-effort decoration only
            }
        }
        return out
    }

    /** GitHub refuses to delete an active deployment (422): mark it inactive first, then retry once. */
    suspend fun deleteDeployment(id: Long) {
        try {
            api.deleteDeployment(id)
        } catch (e: GitHubError.Validation) {
            api.setDeploymentInactive(id)
            api.deleteDeployment(id)
        }
    }

    // ---- M16: cleanup scans (plan 8.6: 100 per page, bounded) ----------------------------

    suspend fun scanCompletedRuns(maxPages: Int = SCAN_MAX_PAGES): List<GhRun> =
        scan(maxPages) { api.listRuns(page = it, perPage = 100, status = "completed") }

    suspend fun scanArtifacts(maxPages: Int = SCAN_MAX_PAGES): List<GhArtifact> =
        scan(maxPages) { api.listArtifacts(page = it, perPage = 100) }

    suspend fun scanDeployments(maxPages: Int = SCAN_MAX_PAGES): List<GhDeployment> =
        scan(maxPages) { api.listDeployments(page = it, perPage = 100) }

    private suspend fun <T> scan(maxPages: Int, fetch: suspend (Int) -> Page<T>): List<T> {
        val all = ArrayList<T>()
        var page = 1
        while (page <= maxPages) {
            val result = fetch(page)
            all += result.items
            if (!result.hasMore) break
            page++
        }
        return all
    }

    /**
     * Fetches the log of [jobId] (default: first failed job, else the last job) to a temp file and
     * builds the error excerpt. Throws a GitHubError (404/410 = logs expired).
     */
    suspend fun loadRunLog(run: GhRun, workflowName: String, jobId: Long? = null): RunLog {
        val jobs = api.listJobs(run.id)
        val header = LogExtractor.Header(repo.fullName, workflowName, run.runNumber, run.branch, run.shortSha, null)
        if (jobs.isEmpty()) {
            return RunLog(jobs, null, null, false, LogExtractor.noJobsText(header, run.name, run.conclusion))
        }
        val job = jobs.firstOrNull { it.id == jobId } ?: jobs.firstOrNull { it.isFailed } ?: jobs.last()
        logDir.mkdirs()
        LogFiles.cleanupDir(logDir)
        val file = File(logDir, "job-${job.id}-${System.nanoTime()}.log")
        val truncated = api.downloadJobLog(job.id, file, LogFiles.MAX_DOWNLOAD_BYTES)
        val tail = LogFiles.readTail(file)
        val excerpt = LogExtractor.excerpt(tail.lines, header.copy(failedStep = job.failedStepName))
        return RunLog(jobs, job, file, truncated, excerpt)
    }

    companion object {
        /** Artifact zips above this are refused (an APK is capped lower after extraction). */
        const val MAX_ARTIFACT_BYTES = 320L * 1024 * 1024
        private const val SCAN_MAX_PAGES = 10
    }
}
