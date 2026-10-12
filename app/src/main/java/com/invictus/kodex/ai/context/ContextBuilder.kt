package com.invictus.kodex.ai.context

/**
 * Builds the source context sent with a chat/inline-edit request.
 * Strategy ladder (plan 9.3): selection ±N lines → enclosing function by brace
 * scan → head + tail with an omission marker. Never exceeds [budgetChars] by much.
 */
object ContextBuilder {

    data class SourceContext(val text: String, val note: String)

    private const val SELECTION_PADDING_LINES = 40
    private const val BUDGET_FLOOR_CHARS = 8_000

    fun build(fileName: String, fullText: String, selStart: Int, selEnd: Int, budgetChars: Int): SourceContext {
        val selStartC = selStart.coerceIn(0, fullText.length)
        val selEndC = selEnd.coerceIn(selStartC, fullText.length)
        val hasSelection = selEndC > selStartC
        val budget = maxOf(budgetChars, BUDGET_FLOOR_CHARS)

        val body = when {
            !hasSelection -> trimToBudget(fullText, budget)
            else -> {
                val startLine = fullText.lastIndexOf('\n', (selStartC - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
                var lineCount = 0
                var windowStart = startLine
                while (windowStart > 0 && lineCount < SELECTION_PADDING_LINES) {
                    windowStart = fullText.lastIndexOf('\n', (windowStart - 2).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
                    lineCount++
                }
                val windowEnd = fullText.indexOf('\n', selEndC).let { if (it < 0) fullText.length else it }
                val window = fullText.substring(windowStart, windowEnd)
                val relSelStart = selStartC - windowStart
                val markerOpen = "<<<SELECTION>>>\n"
                val withMarkers = StringBuilder(window)
                    .insert(relSelStart, markerOpen)
                    .insert(relSelStart + markerOpen.length + (selEndC - selStartC), "\n<<<END SELECTION>>>")
                trimToBudget(withMarkers.toString(), budget)
            }
        }
        val selLine = if (hasSelection) fullText.substring(0, selStartC).count { it == '\n' } + 1 else null
        val header = buildString {
            append("// File: ").append(fileName)
            if (selLine != null) append(" (selection at line ").append(selLine).append(')')
            append('\n')
        }
        return SourceContext(header + body, fileName)
    }

    private fun trimToBudget(text: String, budgetChars: Int): String =
        if (text.length <= budgetChars) text else {
            val head = text.take(budgetChars * 3 / 4)
            val tail = text.takeLast(budgetChars / 4)
            "$head\n\n… omitted ${text.length - head.length - tail.length} chars …\n\n$tail"
        }
}
