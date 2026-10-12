package com.invictus.kodex.ai.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParseTest {
    @Test fun headingsParagraphsAndRules() {
        val b = parseMarkdown("# Title\n\nSome text\nmore text\n\n---\n")
        assertEquals(MdBlock.Heading(1, "Title"), b[0])
        assertEquals(MdBlock.Para("Some text more text"), b[1])
        assertEquals(MdBlock.Rule, b[2])
    }

    @Test fun listsKeepIndentAndMarkers() {
        val b = parseMarkdown("- one\n  - nested\n1. first\n2) second")
        assertEquals(MdBlock.Item(0, "•", "one"), b[0])
        assertEquals(MdBlock.Item(1, "•", "nested"), b[1])
        assertEquals(MdBlock.Item(0, "1.", "first"), b[2])
        assertEquals(MdBlock.Item(0, "2)", "second"), b[3])
    }

    @Test fun fencedCodeKeepsLanguageAndTracksClosing() {
        val closed = parseMarkdown("```kotlin\nval x = 1\n```\nafter").first() as MdBlock.Code
        assertEquals("kotlin", closed.lang)
        assertEquals("val x = 1", closed.code)
        assertTrue(closed.closed)
        val open = parseMarkdown("```py\nprint(1)").first() as MdBlock.Code
        assertFalse(open.closed)
        assertEquals("print(1)", open.code)
    }

    @Test fun tablesParseHeaderAndRows() {
        val t = parseMarkdown("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |").first() as MdBlock.Table
        assertEquals(listOf("a", "b"), t.header)
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), t.rows)
    }

    @Test fun quotesAndMixedContent() {
        val b = parseMarkdown("> note\n> more\n\ntext")
        assertEquals(MdBlock.Quote("note\nmore"), b[0])
        assertEquals(MdBlock.Para("text"), b[1])
    }
}
