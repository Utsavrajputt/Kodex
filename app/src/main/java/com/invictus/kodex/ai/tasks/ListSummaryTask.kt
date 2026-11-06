package com.invictus.kodex.ai.tasks

import com.invictus.kodex.ai.AiSummaryKind
import com.invictus.kodex.ai.Redactor
import com.invictus.kodex.ai.provider.AiEvent
import com.invictus.kodex.ai.provider.AiMessage
import com.invictus.kodex.ai.provider.AiProvider
import com.invictus.kodex.ai.provider.AiRequest
import kotlinx.coroutines.flow.Flow

/** Short digest of open pull requests, open issues, or one issue thread. Only describes the text it is given. */
class ListSummaryTask(
    private val provider: AiProvider,
    private val key: String,
    private val model: String,
) {
    fun stream(kind: AiSummaryKind, text: String): Flow<AiEvent> {
        val rules = "Use ONLY the text provided; never invent causes, owners, dates or status. Be concise, plain Markdown, " +
            "reference items as #number."
        val system = when (kind) {
            AiSummaryKind.PULLS ->
                "You summarize a repository's open pull requests for its maintainer. $rules " +
                    "Start with one line of overall status, then a bullet list grouping related pull requests, then \"Needs attention\" " +
                    "(drafts, large or stale ones) only if the text shows any."
            AiSummaryKind.ISSUES ->
                "You summarize a repository's open issues for its maintainer. $rules " +
                    "Start with one line of overall status, then a bullet list grouping related issues by theme or label, then " +
                    "\"Needs attention\" (many comments, long-open) only if the text shows any."
            AiSummaryKind.THREAD ->
                "You summarize one GitHub issue discussion. $rules Output three short sections: \"Problem\", \"Discussion so far\", " +
                    "\"Open questions / next step\" (only what the thread supports)."
        }
        return provider.stream(
            key, model,
            AiRequest(
                messages = listOf(
                    AiMessage("system", system),
                    AiMessage("user", Redactor.scrub(text)),
                ),
                maxOutputTokens = 900, temperature = 0.2, model = model,
            ),
        )
    }
}
