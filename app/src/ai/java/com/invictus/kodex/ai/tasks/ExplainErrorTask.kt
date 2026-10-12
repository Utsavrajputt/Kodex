package com.invictus.kodex.ai.tasks

import com.invictus.kodex.ai.Redactor
import com.invictus.kodex.ai.provider.AiEvent
import com.invictus.kodex.ai.provider.AiMessage
import com.invictus.kodex.ai.provider.AiProvider
import com.invictus.kodex.ai.provider.AiRequest
import kotlinx.coroutines.flow.Flow

/**
 * Error explanation (plan 9.7). Optionally attaches a file snippet around the
 * first path:line found in the error. May append a fix as an edit-JSON block
 * (same schema as inline edit) which the UI routes through the diff review flow.
 */
class ExplainErrorTask(
    private val provider: AiProvider,
    private val key: String,
    private val model: String,
) {
    private val system = """
        You explain build/runtime errors to a developer. Rules:
        - Output exactly these sections with these headings:
          Cause / Explanation / Fix steps
        - If you can propose a concrete code fix, append ONE block containing ONLY the JSON
          edit object {"explanation":"...","edits":[{"search":"...","replace":"..."}]},
          where "search" is an exact unique substring of the file (with 2+ lines of context).
        - If no snippet was provided, do not invent file contents.
    """.trimIndent()

    fun stream(errorText: String, fileSnippet: String?): Flow<AiEvent> =
        provider.stream(
            key, model,
            AiRequest(
                messages = listOf(
                    AiMessage("system", system),
                    AiMessage("user", buildString {
                        append("ERROR:\n```\n").append(Redactor.scrub(errorText)).append("\n```")
                        if (fileSnippet != null) {
                            append("\n\nFILE SNIPPET:\n```\n").append(Redactor.scrub(fileSnippet)).append("\n```")
                        }
                    }),
                ),
                maxOutputTokens = 3072, temperature = 0.2, model = model,
            ),
        )
}
