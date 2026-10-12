package com.invictus.kodex.ai.agent

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.invictus.kodex.ai.provider.ChatImage
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.max

/** Turns a picked photo into a small upright JPEG in app storage (what the models receive). Blocking: call off the main thread. */
object ImageImporter {
    const val MAX_EDGE = 1568
    private const val QUALITY = 85

    fun import(app: Application, uri: Uri, destDir: File): ChatImage? = runCatching {
        val cr = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@runCatching null
        val rotation = cr.openInputStream(uri)?.use { exifRotation(it) } ?: 0

        val longest = max(decoded.width, decoded.height)
        val scale = if (longest > MAX_EDGE) MAX_EDGE.toFloat() / longest else 1f
        val matrix = Matrix().apply {
            if (scale != 1f) postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        var bmp = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (bmp !== decoded) decoded.recycle()
        if (bmp.hasAlpha()) { // JPEG has no alpha: flatten onto white instead of black
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            bmp.recycle()
            bmp = flat
        }
        destDir.mkdirs()
        val file = File(destDir, UUID.randomUUID().toString() + ".jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        bmp.recycle()
        ChatImage(file.absolutePath)
    }.getOrNull()

    private fun exifRotation(stream: InputStream): Int =
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
}
