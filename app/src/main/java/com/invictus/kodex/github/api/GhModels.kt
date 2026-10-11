package com.invictus.kodex.github.api

import org.json.JSONObject
import java.time.Instant

/** `owner/repo` bound to a project's GitHub remote. */
data class GhRepoRef(val owner: String, val name: String, val defaultBranch: String? = null) {
    val fullName: String get() = "$owner/$name"
}

data class GhWorkflow(val id: Long, val name: String, val path: String, val state: String)

/** What the UI shows for a run (plan section 4 mapping). */
enum class RunDisplayStatus { Queued, Running, Success, Failed, Cancelled, Skipped }

data class GhRun(
    val id: Long,
    val workflowId: Long,
    val name: String,
    val runNumber: Int,
    val status: String,
    val conclusion: String?,
    val branch: String,
    val sha: String,
    val commitMessage: String,
    val event: String,
    val createdAt: Instant?,
    val startedAt: Instant?,
    val updatedAt: Instant?,
) {
    val display: RunDisplayStatus get() = displayStatus(status, conclusion)
    val shortSha: String get() = sha.take(7)
    val isCompleted: Boolean get() = status == "completed"
    val isActive: Boolean get() = display == RunDisplayStatus.Queued || display == RunDisplayStatus.Running
    val isFailed: Boolean get() = display == RunDisplayStatus.Failed
    val canCancel: Boolean get() = isActive
    val canDelete: Boolean get() = isCompleted
    val canRerun: Boolean get() = isCompleted && (display == RunDisplayStatus.Failed || display == RunDisplayStatus.Cancelled)

    /** Wall-clock duration of a finished run; null while active / unknown. */
    val durationMs: Long?
        get() {
            val s = startedAt ?: createdAt ?: return null
            val e = updatedAt ?: return null
            return if (isCompleted) (e.toEpochMilli() - s.toEpochMilli()).coerceAtLeast(0) else null
        }

    companion object {
        fun fromJson(j: JSONObject): GhRun = GhRun(
            id = j.getLong("id"),
            workflowId = j.optLong("workflow_id"),
            name = (j.str("name") ?: j.str("display_title")).orEmpty(),
            runNumber = j.optInt("run_number"),
            status = j.optString("status"),
            conclusion = j.str("conclusion"),
            branch = j.str("head_branch").orEmpty(),
            sha = j.optString("head_sha"),
            commitMessage = (j.optJSONObject("head_commit")?.str("message") ?: j.str("display_title"))
                .orEmpty().lineSequence().firstOrNull().orEmpty(),
            event = j.optString("event"),
            createdAt = parseInstant(j.str("created_at")),
            startedAt = parseInstant(j.str("run_started_at")),
            updatedAt = parseInstant(j.str("updated_at")),
        )
    }
}

fun displayStatus(status: String, conclusion: String?): RunDisplayStatus = when (status) {
    "queued", "waiting", "pending", "requested" -> RunDisplayStatus.Queued
    "in_progress" -> RunDisplayStatus.Running
    "completed" -> when (conclusion) {
        "success" -> RunDisplayStatus.Success
        "failure", "timed_out", "startup_failure" -> RunDisplayStatus.Failed
        "cancelled" -> RunDisplayStatus.Cancelled
        else -> RunDisplayStatus.Skipped // skipped / neutral / stale / action_required / null
    }
    else -> RunDisplayStatus.Queued
}

data class GhStep(
    val number: Int,
    val name: String,
    val conclusion: String?,
    val status: String = "",
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
) {
    val durationMs: Long?
        get() {
            val st = startedAt ?: return null
            val en = completedAt ?: return null
            return (en.toEpochMilli() - st.toEpochMilli()).coerceAtLeast(0)
        }
}

data class GhJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val steps: List<GhStep>,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
) {
    val durationMs: Long?
        get() {
            val st = startedAt ?: return null
            val en = completedAt ?: return null
            return (en.toEpochMilli() - st.toEpochMilli()).coerceAtLeast(0)
        }
    val isFailed: Boolean get() = conclusion == "failure" || conclusion == "timed_out"
    val failedStepName: String? get() = steps.firstOrNull { it.conclusion == "failure" }?.name

    companion object {
        fun fromJson(j: JSONObject): GhJob {
            val steps = j.optJSONArray("steps")
            return GhJob(
                id = j.getLong("id"),
                name = j.optString("name"),
                status = j.optString("status"),
                conclusion = j.str("conclusion"),
                steps = buildList {
                    if (steps != null) for (i in 0 until steps.length()) {
                        val s = steps.getJSONObject(i)
                        add(
                            GhStep(
                                number = s.optInt("number"),
                                name = s.optString("name"),
                                conclusion = s.str("conclusion"),
                                status = s.optString("status"),
                                startedAt = parseInstant(s.str("started_at")),
                                completedAt = parseInstant(s.str("completed_at")),
                            ),
                        )
                    }
                },
                startedAt = parseInstant(j.str("started_at")),
                completedAt = parseInstant(j.str("completed_at")),
            )
        }
    }
}

/** One page of a paged list. [hasMore] derives from `total_count` (the runs endpoint caps at 1000). */
data class Page<T>(val items: List<T>, val page: Int, val hasMore: Boolean, val total: Int)

/** Last seen `x-ratelimit-*` values. */
data class RateLimit(val remaining: Int, val limit: Int, val resetEpochSec: Long) {
    fun isLow(): Boolean = remaining <= 100
}

internal fun parseInstant(s: String?): Instant? =
    if (s.isNullOrEmpty()) null else runCatching { Instant.parse(s) }.getOrNull()

/** `org.json` on Android turns JSON null into the string "null" in optString; this returns a real null. */
internal fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).ifEmpty { null }
