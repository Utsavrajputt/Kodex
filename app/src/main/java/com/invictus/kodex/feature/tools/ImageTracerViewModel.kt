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
import com.invictus.kodex.core.tracer.ImageAnalyzer
import com.invictus.kodex.core.tracer.ImageTracer
import com.invictus.kodex.core.tracer.NativeTracer
import com.invictus.kodex.core.tracer.TraceSession
import com.invictus.kodex.github.ui.GhText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Image tracer: pick a picture, choose its size, let Auto pick Quality and Colors (or set them
 * by hand), see the traced SVG, then download the preview PNG, the SVG or an Android vector XML.
 * Tracing re-runs when a slider is released, a size chip is tapped or Auto is switched on.
 *
 * Runs are serialized: a new one cancels the one in flight (natively, so it stops quickly) and
 * waits for it before starting. The native session keeps the clustering between runs, so a
 * Colors-only change skips it.
 */
class ImageTracerViewModel(private val app: Application) : ViewModel() {

    data class UiState(
        val sourceName: String? = null,
        /** The picture as loaded (already capped at [ImageTracer.MAX_SIDE]); what the "Original" chip stands for. */
        val origWidth: Int = 0,
        val origHeight: Int = 0,
        /** The size actually traced. */
        val width: Int = 0,
        val height: Int = 0,
        val scaledDown: Boolean = false,
        /** Longer side to trace at; 0 = Original. */
        val targetSide: Int = 0,
        val auto: Boolean = true,
        /** What Auto saw in the last traced picture; null when Auto is off. */
        val detected: ImageAnalyzer.Kind? = null,
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
        val origLongSide: Int get() = maxOf(origWidth, origHeight)
    }

    private class Traced(
        val svg: String,
        val preview: Bitmap?,
        val paths: Int,
        val quality: Int,
        val colors: Int,
        val kind: ImageAnalyzer.Kind?,
    )

    sealed interface Effect { data class Message(val text: GhText) : Effect }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** The picture as decoded, and the copy at the chosen size (the same object for "Original"). */
    private var original: Bitmap? = null
    private var working: Bitmap? = null

    /** Native session over [working]; only touched by the serialized trace jobs. */
    @Volatile private var session: TraceSession? = null

    /** Quality/Colors of the SVG on screen, to skip a re-trace that would change nothing. */
    private var tracedKey: Int? = null

    private var svg: String? = null
    private var pendingXml: String? = null
    private var job: Job? = null

    /**
     * Starts [block] as the only trace job: cancels the one in flight (stopping its native render
     * too) and waits for it, so two jobs never touch the session or bitmaps together.
     */
    private fun schedule(block: suspend () -> Unit) {
        val prev = job
        prev?.cancel()
        session?.cancel()
        _state.update { it.copy(running = true) }
        job = viewModelScope.launch {
            prev?.join()
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A render aborted by a newer one fails natively; that is not an error to show.
                ensureActive()
                _state.update { it.copy(running = false) }
                say(GhText.Res(R.string.tracer_failed, listOf(e.message ?: e.javaClass.simpleName)))
            }
        }
    }

    fun pickImage(uri: Uri) {
        _state.update { it.copy(heavyXmlChars = null) }
        schedule {
            val (name, decoded) = withContext(Dispatchers.IO) { displayName(uri) to ImageTracer.decode(app, uri) }
            closeSession()
            setWorking(decoded.bitmap)
            original = decoded.bitmap
            svg = null
            tracedKey = null
            _state.update {
                it.copy(
                    sourceName = name,
                    origWidth = decoded.bitmap.width,
                    origHeight = decoded.bitmap.height,
                    width = decoded.bitmap.width,
                    height = decoded.bitmap.height,
                    scaledDown = decoded.scaledDown,
                    targetSide = 0,
                    detected = null,
                    hasResult = false,
                    preview = null,
                    previewFailed = false,
                )
            }
            runTrace()
        }
    }

    /** Size chip: 0 = Original, otherwise the longer side in px. */
    fun setTargetSide(side: Int) {
        val orig = original ?: return
        if (side == _state.value.targetSide) return
        _state.update { it.copy(targetSide = side) }
        schedule {
            val bmp = withContext(Dispatchers.Default) { if (side == 0) orig else ImageTracer.resize(orig, side) }
            closeSession()
            setWorking(bmp)
            svg = null
            tracedKey = null
            _state.update { it.copy(width = bmp.width, height = bmp.height, hasResult = false) }
            runTrace()
        }
    }

    /** Auto picks Quality and Colors from the picture; off hands the sliders back at their current values. */
    fun setAuto(on: Boolean) {
        if (_state.value.auto == on) return
        _state.update { it.copy(auto = on, detected = if (on) it.detected else null) }
        if (on && working != null) schedule { runTrace() }
    }

    /** Slider moves only update the label; [commit] (on release) re-traces. */
    fun setQuality(q: Int) = _state.update { it.copy(quality = q.coerceIn(ImageTracer.QUALITY_MIN, ImageTracer.QUALITY_MAX)) }

    fun setColorIndex(i: Int) = _state.update { it.copy(colorIndex = i.coerceIn(0, ImageTracer.COLOR_STEPS.lastIndex)) }

    fun commit() {
        if (working == null) return
        val s = _state.value
        if (s.auto) return
        // Slider let go where the traced picture already is: nothing to redo.
        if (s.hasResult && tracedKey == key(s.quality, s.colors)) return
        schedule { runTrace() }
    }

    private fun key(quality: Int, colors: Int) = quality * 1000 + colors

    private fun setWorking(bmp: Bitmap) {
        val old = working
        working = bmp
        if (old != null && old !== original && old !== bmp) old.recycle()
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    private suspend fun runTrace() {
        val bmp = working ?: return
        val s = _state.value
        _state.update { it.copy(running = true) }
        val t0 = SystemClock.elapsedRealtime()
        val out = withContext(Dispatchers.Default) {
            var quality = s.quality
            var colors = s.colors
            var kind: ImageAnalyzer.Kind? = null
            if (s.auto) {
                val pick = ImageAnalyzer.analyze(bmp)
                quality = pick.quality
                colors = pick.colors
                kind = pick.kind
            }
            ensureActive()
            val sess = session ?: ImageTracer.open(bmp).also { session = it }
            val text = sess.render(quality, colors)
            ensureActive()
            val rendered = try {
                ImageTracer.render(text, bmp.width, bmp.height)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            Traced(text, rendered, ImageTracer.pathCount(text), quality, colors, kind)
        }
        svg = out.svg
        tracedKey = key(out.quality, out.colors)
        _state.update {
            it.copy(
                running = false,
                hasResult = true,
                preview = out.preview,
                previewFailed = out.preview == null,
                svgBytes = out.svg.length,
                paths = out.paths,
                elapsedMs = SystemClock.elapsedRealtime() - t0,
                quality = out.quality,
                colorIndex = ImageTracer.COLOR_STEPS.indexOf(out.colors).let { i -> if (i < 0) ImageTracer.COLOR_STEPS.lastIndex else i },
                detected = out.kind,
            )
        }
    }

    override fun onCleared() {
        job?.cancel()
        closeSession()
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
