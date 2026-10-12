package com.invictus.kodex.ai.tasks

import com.invictus.kodex.ai.Redactor
import com.invictus.kodex.ai.provider.AiEvent
import com.invictus.kodex.ai.provider.AiMessage
import com.invictus.kodex.ai.provider.AiProvider
import com.invictus.kodex.ai.provider.AiRequest
import kotlinx.coroutines.flow.Flow

/** Commit message generation (plan 9.6): staged diff in, subject(+body) out. Never auto-commits. */
class CommitMessageTask(
    private val provider: AiProvider,
    private val key: String,
    private val model: String,
) {
    enum class Style(val label: String) {
        NORMAL("Normal"), SHORTER("Shorter"), LONGER("Longer"), CONVENTIONAL("Conventional"),
    }

    fun stream(diffText: String, style: Style): Flow<AiEvent> {
        val system = when (style) {
            Style.CONVENTIONAL ->
                "You write git commit messages in Conventional Commits format: type(scope): subject. " +
                    "Types: feat, fix, chore, refactor, docs, test, build. Then an optional body."
            Style.SHORTER ->
                "You write extremely concise git commit messages: one short imperative subject line, max 50 chars, no body."
            Style.LONGER ->
                "You write git commit messages with a short imperative subject (max 72 chars) and a detailed " +
                    "body explaining what and why, wrapped at 72 chars."
            Style.NORMAL ->
                "You write git commit messages: short imperative subject (max 72 chars), optional body. " +
                    "No code fences, no quotes — just the message text."
        }
        return provider.stream(
            key, model,
            AiRequest(
                messages = listOf(
                    AiMessage("system", system),
                    AiMessage("user", "Here is the staged diff (or working-tree diff when nothing is staged):\n\n${Redactor.scrub(diffText)}\n\nWrite only the commit message."),
                ),
                maxOutputTokens = 512, temperature = 0.2, model = model,
            ),
        )
    }
}
