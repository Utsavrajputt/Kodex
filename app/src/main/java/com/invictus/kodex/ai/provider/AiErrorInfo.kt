package com.invictus.kodex.ai.provider

/** What the user can do about an error; the UI maps each to a button. */
enum class ErrorAction { RETRY, SWITCH_MODEL, OPEN_SETTINGS }

/** A human-readable version of an [AiError]: what happened, why, and what to try. */
data class AiErrorInfo(
    val title: String,
    val explanation: String,
    val actions: List<ErrorAction>,
)

object AiErrorPresenter {

    fun describe(e: AiError, provider: String): AiErrorInfo = when (e) {
        is AiError.NoKey -> AiErrorInfo(
            "API key missing",
            "Add your $provider API key to start using the assistant. It is stored encrypted on this device.",
            listOf(ErrorAction.OPEN_SETTINGS),
        )
        is AiError.NoConsent -> AiErrorInfo(
            "AI is switched off for projects",
            "Turn on \"Allow AI in projects\" so the assistant may read your project files. Files matching your never-send list stay hidden.",
            listOf(ErrorAction.OPEN_SETTINGS),
        )
        is AiError.Unauthorized -> AiErrorInfo(
            "API key rejected",
            "$provider did not accept the saved key. It may be mistyped, revoked or from a different account.",
            listOf(ErrorAction.OPEN_SETTINGS),
        )
        is AiError.RateLimited -> rateLimited(e, provider)
        is AiError.ContextTooLong -> AiErrorInfo(
            "Conversation too long",
            "This chat no longer fits in the model's context window. Start a new chat, or pick a model with a larger context.",
            listOf(ErrorAction.SWITCH_MODEL),
        )
        is AiError.ContentFiltered -> AiErrorInfo(
            "Blocked by the safety filter",
            "$provider refused to answer this request. Rephrase it, or try a different model.",
            listOf(ErrorAction.RETRY, ErrorAction.SWITCH_MODEL),
        )
        is AiError.Timeout -> AiErrorInfo(
            "Request timed out",
            "$provider took too long to respond. Large projects and slow connections can cause this.",
            listOf(ErrorAction.RETRY),
        )
        is AiError.Network -> AiErrorInfo(
            "Can't reach $provider",
            "Check your internet connection and try again. (${e.cause.message ?: "network error"})",
            listOf(ErrorAction.RETRY),
        )
        is AiError.Malformed -> AiErrorInfo(
            "Unreadable reply",
            "The answer from $provider could not be parsed. Retrying usually fixes it.",
            listOf(ErrorAction.RETRY, ErrorAction.SWITCH_MODEL),
        )
        is AiError.Unknown -> AiErrorInfo(
            "Request failed (${e.code})",
            e.message.removePrefix("Error ${e.code}: ").ifBlank { "Unexpected error from $provider." },
            listOf(ErrorAction.RETRY, ErrorAction.SWITCH_MODEL),
        )
    }

    private fun rateLimited(e: AiError.RateLimited, provider: String): AiErrorInfo {
        val sec = e.retryAfterSec
        return when {
            e.daily -> AiErrorInfo(
                "Daily quota used up",
                "You have used today's free allowance for this model on $provider. Waiting a few seconds won't help — " +
                    "it resets later (usually within 24 hours). Switch to another model or provider to keep working.",
                listOf(ErrorAction.SWITCH_MODEL, ErrorAction.OPEN_SETTINGS),
            )
            sec != null && sec >= 300 -> AiErrorInfo(
                "Quota reached",
                "$provider asks you to wait about ${formatWait(sec)} before the next request. " +
                    "Switch to another model or provider to continue now.",
                listOf(ErrorAction.SWITCH_MODEL, ErrorAction.OPEN_SETTINGS),
            )
            else -> AiErrorInfo(
                "Rate limit reached",
                "Requests are arriving faster than $provider allows for this model" +
                    (if (sec != null) ". You can try again in ${formatWait(sec)}." else ". Try again in a minute.") +
                    " Agent runs make several requests per message, so busy projects hit this sooner.",
                listOf(ErrorAction.RETRY, ErrorAction.SWITCH_MODEL),
            )
        }
    }

    /** 45 → "45s", 130 → "2m 10s", 3600 → "1h". */
    fun formatWait(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> if (s % 60 == 0) "${s / 60}m" else "${s / 60}m ${s % 60}s"
            else -> if (s % 3600 == 0) "${s / 3600}h" else "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }
}
