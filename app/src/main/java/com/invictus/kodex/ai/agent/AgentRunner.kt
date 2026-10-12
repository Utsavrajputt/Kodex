package com.invictus.kodex.ai.agent

import com.invictus.kodex.ai.provider.AgentEvent
import com.invictus.kodex.ai.provider.AgentMessage
import com.invictus.kodex.ai.provider.AiError
import com.invictus.kodex.ai.provider.AiException
import com.invictus.kodex.ai.provider.AiProvider
import com.invictus.kodex.ai.provider.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Receives live progress from [AgentRunner]; implemented by the chat ViewModel. */
interface AgentSink {
    fun onRoundStart()
    fun onText(delta: String)
    fun onStepStart(id: String, label: String)
    fun onStepEnd(id: String, ok: Boolean)
}

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
        while (true) {
            Compactor.compact(history, budgetChars)
            val text = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            var tooLong = false
            provider.streamAgent(key, model, system, history.toList(), specs).collect { ev ->
                when (ev) {
                    is AgentEvent.Delta -> { text.append(ev.text); sink.onText(ev.text) }
                    is AgentEvent.Call -> calls += ev.call
                    AgentEvent.Done -> Unit
                    is AgentEvent.Error ->
                        if (ev.error is AiError.ContextTooLong) tooLong = true else throw AiException(ev.error)
                }
            }
            if (!tooLong) return text.toString() to calls
            if (squeezed) throw AiException(AiError.ContextTooLong())
            squeezed = true
            Compactor.squeeze(history)
        }
    }

    companion object {
        const val MAX_ROUNDS = 15
        const val LIMIT_NOTE =
            "(Tool limit reached. Without calling any tools, summarise what you did and what is still left to do.)"
    }
}
