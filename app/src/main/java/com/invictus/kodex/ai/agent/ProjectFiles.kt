package com.invictus.kodex.ai.agent

import com.invictus.kodex.ai.Redactor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sandboxed view of one project: every path is resolved against the project root (no escaping via
 * `..` or symlinks) and filtered through the user's never-send globs. All AI file access goes through here.
 */
class ProjectFiles(val root: File, private val globsCsv: String, private val io: WorkspaceIo) {

    sealed interface Read {
        data class Ok(val text: String) : Read
        data class Fail(val message: String) : Read
    }

    private val rootCanon: File = runCatching { root.canonicalFile }.getOrDefault(root)
    private var cachedIndex: List<String>? = null

    /** The file for a project-relative path, or null when it would escape the project. */
    fun resolve(rel: String): File? {
        val clean = rel.trim().replace('\\', '/').removePrefix("./").trimStart('/')
        val canon = runCatching { File(rootCanon, clean).canonicalFile }.getOrNull() ?: return null
        if (canon != rootCanon && !canon.path.startsWith(rootCanon.path + File.separator)) return null
        // Rebuild from the caller's (non-canonical) root so paths match the editor's buffer keys.
        return File(root, canon.relativeTo(rootCanon).path)
    }

    fun relOf(file: File): String =
        runCatching { file.relativeTo(root).path }.getOrElse { file.path }.replace(File.separatorChar, '/')

    fun isBlocked(rel: String): Boolean = rel.isNotEmpty() && Redactor.isExcluded(rel, globsCsv)

    suspend fun read(rel: String): Read {
        val f = resolve(rel) ?: return Read.Fail("Path is outside the project: $rel")
        val r = relOf(f)
        if (isBlocked(r)) return Read.Fail("Blocked by the user's never-send list: $r")
        if (!f.exists()) return Read.Fail("File not found: $r")
        if (f.isDirectory) return Read.Fail("$r is a directory; use list_files")
        if (isBinaryName(r)) return Read.Fail("$r looks like a binary file")
        if (f.length() > MAX_FILE_BYTES) return Read.Fail("$r is too large (${f.length() / 1024} KB)")
        val text = io.read(f) ?: return Read.Fail("Could not read $r")
        if (text.take(4096).indexOf('\u0000') >= 0) return Read.Fail("$r looks like a binary file")
        return Read.Ok(text)
    }

    /** Relative paths of every readable text file (skips build output, VCS data, blocked and binary files). */
    suspend fun index(): List<String> {
        cachedIndex?.let { return it }
        val list = withContext(Dispatchers.IO) {
            val out = ArrayList<String>()
            root.walkTopDown()
                .maxDepth(MAX_DEPTH)
                .onEnter { it == root || it.name !in SKIP_DIRS }
                .forEach { f ->
                    if (out.size >= MAX_INDEX || !f.isFile) return@forEach
                    val rel = relOf(f)
                    if (!isBlocked(rel) && !isBinaryName(rel) && f.length() <= MAX_FILE_BYTES) out += rel
                }
            out.sorted()
        }
        cachedIndex = list
        return list
    }

    companion object {
        const val MAX_FILE_BYTES = 1_500_000L
        private const val MAX_INDEX = 20_000
        private const val MAX_DEPTH = 14

        val SKIP_DIRS = setOf(
            ".git", ".gradle", "build", "node_modules", ".idea", ".cxx", "__pycache__", ".dart_tool", ".venv",
        )

        private val BINARY_EXT = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "zip", "jar", "aar", "apk", "aab", "so", "dex", "class",
            "mp3", "mp4", "mkv", "avi", "mov", "wav", "ogg", "flac", "pdf", "ttf", "otf", "woff", "woff2", "keystore",
            "jks", "7z", "gz", "tar", "rar", "bin", "exe", "dll", "db", "sqlite", "lock",
        )

        fun isBinaryName(rel: String): Boolean = rel.substringAfterLast('.', "").lowercase() in BINARY_EXT
    }
}
