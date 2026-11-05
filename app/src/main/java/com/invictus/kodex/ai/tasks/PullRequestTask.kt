package com.invictus.kodex.ai.tasks

import com.invictus.kodex.ai.Redactor
import com.invictus.kodex.ai.provider.AiEvent
import com.invictus.kodex.ai.provider.AiMessage
import com.invictus.kodex.ai.provider.AiProvider
import com.invictus.kodex.ai.provider.AiRequest
import kotlinx.coroutines.flow.Flow

/** Pull request title + description from the commits/diff between two branches. Never creates the PR. */
class PullRequestTask(
    private val provider: AiProvider,
    private val key: String,
    private val model: String,
) {
    enum class Style(val label: String) { NORMAL("Normal"), SHORT("Short"), DETAILED("Detailed") }

    fun stream(changes: String, style: Style): Flow<AiEvent> {
        val format = "Output format: the FIRST line is the pull request title only (imperative mood, max 72 chars, " +
            "no quotes, no \"Title:\" prefix, no trailing period). Then one blank line, then the description in Markdown. " +
            "Describe only what the changes show; never invent features, issues or test results. No code fence around the answer."
        val system = "You write GitHub pull request titles and descriptions. " + when (style) {
            Style.NORMAL -> "$format The description is a short summary paragraph followed by a bullet list of the key changes."
            Style.SHORT -> "$format The description is one or two sentences, no headings, no bullets."
            Style.DETAILED -> "$format The description has the sections \"## Summary\", \"## Changes\" (bullets grouped by area) " +
                "and, only if the changes clearly warrant it, \"## Notes\" for risks or follow-ups."
        }
        return provider.stream(
            key, model,
            AiRequest(
                messages = listOf(
                    AiMessage("system", system),
                    AiMessage("user", "Changes between the base and compare branch:\n\n${Redactor.scrub(changes)}\n\nWrite the pull request title and description."),
                ),
                maxOutputTokens = 900, temperature = 0.2, model = model,
            ),
        )
    }

    companion object {
        /** (title, description) from the model output; title = first non-empty line, cleaned. */
        fun parse(raw: String): Pair<String, String> {
            val text = raw.trim().removePrefix("```markdown").removePrefix("```md").removePrefix("```").removeSuffix("```").trim()
            val lines = text.lines()
            val first = lines.indexOfFirst { it.isNotBlank() }
            if (first < 0) return "" to ""
            val title = lines[first].trim()
                .removePrefix("#").trim()
                .replace(Regex("(?i)^(\\*\\*)?title:?(\\*\\*)?:?\\s*"), "")
                .trim('"', '\'', '`', '*', ' ')
                .trimEnd('.')
            val body = lines.drop(first + 1).joinToString("\n").trim()
            return title to body
        }
    }
}
