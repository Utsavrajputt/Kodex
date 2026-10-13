package com.invictus.kodex.ai.agent

import android.app.Application
import com.invictus.kodex.ai.Redactor
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Per-project AI memory and instructions, kept in the app's own private storage (never inside the project
 * folder): `memory.md` is what the agent saves by itself with the `save_memory` tool, `instructions.md` is what
 * the user writes. Both are plain Markdown, editable from the chat's "Memory & instructions" sheet. Notes are
 * secret-scrubbed before they are written.
 */
object ProjectMemory {
    private const val MEMORY_NAME = "memory.md"
    private const val INSTRUCTIONS_NAME = "instructions.md"

    /** Longest memory text sent to the model per turn (newest notes win). */
    const val MAX_SENT_CHARS = 6_000

    /** Longest project instructions text sent. */
    private const val MAX_INSTRUCTIONS_CHARS = 8_000

    /** Above this size the memory file is condensed automatically. */
    const val CONDENSE_AT = 5_000

    /** Longest single note. */
    private const val MAX_NOTE_CHARS = 300

    /** Hard limit for what the sheet editor may save. */
    const val MAX_EDIT_CHARS = 20_000

    private const val HEADER = "# Kodex project memory\n\n"
    private const val GLOBAL_HEADER = "# Kodex universal memory\n\n"

    /** The two documents the sheet can edit. */
    enum class Doc { MEMORY, INSTRUCTIONS }

    data class Found(val text: String)

    private val lock = Mutex()

    /** The private folder holding this project's memory files (same project hash scheme as saved chats). */
    fun dirFor(app: Application, projectPath: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(projectPath.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(app.filesDir, "ai_memory/" + hash.take(16))
    }

    /** The private folder for the universal (not tied to any project) memory, shared by every project. */
    fun globalDir(app: Application): File = File(app.filesDir, "ai_memory/global")

    private fun fileOf(dir: File, doc: Doc) = File(dir, if (doc == Doc.MEMORY) MEMORY_NAME else INSTRUCTIONS_NAME)

    /** Memory text for the system prompt (scrubbed, newest part kept when too long); null when empty. */
    suspend fun load(dir: File): Found? = withContext(Dispatchers.IO) {
        val text = Redactor.scrub(readRaw(fileOf(dir, Doc.MEMORY))).trim()
        if (text.isEmpty() || text == HEADER.trim() || text == GLOBAL_HEADER.trim()) return@withContext null
        Found(if (text.length > MAX_SENT_CHARS) "… (older notes cut off)\n" + text.takeLast(MAX_SENT_CHARS) else text)
    }

    /** The user's instructions for this project (scrubbed, clipped); null when empty. */
    suspend fun loadInstructions(dir: File): String? = withContext(Dispatchers.IO) {
        val text = Redactor.scrub(readRaw(fileOf(dir, Doc.INSTRUCTIONS))).trim()
        if (text.isEmpty()) null
        else if (text.length > MAX_INSTRUCTIONS_CHARS) text.take(MAX_INSTRUCTIONS_CHARS) + "\n… (instructions cut off)" else text
    }

    private fun readRaw(f: File): String = if (f.isFile) runCatching { f.readText() }.getOrDefault("") else ""

    // ---- sheet editor ----

    suspend fun readDoc(dir: File, doc: Doc): String = withContext(Dispatchers.IO) { readRaw(fileOf(dir, doc)) }

    /** Writes the whole document; false when the write fails. */
    suspend fun writeDoc(dir: File, doc: Doc, text: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock { writeAtomic(fileOf(dir, doc), text.take(MAX_EDIT_CHARS)) }
    }

    private fun writeAtomic(f: File, text: String): Boolean = runCatching {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
        true
    }.getOrDefault(false)

    // ---- agent tool ----

    /**
     * What the `save_memory` tool talks to. [dir] is the project's memory folder, [globalDir] the universal one
     * (null = the tool may only touch the project). [condense] rewrites an over-long file shorter (null = failed / unavailable).
     */
    class Store(
        private val dir: File,
        private val globalDir: File? = null,
        private val condense: (suspend (String) -> String?)? = null,
    ) {
        suspend fun update(add: List<String>, remove: List<String>, global: Boolean = false): AgentTools.Outcome = withContext(Dispatchers.IO) {
            lock.withLock {
                val target = if (global) globalDir ?: dir else dir
                val header = if (global && globalDir != null) GLOBAL_HEADER else HEADER
                val label = if (global && globalDir != null) "Universal memory" else "Memory"
                val f = fileOf(target, Doc.MEMORY)
                val current = readRaw(f)
                val lines = current.lines().toMutableList()
                val notes = lines.filter { it.trimStart().startsWith("- ") }.map { it.trim().removePrefix("- ").lowercase() }.toMutableSet()

                var removed = 0
                for (m in remove.map { it.trim().lowercase() }.filter { it.length >= 3 }) {
                    val iter = lines.iterator()
                    while (iter.hasNext()) {
                        val line = iter.next()
                        if (line.trimStart().startsWith("- ") && line.lowercase().contains(m)) { iter.remove(); removed++ }
                    }
                }

                var added = 0
                var skipped = 0
                for (raw in add) {
                    val note = Redactor.scrub(raw).replace(Regex("\\s+"), " ").trim().removePrefix("- ").trim().take(MAX_NOTE_CHARS)
                    if (note.length < 3) { skipped++; continue }
                    if (!notes.add(note.lowercase())) { skipped++; continue }
                    lines += "- $note"
                    added++
                }
                if (added == 0 && removed == 0) {
                    return@withLock AgentTools.Outcome(if (skipped > 0) "Nothing new to save (already in memory)." else "Nothing to change.")
                }

                var text = lines.joinToString("\n").trim()
                if (!text.startsWith("#")) text = header + text
                var note = ""
                if (text.length > CONDENSE_AT && condense != null) {
                    val shorter = runCatching { condense.invoke(text) }.getOrNull()?.let { clean(it, header) }
                    if (shorter != null && shorter.length in 40 until text.length) {
                        note = " Memory was getting long, so it was condensed (${text.length} → ${shorter.length} chars)."
                        text = shorter
                    }
                }
                if (!writeAtomic(f, text + "\n")) return@withLock AgentTools.Outcome("Could not write the memory file.", isError = true)
                AgentTools.Outcome("$label updated: $added added, $removed removed.$note")
            }
        }

        /** Strips code fences the model may wrap around the file and makes sure it keeps the heading. */
        private fun clean(raw: String, header: String): String? {
            var t = raw.trim()
            if (t.startsWith("```")) t = t.removePrefix("```markdown").removePrefix("```md").removePrefix("```").removeSuffix("```").trim()
            if (t.isEmpty() || t.lines().none { it.trimStart().startsWith("- ") }) return null
            return if (t.startsWith("#")) t else header + t
        }
    }

    const val CONDENSE_SYSTEM =
        "You maintain a project's memory file: short Markdown bullet notes an AI coding assistant saved for future chats. " +
            "Rewrite it to be shorter and cleaner: merge duplicates and near-duplicates, drop notes that are outdated, " +
            "contradicted by a newer note, or only about a one-off task, and keep EVERY durable fact (user preferences, " +
            "project conventions, architecture decisions, build/test commands, recurring pitfalls). Keep the first heading line. " +
            "Group related notes under short '## ' headings if that helps. Aim for under 3000 characters. " +
            "Output ONLY the new file content, no explanation, no code fences."
}
