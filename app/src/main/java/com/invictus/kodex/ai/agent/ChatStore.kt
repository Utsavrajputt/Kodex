package com.invictus.kodex.ai.agent

import android.app.Application
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class StoredStep(val label: String, val ok: Boolean)

data class StoredMessage(
    val role: String,
    val text: String,
    val attachments: List<String> = emptyList(),
    val steps: List<StoredStep> = emptyList(),
    /** File names inside [ChatStore.imageDir]. */
    val images: List<String> = emptyList(),
)

data class StoredChat(val id: String, val title: String, val updatedAt: Long, val messages: List<StoredMessage>)

data class ChatMeta(val id: String, val title: String, val updatedAt: Long)

/** Saved chats per project: one small JSON file each under filesDir/ai_chats/<project-hash>/. */
class ChatStore(app: Application, projectPath: String) {
    private val dir = File(app.filesDir, "ai_chats/" + sha(projectPath).take(16))

    /** Photos attached in this project's chats (downscaled JPEGs). */
    val imageDir = File(dir, "img")

    fun list(): List<ChatMeta> = dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
        runCatching { JSONObject(f.readText()).let { ChatMeta(it.getString("id"), it.optString("title"), it.optLong("updatedAt")) } }.getOrNull()
    }.sortedByDescending { it.updatedAt }

    fun load(id: String): StoredChat? = runCatching { fromJson(File(dir, "$id.json").readText()) }.getOrNull()

    fun save(chat: StoredChat) {
        dir.mkdirs()
        val tmp = File(dir, "${chat.id}.json.tmp")
        tmp.writeText(toJson(chat))
        tmp.renameTo(File(dir, "${chat.id}.json"))
        list().drop(MAX_CHATS).forEach { delete(it.id) }
    }

    fun delete(id: String) {
        load(id)?.messages?.flatMap { it.images }?.forEach { File(imageDir, it).delete() }
        File(dir, "$id.json").delete()
    }

    companion object {
        const val MAX_CHATS = 50

        fun toJson(c: StoredChat): String = JSONObject()
            .put("id", c.id).put("title", c.title).put("updatedAt", c.updatedAt)
            .put("messages", JSONArray(c.messages.map { m ->
                JSONObject().put("role", m.role).put("text", m.text)
                    .put("attachments", JSONArray(m.attachments))
                    .put("images", JSONArray(m.images))
                    .put("steps", JSONArray(m.steps.map { JSONObject().put("label", it.label).put("ok", it.ok) }))
            }))
            .toString()

        fun fromJson(json: String): StoredChat {
            val o = JSONObject(json)
            val msgs = o.optJSONArray("messages") ?: JSONArray()
            return StoredChat(
                o.getString("id"), o.optString("title"), o.optLong("updatedAt"),
                (0 until msgs.length()).map { i ->
                    val m = msgs.getJSONObject(i)
                    val atts = m.optJSONArray("attachments") ?: JSONArray()
                    val steps = m.optJSONArray("steps") ?: JSONArray()
                    val imgs = m.optJSONArray("images") ?: JSONArray()
                    StoredMessage(
                        m.optString("role"), m.optString("text"),
                        (0 until atts.length()).map { atts.getString(it) },
                        (0 until steps.length()).map { steps.getJSONObject(it).let { s -> StoredStep(s.optString("label"), s.optBoolean("ok", true)) } },
                        (0 until imgs.length()).map { imgs.getString(it) },
                    )
                },
            )
        }

        private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
