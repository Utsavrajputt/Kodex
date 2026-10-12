package com.invictus.kodex.ai.provider

import kotlinx.coroutines.flow.Flow
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class ClaudeProvider : AiProvider {
    override val id = "claude"
    override val displayName = "Anthropic Claude"
    // Current Claude API ids; the settings screen also allows a custom id for anything newer.
    override val defaultModels = listOf(
        AiModel("claude-sonnet-5-5", "Claude Sonnet 5.5", 800_000),
        AiModel("claude-opus-5-5", "Claude Opus 5.5", 800_000),
        AiModel("claude-haiku-5-5", "Claude Haiku 5.5", 800_000),
    )

    override fun capabilities() = Capabilities()

    private fun headers(key: String) = listOf(
        "x-api-key" to key, "anthropic-version" to "2023-06-01", "content-type" to "application/json",
    )

    private fun body(req: AiRequest, stream: Boolean) = JSONObject()
        .put("model", req.model)
        .put("max_tokens", req.maxOutputTokens)
        .put("temperature", req.temperature)
        .put("stream", stream)
        .apply {
            val system = req.messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
            if (system.isNotEmpty()) put("system", system)
            put("messages", JSONArray(req.messages.filter { it.role != "system" }
                .map { m -> JSONObject().put("role", m.role).put("content", m.content) }))
        }

    private fun request(key: String, req: AiRequest, stream: Boolean): Request {
        val rb = Request.Builder().url("https://api.anthropic.com/v1/messages")
        headers(key).forEach { (h, v) -> rb.header(h, v) }
        return rb.post(AiHttp.jsonBody(body(req, stream))).build()
    }

    override suspend fun complete(key: String, model: String, request: AiRequest): AiResponse =
        AiHttp.completeOrThrow(AiHttp.complete(request(key, request.copy(model = model), stream = false))) { obj ->
            obj.getJSONArray("content").let { arr ->
                (0 until arr.length()).joinToString("") { i -> arr.getJSONObject(i).optString("text") }
            }
        }

    override fun stream(key: String, model: String, request: AiRequest): Flow<AiEvent> =
        AiHttp.stream(request(key, request.copy(model = model), stream = true)) { obj ->
            if (obj.optString("type") == "content_block_delta") obj.optJSONObject("delta")?.optString("text") else null
        }
}
