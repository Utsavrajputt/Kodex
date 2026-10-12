package com.invictus.kodex.ai.agent

import com.invictus.kodex.feature.editor.EditorViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Text read/write seam: plain disk, or the editor's open buffers (which may hold unsaved text). */
interface WorkspaceIo {
    suspend fun read(file: File): String?
    suspend fun write(file: File, text: String)
    suspend fun delete(file: File)
}

object DiskIo : WorkspaceIo {
    override suspend fun read(file: File): String? = withContext(Dispatchers.IO) {
        runCatching { if (file.isFile) file.readText() else null }.getOrNull()
    }

    override suspend fun write(file: File, text: String) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    override suspend fun delete(file: File) {
        withContext(Dispatchers.IO) { file.delete() }
    }
}

/** Reads and writes go through an open editor tab when there is one, so unsaved edits are neither lost nor clobbered. */
class EditorIo(private val editor: EditorViewModel) : WorkspaceIo {
    override suspend fun read(file: File): String? {
        val fromBuffer = withContext(Dispatchers.Main) {
            editor.buffer(file.path)?.takeIf { !it.isPaged }?.content?.toString()
        }
        return fromBuffer ?: DiskIo.read(file)
    }

    override suspend fun write(file: File, text: String) {
        val wrote = withContext(Dispatchers.Main) {
            val buffer = editor.buffer(file.path)?.takeIf { !it.isPaged } ?: return@withContext false
            // One replace over the whole range = one undo step in the editor.
            val c = buffer.content
            c.replace(0, 0, c.lineCount - 1, c.getColumnCount(c.lineCount - 1), text)
            true
        }
        if (!wrote) DiskIo.write(file, text)
    }

    override suspend fun delete(file: File) = DiskIo.delete(file)
}
