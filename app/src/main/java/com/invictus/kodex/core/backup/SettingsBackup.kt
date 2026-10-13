package com.invictus.kodex.core.backup

import android.content.Context
import com.invictus.kodex.ai.AiSwitch
import com.invictus.kodex.ai.agent.ChatStore
import com.invictus.kodex.ai.prefs.AiPrefs
import com.invictus.kodex.core.editor.EditorSettingsStore
import com.invictus.kodex.github.prefs.GitHubPrefs
import com.invictus.kodex.ui.theme.ThemeSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What a backup can carry. [id] is the key used inside the file, so never rename it. */
enum class BackupCategory(val id: String) {
    Appearance("appearance"),
    Editor("editor"),
    Behavior("behavior"),
    Ai("ai"),
    GitHub("github"),
    AiChats("ai_chats"),
}

/** A backup file read into memory: which categories it holds, plus their raw data. */
class BackupContents internal constructor(
    val categories: Set<BackupCategory>,
    internal val settings: JSONObject,
    internal val chats: Map<String, ByteArray>,
)

data class ImportSummary(val categories: Set<BackupCategory>, val chatsAdded: Int, val chatsSkipped: Int)

/** Thrown for a file that is not a (supported) Kodex backup. */
class InvalidBackupException(message: String) : Exception(message)

/**
 * Exports and imports Kodex settings as one .zip: manifest.json, settings.json and ai_chats/<project>/<chat>.json.
 * Secrets are never written: API keys (AiKeyStore), git credentials (GitCredentialStore) and the AI consent flag
 * stay on the device, so the user signs in again after importing. Chat images are left out to keep the file small.
 */
class SettingsBackup(context: Context) {
    private val app = context.applicationContext
    private val editor = EditorSettingsStore(app)
    private val aiPrefs = AiPrefs(app)
    private val gitHubPrefs = GitHubPrefs(app)

    private enum class Store { Editor, Ai, GitHub }

    private class Spec(val shared: List<String>, val store: Store?, val accept: (String) -> Boolean)

    private fun spec(c: BackupCategory): Spec = when (c) {
        BackupCategory.Appearance -> Spec(listOf(PREFS_THEME), Store.Editor) { it in APPEARANCE_KEYS }
        BackupCategory.Editor -> Spec(emptyList(), Store.Editor) { it in EDITOR_KEYS }
        BackupCategory.Behavior -> Spec(emptyList(), Store.Editor) { it in BEHAVIOR_KEYS }
        // The consent flag is a privacy decision tied to this install, so it is not carried over.
        BackupCategory.Ai -> Spec(listOf(PREFS_AI_SWITCH), Store.Ai) { it != KEY_AI_CONSENT }
        BackupCategory.GitHub -> Spec(emptyList(), Store.GitHub) { true }
        BackupCategory.AiChats -> Spec(emptyList(), null) { false }
    }

