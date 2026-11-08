package com.invictus.kodex.core.split

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Thrown when the chosen part size would need more than [FileSplitter.MAX_PARTS] parts. */
class TooManyPartsException : IOException("too many parts")

/** Writes everything to numbered files `name.001`, `name.002`, ... starting a new one after [maxBytes] bytes. */
class SplitOutputStream(private val dir: File, private val base: String, private val maxBytes: Long) : OutputStream() {
    val parts = ArrayList<File>()
    private var out: OutputStream? = null
    private var written = 0L

    private fun next() {
        out?.close()
        if (parts.size >= FileSplitter.MAX_PARTS) throw TooManyPartsException()
        val f = File(dir, "%s.%03d".format(base, parts.size + 1))
        parts += f
        out = BufferedOutputStream(FileOutputStream(f), 64 * 1024)
        written = 0
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var pos = off
        var left = len
        while (left > 0) {
            if (out == null || written >= maxBytes) next()
            val n = minOf(left.toLong(), maxBytes - written).toInt()
            out!!.write(b, pos, n)
            written += n
            pos += n
            left -= n
        }
    }

    override fun flush() { out?.flush() }

    override fun close() {
        out?.close()
        out = null
    }
}

/** Splits a stream, or a folder (zipped on the fly), into parts of at most N bytes. Nothing is buffered whole. */
object FileSplitter {
    const val MAX_PARTS = 999

    /** Folders never worth zipping when sharing source: build output and dependency caches. */
    val BUILD_DIRS = setOf("build", ".gradle", "node_modules", ".cxx")

    private fun clearOld(dir: File, base: String) {
        val re = Regex("^" + Regex.escape(base) + "\\.\\d{3}$")
        dir.listFiles()?.filter { re.matches(it.name) }?.forEach { it.delete() }
    }

    /** Copies [input] into parts `[base].001...`; a failed or cancelled run removes the parts it wrote. */
    suspend fun splitStream(
        input: InputStream, outDir: File, base: String, maxBytes: Long, onProgress: (Long) -> Unit,
    ): List<File> = withContext(Dispatchers.IO) {
        outDir.mkdirs()
        clearOld(outDir, base)
        val sink = SplitOutputStream(outDir, base, maxBytes)
        try {
            input.use { ins ->
                val buf = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    ensureActive()
                    val n = ins.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    total += n
                    onProgress(total)
                }
            }
            sink.close()
            sink.parts.toList()
        } catch (t: Throwable) {
            runCatching { sink.close() }
            sink.parts.forEach { it.delete() }
            throw t
        }
    }

    /** Zips [folder] (entries under `folderName/`) straight into parts; [skipDirs] are left out wherever they appear. */
    suspend fun zipFolder(
        folder: File, outDir: File, base: String, maxBytes: Long, skipDirs: Set<String>, onProgress: (Long) -> Unit,
    ): List<File> = withContext(Dispatchers.IO) {
        outDir.mkdirs()
        clearOld(outDir, base)
        val sink = SplitOutputStream(outDir, base, maxBytes)
        try {
            ZipOutputStream(sink).use { zip ->
                val buf = ByteArray(256 * 1024)
                var total = 0L
                fun add(dir: File, prefix: String) {
                    val children = dir.listFiles()?.sortedBy { it.name } ?: return
                    if (children.isEmpty() && prefix.isNotEmpty()) {
                        zip.putNextEntry(ZipEntry(prefix))
                        zip.closeEntry()
                    }
                    for (f in children) {
                        ensureActive()
                        if (Files.isSymbolicLink(f.toPath())) continue
                        if (f.isDirectory) {
                            if (f.name in skipDirs) continue
                            add(f, "$prefix${f.name}/")
                        } else if (f.canRead()) {
                            zip.putNextEntry(ZipEntry(prefix + f.name).apply { time = f.lastModified() })
                            f.inputStream().use { ins ->
                                while (true) {
                                    ensureActive()
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    zip.write(buf, 0, n)
                                    total += n
                                    onProgress(total)
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
                add(folder, folder.name + "/")
            }
            sink.parts.toList()
        } catch (t: Throwable) {
            runCatching { sink.close() }
            sink.parts.forEach { it.delete() }
            throw t
        }
    }

    /** Total size in bytes of the files that [zipFolder] would read. */
    suspend fun folderSize(folder: File, skipDirs: Set<String>): Long = withContext(Dispatchers.IO) {
        var total = 0L
        fun walk(dir: File) {
            for (f in dir.listFiles().orEmpty()) {
                ensureActive()
                if (Files.isSymbolicLink(f.toPath())) continue
                if (f.isDirectory) { if (f.name !in skipDirs) walk(f) } else total += f.length()
            }
        }
        walk(folder)
        total
    }
}
