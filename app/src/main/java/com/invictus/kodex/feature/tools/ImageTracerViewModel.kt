package com.invictus.kodex.feature.tools

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.R
import com.invictus.kodex.core.preview.VectorExport
import com.invictus.kodex.core.tracer.ImageTracer
import com.invictus.kodex.core.tracer.NativeTracer
import com.invictus.kodex.github.ui.GhText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Image tracer: pick a picture, set Quality and Colors, see the traced SVG, then download the
 * preview PNG, the SVG or an Android vector XML. Tracing re-runs when a slider is released.
 */
class ImageTracerViewModel(private val app: Application) : ViewModel() {

    data class UiState(
        val sourceName: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val scaledDown: Boolean = false,
        val quality: Int = ImageTracer.DEFAULT_QUALITY,
        val colorIndex: Int = ImageTracer.COLOR_STEPS.lastIndex,
        val running: Boolean = false,
        val hasResult: Boolean = false,
        /** The traced SVG drawn back at the source size; null when it was too heavy to render. */
        val preview: Bitmap? = null,
        val previewFailed: Boolean = false,
        val svgBytes: Int = 0,
        val paths: Int = 0,
        val elapsedMs: Long = 0,
        /** Set while the heavy-XML warning is up: characters of path data about to be exported. */
        val heavyXmlChars: Int? = null,
        val nativeMissing: Boolean = !NativeTracer.available,
    ) {
        val colors: Int get() = ImageTracer.COLOR_STEPS[colorIndex.coerceIn(0, ImageTracer.COLOR_STEPS.lastIndex)]
        val hasSource: Boolean get() = sourceName != null
    }

    sealed interface Effect { data class Message(val text: GhText) : Effect }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var source: Bitmap? = null
    private var svg: String? = null
    private var pendingXml: String? = null
    private var job: Job? = null

    fun pickImage(uri: Uri) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(running = true, heavyXmlChars = null) }
            try {
                val (name, decoded) = withContext(Dispatchers.IO) { displayName(uri) to ImageTracer.decode(app, uri) }
                source = decoded.bitmap
                svg = null
                _state.update {
                    it.copy(
                        sourceName = name,
                        width = decoded.bitmap.width,
                        height = decoded.bitmap.height,
                        scaledDown = decoded.scaledDown,
                        hasResult = false,
                        preview = null,
                        previewFailed = false,
                    )
                }
                runTrace()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(running = false) }
                say(GhText.Res(R.string.tracer_failed, listOf(e.message ?: e.javaClass.simpleName)))
            }
        }
    }

    /** Slider moves only update the label; [commit] (on release) re-traces. */
    fun setQuality(q: Int) = _state.update { it.copy(quality = q.coerceIn(ImageTracer.QUALITY_MIN, ImageTracer.QUALITY_MAX)) }

    fun setColorIndex(i: Int) = _state.update { it.copy(colorIndex = i.coerceIn(0, ImageTracer.COLOR_STEPS.lastIndex)) }

    fun commit() {
        if (source == null) return
        job?.cancel()
        job = viewModelScope.launch {
            try {
                runTrace()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(running = false) }
                say(GhText.Res(R.string.tracer_failed, listOf(e.message ?: e.javaClass.simpleName)))
            }
        }
    }

    private suspend fun runTrace() {
        val bmp = source ?: return
        val s = _state.value
        _state.update { it.copy(running = true) }
        val t0 = SystemClock.elapsedRealtime()
        val result = withContext(Dispatchers.Default) {
            val text = ImageTracer.trace(bmp, s.quality, s.colors)
            val rendered = try {
                ImageTracer.render(text, bmp.width, bmp.height)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            Triple(text, rendered, ImageTracer.pathCount(text))
        }
        svg = result.first
        _state.update {
            it.copy(
                running = false,
                hasResult = true,
                preview = result.second,
                previewFailed = result.second == null,
                svgBytes = result.first.length,
                paths = result.third,
                elapsedMs = SystemClock.elapsedRealtime() - t0,
            )
        }
    }

    // ---- exports ----

    fun savePreview() = export { VectorExport.savePng(it.preview ?: error("no preview"), baseName()) }

    fun saveSvg() = export { VectorExport.saveSvg(svg ?: error("no result"), baseName()) }

    /** Converts to an Android vector; a very heavy one waits for the user's go-ahead first. */
    fun saveXml() {
        val text = svg ?: return
        viewModelScope.launch {
            try {
                val xml = withContext(Dispatchers.Default) { NativeTracer.toVectorXml(text) }
                val chars = ImageTracer.pathDataChars(xml)
                if (chars > ImageTracer.HEAVY_PATH_CHARS) {
                    pendingXml = xml
                    _state.update { it.copy(heavyXmlChars = chars) }
                } else {
                    writeXml(xml)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                say(GhText.Res(R.string.tracer_failed, listOf(e.message ?: e.javaClass.simpleName)))
            }
        }
    }

    fun confirmHeavyXml() {
        val xml = pendingXml ?: return
        pendingXml = null
        _state.update { it.copy(heavyXmlChars = null) }
        viewModelScope.launch { runCatching { writeXml(xml) }.onFailure { say(GhText.Res(R.string.tracer_failed, listOf(it.message ?: "?"))) } }
    }

    fun dismissHeavyXml() {
        pendingXml = null
        _state.update { it.copy(heavyXmlChars = null) }
    }

    private suspend fun writeXml(xml: String) {
        val f = VectorExport.saveXml(xml, baseName())
        say(GhText.Res(R.string.tracer_saved, listOf(f.name)))
    }

    private fun export(block: suspend (UiState) -> java.io.File) {
        val s = _state.value
        if (!s.hasResult) return
        viewModelScope.launch {
            try {
                val f = block(s)
                say(GhText.Res(R.string.tracer_saved, listOf(f.name)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                say(GhText.Res(R.string.tracer_failed, listOf(e.message ?: e.javaClass.simpleName)))
            }
        }
    }

    private fun baseName(): String = _state.value.sourceName?.substringBeforeLast('.')?.ifBlank { null } ?: "traced"

    private fun displayName(uri: Uri): String =
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "image"

    private fun say(text: GhText) {
        _effects.trySend(Effect.Message(text))
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { ImageTracerViewModel(this[APPLICATION_KEY] as Application) }
        }
    }
}
