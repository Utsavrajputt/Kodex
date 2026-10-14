package com.invictus.kodex.ai.provider

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Live model lists. The last list each provider returned is kept in memory and on disk, so pickers open
 * instantly and still work offline; [AiProvider.defaultModels] is only the fallback before the first fetch.
 */
object ModelCatalog {
    private const val STORE = "ai_model_cache"
    private const val STALE_MS = 10 * 60 * 1000L

    private var appContext: Context? = null
    private val fetched = ConcurrentHashMap<String, List<AiModel>>()
    private val fetchedAt = ConcurrentHashMap<String, Long>()

    /** Call once from Application.onCreate. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** What the provider last reported (memory, else disk); empty when it never has. */
    fun cached(providerId: String): List<AiModel> = fetched[providerId] ?: load(providerId).also { fetched[providerId] = it }

    /** Picker contents right now: curated defaults the key can use first, then the cached live list. */
    fun models(provider: AiProvider): List<AiModel> = ModelList.merge(provider.defaultModels, cached(provider.id))

    fun find(providerId: String, modelId: String): AiModel? =
        cached(providerId).firstOrNull { it.id == modelId } ?: ProviderRegistry.byId(providerId).defaultModels.firstOrNull { it.id == modelId }

    /** First model a provider with no saved choice should use. */
    fun firstModelId(provider: AiProvider): String = models(provider).firstOrNull()?.id.orEmpty()

    /**
     * Asks the provider for its live list (skipped while the last one is fresh unless [force]). Any failure keeps
     * what is cached. Returns the merged picker list either way.
     */
    suspend fun refresh(provider: AiProvider, key: String, force: Boolean = false): List<AiModel> {
        val age = System.currentTimeMillis() - (fetchedAt[provider.id] ?: 0L)
        if (force || age > STALE_MS || cached(provider.id).isEmpty()) {
            val live = try {
                provider.listModels(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (!live.isNullOrEmpty()) {
                fetched[provider.id] = live
                fetchedAt[provider.id] = System.currentTimeMillis()
                save(provider.id, live)
            }
        }
        return models(provider)
    }

    /**
     * The few models worth showing first: the active one, then recently used, then the provider's own picks,
     * then the newest. Everything else belongs under "All models".
     */
    fun featured(all: List<AiModel>, selectedId: String, recent: List<String>, curated: List<AiModel>, limit: Int = 3): List<AiModel> {
        val byId = all.associateBy { it.id }
        val out = LinkedHashMap<String, AiModel>()
        byId[selectedId]?.let { out[it.id] = it }
        recent.forEach { id -> byId[id]?.let { out.putIfAbsent(it.id, it) } }
        curated.forEach { c -> byId[c.id]?.let { out.putIfAbsent(it.id, it) } }
        all.forEach { out.putIfAbsent(it.id, it) }
        return out.values.take(limit)
    }

    /** Case-insensitive match on label or id. */
    fun search(all: List<AiModel>, query: String): List<AiModel> {
        val q = query.trim().lowercase()
        return if (q.isEmpty()) all else all.filter { q in it.id.lowercase() || q in it.label.lowercase() }
    }

    private fun prefs() = appContext?.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private fun save(providerId: String, list: List<AiModel>) {
        val arr = JSONArray(list.map { JSONObject().put("i", it.id).put("l", it.label).put("c", it.maxContextChars).put("t", it.created) })
        prefs()?.edit()?.putString(providerId, arr.toString())?.apply()
    }

    private fun load(providerId: String): List<AiModel> {
        val raw = prefs()?.getString(providerId, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { n ->
                val o = arr.getJSONObject(n)
                AiModel(o.getString("i"), o.getString("l"), o.getInt("c"), o.optLong("t", 0L))
            }
        }.getOrDefault(emptyList())
    }
}
