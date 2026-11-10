package com.invictus.kodex.ai.tasks

import com.invictus.kodex.ai.provider.AiEvent
import com.invictus.kodex.ai.provider.AiMessage
import com.invictus.kodex.ai.provider.AiProvider
import com.invictus.kodex.ai.provider.AiRequest
import kotlinx.coroutines.flow.Flow

/**
 * Commit message generation (plan 9.6): the changes in (see [CommitDiffCompactor]), subject(+body) out.
 * Every style starts with a type prefix (feat, fix, perf...) chosen by the model. Never auto-commits.
 */
class CommitMessageTask(
    private val provider: AiProvider,
    private val key: String,
    private val model: String,
) {
    enum class Style(val label: String) {
        NORMAL("Normal"), SHORTER("Shorter"), LONGER("Longer"), CONVENTIONAL("Conventional"),
    }

    fun stream(changes: String, style: Style): Flow<AiEvent> {
        val system = TYPES + "\n" + when (style) {
            Style.CONVENTIONAL ->
                "Format: type(scope): subject. The scope is the module or feature area touched (omit it when unclear). " +
                    "Subject in imperative mood, max 72 chars. Optional body after a blank line."
            Style.SHORTER ->
                "Format: type: subject. One line only: imperative, max 50 chars, no body."
            Style.LONGER ->
                "Format: type: subject (imperative, max 72 chars), then a blank line and a body that explains what " +
                    "changed and why, wrapped at 72 chars."
            Style.NORMAL ->
                "Format: type: subject (imperative, max 72 chars). Add a short body of '- ' bullets only when there are " +
                    "several distinct changes."
        }
        return provider.stream(
            key, model,
            AiRequest(
                messages = listOf(
                    AiMessage("system", system),
                    AiMessage("user", "Describe these changes:\n\n$changes\n\nWrite only the commit message."),
                ),
                // Hidden "thinking" tokens (Gemini 3) count against this limit; a small one cuts the message mid-sentence.
                maxOutputTokens = when (style) { Style.SHORTER -> 1024; Style.LONGER -> 3072; else -> 2048 },
                temperature = 0.2, model = model,
            ),
        )
    }

    private companion object {
        const val TYPES =
            "You write git commit messages. Judge only from the changes given. Start the subject with exactly one type " +
                "prefix chosen for the dominant change:\n" +
                "feat = new feature or capability; fix = bug fix; perf = speed or memory improvement; " +
                "refactor = restructure without behaviour change; style = formatting or look only, no logic; " +
                "docs = documentation or comments; test = tests; build = gradle, dependencies, build config; " +
                "ci = workflows or CI scripts; chore = housekeeping, config, cleanup, version bumps; " +
                "revert = undo an earlier commit; major = breaking change or a big overhaul across many areas.\n" +
                "Never invent other types. Name the concrete thing that changed; do not list file names. " +
                "No code fences, no quotes, no explanations: only the message text."
    }
}
