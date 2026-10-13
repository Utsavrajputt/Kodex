package com.invictus.kodex.ai.provider

import org.json.JSONObject

/** A function the model may call. [schema] is a JSON-Schema object limited to type/properties/required/items/description/enum (the subset all three providers accept). */
data class ToolSpec(val name: String, val description: String, val schema: JSONObject)

data class ToolCall(
    val id: String,
    val name: String,
    val args: JSONObject,
    /** Gemini 3 thought signature: must be sent back with the call or the next request is rejected. */
    val signature: String? = null,
    /** Gemini: true when [id] came from the API (the response must then echo it). */
    val idFromApi: Boolean = true,
)

/** Provider-neutral agent transcript. Each provider maps it to its own wire format. */
sealed interface AgentMessage {
    data class User(val text: String, val images: List<ChatImage> = emptyList()) : AgentMessage
    data class Assistant(val text: String, val calls: List<ToolCall> = emptyList()) : AgentMessage
    data class ToolResult(
        val callId: String,
        val name: String,
        val content: String,
        val isError: Boolean = false,
        /** Gemini: echo [callId] in the response only when the API issued it. */
        val echoId: Boolean = true,
    ) : AgentMessage
}

sealed interface AgentEvent {
    data class Delta(val text: String) : AgentEvent
    data class Call(val call: ToolCall) : AgentEvent

    /** A summary of the model's reasoning (Gemini thought summaries); shown in the steps timeline, never sent back. */
    data class Thought(val text: String) : AgentEvent
    data object Done : AgentEvent
    data class Error(val error: AiError) : AgentEvent
}
