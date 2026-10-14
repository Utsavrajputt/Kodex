package com.invictus.kodex.ai.provider

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A user-added OpenAI-compatible endpoint. Its API key lives in AiKeyStore under [id], like every provider's. */
data class CustomProviderDef(val id: String, val name: String, val baseUrl: String)

/**
 * Adding a built-in provider = one AiProvider class (or one [ProviderPresets] entry) + (settings strings).
 * User-added providers are kept in a tiny synchronous store so resolution never races app start-up.
 */
object ProviderRegistry {
    private const val STORE = "ai_custom_providers"
    private const val LIST = "list"

    private val builtIn: List<AiProvider> =
        (listOf<AiProvider>(ClaudeProvider(), OpenAiProvider(), GeminiProvider()) + ProviderPresets.all.map { it.toProvider() })
            .map { UsageTrackedProvider(it) }

    @Volatile private var defs: List<CustomProviderDef> = emptyList()
    @Volatile private var snapshot: List<AiProvider> = builtIn

    val all: List<AiProvider> get() = snapshot

    fun byId(id: String): AiProvider = snapshot.firstOrNull { it.id == id } ?: builtIn.first()

    fun isCustom(id: String) = defs.any { it.id == id }

    fun customDef(id: String): CustomProviderDef? = defs.firstOrNull { it.id == id }

    /** Call once from Application.onCreate. */
    fun init(context: Context) {
        val raw = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(LIST, null)
        val list = runCatching {
            val arr = JSONArray(raw ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CustomProviderDef(o.getString("id"), o.getString("name"), o.getString("url"))
            }
        }.getOrDefault(emptyList())
        apply(list)
    }

    /** Returns the new provider, or null when [baseUrl] isn't a usable http(s) address. */
    fun addCustom(context: Context, name: String, baseUrl: String): CustomProviderDef? {
        val url = normalizeBaseUrl(baseUrl) ?: return null
        val label = name.trim().ifEmpty { return null }
        val def = CustomProviderDef("custom_" + java.util.UUID.randomUUID().toString().take(8), label, url)
        persist(context, defs + def)
        return def
    }

    fun removeCustom(context: Context, id: String) {
        if (isCustom(id)) persist(context, defs.filter { it.id != id })
    }

    /** "https://host/v1/", ".../v1/chat/completions" and ".../v1" all mean the same base. */
    fun normalizeBaseUrl(raw: String): String? {
        val u = raw.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')
        return u.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.takeIf { it.length > 8 }
    }

    private fun persist(context: Context, list: List<CustomProviderDef>) {
        val arr = JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name).put("url", it.baseUrl) })
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(LIST, arr.toString()).apply()
        apply(list)
    }

    private fun apply(list: List<CustomProviderDef>) {
        defs = list
        snapshot = builtIn + list.map { UsageTrackedProvider(OpenAiProvider(it.id, it.name, it.baseUrl, emptyList(), "max_tokens")) }
    }
}
