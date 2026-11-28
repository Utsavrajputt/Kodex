package com.invictus.kodex.core.editor

import android.view.KeyEvent as AndroidKeyEvent
import androidx.annotation.StringRes
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import com.invictus.kodex.R

/**
 * Hardware-keyboard key combination stored as an Android [AndroidKeyEvent] keyCode + modifier flags.
 */
data class KeyCombo(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    fun matches(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || event.key.nativeKeyCode != keyCode) return false
        if (event.isShiftPressed != shift || event.isAltPressed != alt) return false
        return if (ctrl && !meta) {
            event.isCtrlPressed || event.isMetaPressed
        } else {
            event.isCtrlPressed == ctrl && event.isMetaPressed == meta
        }
    }

    fun displayLabel(): String {
        val parts = ArrayList<String>(5)
        when {
            ctrl && meta -> {
                parts += "Ctrl"
                parts += "⌘"
            }
            ctrl -> parts += "Ctrl / ⌘"
            meta -> parts += "⌘"
        }
        if (alt) parts += "Alt"
        if (shift) parts += "Shift"
        parts += keyLabel(keyCode)
        return parts.joinToString(" + ")
    }

    fun encode(): String =
        "${if (ctrl) 1 else 0},${if (shift) 1 else 0},${if (alt) 1 else 0},${if (meta) 1 else 0}:$keyCode"

    companion object {
        fun decode(raw: String): KeyCombo? {
            val parts = raw.split(':')
            if (parts.size != 2) return null
            val mods = parts[0].split(',')
            if (mods.size !in 3..4) return null
            val code = parts[1].toIntOrNull() ?: return null
            return KeyCombo(
                keyCode = code,
                ctrl = mods[0] == "1",
                shift = mods[1] == "1",
                alt = mods[2] == "1",
                meta = mods.getOrNull(3) == "1",
            )
        }

        fun fromKeyEvent(event: KeyEvent): KeyCombo? {
            if (event.type != KeyEventType.KeyDown) return null
            val code = event.key.nativeKeyCode
            if (code == AndroidKeyEvent.KEYCODE_UNKNOWN || code in MODIFIER_KEYCODES) return null
            return KeyCombo(
                keyCode = code,
                ctrl = event.isCtrlPressed,
                shift = event.isShiftPressed,
                alt = event.isAltPressed,
                meta = event.isMetaPressed,
            )
        }

        private val MODIFIER_KEYCODES = setOf(
            AndroidKeyEvent.KEYCODE_CTRL_LEFT,
            AndroidKeyEvent.KEYCODE_CTRL_RIGHT,
            AndroidKeyEvent.KEYCODE_SHIFT_LEFT,
            AndroidKeyEvent.KEYCODE_SHIFT_RIGHT,
            AndroidKeyEvent.KEYCODE_ALT_LEFT,
            AndroidKeyEvent.KEYCODE_ALT_RIGHT,
            AndroidKeyEvent.KEYCODE_META_LEFT,
            AndroidKeyEvent.KEYCODE_META_RIGHT,
            AndroidKeyEvent.KEYCODE_CAPS_LOCK,
            AndroidKeyEvent.KEYCODE_NUM_LOCK,
            AndroidKeyEvent.KEYCODE_SCROLL_LOCK,
        )

        private fun keyLabel(code: Int): String = when (code) {
            AndroidKeyEvent.KEYCODE_GRAVE -> "`"
            AndroidKeyEvent.KEYCODE_COMMA -> ","
            AndroidKeyEvent.KEYCODE_PERIOD -> "."
            AndroidKeyEvent.KEYCODE_SLASH -> "/"
            AndroidKeyEvent.KEYCODE_BACKSLASH -> "\\"
            AndroidKeyEvent.KEYCODE_SEMICOLON -> ";"
            AndroidKeyEvent.KEYCODE_APOSTROPHE -> "'"
            AndroidKeyEvent.KEYCODE_LEFT_BRACKET -> "["
            AndroidKeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
            AndroidKeyEvent.KEYCODE_MINUS -> "-"
            AndroidKeyEvent.KEYCODE_EQUALS -> "="
            AndroidKeyEvent.KEYCODE_TAB -> "Tab"
            AndroidKeyEvent.KEYCODE_SPACE -> "Space"
            AndroidKeyEvent.KEYCODE_ENTER -> "Enter"
            AndroidKeyEvent.KEYCODE_DEL -> "Backspace"
            AndroidKeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
            AndroidKeyEvent.KEYCODE_ESCAPE -> "Esc"
            AndroidKeyEvent.KEYCODE_DPAD_UP -> "↑"
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> "↓"
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> "←"
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> "→"
            else -> AndroidKeyEvent.keyCodeToString(code)
                .removePrefix("KEYCODE_")
                .replace('_', ' ')
                .lowercase()
                .replaceFirstChar { it.uppercase() }
        }
    }
}

