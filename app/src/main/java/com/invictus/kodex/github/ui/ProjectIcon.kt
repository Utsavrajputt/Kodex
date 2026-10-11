package com.invictus.kodex.github.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.invictus.kodex.KodexApp
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

    /** The project's own icon, or null when it has none we can decode. */
    fun loadOrNull(projectPath: String, targetPx: Int = 192): ImageBitmap? {
        val file = findIconFile(projectPath) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outWidth > 0) sample *= 2
        return runCatching {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()?.asImageBitmap()
    }

    fun kodexIcon(context: Context, targetPx: Int = 192): ImageBitmap =
        context.packageManager.getApplicationIcon(context.packageName).toBitmap(targetPx, targetPx).asImageBitmap()

    fun load(context: Context, projectPath: String, targetPx: Int = 192): ImageBitmap =
        loadOrNull(projectPath, targetPx) ?: kodexIcon(context, targetPx)
}

private sealed interface IconState {
    data object Loading : IconState
    data class Found(val bitmap: ImageBitmap) : IconState
    data object Missing : IconState
}

/**
 * The project's own app icon. When the project has none, the GitHub profile picture of [ownerLogin]
 * (the repo owner) is shown; Kodex's own icon is only the last resort (no owner / no network).
 */
@Composable
fun ProjectIcon(projectPath: String, size: Dp, modifier: Modifier = Modifier, corner: Dp = 10.dp, ownerLogin: String? = null) {
    val context = LocalContext.current.applicationContext
    val shape = RoundedCornerShape(corner)
    val icon by produceState<IconState>(IconState.Loading, projectPath) {
        value = withContext(Dispatchers.IO) {
            runCatching { ProjectIconLoader.loadOrNull(projectPath) }.getOrNull()?.let { IconState.Found(it) } ?: IconState.Missing
        }
    }
    when (val st = icon) {
        IconState.Loading -> Unit
        is IconState.Found -> Image(bitmap = st.bitmap, contentDescription = null, modifier = modifier.size(size).clip(shape))
        IconState.Missing -> {
            val kodex by produceState<ImageBitmap?>(null) {
                value = withContext(Dispatchers.IO) { runCatching { ProjectIconLoader.kodexIcon(context) }.getOrNull() }
            }
            val fallback = kodex?.let { BitmapPainter(it) } ?: ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
            if (ownerLogin.isNullOrBlank()) {
                kodex?.let { Image(bitmap = it, contentDescription = null, modifier = modifier.size(size).clip(shape)) }
            } else {
                val loader = (context as KodexApp).container.avatarImageLoader
                AsyncImage(
                    model = "https://github.com/$ownerLogin.png?size=128",
                    imageLoader = loader,
                    contentDescription = null,
                    placeholder = fallback,
                    error = fallback,
                    contentScale = ContentScale.Crop,
                    modifier = modifier.size(size).clip(shape),
                )
            }
        }
    }
}
