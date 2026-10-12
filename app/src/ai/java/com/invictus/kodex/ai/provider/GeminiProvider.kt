package com.invictus.kodex.ai.provider

import java.net.URLEncoder
import kotlinx.coroutines.flow.Flow
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class GeminiProvider : AiProvider {
    override val id = "gemini"
    override val displayName = "Google Gemini"
    override val defaultModels = listOf(
        AiModel(DEFAULT_MODEL_ID, "Gemini 3.8 Flash", 1_000_000),
        AiModel("gemini-3.1-pro-preview", "Gemini 3.1 Pro", 1_000_000),
    )

    override fun capabilities() = Capabilities()

    private fun body(req: AiRequest) = JSONObject().apply {
        val generation = JSONObject()
            .put("maxOutputTokens", req.maxOutputTokens)
            .put("temperature", req.temperature)
        // Hidden thinking tokens count against maxOutputTokens and can leave short answers (commit
        // messages) empty. 2.5 Flash allows turning thinking off; Gemini 3 models can't, so use the
        // lowest level every Gemini 3 model accepts. 2.5 Pro has neither, so it is left alone.
        when {
            req.model.contains("2.5-flash") ->
                generation.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            req.model.startsWith("gemini-3") ->
                generation.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        }
        val system = req.messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        if (system.isNotEmpty()) {
            put("systemInstruction", JSONObject().put("parts", JSONArray(listOf(JSONObject().put("text", system)))))
        }
        put("contents", JSONArray(req.messages.filter { it.role != "system" }.map {
            JSONObject()
                .put("role", if (it.role == "assistant") "model" else "user")
                .put("parts", JSONArray(listOf(JSONObject().put("text", it.content))))
        }))
        put("generationConfig", generation)
    }

    private fun url(model: String, streaming: Boolean) =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:" +
            (if (streaming) "streamGenerateContent?alt=sse" else "generateContent")

    private fun request(key: String, model: String, req: AiRequest, streaming: Boolean) = Request.Builder()
        .url(url(model, streaming))
        .header("x-goog-api-key", key)
        .header("content-type", "application/json")
        .post(AiHttp.jsonBody(body(req)))
        .build()

    private fun extractText(obj: JSONObject): String? =
        obj.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?.let { parts -> (0 until parts.length()).joinToString("") { i -> parts.getJSONObject(i).optString("text") } }

    override suspend fun complete(key: String, model: String, request: AiRequest): AiResponse =
        AiHttp.completeOrThrow(AiHttp.complete(request(key, model, request.copy(model = model), streaming = false))) {
            extractText(it) ?: throw IllegalStateException("no text in response")
        }

    override fun stream(key: String, model: String, request: AiRequest): Flow<AiEvent> =
        AiHttp.stream(request(key, model, request.copy(model = model), streaming = true)) { extractText(it) }

    override suspend fun listModels(key: String): List<AiModel>? {
        val all = mutableListOf<AiModel>()
        var pageToken: String? = null
        repeat(MAX_PAGES) {
            val page = pageToken?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200$page")
                .header("x-goog-api-key", key)
                .get()
                .build()
            val http = AiHttp.complete(req) as? AiHttp.CompleteResult.Http ?: return null
            if (http.code !in 200..299) return null
            val obj = runCatching { JSONObject(http.body) }.getOrNull() ?: return null
            all += parseModels(obj)
            pageToken = obj.optString("nextPageToken").takeIf { it.isNotEmpty() } ?: return all
        }
        return all
    }

    companion object {
        const val DEFAULT_MODEL_ID = "gemini-3.8-flash"
        private const val MAX_PAGES = 5

        // Generative-media / realtime / non-chat variants that also advertise generateContent.
        private val NOT_CHAT = listOf(
            "tts", "image", "live", "audio", "native", "embedding", "robotics", "computer-use", "omni", "veo", "lyria",
        )

        /** Chat-capable Gemini models from a `models.list` response page. */
        fun parseModels(obj: JSONObject): List<AiModel> {
            val arr = obj.optJSONArray("models") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = m.optString("name").removePrefix("models/")
                if (!id.startsWith("gemini-") || NOT_CHAT.any { id.contains(it) }) return@mapNotNull null
                val methods = m.optJSONArray("supportedGenerationMethods") ?: return@mapNotNull null
                if ((0 until methods.length()).none { methods.optString(it) == "generateContent" }) return@mapNotNull null
                val label = m.optString("displayName").ifBlank { id }
                val chars = (m.optLong("inputTokenLimit", 0L) * 3L).coerceIn(100_000L, 1_000_000L).toInt()
                AiModel(id, label, chars)
            }
        }
    }
}
