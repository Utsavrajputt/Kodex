package com.invictus.kodex.ai.usage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Context.usageDataStore by preferencesDataStore(name = "ai_usage")

/** Persists [UsageLedger] counters (and the user's per-model daily-limit overrides) across app restarts. */
object UsageStore {
    private val LEDGER = stringPreferencesKey("usage.ledger")
    private val LIMITS = stringPreferencesKey("usage.limits")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var appContext: Context? = null

    private val _ledger = MutableStateFlow<Map<String, UsageRecord>>(emptyMap())
    val ledger: StateFlow<Map<String, UsageRecord>> = _ledger.asStateFlow()

    private val _limits = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** User overrides of the daily request limit, keyed by [UsageLedger.key]. */
    val limits: StateFlow<Map<String, Int>> = _limits.asStateFlow()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            val p = context.applicationContext.usageDataStore.data.first()
            val loaded = p[LEDGER]?.let(::decodeLedger).orEmpty()
            val loadedLimits = p[LIMITS]?.let(::decodeLimits).orEmpty()
            _ledger.update { cur -> loaded + cur }
            _limits.update { cur -> loadedLimits + cur }
        }
    }

    /** Counts one finished (or failed-after-sending) request. Safe to call from any thread. */
    fun record(provider: String, model: String, inputTokens: Int, outputTokens: Int) {
        val now = System.currentTimeMillis()
        val next = _ledger.updateAndGet { UsageLedger.record(it, provider, model, inputTokens, outputTokens, now) }
        persist(LEDGER, encodeLedger(next))
    }

    /** [limit] <= 0 clears the override and falls back to the built-in default. */
    fun setDailyLimit(provider: String, model: String, limit: Int) {
        val k = UsageLedger.key(provider, model)
        val next = _limits.updateAndGet { if (limit > 0) it + (k to limit) else it - k }
        persist(LIMITS, encodeLimits(next))
    }

    fun clear() {
        _ledger.value = emptyMap()
        persist(LEDGER, encodeLedger(emptyMap()))
    }

    private fun persist(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        val ctx = appContext ?: return
        scope.launch { ctx.usageDataStore.edit { it[key] = value } }
    }

    internal fun encodeLedger(map: Map<String, UsageRecord>): String {
        val o = JSONObject()
        map.forEach { (k, r) ->
            o.put(k, JSONObject()
                .put("dk", r.dayKey).put("d", bucket(r.day))
                .put("mk", r.monthKey).put("m", bucket(r.month)))
        }
        return o.toString()
    }

    internal fun decodeLedger(json: String): Map<String, UsageRecord> = runCatching {
        val o = JSONObject(json)
        o.keys().asSequence().mapNotNull { k ->
            val r = o.optJSONObject(k) ?: return@mapNotNull null
            k to UsageRecord(r.optString("dk"), bucket(r.optJSONObject("d")), r.optString("mk"), bucket(r.optJSONObject("m")))
        }.toMap()
    }.getOrDefault(emptyMap())

    internal fun encodeLimits(map: Map<String, Int>): String = JSONObject(map as Map<*, *>).toString()

    internal fun decodeLimits(json: String): Map<String, Int> = runCatching {
        val o = JSONObject(json)
        o.keys().asSequence().associateWith { o.optInt(it, 0) }.filterValues { it > 0 }
    }.getOrDefault(emptyMap())

    private fun bucket(b: Bucket) = JSONObject().put("r", b.requests).put("i", b.inputTokens).put("o", b.outputTokens)
    private fun bucket(o: JSONObject?) = Bucket(o?.optInt("r") ?: 0, o?.optLong("i") ?: 0, o?.optLong("o") ?: 0)
}
