package com.invictus.kodex.ai.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.invictus.kodex.ai.provider.AiModel
import com.invictus.kodex.ai.provider.GeminiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.aiPrefsStore by preferencesDataStore(name = "ai_prefs")

/** Provider/model/consent/exclusions. Never stores keys (see AiKeyStore). */
class AiPrefs(private val context: Context) {

    private object K {
        val PROVIDER = stringPreferencesKey("ai.provider")
        val CUSTOM_MODEL = stringPreferencesKey("ai.custom_model")
        val CONSENT = booleanPreferencesKey("ai.consent_granted")
        val GLOBS = stringPreferencesKey("ai.exclude_globs")
        val COMMIT_STYLE = stringPreferencesKey("ai.commit_style")
        val AUTO_APPLY = booleanPreferencesKey("ai.auto_apply")
        val GLOBAL_INSTRUCTIONS = stringPreferencesKey("ai.global_instructions")
        val MEMORY_ENABLED = booleanPreferencesKey("ai.memory_enabled")
        fun model(p: String) = stringPreferencesKey("ai.model.$p")
    }

    val providerId: Flow<String> = context.aiPrefsStore.data.map { it[K.PROVIDER] ?: "claude" }
    suspend fun setProviderId(v: String) { context.aiPrefsStore.edit { it[K.PROVIDER] = v } }

    fun modelId(provider: String): Flow<String> = context.aiPrefsStore.data.map { it[K.model(provider)] ?: "" }
    suspend fun setModelId(provider: String, v: String) { context.aiPrefsStore.edit { it[K.model(provider)] = v } }

    val customModel: Flow<String> = context.aiPrefsStore.data.map { it[K.CUSTOM_MODEL] ?: "" }
    suspend fun setCustomModel(v: String) { context.aiPrefsStore.edit { it[K.CUSTOM_MODEL] = v } }

    val consentGranted: Flow<Boolean> = context.aiPrefsStore.data.map { it[K.CONSENT] ?: false }
    suspend fun setConsentGranted(v: Boolean) { context.aiPrefsStore.edit { it[K.CONSENT] = v } }

    // Comma-separated glob exclusions, e.g. ".env, *.pem, id_rsa*" (never put a slash-star pair in a KDoc: Kotlin nests comments).
    val excludeGlobs: Flow<String> =
        context.aiPrefsStore.data.map { it[K.GLOBS] ?: ".env, .env.*, *.pem, *.key, id_rsa*" }
    suspend fun setExcludeGlobs(v: String) { context.aiPrefsStore.edit { it[K.GLOBS] = v } }

    /** Name of the last commit-message style picked in the Git screen's AI sheet (tap on the AI button reuses it). */
    val commitStyle: Flow<String> = context.aiPrefsStore.data.map { it[K.COMMIT_STYLE] ?: "" }
    suspend fun setCommitStyle(v: String) { context.aiPrefsStore.edit { it[K.COMMIT_STYLE] = v } }

    /** On = the chat agent writes its edits straight to the files (with Undo) instead of staging them for review. */
    val autoApply: Flow<Boolean> = context.aiPrefsStore.data.map { it[K.AUTO_APPLY] ?: false }
    suspend fun setAutoApply(v: Boolean) { context.aiPrefsStore.edit { it[K.AUTO_APPLY] = v } }

    /** The user's own standing instructions for the AI, sent in every chat of every project (clipped to [GLOBAL_INSTRUCTIONS_MAX]). */
    val globalInstructions: Flow<String> = context.aiPrefsStore.data.map { it[K.GLOBAL_INSTRUCTIONS] ?: "" }
    suspend fun setGlobalInstructions(v: String) { context.aiPrefsStore.edit { it[K.GLOBAL_INSTRUCTIONS] = v } }

    /** On = the chat agent may save notes to the project's memory on its own. */
    val memoryEnabled: Flow<Boolean> = context.aiPrefsStore.data.map { it[K.MEMORY_ENABLED] ?: true }
    suspend fun setMemoryEnabled(v: Boolean) { context.aiPrefsStore.edit { it[K.MEMORY_ENABLED] = v } }

    /** Rewrites a saved Gemini model that Google has retired to the current default. Idempotent. */
    suspend fun migrateRetiredModels() {
        context.aiPrefsStore.edit { p ->
            val key = K.model("gemini")
            val saved = p[key] ?: return@edit
            val replacement = migrated(saved)
            if (replacement != saved) p[key] = replacement
        }
    }

    suspend fun currentModel(provider: String, defaults: List<AiModel>): String {
        migrateRetiredModels()
        val custom = customModel.first().trim()
        val saved = modelId(provider).first().trim()
        return when {
            custom.isNotEmpty() -> custom
            saved.isNotEmpty() -> saved
            else -> defaults.firstOrNull()?.id ?: ""
        }
    }

    companion object {
        const val GLOBAL_INSTRUCTIONS_MAX = 4_000

        private val RETIRED = mapOf(
            "gemini-2.5-flash" to GeminiProvider.DEFAULT_MODEL_ID,
            "gemini-2.5-pro" to GeminiProvider.DEFAULT_MODEL_ID,
        )

        /** Maps a retired model id to its replacement; any other id is returned unchanged. */
        fun migrated(id: String): String = RETIRED[id.trim()] ?: id
    }
}
