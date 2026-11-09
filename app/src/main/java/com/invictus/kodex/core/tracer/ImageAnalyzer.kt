package com.invictus.kodex.core.tracer

import android.graphics.Bitmap
import kotlin.math.max

/**
 * Picks Quality and Colors for a picture, for the tracer's Auto mode.
 *
 * Looks at a small sample (≤ [SAMPLE_SIDE] px, nearest-neighbour so exact colors survive):
 *  - how many neighbouring pixels are identical (flat art has many, photos almost none),
 *  - how many distinct colors take a real share of the picture,
 *  - how many distinct exact colors there are.
 * Pixel-exact is only picked for tiny, flat pictures (pixel art), where it is cheap and exact
 * matters; everything else stays at High or Balanced so the SVG stays a sane size.
 * All thresholds live here so they can be tuned in one place.
 */
object ImageAnalyzer {

    enum class Kind { PIXEL_ART, FLAT, GRAPHIC, PHOTO }

    class Pick(val quality: Int, val colors: Int, val kind: Kind)

    private const val SAMPLE_SIDE = 256
    private const val EXACT_CAP = 4096

    private const val PIXEL_ART_MAX_SIDE = 160
    private const val PIXEL_ART_MAX_COLORS = 64
    private const val PIXEL_ART_FLAT = 0.55

    private const val FLAT_MIN = 0.80
    private const val FLAT_MAX_SIGNIFICANT = 16
    private const val GRAPHIC_MIN = 0.45
    private const val GRAPHIC_MAX_SIGNIFICANT = 48

    /** Almost no identical neighbours plus a long tail of distinct colors: photo or smooth gradient. */
    private const val PHOTO_MAX_FLAT = 0.15
    private const val PHOTO_MIN_DISTINCT = 1000

    /** A 4-bit-per-channel color bin counts when it holds at least 1/200 (0.5%) of the opaque pixels. */
    private const val SIGNIFICANT_SHARE = 200

    private const val PHOTO_BIG_PIXELS = 1_500_000

    /** Blocking but cheap (≤ 65k samples); call it off the main thread anyway. */
    fun analyze(src: Bitmap): Pick {
        val w0 = src.width
        val h0 = src.height
        val long = max(w0, h0)
        val sw = if (long > SAMPLE_SIDE) max(1, (w0.toLong() * SAMPLE_SIDE / long).toInt()) else w0
        val sh = if (long > SAMPLE_SIDE) max(1, (h0.toLong() * SAMPLE_SIDE / long).toInt()) else h0

        val px = IntArray(sw * sh)
        for (y in 0 until sh) {
            val sy = (y.toLong() * h0 / sh).toInt()
            for (x in 0 until sw) {
                px[y * sw + x] = src.getPixel((x.toLong() * w0 / sw).toInt(), sy)
            }
        }

        var opaque = 0
        val exact = HashSet<Int>()
        val bins = IntArray(4096)
        for (p in px) {
            if ((p ushr 24) < 128) continue
            opaque++
            if (exact.size < EXACT_CAP) exact.add(p and 0xFFFFFF)
            bins[(((p shr 20) and 0xF) shl 8) or (((p shr 12) and 0xF) shl 4) or ((p shr 4) and 0xF)]++
        }
        // Fully transparent: nothing to tune, keep the plain defaults.
        if (opaque == 0) return Pick(ImageTracer.DEFAULT_QUALITY, 0, Kind.FLAT)

        var pairs = 0
        var same = 0
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val i = y * sw + x
                val p = px[i]
                if ((p ushr 24) < 128) continue
                if (x + 1 < sw) { pairs++; if (px[i + 1] == p) same++ }
                if (y + 1 < sh) { pairs++; if (px[i + sw] == p) same++ }
            }
        }
        val flat = if (pairs == 0) 1.0 else same.toDouble() / pairs
        val distinct = exact.size
        var significant = 0
        for (n in bins) if (n * SIGNIFICANT_SHARE >= opaque) significant++

        return when {
            long <= PIXEL_ART_MAX_SIDE && flat >= PIXEL_ART_FLAT && distinct <= PIXEL_ART_MAX_COLORS ->
                Pick(5, 0, Kind.PIXEL_ART)
            flat >= FLAT_MIN && significant <= FLAT_MAX_SIGNIFICANT ->
                Pick(4, stepAtLeast(significant + 2), Kind.FLAT)
            flat < PHOTO_MAX_FLAT && distinct >= PHOTO_MIN_DISTINCT -> photo(w0, h0)
            flat >= GRAPHIC_MIN || significant <= GRAPHIC_MAX_SIGNIFICANT ->
                Pick(4, stepAtLeast(significant * 2).coerceIn(16, 64), Kind.GRAPHIC)
            else -> photo(w0, h0)
        }
    }

    /** Photos stay Balanced; the palette shrinks on big ones to keep time and SVG size in check. */
    private fun photo(w: Int, h: Int) = Pick(3, if (w.toLong() * h > PHOTO_BIG_PIXELS) 32 else 64, Kind.PHOTO)

    /** Smallest Colors step holding at least [n] colors (capped at 128). */
    private fun stepAtLeast(n: Int): Int =
        ImageTracer.COLOR_STEPS.firstOrNull { it != 0 && it >= n } ?: 128
}
