package com.invictus.kodex.feature.archive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.invictus.kodex.core.archive.ArchiveEntry
import com.invictus.kodex.core.archive.ArchiveReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Browses one archive: folder navigation, selection, entry preview and extraction into a new folder next to it. */
class ArchiveExplorerViewModel(val archive: File) : ViewModel() {

    sealed interface Preview {
        val name: String

        data class Text(override val name: String, val text: String, val truncated: Boolean) : Preview
        data class Picture(override val name: String, val bitmap: Bitmap) : Preview
        data class Unsupported(override val name: String, val size: Long) : Preview
    }

    /** One-shot result line for the snackbar: [folder] is set on success. */
    data class Notice(val count: Int, val folder: String?, val error: String?)

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val dir: String = "",
        val rows: List<ArchiveEntry> = emptyList(),
        val selected: Set<String> = emptySet(),
        val preview: Preview? = null,
        val busy: Boolean = false,
        val notice: Notice? = null,
        val totalFiles: Int = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var entries: List<ArchiveEntry> = emptyList()

    init {
        viewModelScope.launch {
            try {
                entries = withContext(Dispatchers.IO) { ArchiveReader.list(archive) }
                _state.update { it.copy(loading = false, rows = ArchiveReader.children(entries, ""), totalFiles = entries.count { e -> !e.isDirectory }) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Opens a folder inside the archive; selection is per-folder view, so it is cleared. */
    fun openDir(dir: String) = _state.update { it.copy(dir = dir, rows = ArchiveReader.children(entries, dir), selected = emptySet()) }

    /** One level up; false when already at the top (the screen then leaves). */
    fun up(): Boolean {
        val dir = _state.value.dir
        if (dir.isEmpty()) return false
        openDir(dir.substringBeforeLast('/', ""))
        return true
    }

    fun toggle(path: String) = _state.update {
        it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path)
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun dismissPreview() = _state.update { it.copy(preview = null) }

    fun noticeShown() = _state.update { it.copy(notice = null) }

    fun preview(entry: ArchiveEntry) {
        viewModelScope.launch {
            val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val result = try {
                withContext(Dispatchers.IO) {
                    when {
                        ext in IMAGE_EXT && entry.size <= MAX_IMAGE_BYTES -> {
                            val raw = ArchiveReader.readEntry(archive, entry.path, MAX_IMAGE_BYTES.toInt())
                            val bmp = if (raw.truncated) null else BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.size)
                            if (bmp != null) Preview.Picture(entry.name, bmp) else Preview.Unsupported(entry.name, entry.size)
                        }
                        ext in IMAGE_EXT -> Preview.Unsupported(entry.name, entry.size)
                        else -> {
                            val raw = ArchiveReader.readEntry(archive, entry.path, MAX_TEXT_BYTES)
                            // A NUL byte in the first KB is the usual "not text" signal.
                            if (raw.bytes.take(1024).any { it == 0.toByte() }) Preview.Unsupported(entry.name, entry.size)
                            else Preview.Text(entry.name, raw.bytes.toString(Charsets.UTF_8), raw.truncated)
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(notice = Notice(0, null, e.message ?: "Couldn't read ${entry.name}")) }
                return@launch
            }
            _state.update { it.copy(preview = result) }
        }
    }

    /** Extracts the selection, or everything when nothing is selected, into a new folder next to the archive. */
    fun extract() {
        if (_state.value.busy) return
        val only = _state.value.selected.takeIf { it.isNotEmpty() }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val notice = try {
                val done = withContext(Dispatchers.IO) { ArchiveReader.extractToNewFolder(archive, archive.parentFile ?: archive.absoluteFile.parentFile, only) }
                Notice(done.fileCount, done.folder.name, null)
            } catch (e: Exception) {
                Notice(0, null, e.message ?: e.javaClass.simpleName)
            }
            _state.update { it.copy(busy = false, notice = notice, selected = if (notice.error == null) emptySet() else it.selected) }
        }
    }

    companion object {
        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
        private const val MAX_TEXT_BYTES = 512 * 1024
    }
}
