package com.invictus.kodex.ai.provider

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Raster image generation / editing for the providers that have an image model (Gemini, OpenAI).
 * Claude has none, so it draws SVG instead (see ImageToolbox). Each provider tries its models newest first and
 * falls over to the next one when a model isn't available to the key.
 */
object ImageGen {
    class Result(val bytes: ByteArray, val mime: String)

    private val GEMINI_MODELS = listOf("gemini-3.1-flash-image", "gemini-3-pro-image", "gemini-2.5-flash-image")
    private val OPENAI_MODELS = listOf("gpt-image-2", "gpt-image-1.5", "gpt-image-1")

    fun supports(providerId: String) = providerId == "gemini" || providerId == "openai"

    /** [reference] is an existing picture to edit / restyle (null = create from scratch). Throws [AiException]. */
    suspend fun generate(providerId: String, key: String, prompt: String, reference: ByteArray?, refMime: String): Result =
        when (providerId) {
            "gemini" -> gemini(key, prompt, reference, refMime)
            "openai" -> openAi(key, prompt, reference, refMime)
            else -> throw AiException(AiError.Unknown(0, "$providerId can't generate images"))
        }

    /** True when the failure is about the model itself (unknown / no access), so the next model is worth a try. */
    private fun modelProblem(code: Int, body: String): Boolean {
        val l = body.lowercase()
        return code == 404 || ((code == 400 || code == 403) &&
            (l.contains("model") || l.contains("verif") || l.contains("not supported") || l.contains("not found")))
    }

    private suspend fun gemini(key: String, prompt: String, reference: ByteArray?, refMime: String): Result {
        var last: AiError? = null
        for (model in GEMINI_MODELS) {
            val parts = JSONArray().put(JSONObject().put("text", prompt))
            if (reference != null) {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", refMime).put("data", Base64.encodeToString(reference, Base64.NO_WRAP)),
                    ),
                )
            }
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", JSONArray(listOf("TEXT", "IMAGE")))
                        .put("imageConfig", JSONObject().put("aspectRatio", "1:1")),
                )
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", key)
                .header("content-type", "application/json")
                .post(AiHttp.jsonBody(body))
                .build()
            when (val r = AiHttp.complete(req)) {
                is AiHttp.CompleteResult.Timeout -> throw AiException(AiError.Timeout())
                is AiHttp.CompleteResult.Net -> throw AiException(AiError.Network(java.io.IOException(r.message)))
                is AiHttp.CompleteResult.Http -> {
                    if (r.code in 200..299) return parseGemini(r.body)
                    val err = AiHttp.mapHttpError(r.code, r.body, null)
                    if (modelProblem(r.code, r.body)) { last = err; continue }
                    throw AiException(err)
                }
            }
        }
        throw AiException(last ?: AiError.Unknown(404, "No Gemini image model is available for this key"))
    }

    private fun parseGemini(body: String): Result {
        val obj = runCatching { JSONObject(body) }.getOrElse { throw AiException(AiError.Malformed(body.take(200))) }
        val cand = obj.optJSONArray("candidates")?.optJSONObject(0)
        val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
        var said = ""
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
                val data = inline?.optString("data").orEmpty()
                if (data.isNotEmpty()) {
                    val mime = inline?.optString("mimeType")?.takeIf { it.isNotEmpty() }
                        ?: inline?.optString("mime_type")?.takeIf { it.isNotEmpty() } ?: "image/png"
                    return Result(Base64.decode(data, Base64.DEFAULT), mime)
                }
                said += p.optString("text")
            }
        }
        val blocked = obj.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty().isNotEmpty() ||
            cand?.optString("finishReason").orEmpty().contains("SAFETY")
        if (blocked) throw AiException(AiError.ContentFiltered())
        throw AiException(AiError.Unknown(0, "No image returned" + if (said.isNotBlank()) ": " + said.take(160) else ""))
    }

    private suspend fun openAi(key: String, prompt: String, reference: ByteArray?, refMime: String): Result {
        var last: AiError? = null
        for (model in OPENAI_MODELS) {
            val req = if (reference == null) {
                val body = JSONObject().put("model", model).put("prompt", prompt).put("size", "1024x1024").put("n", 1)
                Request.Builder()
                    .url("https://api.openai.com/v1/images/generations")
                    .header("Authorization", "Bearer $key")
                    .post(AiHttp.jsonBody(body))
                    .build()
            } else {
                val ext = if (refMime.contains("png")) "png" else "jpg"
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", model)
                    .addFormDataPart("prompt", prompt)
                    .addFormDataPart("size", "1024x1024")
                    .addFormDataPart("image[]", "reference.$ext", reference.toRequestBody(refMime.toMediaType()))
                    .build()
                Request.Builder()
                    .url("https://api.openai.com/v1/images/edits")
                    .header("Authorization", "Bearer $key")
                    .post(form)
                    .build()
            }
            when (val r = AiHttp.complete(req)) {
                is AiHttp.CompleteResult.Timeout -> throw AiException(AiError.Timeout())
                is AiHttp.CompleteResult.Net -> throw AiException(AiError.Network(java.io.IOException(r.message)))
                is AiHttp.CompleteResult.Http -> {
                    if (r.code in 200..299) {
                        val b64 = runCatching {
                            JSONObject(r.body).getJSONArray("data").getJSONObject(0).getString("b64_json")
                        }.getOrElse { throw AiException(AiError.Malformed(r.body.take(200))) }
                        return Result(Base64.decode(b64, Base64.DEFAULT), "image/png")
                    }
                    val err = AiHttp.mapHttpError(r.code, r.body, null)
                    if (modelProblem(r.code, r.body)) { last = err; continue }
                    throw AiException(err)
                }
            }
        }
        throw AiException(last ?: AiError.Unknown(404, "No OpenAI image model is available for this key"))
    }
}
