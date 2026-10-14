package com.invictus.kodex.github.api

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One suspend function per endpoint (plan section 7.1/7.2). Returns models or throws [GitHubError]. */
class GitHubApi(private val http: GitHubHttp, private val repo: GhRepoRef) {

    val rateLimit: RateLimit? get() = http.rateLimit

    private val base get() = "/repos/${repo.owner}/${repo.name}"

    /** `GET /repos/{o}/{r}`: default branch etc. */
    suspend fun getRepo(): GhRepoRef {
        val j = JSONObject(http.getJson(base))
        return repo.copy(defaultBranch = j.str("default_branch"))
    }

    /** `GET /repos/{o}/{r}/actions/workflows` (first 100). */
    suspend fun listWorkflows(): List<GhWorkflow> {
        val arr = JSONObject(http.getJson("$base/actions/workflows", mapOf("per_page" to "100"))).optJSONArray("workflows")
        return buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val w = arr.getJSONObject(i)
                add(GhWorkflow(w.getLong("id"), w.optString("name"), w.optString("path"), w.optString("state")))
            }
        }
    }

    /**
     * `GET /repos/{o}/{r}/actions/runs` or the workflow-scoped variant. [status] accepts both
     * status and conclusion values (`in_progress`, `failure`, `cancelled`, ...).
     */
    suspend fun listRuns(
        page: Int,
        perPage: Int = 30,
        status: String? = null,
        workflowId: Long? = null,
        event: String? = null,
        branch: String? = null,
    ): Page<GhRun> {
        val path = if (workflowId != null) "$base/actions/workflows/$workflowId/runs" else "$base/actions/runs"
        val q = buildMap {
            put("per_page", perPage.toString()); put("page", page.toString())
            if (status != null) put("status", status)
            if (event != null) put("event", event)
            if (branch != null) put("branch", branch)
        }
        val j = JSONObject(http.getJson(path, q))
        val arr = j.optJSONArray("workflow_runs")
        val items = buildList { if (arr != null) for (i in 0 until arr.length()) add(GhRun.fromJson(arr.getJSONObject(i))) }
        val total = j.optInt("total_count")
        return Page(items, page, hasMore = page * perPage < total && items.isNotEmpty(), total = total)
    }

    suspend fun getRun(runId: Long): GhRun = GhRun.fromJson(JSONObject(http.getJson("$base/actions/runs/$runId")))

    /** `GET .../runs/{id}/jobs?filter=latest` */
    suspend fun listJobs(runId: Long): List<GhJob> {
        val arr = JSONObject(http.getJson("$base/actions/runs/$runId/jobs", mapOf("filter" to "latest", "per_page" to "100")))
            .optJSONArray("jobs")
        return buildList { if (arr != null) for (i in 0 until arr.length()) add(GhJob.fromJson(arr.getJSONObject(i))) }
    }

    /** `GET .../jobs/{id}/logs` (302 -> plain text) streamed to [dest]. True when truncated at [maxBytes]. */
    suspend fun downloadJobLog(jobId: Long, dest: File, maxBytes: Long): Boolean =
        http.downloadText("$base/actions/jobs/$jobId/logs", dest, maxBytes)

    suspend fun cancel(runId: Long) { http.send("POST", "$base/actions/runs/$runId/cancel") }

    suspend fun forceCancel(runId: Long) { http.send("POST", "$base/actions/runs/$runId/force-cancel") }

    suspend fun rerun(runId: Long) { http.send("POST", "$base/actions/runs/$runId/rerun") }

    suspend fun rerunFailedJobs(runId: Long) { http.send("POST", "$base/actions/runs/$runId/rerun-failed-jobs") }

    suspend fun deleteRun(runId: Long) { http.send("DELETE", "$base/actions/runs/$runId") }

    // ---- M16 (plan 7.1, 7.3, 7.4, 7.5) ------------------------------------------------

    /** `GET /repos/{o}/{r}/branches` (names only). */
    suspend fun listBranches(page: Int, perPage: Int = 100): Page<String> {
        val arr = JSONArray(http.getJson("$base/branches", mapOf("per_page" to perPage.toString(), "page" to page.toString())))
        val names = buildList { for (i in 0 until arr.length()) add(arr.getJSONObject(i).optString("name")) }
        return Page(names, page, hasMore = names.size == perPage, total = names.size)
    }

    /** `GET /repos/{o}/{r}/tags` (names only, newest 100): feeds release-tag suggestions. */
    suspend fun listTags(): List<String> {
        val arr = JSONArray(http.getJson("$base/tags", mapOf("per_page" to "100")))
        return buildList { for (i in 0 until arr.length()) add(arr.getJSONObject(i).optString("name")) }
    }

    /** `GET /repos/{o}/{r}/contents/{path}?ref=` as raw text (workflow YAML). */
    suspend fun getWorkflowFileText(path: String, ref: String?): String =
        http.getJson(
            "$base/contents/$path",
            buildMap { if (ref != null) put("ref", ref) },
            accept = RAW_ACCEPT,
        )

    /** `POST .../workflows/{id}/dispatches`: 204 with no body. Inputs are always strings. */
    suspend fun dispatch(workflowId: Long, ref: String, inputs: Map<String, String>) {
        val body = JSONObject().put("ref", ref)
        if (inputs.isNotEmpty()) body.put("inputs", JSONObject(inputs))
        http.send("POST", "$base/actions/workflows/$workflowId/dispatches", body)
    }

    /** `GET /repos/{o}/{r}/actions/artifacts` (newest first). */
    suspend fun listArtifacts(page: Int, perPage: Int = 100): Page<GhArtifact> {
        val j = JSONObject(http.getJson("$base/actions/artifacts", mapOf("per_page" to perPage.toString(), "page" to page.toString())))
        val arr = j.optJSONArray("artifacts")
        val items = buildList { if (arr != null) for (i in 0 until arr.length()) add(GhArtifact.fromJson(arr.getJSONObject(i))) }
        val total = j.optInt("total_count")
        return Page(items, page, hasMore = page * perPage < total && items.isNotEmpty(), total = total)
    }

    /** `GET .../artifacts/{id}/zip` (302 -> zip) streamed to [dest]; 410 = expired. */
    suspend fun downloadArtifactZip(artifact: GhArtifact, dest: File, maxBytes: Long, keepPartial: Boolean, onProgress: (Long) -> Unit) =
        http.downloadBinary("$base/actions/artifacts/${artifact.id}/zip", dest, maxBytes, artifact.sizeBytes, keepPartial, onProgress)

    suspend fun deleteArtifact(id: Long) { http.send("DELETE", "$base/actions/artifacts/$id") }

    /** `GET /repos/{o}/{r}/deployments`. The endpoint returns a bare array. */
    suspend fun listDeployments(page: Int, perPage: Int = 30): Page<GhDeployment> {
        val arr = JSONArray(http.getJson("$base/deployments", mapOf("per_page" to perPage.toString(), "page" to page.toString())))
        val items = buildList { for (i in 0 until arr.length()) add(GhDeployment.fromJson(arr.getJSONObject(i))) }
        return Page(items, page, hasMore = items.size == perPage, total = items.size)
    }

    /** Latest status `state` of a deployment, or null when it has none. */
    suspend fun latestDeploymentState(id: Long): String? {
        val arr = JSONArray(http.getJson("$base/deployments/$id/statuses", mapOf("per_page" to "1")))
        return if (arr.length() == 0) null else arr.getJSONObject(0).str("state")
    }

    suspend fun setDeploymentInactive(id: Long) {
        http.send("POST", "$base/deployments/$id/statuses", JSONObject().put("state", "inactive"))
    }

    suspend fun deleteDeployment(id: Long) { http.send("DELETE", "$base/deployments/$id") }

    companion object {
        const val RAW_ACCEPT = "application/vnd.github.raw+json"
    }
}
