package com.invictus.kodex.core.preview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Writes the vector preview out as .svg / .png into the public Downloads folder. */
object VectorExport {

    /** A 24dp icon is useless at 24px, so the PNG's longer side is scaled up to this. */
    private const val PNG_LONG_SIDE_PX = 1024

    suspend fun saveSvg(svgText: String, baseName: String): File = withContext(Dispatchers.IO) {
        val target = uniqueTarget(baseName, "svg")
        target.writeText(svgText, Charsets.UTF_8)
        target
    }

    suspend fun savePng(svgText: String, baseName: String): File = withContext(Dispatchers.IO) {
        val svg = SVG.getFromString(svgText)
        val docWidth = svg.documentWidth.takeIf { it > 0f } ?: svg.documentViewBox?.width() ?: 24f
        val docHeight = svg.documentHeight.takeIf { it > 0f } ?: svg.documentViewBox?.height() ?: 24f
        val scale = PNG_LONG_SIDE_PX / max(docWidth, docHeight)
        val width = (docWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (docHeight * scale).roundToInt().coerceAtLeast(1)

        // Sizing the document to the bitmap lets its viewBox scale the artwork to fill it.
        svg.setDocumentWidth(width.toFloat())
        svg.setDocumentHeight(height.toFloat())

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            svg.renderToCanvas(Canvas(bitmap))
            val target = uniqueTarget(baseName, "png")
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            target
        } finally {
            bitmap.recycle()
        }
    }

    /** Android vector drawable (or any text) as Downloads/<base>.xml. */
    suspend fun saveXml(xmlText: String, baseName: String): File = withContext(Dispatchers.IO) {
        val target = uniqueTarget(baseName, "xml")
        target.writeText(xmlText, Charsets.UTF_8)
        target
    }

    /** Writes an already rendered [bitmap] as Downloads/<base>.png, at its own pixel size. */
    suspend fun savePng(bitmap: Bitmap, baseName: String): File = withContext(Dispatchers.IO) {
        val target = uniqueTarget(baseName, "png")
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        target
    }

    /** Downloads/<base>.<ext>, or "<base> (1).<ext>", "(2)"... so nothing is ever overwritten. */
    private fun uniqueTarget(baseName: String, extension: String): File {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("Can't create ${dir.path}")
        val safeBase = baseName.ifBlank { "vector" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var candidate = File(dir, "$safeBase.$extension")
        var n = 1
        while (candidate.exists()) candidate = File(dir, "$safeBase (${n++}).$extension")
        return candidate
    }
}
