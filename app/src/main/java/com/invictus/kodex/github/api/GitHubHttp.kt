package com.invictus.kodex.github.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * GitHub's clock as seen from this phone. Step/job/run timestamps (`started_at`) are GitHub's, so an
 * elapsed-time timer has to count against GitHub's time, not the phone's - a phone whose clock is a
 * minute off would otherwise show a timer a minute off. The offset is learned from the `Date` header of
 * API responses (see [GitHubHttp]); until the first response it is 0 (plain phone time).
 */
object GhClock {
    @Volatile
    private var offsetMs = 0L

    /** Current time on GitHub's clock, in epoch millis. */
    fun now(): Long = System.currentTimeMillis() + offsetMs

    /**
     * [serverMs] is the `Date` header, which HTTP truncates to whole seconds, so the real server time is
     * somewhere in [serverMs, serverMs + 1s); the midpoint keeps the error to +-0.5s.
     */
    fun sync(serverMs: Long) {
        offsetMs = serverMs + 500L - System.currentTimeMillis()
    }
}

/**
 * Thin OkHttp wrapper (plan section 3): standard headers, typed errors, GET retry/backoff,
 * ETag cache and rate-limit capture. Android-free so it can be unit-tested.
 *
 * Security: the token is fetched through [token] on every call (never stored here), the
 * `Authorization` header is never sent to a redirect target, and nothing here logs URLs/headers.
 */
