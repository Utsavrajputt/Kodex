package com.invictus.kodex.github.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The launcher icon of the project's own app (Android `res/mipmap-*` folders found in the project
 * folder). Falls back to Kodex's own icon when the project has none we can decode.
 */
object ProjectIconLoader {
    private val DENSITIES = listOf("xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi")
    private val NAMES = listOf("ic_launcher", "ic_launcher_round", "ic_launcher_foreground")
    private val EXTENSIONS = listOf("png", "webp", "jpg")

    /** `res` folders worth checking: the usual spots, then one level of module folders. */
    private fun resDirs(root: File): List<File> {
        val direct = listOf("app/src/main/res", "src/main/res", "android/app/src/main/res").map { File(root, it) }
        val modules = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
            .map { File(it, "src/main/res") }
        return (direct + modules).filter { it.isDirectory }.distinct()
    }

    /**
     * 1. the app's launcher icon (Android projects), 2. otherwise a logo-like image anywhere in the repo:
     * file names containing "bundle" first (patch bundles), then "logo", then "icon". null = use Kodex's icon.
     */
    fun findIconFile(projectPath: String): File? {
        val root = File(projectPath)
        for (res in resDirs(root)) {
            for (name in NAMES) for (density in DENSITIES) for (ext in EXTENSIONS) {
                val f = File(res, "mipmap-$density/$name.$ext")
                if (f.isFile && f.length() > 0) return f
            }
        }
        return findLogoImage(root)
    }

    private val IMAGE_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg")
    private val SKIP_DIRS = setOf("build", "node_modules", "venv", "__pycache__", "dist", "out", "target")
    private val KEYWORDS = listOf("bundle", "logo", "icon") // order = priority
    private const val MAX_DEPTH = 4
    private const val MAX_DIRS = 600
    private const val MAX_IMAGE_BYTES = 6L * 1024 * 1024

    private fun findLogoImage(root: File): File? {
        var best: File? = null
        var bestScore = Int.MAX_VALUE
        var bestDepth = Int.MAX_VALUE
        var visited = 0

        fun walk(dir: File, depth: Int) {
            if (depth > MAX_DEPTH || visited++ > MAX_DIRS) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.isDirectory) {
                    val n = f.name
                    if (n == ".github" || (!n.startsWith(".") && n !in SKIP_DIRS)) walk(f, depth + 1)
                } else {
                    if (f.extension.lowercase() !in IMAGE_EXTENSIONS) continue
                    if (f.length() <= 0 || f.length() > MAX_IMAGE_BYTES) continue
                    val name = f.nameWithoutExtension.lowercase()
                    val score = KEYWORDS.indexOfFirst { name.contains(it) }
                    if (score < 0) continue
                    if (score < bestScore || (score == bestScore && depth < bestDepth)) {
                        best = f; bestScore = score; bestDepth = depth
                    }
                }
            }
        }
        walk(root, 0)
        return best
    }

    fun load(context: Context, projectPath: String, targetPx: Int = 192): ImageBitmap {
        val file = findIconFile(projectPath)
        if (file != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outWidth > 0) sample *= 2
            val bmp = runCatching {
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
            if (bmp != null) return bmp.asImageBitmap()
        }
        return context.packageManager.getApplicationIcon(context.packageName).toBitmap(targetPx, targetPx).asImageBitmap()
    }
}

@Composable
fun ProjectIcon(projectPath: String, size: Dp, modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    val context = LocalContext.current.applicationContext
    val icon by produceState<ImageBitmap?>(null, projectPath) {
        value = withContext(Dispatchers.IO) { runCatching { ProjectIconLoader.load(context, projectPath) }.getOrNull() }
    }
    icon?.let {
        Image(bitmap = it, contentDescription = null, modifier = modifier.size(size).clip(RoundedCornerShape(corner)))
    }
}
