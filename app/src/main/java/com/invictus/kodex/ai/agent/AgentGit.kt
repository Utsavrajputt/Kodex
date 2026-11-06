package com.invictus.kodex.ai.agent

import com.invictus.kodex.core.git.GitResult
import com.invictus.kodex.core.git.GitSessionRegistry
import com.invictus.kodex.core.git.model.GitStageState
import com.invictus.kodex.core.git.model.GitWorkingState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Git tools for the agent: read the project's history and status, and make LOCAL commits and tags. Nothing here pushes,
 * resets or rewrites history. Paths on the user's never-send list are left out of every result and never staged.
 */
class AgentGit(
    private val workTree: File,
    private val globalIdentityFile: File?,
    private val files: ProjectFiles,
) {
    private suspend fun <T> withSession(block: suspend (com.invictus.kodex.core.git.GitSession) -> T): T {
        val session = GitSessionRegistry.acquire(workTree, globalIdentityFile)
        try {
            return block(session)
        } finally {
            GitSessionRegistry.release(workTree)
        }
    }

    private fun <T> GitResult<T>.orFail(): T = when (this) {
        is GitResult.Ok -> value
        is GitResult.Err -> throw IllegalStateException(error.message.ifBlank { error.title })
    }

    private fun day(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(ms))

    suspend fun status(): AgentTools.Outcome = withSession { s ->
        val snap = s.snapshot().orFail()
        if (!snap.hasCommits && snap.headName == null) return@withSession AgentTools.Outcome("Not a git repository yet (or no commits).")
        val st = s.status().orFail()
        buildString {
            appendLine("Branch: ${snap.headName ?: "(detached)"}${snap.trackingInfo?.let { " (tracking ${it.remote}/${it.branch}, ahead ${it.ahead}, behind ${it.behind})" }.orEmpty()}")
            if (snap.mergeInProgress) appendLine("A merge is in progress.")
            if (snap.rebaseInProgress) appendLine("A rebase is in progress.")
            val visible = st.changes.filterNot { files.isBlocked(it.repoRelativePath) }
            if (visible.isEmpty()) appendLine("Working tree clean.") else {
                visible.forEach { c ->
                    val tag = when {
                        c.unstaged == GitWorkingState.UNTRACKED -> "untracked"
                        c.staged == GitStageState.CONFLICT || c.unstaged == GitWorkingState.CONFLICT -> "conflict"
                        c.staged != GitStageState.NONE && c.unstaged != GitWorkingState.NONE -> "staged+modified"
                        c.staged != GitStageState.NONE -> "staged ${c.staged.name.lowercase()}"
                        else -> "modified ${c.unstaged.name.lowercase()}"
                    }
                    appendLine("$tag  ${c.repoRelativePath}")
                }
            }
        }.trim().let { AgentTools.Outcome(it) }
    }

    /** Newest commits first. [since] = a tag name, or "last_tag" for the most recent one: lists only commits after it. */
    suspend fun log(max: Int, path: String?, since: String?): AgentTools.Outcome = withSession { s ->
        val limit = max.coerceIn(1, 200)
        var stopAt: String? = null
        var sinceLabel: String? = null
        if (!since.isNullOrBlank()) {
            val tags = s.listTags().orFail().sortedByDescending { it.timeMs }
            val tag = if (since == "last_tag") tags.firstOrNull() else tags.firstOrNull { it.name == since }
            if (tag == null) return@withSession AgentTools.Outcome(if (since == "last_tag") "There are no tags yet." else "Tag not found: $since", isError = true)
            stopAt = tag.commitId
            sinceLabel = tag.name
        }
        val commits = s.log(if (stopAt != null) 400 else limit, path?.takeIf { it.isNotBlank() }, 0).orFail()
        val cut = if (stopAt != null) commits.takeWhile { it.id != stopAt }.take(limit) else commits
        if (cut.isEmpty()) return@withSession AgentTools.Outcome(if (sinceLabel != null) "No commits after tag $sinceLabel." else "No commits.")
        val head = if (sinceLabel != null) "Commits after tag $sinceLabel (newest first):\n" else "Commits (newest first):\n"
        AgentTools.Outcome(
            head + cut.joinToString("\n") { c ->
                val lines = c.message.trim().lines()
                val body = lines.drop(1).filter { it.isNotBlank() }.joinToString(" ").take(300)
                "${c.shortId} ${day(c.timeMs)} ${c.author}: ${lines.firstOrNull().orEmpty()}" + if (body.isNotEmpty()) "\n    $body" else ""
            },
        )
    }

    suspend fun show(commit: String, path: String?): AgentTools.Outcome = withSession { s ->
        val id = commit.trim()
        if (id.isEmpty()) return@withSession AgentTools.Outcome("Missing 'commit'", isError = true)
        val found = s.log(400, null, 0).orFail().firstOrNull { it.id.startsWith(id) || it.shortId == id }
            ?: return@withSession AgentTools.Outcome("Commit not found in the last 400 commits: $id", isError = true)
        val changed = s.commitFiles(found.id).orFail().filterNot { files.isBlocked(it.path) }
        val stats = (s.commitLineStats(found.id) as? GitResult.Ok)?.value
        val out = StringBuilder()
        out.appendLine("commit ${found.id}\nAuthor: ${found.author} <${found.authorEmail}>\nDate: ${day(found.timeMs)}\n\n${found.message.trim()}\n")
        if (stats != null) out.appendLine("${changed.size} files, +${stats.first} -${stats.second}")
        changed.take(80).forEach { out.appendLine("${it.change.name.lowercase()}  ${it.path}") }
        val p = path?.takeIf { it.isNotBlank() }
        if (p != null) {
            if (files.isBlocked(p)) return@withSession AgentTools.Outcome("Blocked by the user's never-send list: $p", isError = true)
            val diff = s.commitFileDiff(found.id, p).orFail()
            out.appendLine("\n--- diff of $p ---")
            out.appendLine(renderRows(diff.rows).take(12_000))
        }
        AgentTools.Outcome(out.toString().trim())
    }

    /** Side-by-side rows back to a plain unified-style text. */
    private fun renderRows(rows: List<com.invictus.kodex.core.git.model.GitDiffRow>): String = buildString {
        for (r in rows) {
            when {
                r.leftType == com.invictus.kodex.core.git.model.GitDiffLineType.HUNK -> appendLine(r.leftText ?: r.rightText ?: "@@")
                r.leftType == com.invictus.kodex.core.git.model.GitDiffLineType.REMOVED || r.rightType == com.invictus.kodex.core.git.model.GitDiffLineType.ADDED -> {
                    if (r.leftType == com.invictus.kodex.core.git.model.GitDiffLineType.REMOVED) appendLine("-" + (r.leftText ?: ""))
                    if (r.rightType == com.invictus.kodex.core.git.model.GitDiffLineType.ADDED) appendLine("+" + (r.rightText ?: ""))
                }
                else -> appendLine(" " + (r.rightText ?: r.leftText ?: ""))
            }
        }
    }

    suspend fun diff(staged: Boolean): AgentTools.Outcome = withSession { s ->
        val raw = (if (staged) s.stagedDiffText() else s.workTreeDiffText()).orFail()
        val kept = raw.split("\ndiff --git ").mapIndexed { i, part -> if (i == 0) part else "diff --git $part" }
            .filter { sec -> val m = Regex("^diff --git a/(\\S+)").find(sec); m == null || !files.isBlocked(m.groupValues[1]) }
            .joinToString("\n")
        AgentTools.Outcome(kept.ifBlank { if (staged) "Nothing is staged." else "No unstaged changes." })
    }

    suspend fun tags(): AgentTools.Outcome = withSession { s ->
        val tags = s.listTags().orFail().sortedByDescending { it.timeMs }
        AgentTools.Outcome(
            if (tags.isEmpty()) "No tags." else tags.take(60).joinToString("\n") { "${it.name}  ${it.commitId.take(7)}  ${day(it.timeMs)}" + (it.message?.let { m -> "  ${m.lineSequence().firstOrNull().orEmpty()}" }.orEmpty()) },
        )
    }

    /** Stages [paths] (or everything with [stageAll]) and commits locally. Never pushes. */
    suspend fun commit(message: String, paths: List<String>, stageAll: Boolean): AgentTools.Outcome = withSession { s ->
        if (message.isBlank()) return@withSession AgentTools.Outcome("Missing 'message'", isError = true)
        if (stageAll) {
            val status = s.status().orFail()
            status.changes.filterNot { files.isBlocked(it.repoRelativePath) }.forEach { c -> s.stage(c.repoRelativePath).orFail() }
        } else {
            for (p in paths) {
                if (files.isBlocked(p)) return@withSession AgentTools.Outcome("Blocked by the user's never-send list: $p", isError = true)
                s.stage(p).orFail()
            }
        }
        val c = when (val r = s.commit(message.trim(), amend = false)) {
            is GitResult.Ok -> r.value
            is GitResult.Err -> return@withSession AgentTools.Outcome(
                if (r.error.message.contains("identity", true)) "No git author identity is set. Ask the user to set their name and email in the Git screen (Identity)."
                else "Commit failed: " + r.error.message.ifBlank { r.error.title },
                isError = true,
            )
        }
        AgentTools.Outcome("Committed ${c.shortId} locally (not pushed): ${c.message.trim().lines().first()}")
    }

    suspend fun tag(name: String, message: String?): AgentTools.Outcome = withSession { s ->
        if (name.isBlank()) return@withSession AgentTools.Outcome("Missing 'name'", isError = true)
        s.createTag(name.trim(), message?.takeIf { it.isNotBlank() }).orFail()
        AgentTools.Outcome("Created tag ${name.trim()} on HEAD locally (not pushed).")
    }
}