/** Editor and workspace actions that can be triggered from an external keyboard and rebound in Settings. */
enum class KeyAction(
    val id: String,
    @StringRes val labelRes: Int,
    val defaultBinding: KeyCombo,
) {
    SAVE("save", R.string.shortcut_save, KeyCombo(AndroidKeyEvent.KEYCODE_S, ctrl = true)),
    SAVE_ALL("save_all", R.string.shortcut_save_all, KeyCombo(AndroidKeyEvent.KEYCODE_S, ctrl = true, shift = true)),
    OPEN_FILE("open_file", R.string.shortcut_open_file, KeyCombo(AndroidKeyEvent.KEYCODE_O, ctrl = true)),
    OPEN_FOLDER("open_folder", R.string.shortcut_open_folder, KeyCombo(AndroidKeyEvent.KEYCODE_O, ctrl = true, shift = true)),
    FIND("find", R.string.shortcut_find, KeyCombo(AndroidKeyEvent.KEYCODE_F, ctrl = true)),
    REPLACE("replace", R.string.shortcut_replace, KeyCombo(AndroidKeyEvent.KEYCODE_H, ctrl = true)),
    SEARCH_PROJECT("search_project", R.string.shortcut_search_project, KeyCombo(AndroidKeyEvent.KEYCODE_F, ctrl = true, shift = true)),
    CLOSE_TAB("close_tab", R.string.shortcut_close_tab, KeyCombo(AndroidKeyEvent.KEYCODE_W, ctrl = true)),
    NEXT_TAB("next_tab", R.string.shortcut_next_tab, KeyCombo(AndroidKeyEvent.KEYCODE_TAB, ctrl = true)),
    PREV_TAB("prev_tab", R.string.shortcut_prev_tab, KeyCombo(AndroidKeyEvent.KEYCODE_TAB, ctrl = true, shift = true)),
    TOGGLE_SIDEBAR("toggle_sidebar", R.string.shortcut_toggle_sidebar, KeyCombo(AndroidKeyEvent.KEYCODE_B, ctrl = true)),
    TOGGLE_TERMINAL("toggle_terminal", R.string.shortcut_toggle_terminal, KeyCombo(AndroidKeyEvent.KEYCODE_GRAVE, ctrl = true)),
    TOGGLE_PREVIEW("toggle_preview", R.string.shortcut_toggle_preview, KeyCombo(AndroidKeyEvent.KEYCODE_V, ctrl = true, shift = true)),
    MOVE_LINE_UP("move_line_up", R.string.shortcut_move_line_up, KeyCombo(AndroidKeyEvent.KEYCODE_DPAD_UP, alt = true)),
    MOVE_LINE_DOWN("move_line_down", R.string.shortcut_move_line_down, KeyCombo(AndroidKeyEvent.KEYCODE_DPAD_DOWN, alt = true)),
    COPY_LINE_UP("copy_line_up", R.string.shortcut_copy_line_up, KeyCombo(AndroidKeyEvent.KEYCODE_DPAD_UP, shift = true, alt = true)),
    COPY_LINE_DOWN("copy_line_down", R.string.shortcut_copy_line_down, KeyCombo(AndroidKeyEvent.KEYCODE_DPAD_DOWN, shift = true, alt = true)),
    DELETE_LINE("delete_line", R.string.shortcut_delete_line, KeyCombo(AndroidKeyEvent.KEYCODE_K, ctrl = true, shift = true)),
    TOGGLE_COMMENT("toggle_comment", R.string.shortcut_toggle_comment, KeyCombo(AndroidKeyEvent.KEYCODE_SLASH, ctrl = true)),
    INSERT_LINE_BELOW("insert_line_below", R.string.shortcut_insert_line_below, KeyCombo(AndroidKeyEvent.KEYCODE_ENTER, ctrl = true)),
    INSERT_LINE_ABOVE("insert_line_above", R.string.shortcut_insert_line_above, KeyCombo(AndroidKeyEvent.KEYCODE_ENTER, ctrl = true, shift = true)),
    INDENT_LINE("indent_line", R.string.shortcut_indent_line, KeyCombo(AndroidKeyEvent.KEYCODE_RIGHT_BRACKET, ctrl = true)),
    OUTDENT_LINE("outdent_line", R.string.shortcut_outdent_line, KeyCombo(AndroidKeyEvent.KEYCODE_LEFT_BRACKET, ctrl = true)),
    SELECT_LINE("select_line", R.string.shortcut_select_line, KeyCombo(AndroidKeyEvent.KEYCODE_L, ctrl = true)),
    ZOOM_IN("zoom_in", R.string.shortcut_zoom_in, KeyCombo(AndroidKeyEvent.KEYCODE_EQUALS, ctrl = true)),
    ZOOM_OUT("zoom_out", R.string.shortcut_zoom_out, KeyCombo(AndroidKeyEvent.KEYCODE_MINUS, ctrl = true)),
    ZOOM_RESET("zoom_reset", R.string.shortcut_zoom_reset, KeyCombo(AndroidKeyEvent.KEYCODE_0, ctrl = true)),
    UNDO("undo", R.string.shortcut_undo, KeyCombo(AndroidKeyEvent.KEYCODE_Z, ctrl = true)),
    REDO("redo", R.string.shortcut_redo, KeyCombo(AndroidKeyEvent.KEYCODE_Z, ctrl = true, shift = true)),
    OPEN_SETTINGS("open_settings", R.string.shortcut_open_settings, KeyCombo(AndroidKeyEvent.KEYCODE_COMMA, ctrl = true)),
}

object Keybindings {
    val defaults: Map<KeyAction, KeyCombo> = KeyAction.entries.associateWith { it.defaultBinding }

    fun match(bindings: Map<KeyAction, KeyCombo>, event: KeyEvent): KeyAction? =
        KeyAction.entries.firstOrNull { action ->
            (bindings[action] ?: action.defaultBinding).matches(event)
        }

    fun encode(bindings: Map<KeyAction, KeyCombo>): String =
        bindings.entries.joinToString("\n") { (action, combo) -> "${action.id}=${combo.encode()}" }

    fun decode(raw: String): Map<KeyAction, KeyCombo> {
        val result = defaults.toMutableMap()
        if (raw.isBlank()) return result
        val byId = KeyAction.entries.associateBy { it.id }
        for (line in raw.split('\n')) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val action = byId[line.substring(0, idx)] ?: continue
            val combo = KeyCombo.decode(line.substring(idx + 1)) ?: continue
            result[action] = combo
        }
        return result
    }
}
