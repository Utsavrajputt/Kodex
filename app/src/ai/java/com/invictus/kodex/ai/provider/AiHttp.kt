package com.invictus.kodex.ai.provider

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Shared HTTP for AI providers: one OkHttp stack + manual SSE line parsing (no new deps, plan 12). */
object AiHttp {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun jsonBody(obj: JSONObject) = obj.toString().toRequestBody(jsonMedia)

    sealed interface CompleteResult {
        data class Http(val code: Int, val body: String) : CompleteResult
        data object Timeout : CompleteResult
        data class Net(val message: String) : CompleteResult
    }

    suspend fun complete(request: Request): CompleteResult = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { CompleteResult.Http(it.code, it.body?.string().orEmpty()) }
        } catch (e: SocketTimeoutException) {
            CompleteResult.Timeout
        } catch (e: IOException) {
            CompleteResult.Net(e.message ?: "network error")
        }
    }

    /** Low-level SSE: emits every `data:` payload; non-2xx collapses to a single Error event. */
    fun stream(request: Request, parsePayload: (JSONObject) -> String?): Flow<AiEvent> = channelFlow {
        val call = client.newCall(request)
        // Collector cancellation aborts the blocking socket read. (Do NOT use awaitClose here:
        // it suspends until the channel closes, so the request below would never run.)
        coroutineContext.job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    send(AiEvent.Error(mapHttpError(resp.code, resp.body?.string().orEmpty(), resp.header("retry-after"))))
                    return@use
                }
                val src = resp.body!!.source()
                var data = StringBuilder()
                var finished = false
                while (!finished) {
                    val line = src.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            val payload = data.toString().also { data = StringBuilder() }
                            when {
                                payload == "[DONE]" -> finished = true
                                payload.isNotEmpty() -> runCatching { JSONObject(payload) }.getOrNull()
                                    ?.let { obj -> parsePayload(obj)?.takeIf { it.isNotEmpty() }?.let { send(AiEvent.Delta(it)) } }
                            }
                        }
                        line.startsWith("data:") -> data.append(line.substring(5).trimStart())
                        line.startsWith(":") -> Unit // keep-alive comment
                    }
                }
                send(AiEvent.Done)
            }
        } catch (e: Exception) {
            if (!call.isCanceled()) {
                send(AiEvent.Error(if (e is SocketTimeoutException) AiError.Timeout() else AiError.Network(e)))
            }
        }
    }.flowOn(Dispatchers.IO) // call.execute() blocks; collectors run on Main (viewModelScope / LaunchedEffect)

    fun mapHttpError(code: Int, body: String, retryAfter: String?): AiError {
        val msg = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optString("message").takeIf { it.isNotBlank() } ?: body.take(200)
        }.getOrDefault(body.take(200))
        val lower = msg.lowercase()
        return when {
            code == 401 || lower.contains("invalid_api_key") || lower.contains("invalid x-api-key") ||
                lower.contains("api key not valid") -> AiError.Unauthorized()
            code == 429 -> AiError.RateLimited(retryAfter?.toIntOrNull())
            (code == 400 || code == 413) && (lower.contains("context length") || lower.contains("context window") ||
                lower.contains("too long") || lower.contains("too many tokens") || lower.contains("maximum context")) -> AiError.ContextTooLong()
            (code == 400 || code == 403) && (lower.contains("content_filter") || lower.contains("blocked") || lower.contains("safety")) -> AiError.ContentFiltered()
            else -> AiError.Unknown(code, msg)
        }
    }

    fun completeOrThrow(result: CompleteResult, onOk: (JSONObject) -> String): AiResponse = when (result) {
        is CompleteResult.Timeout -> throw AiException(AiError.Timeout())
        is CompleteResult.Net -> throw AiException(AiError.Network(IOException(result.message)))
        is CompleteResult.Http -> if (result.code in 200..299) {
            AiResponse(runCatching { onOk(JSONObject(result.body)) }
                .getOrElse { throw AiException(AiError.Malformed(result.body.take(200))) })
        } else throw AiException(mapHttpError(result.code, result.body, null))
    }
}
