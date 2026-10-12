package com.invictus.kodex.ai.provider

import com.invictus.kodex.ai.agent.AiUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * Counts every one-shot and streaming call (commit messages, explain, inline edit, connection test) in
 * [AiUsage]. Agent turns are not counted here: [com.invictus.kodex.ai.agent.AgentRunner] counts those
 * itself, so wrapping `streamAgent` too would double-count.
 */
class UsageTrackedProvider(private val inner: AiProvider) : AiProvider by inner {

    private fun inChars(req: AiRequest) = req.messages.sumOf { it.content.length }

    override suspend fun complete(key: String, model: String, request: AiRequest): AiResponse {
        val res = inner.complete(key, model, request)
        AiUsage.recordRequest(id, model, inChars(request), res.text.length)
        return res
    }

    override fun stream(key: String, model: String, request: AiRequest): Flow<AiEvent> {
        var out = 0
        var counted = false
        return inner.stream(key, model, request)
            .onEach { ev ->
                if (ev is AiEvent.Delta) {
                    out += ev.text.length
                } else if (ev is AiEvent.Done && !counted) {
                    counted = true
                    AiUsage.recordRequest(id, model, inChars(request), out)
                }
            }
    }
}
