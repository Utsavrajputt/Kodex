package com.invictus.kodex.github.api

import org.json.JSONObject
import java.time.Instant

/** What the pull request list filters by. Merged / Closed / Draft / Mine are narrowed on the phone after the fetch. */
enum class PullFilter(val apiState: String) {
    Open("open"), Closed("closed"), Merged("closed"), All("all"), Draft("open"), Mine("all");

    fun matches(p: GhPull, login: String?): Boolean = when (this) {
        Open -> p.isOpen
        Closed -> !p.isOpen && !p.merged
        Merged -> p.merged
        All -> true
        Draft -> p.isOpen && p.draft
        Mine -> login == null || p.author.equals(login, ignoreCase = true)
    }
}

enum class PullMergeMethod(val api: String) { Merge("merge"), Squash("squash"), Rebase("rebase") }

enum class PullDisplay { Open, Draft, Merged, Closed }

data class GhPull(
    val number: Int,
    val title: String,
    val state: String,
    val merged: Boolean,
    val draft: Boolean,
    val author: String,
    val headRef: String,
    val baseRef: String,
    val headSha: String,
    /** `owner/name` of the head repository; null once a fork was deleted. */
    val headRepo: String?,
    val baseRepo: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val mergedAt: Instant?,
    val htmlUrl: String,
    val body: String,
    val comments: Int,
    val commits: Int,
    val additions: Int,
    val deletions: Int,
    val changedFiles: Int,
    /** null = GitHub has not computed it yet. */
    val mergeable: Boolean?,
    val mergeableState: String,
) {
    val isOpen: Boolean get() = state == "open"
    val display: PullDisplay
        get() = when {
            merged -> PullDisplay.Merged
            !isOpen -> PullDisplay.Closed
            draft -> PullDisplay.Draft
            else -> PullDisplay.Open
        }

    /** The head branch lives in this same repository (so it can be deleted after a merge). */
    val canDeleteBranch: Boolean get() = headRepo != null && headRepo == baseRepo

    companion object {
        fun fromJson(j: JSONObject): GhPull {
            val head = j.optJSONObject("head")
            val base = j.optJSONObject("base")
            return GhPull(
                number = j.getInt("number"),
                title = j.optString("title"),
                state = j.optString("state"),
                merged = !j.isNull("merged_at") || j.optBoolean("merged"),
                draft = j.optBoolean("draft"),
                author = j.optJSONObject("user")?.str("login").orEmpty(),
                headRef = head?.str("ref").orEmpty(),
                baseRef = base?.str("ref").orEmpty(),
                headSha = head?.str("sha").orEmpty(),
                headRepo = head?.optJSONObject("repo")?.str("full_name"),
                baseRepo = base?.optJSONObject("repo")?.str("full_name"),
                createdAt = parseInstant(j.str("created_at")),
                updatedAt = parseInstant(j.str("updated_at")),
                mergedAt = parseInstant(j.str("merged_at")),
                htmlUrl = j.optString("html_url"),
                body = j.str("body").orEmpty(),
                comments = j.optInt("comments") + j.optInt("review_comments"),
                commits = j.optInt("commits"),
                additions = j.optInt("additions"),
                deletions = j.optInt("deletions"),
                changedFiles = j.optInt("changed_files"),
                mergeable = if (j.isNull("mergeable")) null else j.optBoolean("mergeable"),
                mergeableState = j.optString("mergeable_state"),
            )
        }
    }
}

data class GhPullCommit(
    val sha: String,
    val message: String,
    val author: String,
    val date: Instant?,
) {
    val shortSha: String get() = sha.take(7)
    val title: String get() = message.lineSequence().firstOrNull().orEmpty()

    companion object {
        fun fromJson(j: JSONObject): GhPullCommit {
            val c = j.optJSONObject("commit")
            val a = c?.optJSONObject("author")
            return GhPullCommit(
                sha = j.optString("sha"),
                message = c?.optString("message").orEmpty(),
                author = j.optJSONObject("author")?.str("login") ?: a?.str("name").orEmpty(),
                date = parseInstant(a?.str("date")),
            )
        }
    }
}

data class GhPullFile(
    val filename: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
    /** Unified diff hunks; null for binary files and diffs GitHub considers too large. */
    val patch: String?,
) {
    companion object {
        fun fromJson(j: JSONObject) = GhPullFile(
            filename = j.optString("filename"),
            status = j.optString("status"),
            additions = j.optInt("additions"),
            deletions = j.optInt("deletions"),
            patch = j.str("patch"),
        )
    }
}
