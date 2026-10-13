package com.invictus.kodex.ai.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import com.invictus.kodex.ai.provider.AiException
import com.invictus.kodex.ai.provider.ImageGen
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
) {
    val canRaster: Boolean = imageProviderId != null && !imageKey.isNullOrBlank() && ImageGen.supports(imageProviderId)

    suspend fun generate(a: JSONObject): AgentTools.Outcome {
        if (!canRaster || imageProviderId == null || imageKey == null) {
            return err("No image model is available. Tell the user to add a Gemini or OpenAI API key in AI settings to create normal images, and offer an SVG drawing instead.")
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
            ImageGen.generate(imageProviderId, imageKey, full, ref?.readBytes(), if (ref?.extension.equals("png", true)) "image/png" else "image/jpeg")
        } catch (e: AiException) {
            return err("Image generation failed: ${e.error.message}")
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
        private const val DONE_NOTE =
            "Done. The image is now shown to the user in the chat with Save and \"Use as app icon\" buttons. " +
                "Do not describe it at length; offer one or two ways to refine it."
        private const val ICON_BRIEF =
            "Make it a polished, high-quality modern app icon, the kind a top designer would ship: rich but clean (soft gradients, depth, " +
                "subtle lighting are welcome). Square 1:1 artwork that fills the whole canvas edge to edge (no rounded corners, no border, " +
                "no mockup, no device frame, no transparent areas). One bold, memorable subject centred inside the central 60% of the " +
                "canvas so launcher masks never cut it. Strong contrast, limited palette, readable at 48 px. No text unless the user asked for it."
    }
}
