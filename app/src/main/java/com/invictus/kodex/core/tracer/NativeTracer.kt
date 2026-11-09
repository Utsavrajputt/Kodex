package com.invictus.kodex.core.tracer

/**
 * JNI binding to libkodex_vtracer.so: the VTracer core (Rust) behind the Image tracer.
 *
 * The library is built by `.github/scripts/build-native.sh` (CI, or `./gradlew buildNative` locally).
 * A build without it still runs: [available] is false and the tool says so instead of crashing.
 */
object NativeTracer {

    val available: Boolean = try {
        System.loadLibrary("kodex_vtracer")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    /**
     * Traces straight (non-premultiplied) RGBA pixels into an SVG document.
     * [quality] is 1..5 (5 = pixel-exact); [colors] caps the palette, 0 = all colors.
     * Throws [IllegalStateException] with the native error message on failure.
     */
    @JvmStatic
    external fun trace(rgba: ByteArray, width: Int, height: Int, quality: Int, colors: Int): String

    // ---- session API: one native handle per picture, see [TraceSession] ----

    /** Opens a session over straight RGBA pixels; returns a handle for the calls below. */
    @JvmStatic
    external fun sessionOpen(rgba: ByteArray, width: Int, height: Int): Long

    /** Like [trace], but reuses the session's cached clustering when only [colors] changed. */
    @JvmStatic
    external fun sessionRender(handle: Long, quality: Int, colors: Int): String

    /** Aborts the render in flight; it then throws "conversion cancelled". */
    @JvmStatic
    external fun sessionCancel(handle: Long)

    @JvmStatic
    external fun sessionClose(handle: Long)

    /** Converts an SVG made by [trace] into an Android VectorDrawable XML document. */
    @JvmStatic
    external fun toVectorXml(svg: String): String
}
