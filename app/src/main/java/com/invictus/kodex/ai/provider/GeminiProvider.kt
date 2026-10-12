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

    override fun streamAgent(
        key: String, model: String, system: String,
        messages: List<AgentMessage>, tools: List<ToolSpec>, maxOutputTokens: Int,
    ): Flow<AgentEvent> {
        val generation = JSONObject().put("maxOutputTokens", maxOutputTokens)
        when {
            model.contains("2.5-flash") -> generation.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            model.startsWith("gemini-3") -> generation.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray(listOf(JSONObject().put("text", system)))))
            .put("contents", toContents(messages))
            .put("generationConfig", generation)
        if (tools.isNotEmpty()) {
            body.put("tools", JSONArray(listOf(JSONObject().put("functionDeclarations", JSONArray(tools.map {
                JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.schema)
            })))))
        }
        val req = Request.Builder()
            .url(url(model, streaming = true))
            .header("x-goog-api-key", key)
            .header("content-type", "application/json")
            .post(AiHttp.jsonBody(body))
            .build()

        var counter = 0
        val handle: (JSONObject) -> List<AgentEvent> = handle@{ obj ->
            val parts = obj.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return@handle emptyList()
            val events = mutableListOf<AgentEvent>()
            for (n in 0 until parts.length()) {
                val part = parts.optJSONObject(n) ?: continue
                val fc = part.optJSONObject("functionCall")
                if (fc != null) {
                    val apiId = fc.optString("id")
                    events += AgentEvent.Call(
                        ToolCall(
                            id = apiId.ifEmpty { "call_${counter++}" },
                            name = fc.optString("name"),
                            args = fc.optJSONObject("args") ?: JSONObject(),
                            signature = part.optString("thoughtSignature").ifEmpty { null },
                            idFromApi = apiId.isNotEmpty(),
                        ),
                    )
                } else if (!part.optBoolean("thought", false)) {
                    part.optString("text").takeIf { it.isNotEmpty() }?.let { events += AgentEvent.Delta(it) }
                }
            }
            events
        }
        return AiHttp.sse(req, handle, { emptyList() }, AgentEvent.Done) { AgentEvent.Error(it) }
    }

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

        /** Neutral transcript -> Gemini `contents` (consecutive tool results share one user turn). */
        fun toContents(msgs: List<AgentMessage>): JSONArray {
            val out = JSONArray()
            var i = 0
            while (i < msgs.size) {
                when (val m = msgs[i]) {
                    is AgentMessage.User -> {
                        out.put(JSONObject().put("role", "user").put("parts", JSONArray(listOf(JSONObject().put("text", m.text)))))
                        i++
                    }
                    is AgentMessage.Assistant -> {
                        val parts = JSONArray()
                        if (m.text.isNotBlank()) parts.put(JSONObject().put("text", m.text))
                        m.calls.forEach { c ->
                            val fc = JSONObject().put("name", c.name).put("args", c.args)
                            if (c.idFromApi) fc.put("id", c.id)
                            val part = JSONObject().put("functionCall", fc)
                            c.signature?.let { part.put("thoughtSignature", it) }
                            parts.put(part)
                        }
                        if (parts.length() > 0) out.put(JSONObject().put("role", "model").put("parts", parts))
                        i++
                    }
                    is AgentMessage.ToolResult -> {
                        val parts = JSONArray()
                        while (i < msgs.size && msgs[i] is AgentMessage.ToolResult) {
                            val r = msgs[i] as AgentMessage.ToolResult
                            val fr = JSONObject().put("name", r.name)
                                .put("response", JSONObject().put(if (r.isError) "error" else "result", r.content))
                            if (r.echoId) fr.put("id", r.callId)
                            parts.put(JSONObject().put("functionResponse", fr))
                            i++
                        }
                        out.put(JSONObject().put("role", "user").put("parts", parts))
                    }
                }
            }
            return out
        }

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
