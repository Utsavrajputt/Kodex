package com.invictus.kodex.ai.agent

import android.app.Application
import com.invictus.kodex.ai.prefs.AiPrefs
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The two editable documents (memory, instructions) of one scope: a single project, or the universal one. */
interface MemoryDocs {
    /** True for the universal scope (shown in every project); false for one project. */
    val isGlobal: Boolean

    /** Longest text the editor accepts for [doc]. */
    fun limit(doc: ProjectMemory.Doc): Int = ProjectMemory.MAX_EDIT_CHARS

    suspend fun read(doc: ProjectMemory.Doc): String

    suspend fun write(doc: ProjectMemory.Doc, text: String): Boolean

    /** Same as [write] but survives the editor closing (used when it is dismissed with unsaved edits). */
    fun writeInBackground(doc: ProjectMemory.Doc, text: String)
}

/** One project's memory + instructions, kept in the app's private storage. */
class ProjectMemoryDocs(private val dir: File, private val scope: CoroutineScope) : MemoryDocs {
    override val isGlobal = false
    override suspend fun read(doc: ProjectMemory.Doc) = ProjectMemory.readDoc(dir, doc)
    override suspend fun write(doc: ProjectMemory.Doc, text: String) = ProjectMemory.writeDoc(dir, doc, text)
    override fun writeInBackground(doc: ProjectMemory.Doc, text: String) { scope.launch { write(doc, text) } }
}

/**
 * Universal memory + instructions (name, language, coding style... anything not tied to one project).
 * The memory is a file in app storage; the instructions stay in AI preferences, so text written
 * before universal memory existed is still there.
 */
class GlobalMemoryDocs(app: Application, private val prefs: AiPrefs, private val scope: CoroutineScope) : MemoryDocs {
    private val dir = ProjectMemory.globalDir(app)
    override val isGlobal = true

    override fun limit(doc: ProjectMemory.Doc): Int =
        if (doc == ProjectMemory.Doc.INSTRUCTIONS) AiPrefs.GLOBAL_INSTRUCTIONS_MAX else ProjectMemory.MAX_EDIT_CHARS

    override suspend fun read(doc: ProjectMemory.Doc): String =
        if (doc == ProjectMemory.Doc.INSTRUCTIONS) prefs.globalInstructions.first() else ProjectMemory.readDoc(dir, doc)

    override suspend fun write(doc: ProjectMemory.Doc, text: String): Boolean =
        if (doc == ProjectMemory.Doc.INSTRUCTIONS) {
            runCatching { prefs.setGlobalInstructions(text.take(AiPrefs.GLOBAL_INSTRUCTIONS_MAX)) }.isSuccess
        } else {
            ProjectMemory.writeDoc(dir, doc, text)
        }

    override fun writeInBackground(doc: ProjectMemory.Doc, text: String) { scope.launch { write(doc, text) } }
}
