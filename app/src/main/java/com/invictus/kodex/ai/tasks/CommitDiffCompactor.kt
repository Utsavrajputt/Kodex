package com.invictus.kodex.ai.tasks

/**
 * Shrinks what the commit-message model has to read: a file list with +/- counts first, then each file's
 * hunks cut to a per-file budget (lockfiles, minified files and binaries are listed but not shown). The
 * model sees every changed file, but a huge diff no longer means a slow answer.
 */
object CommitDiffCompactor {

    /** A new file git doesn't track yet; [head] is its first lines, null for binary or big files. */
    class Untracked(val path: String, val head: List<String>?)

    const val TOTAL_BUDGET = 18_000
    private const val FIRST_PASS_FILE = 2_800
    private const val MIN_FILE = 450
    private const val MAX_LISTED = 150
    private const val MAX_LINE = 200

    private val LOW_VALUE = Regex(
        """(^|/)(package-lock\.json|yarn\.lock|pnpm-lock\.yaml|Cargo\.lock|gradle\.lockfile|[^/]*\.min\.(js|css)|[^/]*\.map)$""",
    )

    private class Chunk(val path: String, val status: Char, val added: Int, val removed: Int, val body: List<String>?)

    /** [diff] is `git diff` text (staged, or work tree); [untracked] adds files the diff can't show. */
    fun compact(diff: String, untracked: List<Untracked>): String {
        val chunks = parse(diff) + untracked.map { u ->
            Chunk(
                path = u.path, status = 'A', added = u.head?.size ?: 0, removed = 0,
                body = u.head?.map { "+$it" },
            )
        }
        if (chunks.isEmpty()) return ""
        var per = FIRST_PASS_FILE
        var out = build(chunks, per)
        while (out.length > TOTAL_BUDGET && per > MIN_FILE) {
            per = per * 2 / 3
            out = build(chunks, per)
        }
        return if (out.length > TOTAL_BUDGET) out.take(TOTAL_BUDGET) + "\n… (truncated)" else out
    }

    private fun parse(diff: String): List<Chunk> {
        if (diff.isBlank()) return emptyList()
        val chunks = ArrayList<Chunk>()
        var header: String? = null
        var lines = ArrayList<String>()
        fun flush() {
            header?.let { chunks += toChunk(it, lines) }
            lines = ArrayList()
        }
        for (line in diff.lineSequence()) {
            if (line.startsWith("diff --git ")) {
                flush()
                header = line
            } else {
                lines += line
            }
        }
        flush()
        return chunks
    }

    private fun toChunk(header: String, lines: List<String>): Chunk {
        // "diff --git a/x b/x": the new path is what follows the last " b/".
        val path = header.substringAfterLast(" b/", missingDelimiterValue = header.removePrefix("diff --git "))
        var status = 'M'
        var binary = false
        var firstHunk = -1
        for ((i, l) in lines.withIndex()) {
            when {
                firstHunk >= 0 -> break
                l.startsWith("new file mode") -> status = 'A'
                l.startsWith("deleted file mode") -> status = 'D'
                l.startsWith("rename from") -> status = 'R'
                l.startsWith("Binary files") || l.startsWith("GIT binary patch") -> binary = true
                l.startsWith("@@") -> firstHunk = i
            }
        }
        val hunk = if (firstHunk >= 0) lines.subList(firstHunk, lines.size) else emptyList()
        val added = hunk.count { it.startsWith("+") && !it.startsWith("+++") }
        val removed = hunk.count { it.startsWith("-") && !it.startsWith("---") }
        val skip = binary || hunk.isEmpty() || LOW_VALUE.containsMatchIn(path)
        return Chunk(path, status, added, removed, if (skip) null else hunk.dropLastWhile { it.isEmpty() })
    }

    private fun build(chunks: List<Chunk>, perFile: Int): String = buildString {
        append("Changed files (").append(chunks.size).append("):\n")
        chunks.take(MAX_LISTED).forEach { c ->
            append(c.status).append(' ').append(c.path).append(" (+").append(c.added).append(" -").append(c.removed).append(")\n")
        }
        if (chunks.size > MAX_LISTED) append("… and ").append(chunks.size - MAX_LISTED).append(" more\n")
        chunks.forEach { c ->
            val body = c.body ?: return@forEach
            append("\n=== ").append(c.path).append(if (c.status == 'A') " (new file)" else "").append(" ===\n")
            append(clip(body, perFile))
        }
    }

    private fun clip(body: List<String>, max: Int): String {
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < body.size) {
            val l = if (body[i].length > MAX_LINE) body[i].take(MAX_LINE) + "…" else body[i]
            if (i > 0 && used + l.length + 1 > max) break
            sb.append(l).append('\n')
            used += l.length + 1
            i++
        }
        if (i < body.size) sb.append("… ").append(body.size - i).append(" more lines\n")
        return sb.toString()
    }
}
