package com.invictus.kodex.ai.provider

import kotlinx.coroutines.flow.Flow
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class OpenAiProvider : AiProvider {
    override val id = "openai"
    override val displayName = "OpenAI"
    // Defaults follow OpenAI's current GPT-5.6 tiers; any other id can go in the custom-model field.
    override val defaultModels = listOf(
        AiModel("gpt-5.6-luna", "GPT-5.6 Luna", 400_000),
        AiModel("gpt-5.6-terra", "GPT-5.6 Terra", 400_000),
        AiModel("gpt-5.6-sol", "GPT-5.6 Sol", 400_000),
    )

    override fun capabilities() = Capabilities()

    private fun body(req: AiRequest, stream: Boolean) = JSONObject()
        .put("model", req.model)
        .put("messages", JSONArray(req.messages.map { JSONObject().put("role", it.role).put("content", it.content) }))
        // GPT-5-era models reject `max_tokens` and any non-default `temperature`; max_completion_tokens
        // is accepted by every current chat-completions model, so send only that.
        .put("max_completion_tokens", req.maxOutputTokens)
        .put("stream", stream)

    private fun request(key: String, req: AiRequest, stream: Boolean) = Request.Builder()
        .url("https://api.openai.com/v1/chat/completions")
        .header("Authorization", "Bearer $key")
        .header("content-type", "application/json")
        .post(AiHttp.jsonBody(body(req, stream)))
        .build()

    override suspend fun complete(key: String, model: String, request: AiRequest): AiResponse =
        AiHttp.completeOrThrow(AiHttp.complete(request(key, request.copy(model = model), stream = false))) { obj ->
            obj.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }

    override fun stream(key: String, model: String, request: AiRequest): Flow<AiEvent> =
        AiHttp.stream(request(key, request.copy(model = model), stream = true)) { obj ->
            obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content")
        }
}
