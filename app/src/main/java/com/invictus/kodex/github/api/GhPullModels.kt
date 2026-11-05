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
    /** Set for renames (`previous_filename`). */
    val previousFilename: String? = null,
) {
    companion object {
        fun fromJson(j: JSONObject) = GhPullFile(
            filename = j.optString("filename"),
            status = j.optString("status"),
            additions = j.optInt("additions"),
            deletions = j.optInt("deletions"),
            patch = j.str("patch"),
            previousFilename = j.str("previous_filename"),
        )
    }
}

/** `GET /repos/{o}/{r}/commits/{sha}`: one commit with its changed files (patches included, up to 300 files). */
data class GhCommitDetail(
    val sha: String,
    val message: String,
    val author: String,
    val authorEmail: String,
    val dateMs: Long,
    val files: List<GhPullFile>,
    val additions: Int,
    val deletions: Int,
) {
    companion object {
        fun fromJson(j: JSONObject): GhCommitDetail {
            val c = j.optJSONObject("commit")
            val a = c?.optJSONObject("author")
            val arr = j.optJSONArray("files")
            val files = buildList { if (arr != null) for (i in 0 until arr.length()) add(GhPullFile.fromJson(arr.getJSONObject(i))) }
            val stats = j.optJSONObject("stats")
            return GhCommitDetail(
                sha = j.optString("sha"),
                message = c?.optString("message").orEmpty(),
                author = j.optJSONObject("author")?.str("login") ?: a?.str("name").orEmpty(),
                authorEmail = a?.str("email").orEmpty(),
                dateMs = parseInstant(a?.str("date"))?.toEpochMilli() ?: 0L,
                files = files,
                additions = stats?.optInt("additions") ?: files.sumOf { it.additions },
                deletions = stats?.optInt("deletions") ?: files.sumOf { it.deletions },
            )
        }
    }
}

/** Result of `GET /compare/{base}...{head}`: what a new pull request would contain. */
data class GhCompare(
    val totalCommits: Int,
    /** Full commit messages, oldest first. */
    val commits: List<String>,
    val files: List<GhPullFile>,
) {
    val isEmpty: Boolean get() = commits.isEmpty() && files.isEmpty()

    /**
     * Compact text for the AI: commit subjects (+ a few body lines), the file list, then as many patches as fit
     * in [maxChars]. Lockfiles/minified assets are skipped, each patch is clipped, and [skip] drops paths the
     * user excluded from AI (their names too).
     */
    fun toPromptText(maxChars: Int = 40_000, skip: (String) -> Boolean = { false }): String {
        val sb = StringBuilder()
        sb.append("Commits (").append(totalCommits).append("):\n")
        commits.take(40).forEach { m ->
            val lines = m.trim().lines()
            sb.append("- ").append(lines.first().take(200)).append('\n')
            lines.drop(1).filter { it.isNotBlank() }.take(3).forEach { sb.append("    ").append(it.take(200)).append('\n') }
        }
        if (totalCommits > 40) sb.append("- … and ").append(totalCommits - 40).append(" more\n")
        val shown = files.filterNot { skip(it.filename) }
        sb.append("\nFiles changed (").append(shown.size).append("):\n")
        shown.take(150).forEach { sb.append("- ${it.filename} (${it.status}, +${it.additions} -${it.deletions})\n") }
        sb.append("\nDiffs:\n")
        for (f in shown) {
            if (sb.length >= maxChars) break
            val patch = f.patch ?: continue
            if (NOISY.containsMatchIn(f.filename)) continue
            sb.append("### ").append(f.filename).append('\n').append(patch.take(3000)).append('\n')
        }
        return sb.toString().take(maxChars + 3000)
    }

    private companion object {
        val NOISY = Regex("""(^|/)(package-lock\.json|yarn\.lock|pnpm-lock\.yaml|[^/]*\.lock|[^/]*\.lockfile|[^/]*\.min\.(js|css)|[^/]*\.map)$""")
    }
}
