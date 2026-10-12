package com.invictus.kodex.ai.agent

import com.invictus.kodex.ai.provider.ToolCall
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Env(val root: File, val pending: PendingChanges, val tools: AgentTools)

    private fun env(globs: String = ".env, *.pem"): Env {
        val root = tmp.newFolder("proj")
        val pending = PendingChanges()
        return Env(root, pending, AgentTools(ProjectFiles(root, globs, DiskIo), pending))
    }

    private fun write(root: File, rel: String, text: String) = File(root, rel).apply { parentFile?.mkdirs(); writeText(text) }

    private fun run(e: Env, name: String, args: JSONObject) = runBlocking { e.tools.execute(ToolCall("c1", name, args)) }

    private fun edit(vararg pairs: Pair<String, String>) = JSONArray(pairs.map { JSONObject().put("search", it.first).put("replace", it.second) })

    @Test fun readFileNumbersLinesAndPages() {
        val e = env()
        write(e.root, "a/B.kt", "one\ntwo\nthree\n")
        val out = run(e, "read_file", JSONObject().put("path", "a/B.kt").put("start_line", 2))
        assertFalse(out.isError)
        assertTrue(out.text.contains("2| two"))
        assertTrue(out.text.contains("3| three"))
        assertFalse(out.text.contains("1| one"))
    }

    @Test fun pathsCannotEscapeTheProjectOrReachBlockedFiles() {
        val e = env()
        write(e.root, ".env", "SECRET=1")
        File(e.root.parentFile, "outside.txt").writeText("x")
        assertTrue(run(e, "read_file", JSONObject().put("path", "../outside.txt")).isError)
        assertTrue(run(e, "read_file", JSONObject().put("path", ".env")).isError)
        assertTrue(run(e, "edit_file", JSONObject().put("path", ".env").put("edits", edit("1" to "2"))).isError)
        assertTrue(run(e, "create_file", JSONObject().put("path", "../evil.txt").put("content", "x")).isError)
    }

    @Test fun editStagesWithoutTouchingDiskAndLaterReadsSeeIt() {
        val e = env()
        val f = write(e.root, "src/Main.kt", "one\ntwo\nthree\n")
        val out = run(e, "edit_file", JSONObject().put("path", "src/Main.kt").put("edits", edit("two" to "2")))
        assertFalse(out.text, out.isError)
        assertEquals("one\ntwo\nthree\n", f.readText())
        val staged = e.pending.snapshot().single()
        assertEquals(ChangeKind.EDIT, staged.kind)
        assertEquals("one\n2\nthree\n", staged.newText)
        assertTrue(run(e, "read_file", JSONObject().put("path", "src/Main.kt")).text.contains("2| 2"))
    }

    @Test fun failedEditStagesNothing() {
        val e = env()
        write(e.root, "A.kt", "alpha\nbeta\n")
        val out = run(e, "edit_file", JSONObject().put("path", "A.kt").put("edits", edit("beta" to "B", "missing" to "x")))
        assertTrue(out.isError)
        assertTrue(e.pending.snapshot().isEmpty())
    }

    @Test fun searchBlocksWithLineNumberPrefixesStillMatch() {
        val e = env()
        write(e.root, "A.kt", "alpha\nbeta\n")
        val out = run(e, "edit_file", JSONObject().put("path", "A.kt").put("edits", edit("2| beta" to "B")))
        assertFalse(out.text, out.isError)
        assertEquals("alpha\nB\n", e.pending.snapshot().single().newText)
    }

    @Test fun crlfFilesKeepTheirLineEndings() {
        val e = env()
        write(e.root, "W.txt", "a\r\nb\r\n")
        run(e, "edit_file", JSONObject().put("path", "W.txt").put("edits", edit("b" to "c")))
        assertEquals("a\r\nc\r\n", e.pending.snapshot().single().newText)
    }

    @Test fun searchSkipsBlockedFilesAndHonoursGlob() {
        val e = env()
        write(e.root, "x.txt", "needle here")
        write(e.root, "y.md", "needle too")
        write(e.root, ".env", "needle secret")
        val all = run(e, "search", JSONObject().put("query", "needle")).text
        assertTrue(all.contains("x.txt:1"))
        assertTrue(all.contains("y.md:1"))
        assertFalse(all.contains(".env"))
        val onlyMd = run(e, "search", JSONObject().put("query", "needle").put("path_glob", "*.md")).text
        assertTrue(onlyMd.contains("y.md"))
        assertFalse(onlyMd.contains("x.txt"))
    }

    @Test fun createDeleteAndNetNothing() {
        val e = env()
        write(e.root, "old.txt", "bye")
        assertTrue(run(e, "create_file", JSONObject().put("path", "old.txt").put("content", "x")).isError)
        assertFalse(run(e, "create_file", JSONObject().put("path", "n/new.txt").put("content", "hi")).isError)
        assertFalse(run(e, "delete_file", JSONObject().put("path", "old.txt")).isError)
        assertEquals(setOf(ChangeKind.CREATE, ChangeKind.DELETE), e.pending.snapshot().map { it.kind }.toSet())
        assertFalse(File(e.root, "n/new.txt").exists())
        assertTrue(File(e.root, "old.txt").exists())
        run(e, "delete_file", JSONObject().put("path", "n/new.txt"))
        assertEquals(listOf("old.txt"), e.pending.snapshot().map { it.path })
    }

    @Test fun listFilesSkipsBuildOutputAndBlocked() {
        val e = env()
        write(e.root, "src/A.kt", "x")
        write(e.root, "build/out.bin", "x")
        write(e.root, "keys/id.pem", "x")
        val out = run(e, "list_files", JSONObject().put("depth", 3)).text
        assertTrue(out.contains("A.kt"))
        assertFalse(out.contains("build/"))
        assertFalse(out.contains("id.pem"))
    }
}
