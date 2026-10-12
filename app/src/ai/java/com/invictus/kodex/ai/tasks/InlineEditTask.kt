package com.invictus.kodex.ai.tasks

import com.invictus.kodex.ai.provider.AiEvent
import com.invictus.kodex.ai.provider.AiMessage
import com.invictus.kodex.ai.provider.AiProvider
import com.invictus.kodex.ai.provider.AiRequest
import kotlinx.coroutines.flow.Flow

/** Inline edit: asks for strict search/replace JSON (plan 9.5 format). */
class InlineEditTask(
    private val provider: AiProvider,
    private val key: String,
    private val model: String,
) {
    private val systemPrompt = """
        You are an expert pair programmer editing a file in the Kodex editor.
        Respond with ONLY a JSON object (no prose, no code fences) of this exact shape:
        {"explanation":"one short sentence","edits":[{"search":"...","replace":"..."}]}
        Rules for every edit:
        - "search" must be an EXACT substring of the file, copied verbatim, including enough
          surrounding lines (at least 2 above/below the change) that it occurs exactly once.
        - "replace" is the new text for that region.
        - Make the smallest correct change. Do not reformat untouched code.
        - The file may be truncated with "… omitted …" markers: never search across or inside them.
    """.trimIndent()

    fun stream(contextText: String, instruction: String): Flow<AiEvent> =
        provider.stream(
            key, model,
            AiRequest(
                messages = listOf(
                    AiMessage("system", systemPrompt),
                    AiMessage("user", "FILE CONTEXT:\n$contextText\n\nUSER INSTRUCTION:\n$instruction"),
                ),
                maxOutputTokens = 4096, temperature = 0.2, model = model,
            ),
        )
}
