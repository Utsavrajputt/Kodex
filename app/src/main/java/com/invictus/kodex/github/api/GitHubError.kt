package com.invictus.kodex.github.api

import java.io.IOException

/**
 * Typed GitHub failure (plan section 11). Never carries a token or a signed URL; [message] is the
 * API's own `message` (sanitised) where GitHub supplies one.
 */
sealed class GitHubError(message: String) : IOException(message) {
    /** 401: token rejected / expired / revoked. */
    class Unauthorized(message: String = "Bad credentials") : GitHubError(message)

    /** 403 that is not a rate limit: the token lacks a permission. */
    class MissingPermission(message: String) : GitHubError(message)

    /** 403/429 with an exhausted primary or secondary limit. [resetEpochSec] = when it lifts, if known. */
    class RateLimited(val resetEpochSec: Long?, message: String = "Rate limit exceeded") : GitHubError(message)

    /** 404: not found *or* no access (GitHub hides private repos, so never claim which). */
    class NotFound(message: String = "Not found") : GitHubError(message)

    /** 410: expired artifact / logs. */
    class Gone(message: String = "Gone") : GitHubError(message)

    /** 409: e.g. run not cancellable. */
    class Conflict(message: String) : GitHubError(message)

    /** 422 validation failure. */
    class Validation(message: String) : GitHubError(message)

    /** No connectivity / DNS / timeout (after retries). */
    class Offline(message: String = "No connection") : GitHubError(message)

    /** A download that cannot be trusted: over the size cap, or cut short (M16). */
    class BadDownload(val reason: Reason) : GitHubError(reason.name) {
        enum class Reason { TooLarge, Incomplete }
    }

    /** Anything else (5xx, unexpected). */
    class Http(val code: Int, message: String) : GitHubError(message)

    companion object {
        /**
         * Pure mapping of an error response. [remaining]/[reset]/[retryAfter] come from the
         * `x-ratelimit-*` / `retry-after` headers.
         */
        fun from(
            code: Int,
            apiMessage: String?,
            remaining: Int? = null,
            reset: Long? = null,
            retryAfter: Long? = null,
            nowEpochSec: Long = System.currentTimeMillis() / 1000,
        ): GitHubError {
            val msg = sanitize(apiMessage)
            return when (code) {
                401 -> Unauthorized(msg.ifEmpty { "Bad credentials" })
                403, 429 -> when {
                    remaining == 0 -> RateLimited(reset, msg.ifEmpty { "Rate limit exceeded" })
                    retryAfter != null -> RateLimited(nowEpochSec + retryAfter, msg.ifEmpty { "Rate limit exceeded" })
                    code == 429 || msg.contains("rate limit", ignoreCase = true) ->
                        RateLimited(reset, msg.ifEmpty { "Rate limit exceeded" })
                    else -> MissingPermission(msg.ifEmpty { "Resource not accessible by token" })
                }
                404 -> NotFound(msg.ifEmpty { "Not found" })
                405, 409 -> Conflict(msg.ifEmpty { "Conflict" })
                410 -> Gone(msg.ifEmpty { "Gone" })
                422 -> Validation(msg.ifEmpty { "Validation failed" })
                else -> Http(code, msg.ifEmpty { "HTTP $code" })
            }
        }

        /** Single line, bounded, no URLs with query strings (signed links must never reach UI/logs). */
        fun sanitize(raw: String?): String =
            raw.orEmpty()
                .replace(Regex("https?://\\S+\\?\\S+"), "<url>")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(300)
    }
}
