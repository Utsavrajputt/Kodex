package com.invictus.kodex.ai.agent

import com.invictus.kodex.ai.provider.AgentEvent
import com.invictus.kodex.ai.provider.AgentMessage
import com.invictus.kodex.ai.provider.AiError
import com.invictus.kodex.ai.provider.AiException
import com.invictus.kodex.ai.provider.AiProvider
import com.invictus.kodex.ai.provider.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Receives live progress from [AgentRunner]; implemented by the chat ViewModel. */
interface AgentSink {
    fun onRoundStart()
    fun onText(delta: String)
    fun onStepStart(id: String, label: String)
    fun onStepEnd(id: String, ok: Boolean)

    /** A 429 is being waited out ([info] set), or the wait is over ([info] null). */
    fun onRateLimit(info: RateLimitInfo?) {}

    /** After each model request: estimated tokens sent (the whole transcript) and received. */
    fun onUsage(inputTokens: Int, outputTokens: Int) {}
}

/** Live rate-limit wait shown as a countdown card in the chat. */
data class RateLimitInfo(
    val provider: String,
    val model: String,
    val waitSec: Int,
    val attempt: Int,
    val maxAttempts: Int,
    val startedAt: Long = System.currentTimeMillis(),
)

/**
 * The agent loop: stream a turn, run any requested tools, feed the results back, repeat until the model answers
 * without calling tools or [maxRounds] is reached (then one tool-less turn asks it to summarise).
 * Mutates [history] in place; callers roll it back on failure.
 */
class AgentRunner(
    private val provider: AiProvider,
    private val key: String,
    private val model: String,
    private val tools: AgentTools,
    private val budgetChars: Int,
    private val maxRounds: Int = MAX_ROUNDS,
) {
    private val skip = Channel<Unit>(Channel.CONFLATED)

    /** Ends a rate-limit wait early and retries right away. */
    fun skipWait() {
        skip.trySend(Unit)
    }

    suspend fun run(system: String, history: MutableList<AgentMessage>, sink: AgentSink) {
        var rounds = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val toolsLeft = rounds < maxRounds
            val limitNote = AgentMessage.User(LIMIT_NOTE)
            if (!toolsLeft) history += limitNote
            sink.onRoundStart()
            val (text, calls) = streamOnce(system, history, if (toolsLeft) tools.specs else emptyList(), sink)
            if (!toolsLeft) {
                history.remove(limitNote)
                if (text.isNotBlank()) history += AgentMessage.Assistant(text)
                return
            }
            if (text.isBlank() && calls.isEmpty()) return
            history += AgentMessage.Assistant(text, calls)
            if (calls.isEmpty()) return
            for (call in calls) {
                currentCoroutineContext().ensureActive()
                val stepId = "${rounds}_${call.id}"
                sink.onStepStart(stepId, tools.label(call))
                val outcome = withContext(Dispatchers.IO) { tools.execute(call) }
                sink.onStepEnd(stepId, !outcome.isError)
                history += AgentMessage.ToolResult(call.id, call.name, outcome.text, outcome.isError, call.idFromApi)
            }
            rounds++
        }
    }

    private suspend fun streamOnce(
        system: String,
        history: MutableList<AgentMessage>,
        specs: List<com.invictus.kodex.ai.provider.ToolSpec>,
        sink: AgentSink,
    ): Pair<String, List<ToolCall>> {
        var squeezed = false
        var rateRetries = 0
        while (true) {
            Compactor.compact(history, budgetChars)
            val text = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            var tooLong = false
            var rateLimit: AiError.RateLimited? = null
            val inChars = system.length + historyChars(history)
            provider.streamAgent(key, model, system, history.toList(), specs).collect { ev ->
                when (ev) {
                    is AgentEvent.Delta -> { text.append(ev.text); sink.onText(ev.text) }
                    is AgentEvent.Call -> calls += ev.call
                    AgentEvent.Done -> Unit
                    is AgentEvent.Error -> when (val e = ev.error) {
                        is AiError.ContextTooLong -> tooLong = true
                        is AiError.RateLimited -> rateLimit = e
                        else -> throw AiException(e)
                    }
                }
            }
            // A 429 arrives before any output, so the request is safe to repeat. Wait it out (shown as a
            // step the user can cancel) instead of failing the whole run; long quota waits still fail fast.
            val rl = rateLimit
            if (rl != null) {
                val wait = rl.retryAfterSec?.coerceAtLeast(1) ?: (BASE_WAIT_SEC shl rateRetries)
                AiUsage.recordRateLimit(wait, rl.daily)
                if (rl.daily || rateRetries >= MAX_RATE_RETRIES || wait > MAX_WAIT_SEC) throw AiException(rl)
                rateRetries++
                sink.onRateLimit(RateLimitInfo(provider.displayName, model, wait, rateRetries, MAX_RATE_RETRIES))
                skip.tryReceive()
                try {
                    withTimeoutOrNull(wait * 1000L) { skip.receive() }
                } finally {
                    sink.onRateLimit(null)
                }
                continue
            }
            AiUsage.recordRequest(provider.id, model, inChars, text.length)
            sink.onUsage(AiUsage.estimateTokens(inChars), AiUsage.estimateTokens(text.length))
            if (!tooLong) return text.toString() to calls
            if (squeezed) throw AiException(AiError.ContextTooLong())
            squeezed = true
            Compactor.squeeze(history)
        }
    }

    private fun historyChars(history: List<AgentMessage>): Int = history.sumOf { m ->
        when (m) {
            is AgentMessage.User -> m.text.length
            is AgentMessage.Assistant -> m.text.length + m.calls.sumOf { it.args.toString().length }
            is AgentMessage.ToolResult -> m.content.length
        }
    }

    companion object {
        const val MAX_ROUNDS = 15
        const val MAX_RATE_RETRIES = 4
        const val BASE_WAIT_SEC = 5
        const val MAX_WAIT_SEC = 90
        const val LIMIT_NOTE =
            "(Tool limit reached. Without calling any tools, summarise what you did and what is still left to do.)"
    }
}
