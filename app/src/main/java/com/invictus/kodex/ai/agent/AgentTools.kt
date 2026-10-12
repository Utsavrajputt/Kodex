package com.invictus.kodex.ai.agent

import com.invictus.kodex.ai.Redactor
import com.invictus.kodex.ai.edit.EditApplier
import com.invictus.kodex.ai.edit.TextEdit
import com.invictus.kodex.ai.provider.ToolCall
import com.invictus.kodex.ai.provider.ToolSpec
import java.io.File
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent's tools. Reads (list/read/search) run immediately; writes (edit/create/delete) only STAGE
 * changes into [pending] -- the user reviews and applies them. Every path is sandboxed by [ProjectFiles].
 */
class AgentTools(private val files: ProjectFiles, private val pending: PendingChanges) {

    data class Outcome(val text: String, val isError: Boolean = false)

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "list_files",
            "List files and folders in the project (names and sizes). Use it to explore the structure.",
            schema(
                "path" to str("Folder relative to the project root; empty for the root."),
                "depth" to int("How many levels to show (1-6, default 2)."),
            ),
        ),
        ToolSpec(
            "read_file",
            "Read a text file with line numbers. Long files are cut at $MAX_LINES lines; use start_line/end_line to page. " +
                "The 'N| ' line-number prefix is NOT part of the file.",
            schema(
                "path" to str("File path relative to the project root."),
                "start_line" to int("First line to read (1-based, default 1)."),
                "end_line" to int("Last line to read."),
                required = listOf("path"),
            ),
        ),
        ToolSpec(
            "search",
            "Search file contents across the whole project. Returns 'path:line: text' matches.",
            schema(
                "query" to str("Text to find (or a regex when regex is true)."),
                "regex" to bool("Treat query as a regular expression."),
                "case_sensitive" to bool("Match case (default false)."),
                "path_glob" to str("Only search paths matching this glob, e.g. '*.kt' or 'app/src/**'."),
                "max_results" to int("Maximum matches to return (default 40, max 100)."),
                required = listOf("query"),
            ),
        ),
        ToolSpec(
            "edit_file",
            "Stage edits to an existing file as exact search/replace blocks, applied in order. Each 'search' must match the " +
                "file text exactly ONCE (include enough surrounding lines to be unique) and must not contain line-number prefixes. " +
                "Nothing is written until the user reviews and applies the change.",
            JSONObject().put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("path", str("File path relative to the project root."))
                        .put(
                            "edits",
                            JSONObject().put("type", "array").put("description", "Ordered search/replace blocks.").put(
                                "items",
                                JSONObject().put("type", "object").put(
                                    "properties",
                                    JSONObject()
                                        .put("search", str("Exact existing text to replace."))
                                        .put("replace", str("Replacement text (may be empty to delete).")),
                                ).put("required", JSONArray(listOf("search", "replace"))),
                            ),
                        )
                        .put("explanation", str("One short sentence describing the change.")),
                )
                .put("required", JSONArray(listOf("path", "edits"))),
        ),
        ToolSpec(
            "create_file",
            "Stage a new file with the given full content. Fails if the file already exists (use edit_file). Not written until the user applies it.",
            schema(
                "path" to str("New file path relative to the project root."),
                "content" to str("Full file content."),
                required = listOf("path", "content"),
            ),
        ),
        ToolSpec(
            "delete_file",
            "Stage deletion of a text file. Not deleted until the user applies it.",
            schema("path" to str("File path relative to the project root."), required = listOf("path")),
        ),
    )

    /** Short human label shown as a live step in the chat. */
    fun label(call: ToolCall): String {
        val a = call.args
        val path = a.optString("path")
        return when (call.name) {
            "list_files" -> "Listing ${path.ifBlank { "project" }}"
            "read_file" -> "Reading $path" + (if (a.has("start_line")) " (from line ${a.optInt("start_line")})" else "")
            "search" -> "Searching “${a.optString("query")}”"
            "edit_file" -> "Editing $path"
            "create_file" -> "Creating $path"
            "delete_file" -> "Deleting $path"
            else -> call.name
        }
    }

    suspend fun execute(call: ToolCall): Outcome = try {
        val out = run(call)
        if (out.text.length > MAX_RESULT_CHARS) out.copy(text = out.text.take(MAX_RESULT_CHARS) + "\n… output truncated …") else out
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        err("Tool failed: ${e.message}")
    }

    private suspend fun run(call: ToolCall): Outcome = when (call.name) {
        "list_files" -> listFiles(call.args)
        "read_file" -> readFile(call.args)
        "search" -> search(call.args)
        "edit_file" -> editFile(call.args)
        "create_file" -> createFile(call.args)
        "delete_file" -> deleteFile(call.args)
        else -> err("Unknown tool: ${call.name}")
    }

    // ---- reads ----

    private fun listFiles(a: JSONObject): Outcome {
        val dir = files.resolve(a.optString("path")) ?: return err("Path is outside the project")
        if (!dir.isDirectory) return err("Not a directory: ${a.optString("path")}")
        val base = files.relOf(dir).let { if (it == ".") "" else it }
        if (files.isBlocked(base)) return err("Blocked by the user's never-send list: $base")
        val depth = a.optInt("depth", 2).coerceIn(1, 6)
        val lines = ArrayList<String>()
        walk(dir, 0, depth, lines)
        if (lines.isEmpty()) return Outcome("(empty)")
        val truncated = lines.size >= MAX_LIST
        return Outcome(lines.joinToString("\n") + if (truncated) "\n… listing truncated; pass a deeper 'path'." else "")
    }

    private fun walk(dir: File, level: Int, maxDepth: Int, out: MutableList<String>) {
        val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
        for (c in children) {
            if (out.size >= MAX_LIST) return
            val rel = files.relOf(c)
            if (files.isBlocked(rel)) continue
            val pad = "  ".repeat(level)
            if (c.isDirectory) {
                if (c.name in ProjectFiles.SKIP_DIRS) continue
                out += "$pad${c.name}/"
                if (level + 1 < maxDepth) walk(c, level + 1, maxDepth, out)
            } else {
                out += "$pad${c.name} (${size(c.length())})"
            }
        }
    }

    private fun size(bytes: Long) = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }

    private suspend fun textOf(rel: String): Effective = when (val eff = pending.effective(rel)) {
        Effective.None -> when (val r = files.read(rel)) {
            is ProjectFiles.Read.Ok -> Effective.Text(r.text)
            is ProjectFiles.Read.Fail -> throw ToolError(r.message)
        }
        else -> eff
    }

    private suspend fun readFile(a: JSONObject): Outcome {
        val path = a.optString("path").takeIf { it.isNotBlank() } ?: return err("Missing 'path'")
        val file = files.resolve(path) ?: return err("Path is outside the project: $path")
        val rel = files.relOf(file)
        val text = when (val eff = try { textOf(rel) } catch (e: ToolError) { return err(e.message.orEmpty()) }) {
            is Effective.Text -> eff.text
            else -> return err("$rel was deleted by a staged change")
        }
        val lines = text.split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        val total = lines.size
        val start = a.optInt("start_line", 1).coerceAtLeast(1)
        if (total == 0) return Outcome("$rel is empty")
        if (start > total) return err("start_line $start is past the end ($total lines)")
        val end = minOf(a.optInt("end_line", start + MAX_LINES - 1).coerceAtLeast(start), total, start + MAX_LINES - 1)
        val body = (start..end).joinToString("\n") { n -> "$n| ${lines[n - 1].trimEnd('\r')}" }
        val more = if (end < total) "\n… ${total - end} more lines; call again with start_line=${end + 1}." else ""
        return Outcome("$rel (lines $start-$end of $total)\n${Redactor.scrub(body)}$more")
    }

    private suspend fun search(a: JSONObject): Outcome {
        val query = a.optString("query").takeIf { it.isNotEmpty() } ?: return err("Missing 'query'")
        val opts = if (a.optBoolean("case_sensitive", false)) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val regex = try {
            if (a.optBoolean("regex", false)) Regex(query, opts) else Regex(Regex.escape(query), opts)
        } catch (e: Exception) {
            return err("Invalid regex: ${e.message}")
        }
        val glob = a.optString("path_glob")
        val max = a.optInt("max_results", 40).coerceIn(1, 100)
        val deadline = System.nanoTime() + SEARCH_BUDGET_NS
        val hits = ArrayList<String>()
        var filesWithHits = 0
        var scanned = 0
        var timedOut = false
        for (rel in files.index()) {
            if (hits.size >= max) break
            if (System.nanoTime() > deadline) { timedOut = true; break }
            if (glob.isNotBlank() && !Redactor.isExcluded(rel, glob)) continue
            val text = when (val eff = pending.effective(rel)) {
                is Effective.Text -> eff.text
                Effective.Deleted -> continue
                Effective.None -> (files.read(rel) as? ProjectFiles.Read.Ok)?.text ?: continue
            }
            scanned++
            var perFile = 0
            for ((i, line) in text.split('\n').withIndex()) {
                if (regex.containsMatchIn(line)) {
                    if (perFile == 0) filesWithHits++
                    hits += "$rel:${i + 1}: ${Redactor.scrub(line.trim().take(200))}"
                    if (++perFile >= MAX_PER_FILE || hits.size >= max) break
                }
            }
        }
        val summary = "${hits.size} match(es) in $filesWithHits file(s), $scanned file(s) scanned" +
            (if (timedOut) " (stopped early: time limit)" else "") + (if (hits.size >= max) " (limit reached)" else "")
        return Outcome(if (hits.isEmpty()) "No matches. ($summary)" else hits.joinToString("\n") + "\n— $summary")
    }

    // ---- staged writes ----

    private suspend fun editFile(a: JSONObject): Outcome {
        val path = a.optString("path").takeIf { it.isNotBlank() } ?: return err("Missing 'path'")
        val edits = a.optJSONArray("edits")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { TextEdit(it.optString("search"), it.optString("replace")) }
            }
        }.orEmpty()
        if (edits.isEmpty()) return err("'edits' must contain at least one {search, replace} block")
        val file = files.resolve(path) ?: return err("Path is outside the project: $path")
        val rel = files.relOf(file)
        if (files.isBlocked(rel)) return err("Blocked by the user's never-send list: $rel")
        val current = when (val eff = try { textOf(rel) } catch (e: ToolError) { return err(e.message.orEmpty()) }) {
            is Effective.Text -> eff.text
            else -> return err("$rel was deleted by a staged change")
        }
        val crlf = current.contains("\r\n")
        var work = if (crlf) current.replace("\r\n", "\n") else current
        for ((i, edit) in edits.withIndex()) {
            val e = TextEdit(normalize(edit.search, work), edit.replace)
            when (val r = EditApplier.apply(work, e)) {
                is EditApplier.Outcome.Applied -> work = r.newText
                is EditApplier.Outcome.Conflict ->
                    return err("Edit ${i + 1} failed: ${r.reason}. Nothing was staged; re-read the file and retry with exact text.")
            }
        }
        val result = if (crlf) work.replace("\n", "\r\n") else work
        if (result == current) return err("The edits produce no change")
        pending.stageEdit(rel, current, result, a.optString("explanation"))
        return Outcome("Staged ${edits.size} edit(s) to $rel. Not applied yet; the user will review it.")
    }

    /** Models sometimes paste read_file's 'N| ' prefixes into a search block; strip them when that makes it match. */
    private fun normalize(search: String, text: String): String {
        if (text.contains(search)) return search
        val prefix = Regex("""(?m)^\s*\d+\|\s?""")
        val stripped = search.replace(prefix, "")
        return if (stripped != search && text.contains(stripped)) stripped else search
    }

    private suspend fun createFile(a: JSONObject): Outcome {
        val path = a.optString("path").takeIf { it.isNotBlank() } ?: return err("Missing 'path'")
        val content = a.optString("content")
        if (content.length > MAX_CREATE_CHARS) return err("Content too large")
        val file = files.resolve(path) ?: return err("Path is outside the project: $path")
        val rel = files.relOf(file)
        if (rel.isEmpty() || rel == ".") return err("Invalid path")
        if (files.isBlocked(rel)) return err("Blocked by the user's never-send list: $rel")
        val exists = when (pending.effective(rel)) {
            is Effective.Text -> true
            Effective.Deleted -> false
            Effective.None -> file.exists()
        }
        if (exists) return err("$rel already exists; use edit_file")
        if (file.isDirectory) return err("$rel is a directory")
        pending.stageCreate(rel, content, "")
        return Outcome("Staged new file $rel. Not created yet; the user will review it.")
    }

    private suspend fun deleteFile(a: JSONObject): Outcome {
        val path = a.optString("path").takeIf { it.isNotBlank() } ?: return err("Missing 'path'")
        val file = files.resolve(path) ?: return err("Path is outside the project: $path")
        val rel = files.relOf(file)
        if (files.isBlocked(rel)) return err("Blocked by the user's never-send list: $rel")
        val disk = when (val r = files.read(rel)) {
            is ProjectFiles.Read.Ok -> r.text
            is ProjectFiles.Read.Fail -> if (pending.effective(rel) is Effective.Text) "" else return err(r.message)
        }
        pending.stageDelete(rel, disk, "")
        return Outcome("Staged deletion of $rel. Not deleted yet; the user will review it.")
    }

    private class ToolError(message: String) : Exception(message)

    private fun err(message: String) = Outcome(message, isError = true)

    private companion object {
        const val MAX_LINES = 400
        const val MAX_LIST = 300
        const val MAX_PER_FILE = 8
        const val MAX_RESULT_CHARS = 30_000
        const val MAX_CREATE_CHARS = 500_000
        const val SEARCH_BUDGET_NS = 8_000_000_000L

        fun schema(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
            JSONObject().put("type", "object")
                .put("properties", JSONObject().also { o -> props.forEach { (k, v) -> o.put(k, v) } })
                .put("required", JSONArray(required))

        fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
        fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
        fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
    }
}
