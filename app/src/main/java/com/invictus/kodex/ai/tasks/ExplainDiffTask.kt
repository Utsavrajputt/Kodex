package com.invictus.kodex.ai.tasks

import com.invictus.kodex.ai.Redactor
import com.invictus.kodex.ai.provider.AiEvent
import com.invictus.kodex.ai.provider.AiMessage
import com.invictus.kodex.ai.provider.AiProvider
import com.invictus.kodex.ai.provider.AiRequest
import kotlinx.coroutines.flow.Flow

/** Diff explanation (plan 9.7): may ONLY cite the provided diff. */
class ExplainDiffTask(
    private val provider: AiProvider,
    private val key: String,
    private val model: String,
) {
    private val system = """
        You explain code changes to a developer. Rules:
        - ONLY discuss changes visible in the provided diff. Never claim anything about code you were not shown.
        - Output exactly these sections with these headings:
          What changed / Why (labelled as your inference) / Side effects / Files & symbols
        - Be concrete and concise. Reference symbols and line context from the diff.
    """.trimIndent()

    fun stream(diffText: String): Flow<AiEvent> =
        provider.stream(
            key, model,
            AiRequest(
                messages = listOf(
                    AiMessage("system", system),
                    AiMessage("user", "DIFF:\n```diff\n${Redactor.scrub(diffText)}\n```"),
                ),
                maxOutputTokens = 2048, temperature = 0.2, model = model,
            ),
        )
}
