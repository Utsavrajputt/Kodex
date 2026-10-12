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
        private val RETIRED = mapOf(
            "gemini-2.5-flash" to GeminiProvider.DEFAULT_MODEL_ID,
            "gemini-2.5-pro" to GeminiProvider.DEFAULT_MODEL_ID,
        )

        /** Maps a retired model id to its replacement; any other id is returned unchanged. */
        fun migrated(id: String): String = RETIRED[id.trim()] ?: id
    }
}
