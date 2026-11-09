package com.invictus.kodex.core.archive

import com.github.junrar.Archive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile

enum class ArchiveFormat { ZIP, SEVEN_Z, RAR }

/** One entry of an archive. [path] uses '/', has no leading or trailing slash and never contains "..". */
data class ArchiveEntry(val path: String, val isDirectory: Boolean, val size: Long, val modified: Long) {
    val name: String get() = path.substringAfterLast('/')
}

/** Readable message for the UI (corrupt, encrypted, unsupported). */
class ArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Bytes of one entry, cut at the requested limit; [truncated] says more existed. */
class EntryBytes(val bytes: ByteArray, val truncated: Boolean)

class Extracted(val folder: File, val fileCount: Int)

/**
 * Lists, previews and extracts zip (zip/jar/aar/apk), 7z and RAR archives. Blocking: call it off
 * the main thread. Extraction never writes outside the destination (zip-slip safe) and never
 * overwrites: it always goes into a new folder.
 */
object ArchiveReader {

    private val ZIP_EXT = setOf("zip", "jar", "aar", "apk")

    fun formatOf(file: File): ArchiveFormat? = when (file.extension.lowercase(Locale.ROOT)) {
        in ZIP_EXT -> ArchiveFormat.ZIP
        "7z" -> ArchiveFormat.SEVEN_Z
        "rar" -> ArchiveFormat.RAR
        else -> null
    }

    fun isArchive(file: File): Boolean = formatOf(file) != null

    /** Every entry (files and explicit folders). Folders only implied by a file's path are not listed; see [children]. */
    fun list(file: File): List<ArchiveEntry> {
        val out = ArrayList<ArchiveEntry>()
        walk(file) { entry, _ ->
            out += entry
            true
        }
        return out
    }

    /**
     * What is directly inside [dir] ("" = the root): folders first, then files, by name. Folders
     * that no entry names explicitly (common in zips) are derived from the paths below them.
     */
    fun children(entries: List<ArchiveEntry>, dir: String): List<ArchiveEntry> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val seen = HashMap<String, ArchiveEntry>()
        for (e in entries) {
            if (!e.path.startsWith(prefix) || e.path.length == prefix.length) continue
            val rest = e.path.substring(prefix.length)
            val slash = rest.indexOf('/')
            if (slash < 0) {
                seen[rest] = e
            } else {
                val child = rest.substring(0, slash)
                if (seen[child]?.isDirectory != true) seen[child] = ArchiveEntry(prefix + child, true, 0, e.modified)
            }
        }
        return seen.values.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
    }

    /** Reads up to [maxBytes] of one entry, for previews. */
    fun readEntry(file: File, entryPath: String, maxBytes: Int): EntryBytes {
        var result: EntryBytes? = null
        walk(file) { entry, open ->
            if (entry.isDirectory || entry.path != entryPath) return@walk true
            open().use { input ->
                val buf = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
                val chunk = ByteArray(16 * 1024)
                var truncated = false
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (buf.size() + n > maxBytes) {
                        buf.write(chunk, 0, maxBytes - buf.size())
                        truncated = true
                        break
                    }
                    buf.write(chunk, 0, n)
                }
                result = EntryBytes(buf.toByteArray(), truncated)
            }
            false
        }
        return result ?: throw ArchiveException("Entry not found: $entryPath")
    }

    /**
     * Extracts into a NEW folder inside [parent] named after the archive ("app.zip" -> "app/",
     * "app (1)/" if taken). [only] limits it to those entry paths (a folder path takes everything
     * below it); null extracts everything. A failed run removes the half-written folder.
     */
    fun extractToNewFolder(archive: File, parent: File, only: Set<String>? = null): Extracted {
        val dest = uniqueDir(parent, archive.name.substringBeforeLast('.', archive.name))
        if (!dest.mkdirs()) throw IOException("Can't create ${dest.name}")
        return try {
            Extracted(dest, extractInto(archive, dest, only))
        } catch (e: Exception) {
            dest.deleteRecursively()
            throw e
        }
    }

    /** Extracts into the existing folder [dest]; returns how many files were written. */
    fun extractInto(archive: File, dest: File, only: Set<String>? = null): Int {
        val destRoot = dest.canonicalPath + File.separator
        var count = 0
        walk(archive) { entry, open ->
            if (only != null && only.none { entry.path == it || entry.path.startsWith("$it/") }) return@walk true
            val out = File(dest, entry.path)
            if (!out.canonicalPath.startsWith(destRoot)) throw ArchiveException("Unsafe path in archive: ${entry.path}")
            if (entry.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                open().use { input -> out.outputStream().buffered().use { input.copyTo(it) } }
                count++
            }
            true
        }
        return count
    }

    private fun uniqueDir(parent: File, name: String): File {
        var candidate = File(parent, name)
        var n = 1
        while (candidate.exists()) {
            candidate = File(parent, "$name ($n)")
            n++
        }
        return candidate
    }

    // ---- format back ends ----

    /** Visits every entry in order. [visit] gets a lazy stream opener (valid only during the call); return false to stop. */
    private fun walk(file: File, visit: (ArchiveEntry, () -> InputStream) -> Boolean) {
        val format = formatOf(file) ?: throw ArchiveException("Not a supported archive: ${file.name}")
        try {
            when (format) {
                ArchiveFormat.ZIP -> walkZip(file, visit)
                ArchiveFormat.SEVEN_Z -> walkSevenZ(file, visit)
                ArchiveFormat.RAR -> walkRar(file, visit)
            }
        } catch (e: ArchiveException) {
            throw e
        } catch (e: IOException) {
            throw ArchiveException(e.message ?: "Can't read ${file.name}", e)
        } catch (e: Exception) {
            // Library-specific failures (RarException, encrypted 7z, ...) all end up as one readable error.
            throw ArchiveException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun walkZip(file: File, visit: (ArchiveEntry, () -> InputStream) -> Boolean) {
        ZipFile(file).use { zip ->
            val en = zip.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                val entry = entryOf(e.name, e.isDirectory, e.size, e.time) ?: continue
                if (!visit(entry) { zip.getInputStream(e) }) break
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun walkSevenZ(file: File, visit: (ArchiveEntry, () -> InputStream) -> Boolean) {
        SevenZFile(file).use { sz ->
            val stream = object : InputStream() {
                override fun read(): Int = sz.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = sz.read(b, off, len)
            }
            while (true) {
                val e = sz.nextEntry ?: break
                val modified = if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0L
                val entry = entryOf(e.name, e.isDirectory, e.size, modified) ?: continue
                if (!visit(entry) { stream }) break
            }
        }
    }

    private fun walkRar(file: File, visit: (ArchiveEntry, () -> InputStream) -> Boolean) {
        Archive(file).use { rar ->
            while (true) {
                val h = rar.nextFileHeader() ?: break
                val entry = entryOf(h.fileName, h.isDirectory, h.fullUnpackSize, h.mTime?.time ?: 0L) ?: continue
                if (!visit(entry) { rar.getInputStream(h) }) break
            }
        }
    }

    /** Normalizes a raw name; null for empty names and for anything that climbs out with "..". */
    private fun entryOf(rawName: String?, isDir: Boolean, size: Long, modified: Long): ArchiveEntry? {
        val path = (rawName ?: return null).replace('\\', '/').trim('/')
        if (path.isEmpty() || path.split('/').any { it == ".." }) return null
        return ArchiveEntry(path, isDir, size.coerceAtLeast(0), modified.coerceAtLeast(0))
    }
}
