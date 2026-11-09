package com.invictus.kodex.ai.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import com.invictus.kodex.ai.provider.AiException
import com.invictus.kodex.ai.provider.ImageGen
import com.invictus.kodex.core.tracer.ImageTracer
import com.invictus.kodex.core.tracer.NativeTracer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** A picture the agent made: always has a PNG (the preview); vector work also keeps its SVG source. */
data class GeneratedImage(
    val path: String,
    val svgPath: String?,
    val title: String,
    /** "app_icon" or "image". */
    val purpose: String,
) {
    val isIcon get() = purpose == PURPOSE_ICON

    companion object {
        const val PURPOSE_ICON = "app_icon"
        const val PURPOSE_IMAGE = "image"
    }
}

/**
 * Image tools for the agent. `generate_image` paints normal pictures with an image model (Gemini / OpenAI, whichever has a
 * key, even when the chat model is Claude); `render_svg` draws vector art and is for when the user asks for SVG / vector. Results are saved under [outDir] and
 * handed to [onImage] so the chat can show them with Save / Use-as-app-icon buttons.
 */
class ImageToolbox(
    /** Provider that paints images (Gemini / OpenAI), or null when none is available: then only render_svg works. */
    private val imageProviderId: String?,
    private val imageKey: String?,
    private val outDir: File,
    /** The newest photo the user attached to the conversation (to edit / restyle it). */
    private val attachedImage: () -> File?,
    /** The newest image the agent made earlier in this chat (to refine it). */
    private val lastGenerated: () -> File?,
    private val onImage: (GeneratedImage) -> Unit,
    /** True when the user asked for SVG / vector art somewhere in this chat; render_svg is refused otherwise. */
    private val svgAllowed: () -> Boolean = { true },
) {
    val canRaster: Boolean = imageProviderId != null && !imageKey.isNullOrBlank() && ImageGen.supports(imageProviderId)

    suspend fun generate(a: JSONObject): AgentTools.Outcome {
        if (!canRaster || imageProviderId == null || imageKey == null) {
            return err("No image model is available: image generation needs a Gemini or OpenAI API key in AI settings. Tell the user exactly that. Do NOT draw an SVG instead unless the user asks for SVG / vector art.")
        }
        val prompt = a.optString("prompt").trim()
        if (prompt.isEmpty()) return err("Missing 'prompt'")
        val purpose = purposeOf(a)
        val title = titleOf(a, purpose)
        val ref: File? = when (a.optString("use_reference")) {
            "attached" -> attachedImage() ?: return err("The user has not attached an image in this chat")
            "last_generated" -> lastGenerated() ?: return err("There is no earlier generated image in this chat")
            else -> null
        }
        val full = if (purpose == GeneratedImage.PURPOSE_ICON) prompt + "\n\n" + ICON_BRIEF else prompt
        val result = try {
            ImageGen.generate(
                imageProviderId, imageKey, full, ref?.readBytes(), if (ref?.extension.equals("png", true)) "image/png" else "image/jpeg",
                highQuality = purpose == GeneratedImage.PURPOSE_ICON,
            )
        } catch (e: AiException) {
            return err("Image generation failed: ${e.error.message}. Tell the user this exact reason. Do NOT draw an SVG instead unless the user asks for SVG / vector art.")
        }
        val decoded = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
            ?: return err("The provider returned data that is not an image")
        val bmp = if (purpose == GeneratedImage.PURPOSE_ICON) squareScaled(decoded, SIZE) else decoded
        val png = writePng(bmp)
        if (bmp !== decoded) bmp.recycle()
        decoded.recycle()
        onImage(GeneratedImage(png.absolutePath, null, title, purpose))
        return AgentTools.Outcome(DONE_NOTE)
    }

    fun renderSvg(a: JSONObject): AgentTools.Outcome {
        if (!svgAllowed()) {
            return err("The user did not ask for SVG / vector art, so do not draw one. Use generate_image for pictures and icons; if it is unavailable or failed, tell the user why and stop.")
        }
        val text = a.optString("svg").trim()
        if (!text.contains("<svg")) return err("'svg' must be a complete <svg>…</svg> document")
        val purpose = purposeOf(a)
        val title = titleOf(a, purpose)
        val svg = try {
            SVG.getFromString(text)
        } catch (e: SVGParseException) {
            return err("Invalid SVG: ${e.message}. Fix it and call render_svg again.")
        }
        if (svg.documentViewBox == null && svg.documentWidth <= 0f) {
            return err("The SVG needs a viewBox, e.g. viewBox=\"0 0 512 512\". Add it and call render_svg again.")
        }
        svg.setDocumentWidth(SIZE.toFloat())
        svg.setDocumentHeight(SIZE.toFloat())
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        try {
            svg.renderToCanvas(Canvas(bmp))
            val png = writePng(bmp)
            val svgFile = File(png.parentFile, png.nameWithoutExtension + ".svg").also { it.writeText(text, Charsets.UTF_8) }
            onImage(GeneratedImage(png.absolutePath, svgFile.absolutePath, title, purpose))
        } finally {
            bmp.recycle()
        }
        return AgentTools.Outcome(DONE_NOTE)
    }

    /** Converts the user's attached photo (or the last generated image) into an SVG with the native tracer. */
    suspend fun traceImage(a: JSONObject): AgentTools.Outcome {
        if (!NativeTracer.available) {
            return err("The native image tracer is not part of this build. Tell the user that, and that the Image tracer tool is available in Tools once the app is built with it.")
        }
        val src: File = when (a.optString("source")) {
            "last_generated" -> lastGenerated() ?: return err("There is no earlier generated image in this chat")
            "attached" -> attachedImage() ?: return err("The user has not attached an image in this chat")
            else -> attachedImage() ?: lastGenerated() ?: return err("There is no image to trace: ask the user to attach one")
        }
        val quality = a.optInt("quality", 4).coerceIn(ImageTracer.QUALITY_MIN, ImageTracer.QUALITY_MAX)
        val colors = a.optInt("colors", 0).coerceAtLeast(0)
        val title = a.optString("title").trim().take(60).ifEmpty { "Traced SVG" }
        return try {
            val (bmp, svgText) = withContext(Dispatchers.Default) {
                val decoded = ImageTracer.decode(src)
                decoded.bitmap to ImageTracer.trace(decoded.bitmap, quality, colors)
            }
            // The PNG shown in the chat is the SVG drawn back; a very heavy SVG falls back to the source picture.
            val preview = withContext(Dispatchers.Default) {
                try {
                    ImageTracer.render(svgText, bmp.width, bmp.height)
                } catch (e: Exception) {
                    null
                } catch (e: OutOfMemoryError) {
                    null
                }
            } ?: bmp
            val png = writePng(preview)
            val svgFile = File(png.parentFile, png.nameWithoutExtension + ".svg").also { it.writeText(svgText, Charsets.UTF_8) }
            onImage(GeneratedImage(png.absolutePath, svgFile.absolutePath, title, GeneratedImage.PURPOSE_IMAGE))
            AgentTools.Outcome(TRACE_NOTE)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            err("Tracing failed: ${e.message}. Tell the user this exact reason.")
        }
    }

    private fun purposeOf(a: JSONObject) =
        if (a.optString("purpose") == GeneratedImage.PURPOSE_ICON) GeneratedImage.PURPOSE_ICON else GeneratedImage.PURPOSE_IMAGE

    private fun titleOf(a: JSONObject, purpose: String) = a.optString("title").trim().take(60)
        .ifEmpty { if (purpose == GeneratedImage.PURPOSE_ICON) "App icon" else "Generated image" }

    private fun squareScaled(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val cropped = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = if (side == size) cropped else Bitmap.createScaledBitmap(cropped, size, size, true)
        if (cropped !== src && cropped !== out) cropped.recycle()
        return out
    }

    private fun writePng(bmp: Bitmap): File {
        outDir.mkdirs()
        val f = File(outDir, "gen_" + UUID.randomUUID().toString().take(12) + ".png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun err(m: String) = AgentTools.Outcome(m, isError = true)

    companion object {
        const val SIZE = 1024
        private const val TRACE_NOTE =
            "Done. The traced SVG is now shown to the user in the chat with Save buttons for the PNG preview and the SVG. " +
                "Mention the quality and colors you used and offer to retrace with different values; do not paste any SVG."
        private const val DONE_NOTE =
            "Done. The image is now shown to the user in the chat with Save and \"Use as app icon\" buttons. " +
                "Do not describe it at length; offer one or two ways to refine it."
        private const val ICON_BRIEF =
            "Design this like a top app-icon designer would for the Google Play / App Store: ONE bold, instantly recognisable symbol (a simple, " +
                "clever shape, not a busy scene), built from clean geometric forms with smooth curves and precise edges. Modern premium look: " +
                "a rich two- or three-colour gradient background, soft layered depth, a gentle highlight and a subtle soft shadow under the symbol, " +
                "like polished glass or satin 3D, never flat clip-art and never noisy. Strong contrast between symbol and background, a limited " +
                "harmonious palette, crisp at 48 px. Square 1:1 artwork that fills the whole canvas edge to edge (no rounded corners, no border, " +
                "no frame, no mockup, no device, no transparent areas), symbol centred inside the central 60% so launcher masks never cut it. " +
                "No text, letters or watermarks unless the user asked for them."
    }
}
