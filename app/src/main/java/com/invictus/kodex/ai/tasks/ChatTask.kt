package com.invictus.kodex.ai.tasks

import com.invictus.kodex.ai.provider.AiEvent
import com.invictus.kodex.ai.provider.AiMessage
import com.invictus.kodex.ai.provider.AiProvider
import com.invictus.kodex.ai.provider.AiRequest
import kotlinx.coroutines.flow.Flow

/** Provider-agnostic chat: prompt building lives here — no `if (provider == …)` outside provider/. */
class ChatTask(
    private val provider: AiProvider,
    private val key: String,
    private val model: String,
) {
    private val systemPrompt = """
        You are Kodex AI, a concise senior pair programmer working inside a code editor on Android.
        Answer directly. Use fenced code blocks for code. Keep explanations short unless asked.
        Never invent file contents you were not shown.
    """.trimIndent()

    fun stream(history: List<AiMessage>, userText: String): Flow<AiEvent> =
        provider.stream(
            key, model,
            AiRequest(
                messages = listOf(AiMessage("system", systemPrompt)) + history + AiMessage("user", userText),
                maxOutputTokens = 2048, temperature = 0.3, model = model,
            ),
        )
}
