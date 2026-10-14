package com.invictus.kodex.ai.provider

import kotlinx.coroutines.flow.Flow
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chat-completions client. Defaults to OpenAI itself; with another [baseUrl] it serves every
 * OpenAI-compatible host (OpenRouter, Groq, DeepSeek, Mistral, xAI, Together, Ollama, Hugging Face, custom).
 * [defaultModels] is only the offline / listing-failed fallback; the live list comes from [listModels].
 */
class OpenAiProvider(
    override val id: String = "openai",
    override val displayName: String = "OpenAI",
    baseUrl: String = "https://api.openai.com/v1",
    // Defaults follow OpenAI's current GPT-5.6 tiers; any other id can go in the custom-model field.
    override val defaultModels: List<AiModel> = listOf(
        AiModel("gpt-5.6-luna", "GPT-5.6 Luna", 400_000),
        AiModel("gpt-5.6-terra", "GPT-5.6 Terra", 400_000),
        AiModel("gpt-5.6-sol", "GPT-5.6 Sol", 400_000),
    ),
    /** OpenAI's GPT-5 family rejects `max_tokens`; most other hosts only know `max_tokens`. */
    private val tokenParam: String = "max_completion_tokens",
) : AiProvider {
    private val base = baseUrl.trim().trimEnd('/')
    private val chatUrl = "$base/chat/completions"

    override fun capabilities() = Capabilities()

    override suspend fun listModels(key: String): List<AiModel>? {
        val req = Request.Builder().url("$base/models").header("Authorization", "Bearer $key").get().build()
        val http = AiHttp.complete(req) as? AiHttp.CompleteResult.Http ?: return null
        if (http.code !in 200..299) return null
        val body = http.body.trim()
        val arr = runCatching {
            if (body.startsWith("[")) JSONArray(body) else JSONObject(body).optJSONArray("data")
        }.getOrNull() ?: return null
        val out = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseModel(it) } }
        return out.ifEmpty { null }
    }

    private fun parseModel(m: JSONObject): AiModel? {
        val id = m.optString("id").takeIf { it.isNotBlank() } ?: return null
        val l = id.lowercase()
        if (NOT_CHAT.any { it in l }) return null
        // Hosts that say what a model is (Together `type`, Mistral `capabilities`) let us drop non-chat ones exactly.
        m.optString("type").takeIf { it.isNotBlank() }?.let { if (it != "chat" && it != "language" && it != "code") return null }
        m.optJSONObject("capabilities")?.let { if (it.has("completion_chat") && !it.optBoolean("completion_chat")) return null }
        val tokens = maxOf(m.optLong("context_length"), m.optLong("context_window"), m.optLong("max_model_len"))
        val chars = if (tokens > 0) (tokens * 3L).coerceIn(100_000L, 1_000_000L).toInt() else DEFAULT_CONTEXT_CHARS
        val label = m.optString("name").takeIf { it.isNotBlank() && it != id } ?: id
        return AiModel(id, label, chars, m.optLong("created", 0L))
    }

    private fun body(req: AiRequest, stream: Boolean) = JSONObject()
        .put("model", req.model)
        .put("messages", JSONArray(req.messages.map { JSONObject().put("role", it.role).put("content", it.content) }))
        // GPT-5-era models reject `max_tokens` and any non-default `temperature`; max_completion_tokens
        // is accepted by every current chat-completions model, so send only that.
        .put(tokenParam, req.maxOutputTokens)
        .put("stream", stream)

    private fun request(key: String, req: AiRequest, stream: Boolean) = Request.Builder()
        .url(chatUrl)
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

    private class PartialCall(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    override fun streamAgent(
        key: String, model: String, system: String,
        messages: List<AgentMessage>, tools: List<ToolSpec>, maxOutputTokens: Int,
    ): Flow<AgentEvent> {
        val all = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        val converted = toMessages(messages)
        for (i in 0 until converted.length()) all.put(converted.get(i))
        val body = JSONObject()
            .put("model", model)
            .put("messages", all)
            .put(tokenParam, maxOutputTokens)
            .put("stream", true)
        if (tools.isNotEmpty()) {
            body.put("tools", JSONArray(tools.map {
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.schema),
                )
            }))
        }
        val req = Request.Builder()
            .url(chatUrl)
            .header("Authorization", "Bearer $key")
            .header("content-type", "application/json")
            .post(AiHttp.jsonBody(body))
            .build()

        val partial = java.util.TreeMap<Int, PartialCall>()
        val handle: (JSONObject) -> List<AgentEvent> = handle@{ obj ->
            val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                ?: return@handle emptyList()
            val events = mutableListOf<AgentEvent>()
            if (!delta.isNull("content")) delta.optString("content").takeIf { it.isNotEmpty() }?.let { events += AgentEvent.Delta(it) }
            delta.optJSONArray("tool_calls")?.let { arr ->
                for (n in 0 until arr.length()) {
                    val tc = arr.optJSONObject(n) ?: continue
                    val pc = partial.getOrPut(tc.optInt("index", n)) { PartialCall() }
                    tc.optString("id").takeIf { it.isNotEmpty() }?.let { pc.id = it }
                    tc.optJSONObject("function")?.let { fn ->
                        fn.optString("name").takeIf { it.isNotEmpty() }?.let { pc.name = it }
                        if (!fn.isNull("arguments")) pc.args.append(fn.optString("arguments"))
                    }
                }
            }
            events
        }
        val finish: () -> List<AgentEvent> = {
            partial.values.filter { it.name.isNotEmpty() }.mapIndexed { n, pc ->
                val args = runCatching { JSONObject(pc.args.toString().ifBlank { "{}" }) }.getOrElse { JSONObject() }
                AgentEvent.Call(ToolCall(pc.id.ifEmpty { "call_$n" }, pc.name, args))
            }
        }
        return AiHttp.sse(req, handle, finish, AgentEvent.Done) { AgentEvent.Error(it) }
    }

    companion object {
        const val DEFAULT_CONTEXT_CHARS = 400_000
        private val NOT_CHAT = listOf(
            "embed", "whisper", "tts", "dall-e", "moderation", "image", "audio", "realtime", "transcribe",
            "rerank", "guard", "davinci", "babbage", "sora", "speech",
        )

        /** Neutral transcript -> chat-completions messages (no system message; the caller prepends it). */
        fun toMessages(msgs: List<AgentMessage>): JSONArray = JSONArray().also { out ->
            msgs.forEach { m ->
                when (m) {
                    is AgentMessage.User -> {
                        val encoded = m.images.mapNotNull { img -> img.base64()?.let { img.mime to it } }
                        if (encoded.isEmpty()) {
                            out.put(JSONObject().put("role", "user").put("content", m.text))
                        } else {
                            val parts = JSONArray().put(JSONObject().put("type", "text").put("text", m.text))
                            encoded.forEach { (mime, data) ->
                                parts.put(
                                    JSONObject().put("type", "image_url")
                                        .put("image_url", JSONObject().put("url", "data:$mime;base64,$data")),
                                )
                            }
                            out.put(JSONObject().put("role", "user").put("content", parts))
                        }
                    }
                    is AgentMessage.Assistant -> out.put(
                        JSONObject().put("role", "assistant")
                            .put("content", if (m.text.isBlank()) JSONObject.NULL else m.text)
                            .apply {
                                if (m.calls.isNotEmpty()) put("tool_calls", JSONArray(m.calls.map { c ->
                                    JSONObject().put("id", c.id).put("type", "function").put(
                                        "function", JSONObject().put("name", c.name).put("arguments", c.args.toString()),
                                    )
                                }))
                            },
                    )
                    is AgentMessage.ToolResult -> out.put(
                        JSONObject().put("role", "tool").put("tool_call_id", m.callId).put("content", m.content),
                    )
                }
            }
        }
    }
}
