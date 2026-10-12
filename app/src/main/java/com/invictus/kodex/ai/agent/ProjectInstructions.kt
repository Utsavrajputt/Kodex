package com.invictus.kodex.ai.agent

import com.invictus.kodex.ai.Redactor

/**
 * Per-project standing instructions for the AI agent ("use Compose", "no !!", "follow Clean Architecture"...).
 * The user keeps them in a plain Markdown file in the project root; it is read at the start of every turn
 * so edits apply immediately, and it goes through the same never-send globs and secret scrubbing as any file.
 */
object ProjectInstructions {
    /** Looked up in this order; the first one that exists wins. */
    val FILE_NAMES = listOf(".kodex-ai.md", "KODEX.md", "AGENTS.md", "CLAUDE.md")

    /** Longest text sent; the rest is cut so a huge file cannot eat the context window. */
    private const val MAX_CHARS = 8000

    data class Found(val fileName: String, val text: String)

    suspend fun load(files: ProjectFiles): Found? {
        for (name in FILE_NAMES) {
            val f = files.resolve(name) ?: continue
            if (!f.isFile) continue
            val read = files.read(name) as? ProjectFiles.Read.Ok ?: continue
            val text = Redactor.scrub(read.text).trim()
            if (text.isEmpty()) continue
            val clipped = if (text.length > MAX_CHARS) text.take(MAX_CHARS) + "\n… (instructions cut off)" else text
            return Found(name, clipped)
        }
        return null
    }
}
