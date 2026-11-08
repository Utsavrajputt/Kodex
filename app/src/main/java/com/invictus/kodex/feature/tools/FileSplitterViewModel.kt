package com.invictus.kodex.feature.tools

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.KodexApp
import com.invictus.kodex.R
import com.invictus.kodex.core.editor.EditorSettingsStore
import com.invictus.kodex.core.split.FileSplitter
import com.invictus.kodex.core.split.TooManyPartsException
import com.invictus.kodex.github.ui.GhText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface SplitSource {
    data class FileSource(val uri: Uri, val name: String, val size: Long) : SplitSource
    data class FolderSource(val dir: File) : SplitSource
}

data class SplitPart(val name: String, val size: Long)
data class SplitResult(val dir: String, val base: String, val parts: List<SplitPart>)

/** File splitter: cuts a file, or a project folder zipped on the fly, into parts of at most N MB. */
class FileSplitterViewModel(
    private val app: Application,
    private val store: EditorSettingsStore,
) : ViewModel() {

    data class UiState(
        val source: SplitSource? = null,
        /** Size of the files a folder would read (null while measuring or when a file is chosen). */
        val folderBytes: Long? = null,
        val measuring: Boolean = false,
        val sizeMb: Int = 25,
        val custom: Boolean = false,
        val customText: String = "",
        val outDir: String = defaultDir(),
        val includeGit: Boolean = false,
        val skipBuild: Boolean = true,
        val running: Boolean = false,
        val doneBytes: Long = 0,
        val result: SplitResult? = null,
    ) {
        val partMb: Int? get() = (if (custom) customText.toIntOrNull() else sizeMb)?.takeIf { it in 1..4096 }
        val totalBytes: Long? get() = when (val s = source) {
            is SplitSource.FileSource -> s.size.takeIf { it > 0 }
            is SplitSource.FolderSource -> folderBytes
            null -> null
        }
        val estimatedParts: Int?
            get() {
                val total = totalBytes ?: return null
                val part = (partMb ?: return null) * MIB
                return ((total + part - 1) / part).toInt().coerceAtLeast(1)
            }
    }

    sealed interface Effect { data class Message(val text: GhText) : Effect }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var job: Job? = null
    private var measureJob: Job? = null

    init {
        // The folder picked last time is the default now.
        viewModelScope.launch {
            val saved = store.splitOutputDir.first()
            if (saved.isNotBlank()) _state.update { it.copy(outDir = saved) }
        }
    }

    fun edit(block: UiState.() -> UiState) = _state.update { if (it.running) it else it.block() }

    fun setOutDir(path: String) {
        if (_state.value.running) return
        _state.update { it.copy(outDir = path) }
        viewModelScope.launch { store.setSplitOutputDir(path) }
    }

    fun pickFile(uri: Uri) {
        if (_state.value.running) return
        viewModelScope.launch {
            val (name, size) = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) (c.getString(0) ?: "file") to (if (c.isNull(1)) -1L else c.getLong(1)) else null
                    }
                }.getOrNull() ?: ("file" to -1L)
            }
            measureJob?.cancel()
            _state.update { it.copy(source = SplitSource.FileSource(uri, name, size), folderBytes = null, measuring = false, result = null) }
        }
    }

    fun pickFolder(path: String) {
        if (_state.value.running) return
        val dir = File(path)
        if (!dir.isDirectory) return
        _state.update { it.copy(source = SplitSource.FolderSource(dir), folderBytes = null, result = null) }
        measure()
    }

    fun toggleGit(on: Boolean) { edit { copy(includeGit = on) }; measure() }
    fun toggleBuild(on: Boolean) { edit { copy(skipBuild = on) }; measure() }

    private fun skipDirs(s: UiState): Set<String> = buildSet {
        if (!s.includeGit) add(".git")
        if (s.skipBuild) addAll(FileSplitter.BUILD_DIRS)
    }

    private fun measure() {
        val s = _state.value
        val dir = (s.source as? SplitSource.FolderSource)?.dir ?: return
        measureJob?.cancel()
        _state.update { it.copy(measuring = true, folderBytes = null) }
        measureJob = viewModelScope.launch {
            val bytes = runCatching { FileSplitter.folderSize(dir, skipDirs(_state.value)) }.getOrNull()
            _state.update { it.copy(measuring = false, folderBytes = bytes) }
        }
    }

    fun cancel() { job?.cancel() }

    fun start() {
        val s = _state.value
        if (s.running) return
        val src = s.source ?: return message(R.string.split_err_pick)
        val mb = s.partMb ?: return message(R.string.split_err_size)
        val maxBytes = mb * MIB
        if (src is SplitSource.FileSource && src.size == 0L) return message(R.string.split_err_empty)
        val parts = s.estimatedParts
        if (src is SplitSource.FileSource && parts != null && parts > FileSplitter.MAX_PARTS) return message(R.string.split_err_too_many)

        val base = when (src) { is SplitSource.FileSource -> src.name; is SplitSource.FolderSource -> src.dir.name + ".zip" }
        val outRoot = File(s.outDir.ifBlank { defaultDir() })
        val partsDir = File(outRoot, "$base-parts")
        val skip = skipDirs(s)
        _state.update { it.copy(running = true, doneBytes = 0, result = null) }

        job = viewModelScope.launch {
            var lastUi = 0L
            val progress: (Long) -> Unit = { done ->
                val now = System.nanoTime()
                if (now - lastUi > 100_000_000L) { lastUi = now; _state.update { it.copy(doneBytes = done) } }
            }
            try {
                val files = when (src) {
                    is SplitSource.FileSource -> {
                        val input = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(src.uri) } ?: throw IOException("cannot open file")
                        FileSplitter.splitStream(input, partsDir, base, maxBytes, progress)
                    }
                    is SplitSource.FolderSource -> FileSplitter.zipFolder(src.dir, partsDir, base, maxBytes, skip, progress)
                }
                if (files.isEmpty()) {
                    message(R.string.split_err_empty)
                } else {
                    _state.update {
                        it.copy(result = SplitResult(partsDir.absolutePath, base, files.map { f -> SplitPart(f.name, f.length()) }))
                    }
                }
            } catch (e: CancellationException) {
                message(R.string.split_cancelled)
                throw e
            } catch (e: TooManyPartsException) {
                message(R.string.split_err_too_many)
            } catch (e: Exception) {
                _effects.trySend(Effect.Message(GhText.Res(R.string.split_err_failed, listOf(e.message ?: e.javaClass.simpleName))))
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    private fun message(res: Int) { _effects.trySend(Effect.Message(GhText.Res(res))) }

    companion object {
        const val MIB = 1024L * 1024L

        fun defaultDir(): String = File(Environment.getExternalStorageDirectory(), "Documents/Kodex/splits").absolutePath

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                FileSplitterViewModel(app, app.container.editorSettingsStore)
            }
        }
    }
}
