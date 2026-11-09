package com.invictus.kodex.ai.agent

import com.invictus.kodex.core.archive.ArchiveReader
import java.io.File
import java.io.IOException

/**
 * The filesystem operations the AI can stage next to text edits: move/rename, copy, create folder,
 * delete folder and extract an archive. Each one can be checked ([check]), applied ([apply]) and
 * reversed ([undo]). Folder deletes go to a trash folder outside the project, so they are undoable.
 * Paths are project-relative; callers have already sandboxed them with [ProjectFiles].
 */
object FsOps {

    /** How to reverse one applied operation. */
    sealed interface Undo {
        /** Put [to] back where it was ([from]). */
        data class MoveBack(val from: String, val to: String) : Undo

        /** Delete something the operation created; [onlyIfEmpty] keeps a folder that has since been filled. */
        data class Remove(val path: String, val onlyIfEmpty: Boolean) : Undo

        /** Bring a trashed folder back to [path]. */
        data class Restore(val trashPath: String, val path: String) : Undo
    }

    class Applied(val undo: Undo, val touched: List<String>)

    /** The project root, `.git` and anything inside it are never touched by these operations. */
    fun forbidden(rel: String): Boolean = rel.isEmpty() || rel == ".git" || rel.startsWith(".git/")

    /** Null when [c] can be applied right now, otherwise why not. */
    fun check(root: File, c: FileChange): String? {
        if (!c.kind.isFsOp) return "Not a file operation"
        val src = File(root, c.path)
        if (forbidden(c.path)) return "Can't change ${c.path}"
        val dstRel = c.toPath
        val dst = dstRel?.let { File(root, it) }
        if (dstRel != null && forbidden(dstRel)) return "Can't write to $dstRel"
        return when (c.kind) {
            ChangeKind.MOVE, ChangeKind.COPY -> when {
                dst == null -> "Missing destination"
                !src.exists() -> "Not found: ${c.path}"
                dst.exists() -> "$dstRel already exists"
                src.isDirectory && dst.path.startsWith(src.path + File.separator) -> "Can't put a folder inside itself"
                else -> null
            }
            ChangeKind.MKDIR -> if (src.exists()) "${c.path} already exists" else null
            ChangeKind.TRASH -> when {
                !src.exists() -> "Not found: ${c.path}"
                !src.isDirectory -> "${c.path} is a file; use delete_file"
                else -> null
            }
            ChangeKind.EXTRACT -> when {
                !src.isFile -> "Not found: ${c.path}"
                !ArchiveReader.isArchive(src) -> "${c.path} is not a zip, 7z or rar archive"
                dst == null -> "Missing destination"
                dst.exists() -> "$dstRel already exists"
                else -> null
            }
            else -> "Not a file operation"
        }
    }

    /** Applies [c] (already [check]ed). [trash] is where deleted folders go. Throws on failure, leaving nothing half-done. */
    fun apply(root: File, trash: File, c: FileChange): Applied {
        val src = File(root, c.path)
        val dstRel = c.toPath
        val dst = dstRel?.let { File(root, it) }
        return when (c.kind) {
            ChangeKind.MOVE -> {
                move(src, dst ?: throw IOException("Missing destination"))
                Applied(Undo.MoveBack(c.path, dstRel!!), listOf(c.path, dstRel))
            }
            ChangeKind.COPY -> {
                val to = dst ?: throw IOException("Missing destination")
                to.parentFile?.mkdirs()
                if (!src.copyRecursively(to, overwrite = false)) {
                    to.deleteRecursively()
                    throw IOException("Couldn't copy ${c.path}")
                }
                Applied(Undo.Remove(dstRel!!, onlyIfEmpty = false), listOf(dstRel))
            }
            ChangeKind.MKDIR -> {
                if (!src.mkdirs()) throw IOException("Couldn't create ${c.path}")
                Applied(Undo.Remove(c.path, onlyIfEmpty = true), listOf(c.path))
            }
            ChangeKind.TRASH -> {
                val bin = File(trash, "${System.currentTimeMillis()}/${src.name}")
                bin.parentFile?.mkdirs()
                move(src, bin)
                Applied(Undo.Restore(bin.path, c.path), listOf(c.path))
            }
            ChangeKind.EXTRACT -> {
                val to = dst ?: throw IOException("Missing destination")
                if (!to.mkdirs()) throw IOException("Couldn't create $dstRel")
                try {
                    ArchiveReader.extractInto(src, to)
                } catch (e: Exception) {
                    to.deleteRecursively()
                    throw e
                }
                Applied(Undo.Remove(dstRel!!, onlyIfEmpty = false), listOf(dstRel))
            }
            else -> throw IOException("Not a file operation")
        }
    }

    /** True when reversed; false when the files are no longer as the operation left them. */
    fun undo(root: File, u: Undo): Boolean = when (u) {
        is Undo.MoveBack -> {
            val now = File(root, u.to)
            val orig = File(root, u.from)
            if (now.exists() && !orig.exists()) {
                orig.parentFile?.mkdirs()
                move(now, orig)
                true
            } else false
        }
        is Undo.Remove -> {
            val f = File(root, u.path)
            when {
                !f.exists() -> false
                u.onlyIfEmpty && f.isDirectory && !f.list().isNullOrEmpty() -> false
                else -> f.deleteRecursively()
            }
        }
        is Undo.Restore -> {
            val bin = File(u.trashPath)
            val orig = File(root, u.path)
            if (bin.exists() && !orig.exists()) {
                orig.parentFile?.mkdirs()
                move(bin, orig)
                true
            } else false
        }
    }

    /** Rename when possible; across filesystems (the trash is outside the project) copy then delete. */
    private fun move(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return
        if (!src.copyRecursively(dst, overwrite = false)) {
            dst.deleteRecursively()
            throw IOException("Couldn't move ${src.name}")
        }
        if (!src.deleteRecursively()) throw IOException("Moved ${src.name} but couldn't remove the original")
    }
}
