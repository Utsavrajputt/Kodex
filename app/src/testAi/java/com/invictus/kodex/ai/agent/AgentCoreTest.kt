package com.invictus.kodex.ai.agent

import com.invictus.kodex.ai.provider.AgentMessage
import com.invictus.kodex.ai.provider.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCoreTest {
    private fun big(n: Int) = "x".repeat(n)

    @Test fun compactShrinksOldToolResultsFirstAndKeepsRecentOnes() {
        val history = mutableListOf<AgentMessage>(
            AgentMessage.User("q"),
            AgentMessage.ToolResult("1", "read_file", big(5000)),
            AgentMessage.ToolResult("2", "read_file", big(5000)),
            AgentMessage.Assistant("a"),
            AgentMessage.ToolResult("3", "read_file", big(5000)),
            AgentMessage.User("next"),
            AgentMessage.Assistant("b"),
            AgentMessage.User("last"),
        )
        Compactor.compact(history, budgetChars = 7000, keepLast = 3)
        assertEquals(Compactor.STUB, (history[1] as AgentMessage.ToolResult).content)
        assertEquals(Compactor.STUB, (history[2] as AgentMessage.ToolResult).content)
        assertEquals(5000, (history[4] as AgentMessage.ToolResult).content.length)
    }

    @Test fun compactIsANoOpUnderBudget() {
        val history = mutableListOf<AgentMessage>(AgentMessage.User("q"), AgentMessage.ToolResult("1", "t", big(1000)))
        Compactor.compact(history, budgetChars = 10_000)
        assertEquals(1000, (history[1] as AgentMessage.ToolResult).content.length)
    }

    @Test fun squeezeTruncatesEverythingBulky() {
        val history = mutableListOf<AgentMessage>(
            AgentMessage.ToolResult("1", "t", big(900)), AgentMessage.Assistant("a"), AgentMessage.User("u"), AgentMessage.User("v"),
        )
        Compactor.squeeze(history, keepLast = 2)
        assertTrue((history[0] as AgentMessage.ToolResult).content.length < 400)
    }

    @Test fun pendingEditsAccumulateOnTheOriginalBase() {
        val p = PendingChanges()
        p.stageEdit("a.kt", "v0", "v1", "first")
        p.stageEdit("a.kt", "v1", "v2", "second")
        val c = p.snapshot().single()
        assertEquals("v0", c.baseText)
        assertEquals("v2", c.newText)
        assertEquals(Effective.Text("v2"), p.effective("a.kt"))
        assertEquals(Effective.None, p.effective("other.kt"))
    }

    @Test fun deleteThenCreateBecomesAReplace() {
        val p = PendingChanges()
        p.stageDelete("a.kt", "old", "")
        assertEquals(Effective.Deleted, p.effective("a.kt"))
        p.stageCreate("a.kt", "new", "")
        val c = p.snapshot().single()
        assertEquals(ChangeKind.EDIT, c.kind)
        assertEquals("old", c.baseText)
        assertEquals("new", c.newText)
    }

    @Test fun createThenDeleteCancelsOut() {
        val p = PendingChanges()
        p.stageCreate("n.kt", "x", "")
        p.stageDelete("n.kt", "", "")
        assertTrue(p.snapshot().isEmpty())
    }

    @Test fun chatsRoundTripThroughJson() {
        val chat = StoredChat(
            "id1", "Title", 42L,
            listOf(
                StoredMessage("user", "hi \"there\"\nline2", listOf("a/B.kt")),
                StoredMessage("assistant", "```kotlin\nval x = 1\n```", steps = listOf(StoredStep("Reading a/B.kt", true), StoredStep("Editing x", false))),
            ),
        )
        assertEquals(chat, ChatStore.fromJson(ChatStore.toJson(chat)))
    }

    @Test fun toolCallsCarryProviderIds() {
        val c = ToolCall("id", "read_file", JSONObject().put("path", "x"))
        assertEquals("id", c.id)
        assertNull(c.signature)
        assertNotNull(c.args)
    }
}
