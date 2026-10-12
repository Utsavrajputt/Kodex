package com.invictus.kodex.ai.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory usage counters for this app session (reset on app restart or via [reset]).
 * Token counts are estimates (about 4 characters per token) — providers bill by their own tokenizer,
 * so treat them as a guide, not an invoice.
 */
object AiUsage {

    data class Snapshot(
        val sinceMillis: Long = System.currentTimeMillis(),
        val requests: Int = 0,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val rateLimitHits: Int = 0,
        val lastRateLimitAt: Long? = null,
        val lastRateLimitDaily: Boolean = false,
        val lastRateLimitWaitSec: Int? = null,
        /** Estimated size of the most recent request, for the chat's context meter. */
        val lastRequestTokens: Int = 0,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun estimateTokens(chars: Int): Int = (chars + 3) / 4

    fun recordRequest(inputChars: Int, outputChars: Int) = _state.update {
        val inTok = estimateTokens(inputChars)
        it.copy(
            requests = it.requests + 1,
            inputTokens = it.inputTokens + inTok,
            outputTokens = it.outputTokens + estimateTokens(outputChars),
            lastRequestTokens = inTok,
        )
    }

    fun recordRateLimit(waitSec: Int?, daily: Boolean) = _state.update {
        it.copy(
            rateLimitHits = it.rateLimitHits + 1,
            lastRateLimitAt = System.currentTimeMillis(),
            lastRateLimitDaily = daily,
            lastRateLimitWaitSec = waitSec,
        )
    }

    fun reset() {
        _state.value = Snapshot()
    }
}
