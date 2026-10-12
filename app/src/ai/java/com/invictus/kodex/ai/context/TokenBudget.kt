package com.invictus.kodex.ai.context

import com.invictus.kodex.ai.provider.AiModel

/** chars/4 heuristic — deliberately no tokenizer dependency (plan 9.3). */
object TokenBudget {
    const val RESERVE_OUTPUT_TOKENS = 2048

    fun estimate(text: String): Int = text.length / 4 + 1

    fun fits(inputChars: Int, model: AiModel): Boolean =
        estimate("x".repeat(inputChars)) + RESERVE_OUTPUT_TOKENS <= model.maxContextChars / 4
}