    /** Writes [categories] to [out]; returns how many chats were included. */
    suspend fun export(out: OutputStream, categories: Set<BackupCategory>): Int = withContext(Dispatchers.IO) {
        require(categories.isNotEmpty()) { "No category selected" }
        val settings = JSONObject()
        for (c in categories) {
            if (c == BackupCategory.AiChats) continue
            val s = spec(c)
            val o = JSONObject()
            if (s.shared.isNotEmpty()) {
                val shared = JSONObject()
                for (name in s.shared) {
                    shared.put(name, PrefsJson.encode(app.getSharedPreferences(name, Context.MODE_PRIVATE).all, s.accept))
                }
                o.put("shared", shared)
            }
            when (s.store) {
                Store.Editor -> o.put("store", editor.exportEntries(s.accept))
                Store.Ai -> o.put("store", aiPrefs.exportEntries(s.accept))
                Store.GitHub -> o.put("store", gitHubPrefs.exportEntries(s.accept))
                null -> Unit
            }
            settings.put(c.id, o)
        }

        var chatCount = 0
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                JSONObject().put("app", APP_ID).put("format", FORMAT).put("createdAt", System.currentTimeMillis())
                    .put("categories", org.json.JSONArray(categories.map { it.id })).toString().toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(settings.toString().toByteArray())
            zip.closeEntry()
            if (BackupCategory.AiChats in categories) {
                File(app.filesDir, CHATS_DIR).listFiles { f -> f.isDirectory }.orEmpty().forEach { dir ->
                    dir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty().forEach { f ->
                        val chat = runCatching { ChatStore.fromJson(f.readText()) }.getOrNull() ?: return@forEach
                        // Images stay on the device: drop the references so nothing points at a missing file.
                        val lean = chat.copy(messages = chat.messages.map { it.copy(images = emptyList(), generated = emptyList()) })
                        zip.putNextEntry(ZipEntry("$CHATS_DIR/${dir.name}/${f.name}"))
                        zip.write(ChatStore.toJson(lean).toByteArray())
                        zip.closeEntry()
                        chatCount++
                    }
                }
            }
        }
        chatCount
    }

    /** Reads and validates a backup file. Throws [InvalidBackupException] if it is not one of ours. */
    suspend fun read(input: InputStream): BackupContents = withContext(Dispatchers.IO) {
        var manifest: JSONObject? = null
        var settings: JSONObject? = null
        val chats = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                val wanted = name == "manifest.json" || name == "settings.json" || CHAT_ENTRY.matches(name)
                if (!wanted) continue
                val bytes = ByteArrayOutputStream().also { buf ->
                    val tmp = ByteArray(8192)
                    while (true) {
                        val n = zip.read(tmp)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw InvalidBackupException("Backup is too large")
                        buf.write(tmp, 0, n)
                    }
                }.toByteArray()
                when {
                    name == "manifest.json" -> manifest = runCatching { JSONObject(String(bytes)) }.getOrNull()
                    name == "settings.json" -> settings = runCatching { JSONObject(String(bytes)) }.getOrNull()
                    else -> chats[name] = bytes
                }
            }
        }
        val m = manifest
        val s = settings
        if (m == null || s == null || m.optString("app") != APP_ID) throw InvalidBackupException("Not a Kodex backup")
        if (m.optInt("format", 0) != FORMAT) throw InvalidBackupException("Unsupported backup version")
        val found = BackupCategory.entries.filter { c ->
            if (c == BackupCategory.AiChats) chats.isNotEmpty() else s.has(c.id)
        }.toSet()
        BackupContents(found, s, chats)
    }

    /** Applies [categories] (those the backup holds) in [replace] or merge mode. */
    suspend fun import(contents: BackupContents, categories: Set<BackupCategory>, replace: Boolean): ImportSummary =
        withContext(Dispatchers.IO) {
            val todo = categories.intersect(contents.categories)
            if (todo.isEmpty()) throw InvalidBackupException("Nothing selected is in this backup")
            var added = 0
            var skipped = 0
            for (c in todo) {
                if (c == BackupCategory.AiChats) {
                    val r = importChats(contents.chats, replace)
                    added = r.first
                    skipped = r.second
                    continue
                }
                val s = spec(c)
                val o = contents.settings.getJSONObject(c.id)
                o.optJSONObject("shared")?.let { shared ->
                    for (name in s.shared) {
                        val values = PrefsJson.decode(shared.optJSONArray(name), s.accept)
                        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
                        val ed = prefs.edit()
                        PrefsJson.applyTo(ed, prefs.all, values, replace)
                        ed.commit()
                    }
                }
                val values = PrefsJson.decode(o.optJSONArray("store"), s.accept)
                when (s.store) {
                    Store.Editor -> editor.importEntries(values, s.accept, replace)
                    Store.Ai -> aiPrefs.importEntries(values, s.accept, replace)
                    Store.GitHub -> gitHubPrefs.importEntries(values, s.accept, replace)
                    null -> Unit
                }
            }
            // Theme and the AI switch live in memory too: reload them so the change shows without a restart.
            withContext(Dispatchers.Main) {
                if (BackupCategory.Appearance in todo) ThemeSettings.init(app)
                if (BackupCategory.Ai in todo) AiSwitch.init(app)
            }
            ImportSummary(todo, added, skipped)
        }

    /** Replace wipes every saved chat first (images included); merge only adds chats that are not there yet. */
    private fun importChats(entries: Map<String, ByteArray>, replace: Boolean): Pair<Int, Int> {
        val root = File(app.filesDir, CHATS_DIR)
        if (replace) root.deleteRecursively()
        var added = 0
        var skipped = 0
        for ((name, bytes) in entries) {
            val m = CHAT_ENTRY.matchEntire(name) ?: continue
            val chat = runCatching { ChatStore.fromJson(String(bytes)) }.getOrNull()
            if (chat == null || !SAFE_ID.matches(chat.id)) { skipped++; continue }
            val dir = File(root, m.groupValues[1])
            val target = File(dir, chat.id + ".json")
            if (target.exists()) { skipped++; continue }
            val lean = chat.copy(messages = chat.messages.map { it.copy(images = emptyList(), generated = emptyList()) })
            dir.mkdirs()
            val tmp = File(dir, chat.id + ".json.tmp")
            tmp.writeText(ChatStore.toJson(lean))
            if (tmp.renameTo(target)) added++ else { tmp.delete(); skipped++ }
        }
        return added to skipped
    }

    private companion object {
        const val APP_ID = "kodex"
        const val FORMAT = 1
        const val CHATS_DIR = "ai_chats"
        const val MAX_BYTES = 64L * 1024 * 1024
        const val PREFS_THEME = "kodex_theme"
        const val PREFS_AI_SWITCH = "ai_switch"
        const val KEY_AI_CONSENT = "ai.consent_granted"

        val CHAT_ENTRY = Regex("ai_chats/([0-9a-f]{16})/[A-Za-z0-9_-]{1,64}\\.json")
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")

        val APPEARANCE_KEYS = setOf("editor_theme_id", "editor_font_size_px")
        val EDITOR_KEYS = setOf(
            "editor_symbol_bar", "editor_symbol_bar_visible", "editor_autocomplete_enabled",
            "editor_pair_cursor_enabled", "editor_auto_reload_external", "editor_auto_preview_enabled",
            "editor_restore_tabs_on_open", "search_default_strings_only", "search_extra_excludes", "search_max_file_mb",
        )
        val BEHAVIOR_KEYS = setOf(
            "project_sheet_show_hidden", "clone_default_parent", "git_status_polling_enabled",
            "file_tree_show_hidden", "file_tree_hscroll_long_names", "browse_shortcuts",
        )
    }
}
