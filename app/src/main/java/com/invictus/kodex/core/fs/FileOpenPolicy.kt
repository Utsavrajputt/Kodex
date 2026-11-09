package com.invictus.kodex.core.fs

import com.invictus.kodex.core.archive.ArchiveReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

sealed interface OpenDecision {
    /** Safe to open straight away. */
    data object Open : OpenDecision

    /** Text file over the size threshold: ask first. */
    data class LargeText(val sizeBytes: Long) : OpenDecision

    /** Looks binary: ask first. */
    data object Binary : OpenDecision
}

/** Decides whether tapping a file needs a "are you sure?" first. */
class FileOpenPolicy(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val largeTextBytes: Long = DEFAULT_LARGE_TEXT_BYTES,
) {

    suspend fun check(file: File): OpenDecision = withContext(io) {
        val ext = file.extension.lowercase(Locale.ROOT)
        // Media gets a real preview tab in M5, so it is never a "binary" warning.
        if (ext in PREVIEWABLE_MEDIA) return@withContext OpenDecision.Open
        // zip/7z/rar open in the archive explorer, so there is nothing to warn about.
        if (ArchiveReader.isArchive(file)) return@withContext OpenDecision.Open
        if (ext in BINARY_EXTENSIONS) return@withContext OpenDecision.Binary
        val size = file.length()
        if (size == 0L) return@withContext OpenDecision.Open
        val binary = try {
            looksBinary(file)
        } catch (_: IOException) {
            false
        }
        when {
            binary -> OpenDecision.Binary
            size > largeTextBytes -> OpenDecision.LargeText(size)
            else -> OpenDecision.Open
        }
    }

    /** A NUL byte in the first few KB is the classic "not text" signal. */
    private fun looksBinary(file: File): Boolean = file.inputStream().use { input ->
        val buffer = ByteArray(SNIFF_BYTES)
        val read = input.read(buffer)
        (0 until maxOf(read, 0)).any { buffer[it] == 0.toByte() }
    }

    companion object {
        const val DEFAULT_LARGE_TEXT_BYTES = 1L * 1024 * 1024
        private const val SNIFF_BYTES = 8192

        private val PREVIEWABLE_MEDIA = setOf(
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "mp4", "mkv", "webm", "3gp", "mov", "svg",
        )
        private val BINARY_EXTENSIONS = setOf(
            "zip", "jar", "aar", "apk", "aab", "so", "dex", "class", "o", "a", "bin", "exe", "dll",
            "pdf", "ttf", "otf", "woff", "woff2", "7z", "rar", "gz", "tgz", "tar", "xz", "keystore",
            "jks", "db", "sqlite", "mp3", "wav", "ogg", "flac", "m4a", "ico",
        )
    }
}
