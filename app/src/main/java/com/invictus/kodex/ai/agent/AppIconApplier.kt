package com.invictus.kodex.ai.agent

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.provider.MediaStore
import java.io.File

/** Installs a generated picture as the project's launcher icon, and saves pictures to the device. Blocking: call off the main thread. */
object AppIconApplier {

    data class Applied(val written: List<String>, val note: String)

    private val DENSITIES = listOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)
    private const val FOREGROUND_PX = 432 // 108dp at xxxhdpi
    private const val FG_NAME = "ic_launcher_ai_foreground"
    private const val BG_COLOR = "ic_launcher_ai_background"

    /**
     * Android project = a folder with src/main/AndroidManifest.xml that declares an application. Prefers the module
     * with a LAUNCHER activity. Null when the project is not an Android app.
     */
    fun findMain(root: File): File? {
        val manifests = root.walkTopDown()
            .maxDepth(5)
            .onEnter { it == root || it.name !in ProjectFiles.SKIP_DIRS }
            .filter { it.isFile && it.name == "AndroidManifest.xml" && it.parentFile?.name == "main" && it.parentFile?.parentFile?.name == "src" }
            .toList()
        val apps = manifests.filter { runCatching { it.readText().contains("<application") }.getOrDefault(false) }
        val launcher = apps.firstOrNull { runCatching { it.readText().contains("android.intent.category.LAUNCHER") }.getOrDefault(false) }
        return (launcher ?: apps.firstOrNull())?.parentFile
    }

    fun apply(root: File, png: File): Applied {
        val main = findMain(root) ?: throw IllegalStateException("No Android app module found in this project (looked for src/main/AndroidManifest.xml)")
        val res = File(main, "res")
        val manifest = File(main, "AndroidManifest.xml").readText()
        val name = Regex("""android:icon="@mipmap/([A-Za-z0-9_]+)"""").find(manifest)?.groupValues?.get(1) ?: "ic_launcher"
        val roundName = Regex("""android:roundIcon="@mipmap/([A-Za-z0-9_]+)"""").find(manifest)?.groupValues?.get(1) ?: "${name}_round"

        val src = BitmapFactory.decodeFile(png.absolutePath) ?: throw IllegalStateException("Can't read the image")
        val written = ArrayList<String>()
        fun rel(f: File) = f.relativeTo(root).path.replace(File.separatorChar, '/')
        fun write(f: File, bmp: Bitmap) {
            f.parentFile?.mkdirs()
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            written += rel(f)
        }
        try {
            // Legacy launcher bitmaps (+ drop same-named .webp that would clash).
            for ((dpi, px) in DENSITIES) {
                val dir = File(res, "mipmap-$dpi")
                listOf(name, roundName).forEach { n -> File(dir, "$n.webp").delete() }
                val square = Bitmap.createScaledBitmap(src, px, px, true)
                val rounded = masked(square, circle = false)
                write(File(dir, "$name.png"), rounded)
                val circle = masked(square, circle = true)
                write(File(dir, "$roundName.png"), circle)
                square.recycle(); rounded.recycle(); circle.recycle()
            }

            // Adaptive icon: the artwork is the foreground, its edge colour the background.
            val fg = Bitmap.createScaledBitmap(src, FOREGROUND_PX, FOREGROUND_PX, true)
            write(File(res, "drawable-nodpi/$FG_NAME.png"), fg)
            val bgHex = cornerColor(src)
            fg.recycle()
            val colors = File(res, "values/$BG_COLOR.xml")
            colors.parentFile?.mkdirs()
            colors.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <color name=\"$BG_COLOR\">$bgHex</color>\n</resources>\n")
            written += rel(colors)
            val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                "    <background android:drawable=\"@color/$BG_COLOR\" />\n" +
                "    <foreground android:drawable=\"@drawable/$FG_NAME\" />\n" +
                "</adaptive-icon>\n"
            val adaptiveDirs = res.listFiles { f -> f.isDirectory && f.name.startsWith("mipmap-anydpi") }.orEmpty().toMutableList()
            if (adaptiveDirs.isEmpty()) adaptiveDirs += File(res, "mipmap-anydpi-v26")
            for (d in adaptiveDirs) {
                d.mkdirs()
                for (n in listOf(name, roundName)) {
                    val f = File(d, "$n.xml")
                    f.writeText(xml)
                    written += rel(f)
                }
            }

            // Play Store listing icon.
            val store = Bitmap.createScaledBitmap(src, 512, 512, true)
            write(File(main, "ic_launcher-playstore.png"), store)
            store.recycle()
        } finally {
            src.recycle()
        }
        return Applied(
            written,
            "Updated ${written.size} files under ${rel(res)}. Old adaptive layers are no longer referenced; rebuild to see the new icon. " +
                "You can revert it from Git.",
        )
    }

    /** Rounded-square (or circular) copy for the legacy launcher bitmaps. */
    private fun masked(src: Bitmap, circle: Boolean): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        val radius = if (circle) src.width / 2f else src.width * 0.18f
        val shape = Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }
        c.drawPath(shape, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, 0f, 0f, p)
        return out
    }

    /** Average colour of the four corners as #RRGGBB (the adaptive background behind the artwork). */
    private fun cornerColor(bmp: Bitmap): String {
        val n = 8
        var r = 0L; var g = 0L; var b = 0L; var count = 0
        val xs = listOf(0, bmp.width - n)
        val ys = listOf(0, bmp.height - n)
        for (x0 in xs) for (y0 in ys) for (x in x0 until x0 + n) for (y in y0 until y0 + n) {
            val px = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
            r += (px shr 16) and 0xFF; g += (px shr 8) and 0xFF; b += px and 0xFF; count++
        }
        return "#%02X%02X%02X".format((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    /** Copies [file] into Pictures/Kodex (PNG) or Download/Kodex (anything else). */
    fun saveToDevice(app: Application, file: File, displayName: String, mime: String): Boolean {
        val isImage = mime.startsWith("image/") && mime != "image/svg+xml"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (isImage) "Pictures/Kodex" else "Download/Kodex")
        }
        val collection = if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = app.contentResolver.insert(collection, values) ?: return false
        val ok = runCatching {
            app.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
        }.getOrDefault(false)
        if (!ok) app.contentResolver.delete(uri, null, null)
        return ok
    }
}
