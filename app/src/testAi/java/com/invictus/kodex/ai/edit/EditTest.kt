package com.invictus.kodex.ai.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditTest {
    @Test fun appliesUniqueMatch() {
        val r = EditApplier.apply("a\nb\nc", TextEdit("b", "B"))
        assertEquals("a\nB\nc", (r as EditApplier.Outcome.Applied).newText)
    }

    @Test fun rejectsMissingAndAmbiguousMatches() {
        assertTrue(EditApplier.apply("a\nb", TextEdit("zzz", "x")) is EditApplier.Outcome.Conflict)
        assertTrue(EditApplier.apply("x x", TextEdit("x", "y")) is EditApplier.Outcome.Conflict)
        assertTrue(EditApplier.apply("abc", TextEdit("", "y")) is EditApplier.Outcome.Conflict)
    }

    @Test fun parsesJsonInsideFencesAndProse() {
        val raw = "Here you go:\n```json\n{\"explanation\":\"fix\",\"edits\":[{\"search\":\"a\",\"replace\":\"b\"}]}\n```"
        val p = EditParser.parse(raw)
        assertNotNull(p)
        assertEquals("fix", p!!.explanation)
        assertEquals(TextEdit("a", "b"), p.edits.single())
    }

    @Test fun rejectsGarbageAndEmptyEdits() {
        assertNull(EditParser.parse("no json here"))
        assertNull(EditParser.parse("{\"explanation\":\"x\",\"edits\":[]}"))
    }

    @Test fun sha256IsStable() {
        assertEquals(EditApplier.sha256("abc"), EditApplier.sha256("abc"))
        assertTrue(EditApplier.sha256("abc") != EditApplier.sha256("abd"))
    }
}
