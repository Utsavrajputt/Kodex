package com.invictus.kodex.ai.provider

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWireTest {
    private val call = ToolCall("call_1", "read_file", JSONObject().put("path", "A.kt"), signature = "sig", idFromApi = true)
    private val transcript = listOf(
        AgentMessage.User("look at A"),
        AgentMessage.Assistant("Reading it.", listOf(call, ToolCall("call_2", "search", JSONObject().put("query", "x"), idFromApi = false))),
        AgentMessage.ToolResult("call_1", "read_file", "1| code"),
        AgentMessage.ToolResult("call_2", "search", "no matches", isError = true, echoId = false),
    )

    @Test fun claudeGroupsToolResultsIntoOneUserTurn() {
        val out = ClaudeProvider.toMessages(transcript)
        assertEquals(3, out.length())
        val assistant = out.getJSONObject(1).getJSONArray("content")
        assertEquals("text", assistant.getJSONObject(0).getString("type"))
        assertEquals("tool_use", assistant.getJSONObject(1).getString("type"))
        assertEquals("A.kt", assistant.getJSONObject(1).getJSONObject("input").getString("path"))
        val results = out.getJSONObject(2).getJSONArray("content")
        assertEquals(2, results.length())
        assertEquals("call_1", results.getJSONObject(0).getString("tool_use_id"))
        assertTrue(results.getJSONObject(1).getBoolean("is_error"))
    }

    @Test fun openAiUsesToolRoleAndStringArguments() {
        val out = OpenAiProvider.toMessages(transcript)
        assertEquals(4, out.length())
        val calls = out.getJSONObject(1).getJSONArray("tool_calls")
        assertEquals("function", calls.getJSONObject(0).getString("type"))
        assertEquals("""{"path":"A.kt"}""", calls.getJSONObject(0).getJSONObject("function").getString("arguments"))
        assertEquals("tool", out.getJSONObject(2).getString("role"))
        assertEquals("call_2", out.getJSONObject(3).getString("tool_call_id"))
    }

    @Test fun geminiEchoesSignaturesAndOnlyApiIssuedIds() {
        val out = GeminiProvider.toContents(transcript)
        assertEquals(3, out.length())
        val parts = out.getJSONObject(1).getJSONArray("parts")
        val first = parts.getJSONObject(1)
        assertEquals("sig", first.getString("thoughtSignature"))
        assertEquals("call_1", first.getJSONObject("functionCall").getString("id"))
        assertFalse(parts.getJSONObject(2).getJSONObject("functionCall").has("id"))
        val responses = out.getJSONObject(2).getJSONArray("parts")
        assertEquals(2, responses.length())
        assertEquals("call_1", responses.getJSONObject(0).getJSONObject("functionResponse").getString("id"))
        assertFalse(responses.getJSONObject(1).getJSONObject("functionResponse").has("id"))
        assertTrue(responses.getJSONObject(1).getJSONObject("functionResponse").getJSONObject("response").has("error"))
    }

    @Test fun assistantTurnsWithNothingInThemAreSkipped() {
        val msgs = listOf(AgentMessage.User("hi"), AgentMessage.Assistant("", emptyList()), AgentMessage.User("again"))
        assertEquals(2, ClaudeProvider.toMessages(msgs).length())
        assertEquals(2, GeminiProvider.toContents(msgs).length())
    }
}
