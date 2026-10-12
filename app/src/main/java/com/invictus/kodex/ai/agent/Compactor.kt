package com.invictus.kodex.ai.agent

import com.invictus.kodex.ai.provider.AgentMessage

/** Keeps the agent transcript inside the model's context by shrinking the oldest bulky tool output first. */
object Compactor {
    const val STUB = "[older tool output omitted to save context]"

    /** Rough context cost of one attached photo, in characters (about 1K tokens). */
    const val IMAGE_CHARS = 4_000

    fun totalChars(history: List<AgentMessage>): Int = history.sumOf {
        when (it) {
            is AgentMessage.User -> it.text.length + it.images.size * IMAGE_CHARS
            is AgentMessage.Assistant -> it.text.length + it.calls.sumOf { c -> c.args.toString().length }
            is AgentMessage.ToolResult -> it.content.length
        }
    }

    /** Replaces old tool results (oldest first, never the last [keepLast] messages) until under [budgetChars]. */
    fun compact(history: MutableList<AgentMessage>, budgetChars: Int, keepLast: Int = 6) {
        var total = totalChars(history)
        if (total <= budgetChars) return
        for (i in 0 until (history.size - keepLast).coerceAtLeast(0)) {
            if (total <= budgetChars) break
            val m = history[i]
            if (m is AgentMessage.ToolResult && m.content.length > 400) {
                total -= m.content.length - STUB.length
                history[i] = m.copy(content = STUB)
            } else if (m is AgentMessage.User && m.text.length > 6000) {
                val cut = m.text.take(2000) + "\n…[older attachment truncated]"
                total -= m.text.length - cut.length
                history[i] = m.copy(text = cut)
            }
        }
    }

    /** Last resort after a context-too-long error: shrink every tool result and old user turn hard. */
    fun squeeze(history: MutableList<AgentMessage>, keepLast: Int = 2) {
        for (i in 0 until (history.size - keepLast).coerceAtLeast(0)) {
            val m = history[i]
            if (m is AgentMessage.ToolResult && m.content.length > 300) history[i] = m.copy(content = m.content.take(300) + "\n…[truncated]")
            if (m is AgentMessage.User && m.text.length > 3000) history[i] = m.copy(text = m.text.take(1500) + "\n…[truncated]")
        }
    }
}
