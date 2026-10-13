package com.invictus.kodex.core.backup

import android.content.SharedPreferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed JSON form of a preferences map (DataStore or SharedPreferences), used by the settings backup.
 * Each entry is {"k": name, "t": type, "v": value} so Int/Long/Float survive the round trip.
 */
object PrefsJson {

    fun encode(all: Map<String, Any?>, accept: (String) -> Boolean): JSONArray {
        val arr = JSONArray()
        loop@ for ((name, value) in all) {
            if (!accept(name)) continue
            val o = JSONObject().put("k", name)
            when (value) {
                is Boolean -> o.put("t", "b").put("v", value)
                is Int -> o.put("t", "i").put("v", value)
                is Long -> o.put("t", "l").put("v", value)
                is Float -> o.put("t", "f").put("v", value.toDouble())
                is Double -> o.put("t", "d").put("v", value)
                is String -> o.put("t", "s").put("v", value)
                is Set<*> -> o.put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                else -> continue@loop
            }
            arr.put(o)
        }
        return arr
    }

    /** Entries that pass [accept] and have a well-formed value. Anything else is dropped. */
    fun decode(arr: JSONArray?, accept: (String) -> Boolean): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("k")
            if (name.isEmpty() || !accept(name) || !o.has("v")) continue
            val value: Any? = runCatching {
                when (o.optString("t")) {
                    "b" -> o.getBoolean("v")
                    "i" -> o.getInt("v")
                    "l" -> o.getLong("v")
                    "f" -> o.getDouble("v").toFloat()
                    "d" -> o.getDouble("v")
                    "s" -> o.getString("v")
                    "ss" -> o.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    else -> null
                }
            }.getOrNull()
            if (value != null) out[name] = value
        }
        return out
    }

    /**
     * Writes [values] into a DataStore edit. [replace] first removes every existing key that passes [accept]
     * (so the category goes back to exactly what the backup holds). A value whose type differs from what the
     * store already holds under that name is skipped, so a stale file can never poison a typed read.
     */
    @Suppress("UNCHECKED_CAST")
    fun applyTo(prefs: MutablePreferences, values: Map<String, Any>, accept: (String) -> Boolean, replace: Boolean) {
        val existing = prefs.asMap().entries.associate { it.key.name to it.value }
        if (replace) {
            prefs.asMap().keys.filter { accept(it.name) }.forEach { prefs.remove(it as Preferences.Key<Any>) }
        }
        for ((name, value) in values) {
            if (!accept(name)) continue
            val current = existing[name]
            if (!replace && current != null && !sameType(current, value)) continue
            when (value) {
                is Boolean -> prefs[booleanPreferencesKey(name)] = value
                is Int -> prefs[intPreferencesKey(name)] = value
                is Long -> prefs[longPreferencesKey(name)] = value
                is Float -> prefs[floatPreferencesKey(name)] = value
                is Double -> prefs[doublePreferencesKey(name)] = value
                is String -> prefs[stringPreferencesKey(name)] = value
                is Set<*> -> prefs[stringSetPreferencesKey(name)] = value.map { it.toString() }.toSet()
            }
        }
    }

    /** Writes [values] into a SharedPreferences editor; [replace] clears the file first. */
    fun applyTo(editor: SharedPreferences.Editor, current: Map<String, *>, values: Map<String, Any>, replace: Boolean) {
        if (replace) editor.clear()
        for ((name, value) in values) {
            val old = current[name]
            if (!replace && old != null && !sameType(old, value)) continue
            when (value) {
                is Boolean -> editor.putBoolean(name, value)
                is Int -> editor.putInt(name, value)
                is Long -> editor.putLong(name, value)
                is Float -> editor.putFloat(name, value)
                is String -> editor.putString(name, value)
                is Set<*> -> editor.putStringSet(name, value.map { it.toString() }.toSet())
            }
        }
    }

    private fun sameType(a: Any, b: Any): Boolean = (a is Set<*> && b is Set<*>) || a::class == b::class
}
