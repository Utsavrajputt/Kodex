package com.invictus.kodex.ai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Global on/off switch for every AI feature (Settings > Behavior > Enable AI).
 * Compose state, so every screen that reads [AiGate] recomposes the moment it flips.
 * Backed by SharedPreferences (synchronous) so the right value is known before the first frame.
 */
object AiSwitch {
    private const val PREFS = "ai_switch"
    private const val KEY = "enabled"

    var enabled: Boolean by mutableStateOf(true)
        private set

    fun init(context: Context) {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        enabled = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, value).apply()
    }
}