class GitHubHttp(
    client: OkHttpClient,
    private val token: () -> String?,
    private val baseUrl: String = "https://api.github.com",
    private val backoffMs: List<Long> = listOf(500, 1_000, 2_000),
) {
    /** Redirects are followed by hand so credentials never travel to the storage host. */
    private val api: OkHttpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    @Volatile
    var rateLimit: RateLimit? = null
        private set

    private class Cached(val etag: String, val body: String)

    private val etags = ConcurrentHashMap<String, Cached>()

    fun clearCache() = etags.clear()

    /** GET returning the JSON body text. Retries transient failures (IO, 5xx) up to 3 times. */
    suspend fun getJson(
        path: String,
        query: Map<String, String> = emptyMap(),
        accept: String = ACCEPT_JSON,
    ): String = withContext(Dispatchers.IO) {
        val url = buildUrl(path, query)
        var attempt = 0
        while (true) {
            try {
                return@withContext getJsonOnce(url, accept)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError.Http) {
                if (e.code < 500 || attempt >= backoffMs.size) throw e
            } catch (e: GitHubError) {
                throw e
            } catch (e: IOException) {
                if (attempt >= backoffMs.size) throw GitHubError.Offline()
            }
            delay(backoffMs[attempt])
            attempt++
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun getJsonOnce(url: String, accept: String): String {
        val b = request(url).header("Accept", accept)
        etags[url]?.let { b.header("If-None-Match", it.etag) }
        api.newCall(b.build()).execute().use { res ->
            capture(res)
            if (res.code == 304) return etags[url]?.body ?: throw GitHubError.Http(304, "Not modified")
            if (!res.isSuccessful) throw toError(res)
            val body = res.body?.string().orEmpty()
            res.header("ETag")?.let { etags[url] = Cached(it, body) }
            return body
        }
    }

    /** Mutating call (POST/DELETE) with an optional JSON body; returns the status code (2xx only). */
    suspend fun send(method: String, path: String, body: JSONObject? = null): Int = withContext(Dispatchers.IO) {
        val rb = (body?.toString() ?: "").toRequestBody(JSON)
        val req = request(buildUrl(path, emptyMap()))
            .header("Accept", ACCEPT_JSON)
            .method(method, if (method == "DELETE" && body == null) null else rb)
            .build()
        try {
            api.newCall(req).execute().use { res ->
                capture(res)
                if (!res.isSuccessful) throw toError(res)
                res.code
            }
        } catch (e: GitHubError) {
            throw e
        } catch (e: IOException) {
            throw GitHubError.Offline()
        }
    }

    /**
     * Downloads a plain-text endpoint (job logs) into [dest]. The API answers 302 to a short-lived
     * URL; that second hop is made WITHOUT credentials. Returns true when [maxBytes] cut it short.
     */
    suspend fun downloadText(path: String, dest: File, maxBytes: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val first = request(buildUrl(path, emptyMap())).header("Accept", "application/vnd.github+json").build()
            api.newCall(first).execute().use { res ->
                capture(res)
                when {
                    res.isRedirect -> {
                        val loc = res.header("Location") ?: throw GitHubError.Http(res.code, "Missing redirect")
                        val plain = Request.Builder().url(loc.toHttpUrl()).header("User-Agent", UA).build()
                        api.newCall(plain).execute().use { r2 ->
                            if (!r2.isSuccessful) throw GitHubError.from(r2.code, null)
                            writeCapped(r2, dest, maxBytes)
                        }
                    }
                    res.isSuccessful -> writeCapped(res, dest, maxBytes)
                    else -> throw toError(res)
                }
            }
        } catch (e: GitHubError) {
            dest.delete()
            throw e
        } catch (e: IOException) {
            dest.delete()
            throw GitHubError.Offline()
        }
    }

    /**
     * Streams [path] to [dest]. With [keepPartial] a partial [dest] from an earlier (paused or interrupted)
     * attempt is continued with a `Range` request; the partial is only trusted when the server answers
     * `206` with a `Content-Range` that starts exactly at its length, otherwise the file is started over.
     * The partial is kept on cancel / connection loss (so it can be resumed) and removed on every other failure.
     */
    suspend fun downloadBinary(
        path: String,
        dest: File,
        maxBytes: Long,
        expectedSize: Long,
        keepPartial: Boolean,
        onProgress: (Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val ctx = currentCoroutineContext()
        var resumeFrom = if (keepPartial && dest.isFile) dest.length() else 0L
        // A partial that is already as big as the whole file cannot be trusted: start over.
        if (resumeFrom > 0 && expectedSize > 0 && resumeFrom >= expectedSize) { dest.delete(); resumeFrom = 0L }
        if (resumeFrom == 0L) dest.delete()
        try {
            try {
                fetchBinary(path, dest, maxBytes, expectedSize, onProgress, ctx, resumeFrom)
            } catch (e: RangeRejected) {
                dest.delete()
                fetchBinary(path, dest, maxBytes, expectedSize, onProgress, ctx, 0L)
            }
        } catch (e: GitHubError) {
            dest.delete()
            throw e
        } catch (e: RangeRejected) {
            dest.delete()
            throw GitHubError.BadDownload(GitHubError.BadDownload.Reason.Incomplete)
        } catch (e: CancellationException) {
            if (!keepPartial) dest.delete()
            throw e
        } catch (e: IOException) {
            if (!keepPartial) dest.delete()
            throw GitHubError.Offline()
        }
    }

    /** The server did not honour the `Range` request the way a safe resume needs. */
    private class RangeRejected : Exception()

    private fun fetchBinary(
        path: String,
        dest: File,
        maxBytes: Long,
        expectedSize: Long,
        onProgress: (Long) -> Unit,
        ctx: CoroutineContext,
        resumeFrom: Long,
    ) {
        val first = request(buildUrl(path, emptyMap())).header("Accept", ACCEPT_JSON).build()
        api.newCall(first).execute().use { res ->
            capture(res)
            when {
                res.isRedirect -> {
                    val loc = res.header("Location") ?: throw GitHubError.Http(res.code, "Missing redirect")
                    // The redirect target is a short-lived signed URL: it is requested fresh on every (re)start.
                    val plain = Request.Builder().url(loc.toHttpUrl()).header("User-Agent", UA)
                    if (resumeFrom > 0) plain.header("Range", "bytes=$resumeFrom-")
                    api.newCall(plain.build()).execute().use { r2 ->
                        if (r2.code == 416) throw RangeRejected()
                        if (!r2.isSuccessful) throw GitHubError.from(r2.code, null)
                        copyBinary(r2, dest, maxBytes, expectedSize, onProgress, ctx, resumeFrom)
                    }
                }
                res.isSuccessful -> copyBinary(res, dest, maxBytes, expectedSize, onProgress, ctx, 0L)
                else -> throw toError(res)
            }
        }
    }

    private fun copyBinary(
        res: Response,
        dest: File,
        maxBytes: Long,
        expectedSize: Long,
        onProgress: (Long) -> Unit,
        ctx: CoroutineContext,
        resumeFrom: Long,
    ) {
        dest.parentFile?.mkdirs()
        // 206 + a Content-Range that starts exactly where the partial ends = safe to append. A plain 200 means
        // the server ignored the Range: the whole file arrives again, so the partial is overwritten.
        val appending = resumeFrom > 0 && res.code == 206
        if (appending) {
            val start = res.header("Content-Range")?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()
            if (start != resumeFrom || dest.length() != resumeFrom) throw RangeRejected()
        }
        var total = if (appending) resumeFrom else 0L
        res.body!!.byteStream().use { input ->
            java.io.FileOutputStream(dest, appending).use { out ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    ctx.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw GitHubError.BadDownload(GitHubError.BadDownload.Reason.TooLarge)
                    out.write(buf, 0, n)
                    onProgress(total)
                }
            }
        }
        if (expectedSize > 0 && total < expectedSize) {
            throw GitHubError.BadDownload(GitHubError.BadDownload.Reason.Incomplete)
        }
    }

    private fun writeCapped(res: Response, dest: File, maxBytes: Long): Boolean {
        dest.parentFile?.mkdirs()
        var truncated = false
        res.body!!.byteStream().use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (total + n > maxBytes) {
                        out.write(buf, 0, (maxBytes - total).toInt())
                        truncated = true
                        break
                    }
                    out.write(buf, 0, n)
                    total += n
                }
            }
        }
        return truncated
    }

    private fun request(url: String): Request.Builder {
        val t = token()?.takeIf { it.isNotBlank() } ?: throw GitHubError.Unauthorized("No GitHub token saved")
        return Request.Builder().url(url)
            .header("Authorization", "Bearer $t")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("User-Agent", UA)
    }

    private fun buildUrl(path: String, query: Map<String, String>): String {
        val b = (baseUrl + path).toHttpUrl().newBuilder()
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    private fun capture(res: Response) {
        // Every response (304s too) carries GitHub's clock; keep the phone-vs-GitHub offset fresh.
        res.headers.getDate("Date")?.let { GhClock.sync(it.time) }
        val remaining = res.header("x-ratelimit-remaining")?.toIntOrNull() ?: return
        val limit = res.header("x-ratelimit-limit")?.toIntOrNull() ?: 0
        val reset = res.header("x-ratelimit-reset")?.toLongOrNull() ?: 0L
        rateLimit = RateLimit(remaining, limit, reset)
    }

    private fun toError(res: Response): GitHubError {
        val msg = runCatching { JSONObject(res.body?.string().orEmpty()).optString("message") }.getOrNull()
        return GitHubError.from(
            code = res.code,
            apiMessage = msg,
            remaining = res.header("x-ratelimit-remaining")?.toIntOrNull(),
            reset = res.header("x-ratelimit-reset")?.toLongOrNull(),
            retryAfter = res.header("retry-after")?.toLongOrNull(),
        )
    }

    companion object {
        const val ACCEPT_JSON = "application/vnd.github+json"
        const val API_VERSION = "2022-11-28"
        const val UA = "Kodex-Android"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
