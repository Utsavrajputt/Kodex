package com.invictus.kodex.ai.provider

/** [created] = release time in epoch seconds when the API reports it (0 = unknown); used to list newest first. */
data class AiModel(val id: String, val label: String, val maxContextChars: Int, val created: Long = 0L)

object ModelList {
    /**
     * Dropdown contents: our curated defaults first (only those the key can actually use, with our
     * labels), then every other model the API reports, newest first (by release time, else id). An empty [fetched] list
     * (offline, no key, listing failed) leaves the curated defaults untouched.
     */
    fun merge(defaults: List<AiModel>, fetched: List<AiModel>): List<AiModel> {
        if (fetched.isEmpty()) return defaults
        val byId = fetched.associateBy { it.id }
        val head = defaults.filter { it.id in byId }
        val headIds = head.map { it.id }.toSet()
        val rest = fetched.filter { it.id !in headIds }.distinctBy { it.id }
            .sortedWith(compareByDescending<AiModel> { it.created }.thenByDescending { it.id })
        return (head + rest).ifEmpty { defaults }
    }
}
data class AiMessage(val role: String, val content: String) // system | user | assistant
data class AiRequest(
    val messages: List<AiMessage>,
    val maxOutputTokens: Int = 2048,
    val temperature: Double = 0.3,
    val model: String,
)

data class AiResponse(val text: String)
data class Capabilities(val streaming: Boolean = true, val maxOutputTokens: Int = 4096)

sealed interface AiEvent {
    data class Delta(val text: String) : AiEvent
    data object Done : AiEvent
    data class Error(val error: AiError) : AiEvent
}

sealed class AiError(val message: String) {
    class NoKey : AiError("API key not set — add one in Settings → AI")
    class NoConsent : AiError("AI is off for projects — enable \"Allow AI in projects\" in Settings → AI")
    class Unauthorized : AiError("Invalid or revoked API key (401)")
    /** [daily] = a per-day quota: waiting a few seconds will not help, the user must switch model/provider or wait for the reset. */
    class RateLimited(val retryAfterSec: Int?, val daily: Boolean = false) : AiError(
        when {
            daily -> "Daily quota reached — try again later, or switch model/provider in Settings → AI"
            retryAfterSec == null -> "Rate limited — try later"
            retryAfterSec >= 300 -> "Quota reached — try again later, or switch model/provider in Settings → AI"
            else -> "Rate limited — try again in ${retryAfterSec}s"
        },
    )
    class ContextTooLong : AiError("Context too long for this model")
    class ContentFiltered : AiError("Response blocked by the provider's safety filter")
    class Timeout : AiError("Request timed out")
    class Network(val cause: Throwable) : AiError("Network error: ${cause.message}")
    class Malformed(val detail: String) : AiError("Couldn't parse model response")
    class Unknown(val code: Int, detail: String) : AiError("Error $code: $detail")
}

class AiException(val error: AiError) : Exception(error.message)
