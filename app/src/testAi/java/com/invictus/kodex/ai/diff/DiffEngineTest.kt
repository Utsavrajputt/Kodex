package com.invictus.kodex.ai.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffEngineTest {
    private val old = "a\nb\nc\n"
    private val new = "a\nB\nc\n"

    @Test fun identicalTextHasNoHunks() {
        assertTrue(DiffEngine.diff(old, old).isEmpty())
    }

    @Test fun acceptedHunkProducesNewText() {
        assertEquals(new, DiffEngine.applySelected(old, DiffEngine.diff(old, new)))
    }

    @Test fun rejectedHunkKeepsOriginalText() {
        val rejected = DiffEngine.diff(old, new).map { it.copy(included = false) }
        assertEquals(old, DiffEngine.applySelected(old, rejected))
    }

    @Test fun onlyAcceptedHunksAreApplied() {
        val oldText = (1..30).joinToString("\n", postfix = "\n") { "line$it" }
        val newText = oldText.replace("line2\n", "LINE2\n").replace("line28\n", "LINE28\n")
        val hunks = DiffEngine.diff(oldText, newText)
        assertEquals(2, hunks.size)
        val result = DiffEngine.applySelected(oldText, listOf(hunks[0], hunks[1].copy(included = false)))
        assertTrue(result.contains("LINE2\n"))
        assertTrue(result.contains("line28\n"))
    }

    @Test fun unifiedTextMarksAddsAndRemoves() {
        val text = DiffEngine.toUnifiedText(DiffEngine.diff(old, new))
        assertTrue(text.contains("-b"))
        assertTrue(text.contains("+B"))
    }
}
