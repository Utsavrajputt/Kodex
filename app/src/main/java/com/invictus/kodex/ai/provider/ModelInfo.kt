package com.invictus.kodex.ai.provider

import java.util.Locale

enum class ModelTier(val label: String) { FAST("Fast"), BALANCED("Balanced"), POWERFUL("Most capable") }

/** Display helpers for model cards. Tiers come from well-known name keywords only; other ids get no tag. */
object ModelInfo {
    private val PRO = Regex("(^|[-_.])pro([-_.]|$)")

    fun tier(id: String): ModelTier? {
        val l = id.lowercase()
        return when {
            "haiku" in l || "flash" in l || "mini" in l || "nano" in l -> ModelTier.FAST
            "sonnet" in l -> ModelTier.BALANCED
            "opus" in l || PRO.containsMatchIn(l) -> ModelTier.POWERFUL
            else -> null
        }
    }

    /** Whether this model accepts photos. Claude and Gemini chat models all do; OpenAI's older/text-only ids don't. */
    fun supportsImages(providerId: String, modelId: String): Boolean {
        val l = modelId.lowercase()
        return when (providerId) {
            "claude" -> true
            "gemini" -> l.startsWith("gemini") && "embedding" !in l
            "openai" -> listOf("gpt-3", "o1-mini", "o1-preview", "o3-mini", "instruct", "embedding", "audio", "tts", "whisper", "davinci")
                .none { l.startsWith(it) || it in l }
            else -> guessesVision(l)
        }
    }

    // Hosts that serve many vendors' models (OpenRouter, Together, Hugging Face, custom...) don't say which take
    // photos in a common way, so go by well-known name markers; anything else keeps the image option off.
    private val VISION_HINTS = listOf(
        "vision", "-vl", "vl-", "gpt-4o", "gpt-4.1", "gpt-5", "claude", "gemini", "gemma-3", "gemma3", "llava",
        "pixtral", "llama-4", "llama-3.2-11b", "llama-3.2-90b", "grok-4", "mistral-medium", "mistral-small-3",
        "mistral-large-3", "internvl", "minicpm-v", "kimi-k2.5", "glm-4.5v", "glm-4.6v",
    )
    private val NOT_VISION = listOf("embed", "whisper", "tts", "audio", "rerank", "guard", "coder", "instruct-0")

    private fun guessesVision(l: String) = NOT_VISION.none { it in l } && VISION_HINTS.any { it in l }

    /** 1_000_000 → "1M", 200_000 → "200K", 850 → "850". */
    fun formatTokens(n: Long): String = when {
        n >= 1_000_000 -> trim(String.format(Locale.US, "%.1f", n / 1_000_000.0)) + "M"
        n >= 10_000 -> "${n / 1000}K"
        n >= 1_000 -> trim(String.format(Locale.US, "%.1f", n / 1000.0)) + "K"
        else -> n.toString()
    }

    /** Approximate context window in tokens (about 4 characters each). */
    fun contextLabel(maxContextChars: Int): String = "~" + formatTokens(maxContextChars / 4L) + " context"

    private fun trim(s: String) = s.removeSuffix(".0")
}
