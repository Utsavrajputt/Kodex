package com.invictus.kodex.ai

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One-shot hand-off from the editor (selection popup) to the editor's AI panel: the editor drops a ready prompt
 * here and opens the panel; the panel picks it up once and sends it. Keeps the editor free of AI internals.
 */
object AiEditorRequests {
    val pending = MutableStateFlow<String?>(null)

    /** Largest selection that is sent along (the rest is cut off with a note). */
    private const val MAX_SELECTION_CHARS = 6000

    /** Builds the "explain this" prompt for [code] taken from [fileName]. */
    fun explainPrompt(fileName: String?, code: String): String {
        val clipped = if (code.length > MAX_SELECTION_CHARS) code.take(MAX_SELECTION_CHARS) + "\n… (selection cut off)" else code
        val where = fileName?.let { " from `$it`" }.orEmpty()
        return "Explain this code$where. Say what it does, how it works step by step, and point out anything " +
            "surprising or risky. Do not change any files.\n\n```\n$clipped\n```"
    }
}
