package com.invictus.kodex.core.tracer

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * One picture traced many times. The native side caches the clustering, so changing only the
 * Colors cap skips it, and [cancel] stops a render that a newer setting has made stale.
 *
 * Renders are serialized natively. [close] takes the write lock, so the handle is never freed
 * while a render or cancel is running on another thread.
 */
class TraceSession private constructor(private var handle: Long) : AutoCloseable {

    private val lock = ReentrantReadWriteLock()

    /** Blocking and CPU-heavy: call it off the main thread. */
    fun render(quality: Int, colors: Int): String = lock.read {
        check(handle != 0L) { "The tracer session is closed" }
        NativeTracer.sessionRender(
            handle,
            quality.coerceIn(ImageTracer.QUALITY_MIN, ImageTracer.QUALITY_MAX),
            colors,
        )
    }

    /** Safe from any thread, including while a render runs. */
    fun cancel() = lock.read {
        if (handle != 0L) NativeTracer.sessionCancel(handle)
    }

    override fun close() {
        cancel()
        lock.write {
            if (handle != 0L) {
                NativeTracer.sessionClose(handle)
                handle = 0L
            }
        }
    }

    companion object {
        fun open(rgba: ByteArray, width: Int, height: Int): TraceSession {
            check(NativeTracer.available) { "The native tracer is not part of this build" }
            return TraceSession(NativeTracer.sessionOpen(rgba, width, height))
        }
    }
}
