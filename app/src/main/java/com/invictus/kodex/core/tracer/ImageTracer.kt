package com.invictus.kodex.core.tracer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import com.caverock.androidsvg.SVG
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Image → SVG on top of [NativeTracer]: decoding, the slider scales, and rendering the result back. */
object ImageTracer {

    /** Longer side limit. Bigger pictures are scaled down first: exact tracing grows with the pixel count. */
    const val MAX_SIDE = 2048

    const val QUALITY_MIN = 1
    const val QUALITY_MAX = 5
    const val DEFAULT_QUALITY = 3

    /** Palette caps along the Colors slider; 0 = all colors (the last stop). */
    val COLOR_STEPS = intArrayOf(2, 4, 8, 16, 32, 64, 128, 0)

    /** Path data above this many characters is likely to trouble Android's vector compiler. */
    const val HEAVY_PATH_CHARS = 300_000

    class Decoded(val bitmap: Bitmap, val scaledDown: Boolean)

    fun decode(context: Context, uri: Uri): Decoded =
        decode(ImageDecoder.createSource(context.contentResolver, uri))

    fun decode(file: File): Decoded = decode(ImageDecoder.createSource(file))

    private fun decode(source: ImageDecoder.Source): Decoded {
        var scaled = false
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Software memory: Bitmap.getPixels() can't read a hardware bitmap.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longSide = max(info.size.width, info.size.height)
            if (longSide > MAX_SIDE) {
                val k = MAX_SIDE.toFloat() / longSide
                decoder.setTargetSize(
                    (info.size.width * k).roundToInt().coerceAtLeast(1),
                    (info.size.height * k).roundToInt().coerceAtLeast(1),
                )
                scaled = true
            }
        }
        return Decoded(bitmap, scaled)
    }

    /** Traces [bitmap]; blocking and CPU-heavy, call it off the main thread. */
    fun trace(bitmap: Bitmap, quality: Int, colors: Int): String {
        check(NativeTracer.available) { "The native tracer is not part of this build" }
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        val rgba = ByteArray(w * h * 4)
        var o = 0
        for (p in argb) {
            rgba[o++] = (p shr 16).toByte()
            rgba[o++] = (p shr 8).toByte()
            rgba[o++] = p.toByte()
            rgba[o++] = (p ushr 24).toByte()
        }
        return NativeTracer.trace(rgba, w, h, quality.coerceIn(QUALITY_MIN, QUALITY_MAX), colors)
    }

    /** Draws [svg] 1:1 into a [width]×[height] bitmap. May throw [OutOfMemoryError] on very heavy SVGs. */
    fun render(svg: String, width: Int, height: Int): Bitmap {
        val parsed = SVG.getFromString(svg)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            parsed.renderToCanvas(Canvas(bitmap))
        } catch (t: Throwable) {
            bitmap.recycle()
            throw t
        }
        return bitmap
    }

    fun pathCount(svg: String): Int {
        var n = 0
        var i = svg.indexOf("<path")
        while (i >= 0) {
            n++
            i = svg.indexOf("<path", i + 5)
        }
        return n
    }

    /** Characters of path data in an Android vector XML, a cheap proxy for how heavy it is. */
    fun pathDataChars(xml: String): Int {
        var total = 0
        var i = xml.indexOf("android:pathData=\"")
        while (i >= 0) {
            val from = i + 18
            val to = xml.indexOf('"', from)
            if (to < 0) break
            total += to - from
            i = xml.indexOf("android:pathData=\"", to)
        }
        return total
    }
}
