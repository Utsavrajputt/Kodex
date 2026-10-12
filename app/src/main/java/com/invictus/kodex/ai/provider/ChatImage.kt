package com.invictus.kodex.ai.provider

import java.io.File
import java.util.Base64

/**
 * A photo attached to a user turn: a downscaled JPEG in app storage. Only the path is kept in memory and in saved
 * chats; the bytes are read and base64-encoded when a request is built (null when the file has been removed).
 */
data class ChatImage(val path: String, val mime: String = "image/jpeg") {
    fun base64(): String? = runCatching { Base64.getEncoder().encodeToString(File(path).readBytes()) }.getOrNull()
}
