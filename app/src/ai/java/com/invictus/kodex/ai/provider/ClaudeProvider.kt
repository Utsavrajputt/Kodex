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

    override fun streamAgent(
        key: String, model: String, system: String,
        messages: List<AgentMessage>, tools: List<ToolSpec>, maxOutputTokens: Int,
    ): Flow<AgentEvent> {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxOutputTokens)
            .put("stream", true)
            .put("system", system)
            .put("messages", toMessages(messages))
        if (tools.isNotEmpty()) {
            body.put("tools", JSONArray(tools.map {
                JSONObject().put("name", it.name).put("description", it.description).put("input_schema", it.schema)
            }))
        }
        val rb = Request.Builder().url("https://api.anthropic.com/v1/messages")
        headers(key).forEach { (h, v) -> rb.header(h, v) }
        val req = rb.post(AiHttp.jsonBody(body)).build()

        val ids = HashMap<Int, String>()
        val names = HashMap<Int, String>()
        val json = HashMap<Int, StringBuilder>()
        val handle: (JSONObject) -> List<AgentEvent> = { obj ->
            val index = obj.optInt("index")
            when (obj.optString("type")) {
                "content_block_start" -> {
                    val block = obj.optJSONObject("content_block")
                    if (block != null && block.optString("type") == "tool_use") {
                        ids[index] = block.optString("id")
                        names[index] = block.optString("name")
                        json[index] = StringBuilder()
                    }
                    emptyList()
                }
                "content_block_delta" -> {
                    val delta = obj.optJSONObject("delta")
                    if (delta == null) emptyList() else when (delta.optString("type")) {
                        "text_delta" -> listOf(AgentEvent.Delta(delta.optString("text")))
                        "input_json_delta" -> { json[index]?.append(delta.optString("partial_json")); emptyList() }
                        else -> emptyList()
                    }
                }
                "content_block_stop" -> {
                    val id = ids.remove(index)
                    if (id == null) emptyList() else {
                        val raw = json.remove(index)?.toString().orEmpty().ifBlank { "{}" }
                        val args = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                        listOf(AgentEvent.Call(ToolCall(id, names.remove(index).orEmpty(), args)))
                    }
                }
                "error" -> listOf(
                    AgentEvent.Error(AiError.Unknown(0, obj.optJSONObject("error")?.optString("message") ?: "stream error")),
                )
                else -> emptyList()
            }
        }
        return AiHttp.sse(req, handle, { emptyList() }, AgentEvent.Done) { AgentEvent.Error(it) }
    }

    companion object {
        /** Neutral transcript -> Anthropic messages (consecutive tool results share one user turn). */
        fun toMessages(msgs: List<AgentMessage>): JSONArray {
            val out = JSONArray()
            var i = 0
            while (i < msgs.size) {
                when (val m = msgs[i]) {
                    is AgentMessage.User -> {
                        out.put(JSONObject().put("role", "user").put("content", m.text))
                        i++
                    }
                    is AgentMessage.Assistant -> {
                        val blocks = JSONArray()
                        if (m.text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", m.text))
                        m.calls.forEach { c ->
                            blocks.put(JSONObject().put("type", "tool_use").put("id", c.id).put("name", c.name).put("input", c.args))
                        }
                        if (blocks.length() > 0) out.put(JSONObject().put("role", "assistant").put("content", blocks))
                        i++
                    }
                    is AgentMessage.ToolResult -> {
                        val blocks = JSONArray()
                        while (i < msgs.size && msgs[i] is AgentMessage.ToolResult) {
                            val r = msgs[i] as AgentMessage.ToolResult
                            blocks.put(
                                JSONObject().put("type", "tool_result").put("tool_use_id", r.callId)
                                    .put("content", r.content).put("is_error", r.isError),
                            )
                            i++
                        }
                        out.put(JSONObject().put("role", "user").put("content", blocks))
                    }
                }
            }
            return out
        }
    }
}
