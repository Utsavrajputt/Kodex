package com.invictus.kodex.feature.editor

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.invictus.kodex.core.editor.EditorThemes
import com.invictus.kodex.core.editor.TabBuffer
import com.invictus.kodex.core.editor.TextMateSupport
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import kotlinx.coroutines.flow.SharedFlow

/** Lets the top bar reach the live editor for undo/redo without owning the view. */
class EditorHandle {
    var editor: CodeEditor? = null
}

/**
 * Reports user-driven vertical scroll deltas from the embedded [CodeEditor] so the app bar can
 * collapse on scroll-down and reappear on scroll-up, the same feel as a Compose
 * `TopAppBarScrollBehavior` -- but CodeEditor is a plain View with no nested-scroll connection,
 * so [EditorScreen] listens to this instead of attaching one.
 */
fun interface OnEditorScroll {
    /** Positive when the user scrolled down (content moved up), negative when scrolling up. */
    fun onScroll(deltaY: Int)
}

/** Also read by the settings screen to show where the font-size slider starts. */
internal const val DEFAULT_TEXT_SIZE_SP = 14f

/** Pinch-zoom bounds in px; Sora's built-in cap stopped pinch working past ~35px. */
internal const val MIN_PINCH_TEXT_SIZE_PX = 20f
internal const val MAX_PINCH_TEXT_SIZE_PX = 64f

/**
 * Sora's [CodeEditor] inside Compose. One view per tab: callers wrap this in `key(path)` so a
 * tab switch builds a fresh view around that tab's own [TabBuffer.content] (which carries the
 * undo stack) and puts the cursor and zoom back.
 */
@Composable
fun CodeEditorView(
    buffer: TabBuffer,
    darkTheme: Boolean,
    textMate: TextMateSupport,
    highlightReady: Boolean,
    themeId: String = EditorThemes.DEFAULT_ID,
    autocompleteEnabled: Boolean = true,
    handle: EditorHandle,
    onEdited: () -> Unit,
    onViewState: (line: Int, column: Int, textSizePx: Float, scrollX: Int, scrollY: Int) -> Unit,
    onScroll: OnEditorScroll = OnEditorScroll {},
    /** Fired (posted, after Sora updated its undo stack) on any content change incl. undo/redo/new text. */
    onHistoryChanged: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** M11: code-search jump requests (path to 1-based line) for this tab's view. */
    path: String = "",
    jumpToLine: SharedFlow<JumpRequest>? = null,
    /** Non-null when AI is on: adds "Explain" to the selection popup, called with the selected code. */
    onExplainSelection: ((String) -> Unit)? = null,
) {
    val currentOnExplain by rememberUpdatedState(onExplainSelection)
    val currentOnEdited by rememberUpdatedState(onEdited)
    val currentOnJump by rememberUpdatedState(jumpToLine)
    val currentOnViewState by rememberUpdatedState(onViewState)
    val currentOnScroll by rememberUpdatedState(onScroll)
    val currentOnHistory by rememberUpdatedState(onHistoryChanged)

    // Live editor reference so the jump collector (below) can drive the cursor.
    val editorHolder = remember { arrayOfNulls<CodeEditor>(1) }

    LaunchedEffect(path) {
        val flow = currentOnJump ?: return@LaunchedEffect
        flow.collect { request ->
            if (request.path != path) return@collect
            editorHolder[0]?.post {
                editorHolder[0]?.let { editor ->
                    val lc = (request.line - 1).coerceIn(0, buffer.content.lineCount - 1)
                    editor.setSelection(lc, maxOf(request.column, 0), true)
                    if (request.column >= 0 && request.length > 0) flashMatch(editor, lc, request.column, request.length)
                }
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            CodeEditor(context).apply {
                typefaceText = Typeface.MONOSPACE
                setTextSize(DEFAULT_TEXT_SIZE_SP)
                setScaleTextSizes(MIN_PINCH_TEXT_SIZE_PX, MAX_PINCH_TEXT_SIZE_PX)
                if (buffer.textSizePx > 0f) textSizePx = buffer.textSizePx
                applyLook(this, buffer, textMate, darkTheme, highlightReady, themeId)
                getComponent(EditorAutoCompletion::class.java).isEnabled = autocompleteEnabled
                installSelectionPopup(this) { currentOnExplain }
                // TextMate schemes listen to the global ThemeRegistry and reset their colours whenever
                // any editor switches theme, which silently wiped our selection colour. Re-assert it.
                val selfEditor = this
                subscribeEvent(SelectionChangeEvent::class.java) { _, _ -> enforceSelectionColor(selfEditor) }

                // Same Content object as last time, so undo/redo history comes along.
                setText(buffer.content)

                subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                    // Loading text into the view is not an edit.
                    if (event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) currentOnEdited()
                    // Deferred so canUndo()/canRedo() are read after the undo stack has been updated.
                    post { currentOnHistory() }
                }

                subscribeEvent(ScrollEvent::class.java) { event, _ ->
                    // Only user drags/flings drive the app bar -- programmatic scrolls (tab
                    // open, cursor-follow, find-navigation, pinch-zoom) must not move it.
                    if (event.cause == ScrollEvent.CAUSE_USER_DRAG || event.cause == ScrollEvent.CAUSE_USER_FLING) {
                        currentOnScroll.onScroll(event.endY - event.startY)
                    }
                }

                val content = buffer.content
                val line = buffer.cursorLine.coerceIn(0, content.lineCount - 1)
                val column = buffer.cursorColumn.coerceIn(0, content.getColumnCount(line))
                // Cursor first (without centering the view -- restoreScroll below places the
                // viewport), then the exact scroll offset the user left the tab at.
                if (line != 0 || column != 0) post { setSelection(line, column, false) }
                restoreScroll(this, buffer.scrollX, buffer.scrollY)
                if (buffer.pendingFlashLength > 0) {
                    val col = buffer.pendingFlashColumn
                    val len = buffer.pendingFlashLength
                    buffer.pendingFlashColumn = -1
                    buffer.pendingFlashLength = 0
                    // After the restore posts above have run, so the match is scrolled into view first.
                    post { post { flashMatch(this, line, col, len) } }
                }

                handle.editor = this
                editorHolder[0] = this
                post { currentOnHistory() }
            }
        },
        update = { editor ->
            if (editor.tag != Look(darkTheme, highlightReady, themeId)) {
                applyLook(editor, buffer, textMate, darkTheme, highlightReady, themeId)
            }
            enforceSelectionColor(editor)
            editor.getComponent(EditorAutoCompletion::class.java).isEnabled = autocompleteEnabled
            val wordsVersion = textMate.userWords.version
            if (appliedWordsVersion[editor] != wordsVersion) {
                appliedWordsVersion[editor] = wordsVersion
                textMate.refreshCompletionWords(editor, buffer.file)
            }
        },
        onRelease = { editor ->
            val cursor = editor.cursor
            val (scrollX, scrollY) = captureScroll(editor)
            currentOnViewState(cursor.leftLine, cursor.leftColumn, editor.textSizePx, scrollX, scrollY)
            if (handle.editor === editor) handle.editor = null
            if (editorHolder[0] === editor) editorHolder[0] = null
            // Detach from the shared Content before releasing so the old view stops listening.
            editor.setText("")
            editor.release()
        },
    )
}

/**
 * Sora's virtual scroll position isn't part of its public-stable surface, so both sides of
 * this are defensive: a failure here must never take the editor down with it, it just means
 * the tab opens scrolled to the cursor instead of exactly where it was left.
 */
private fun captureScroll(editor: CodeEditor): Pair<Int, Int> = try {
    editor.scroller.currX to editor.scroller.currY
} catch (_: Throwable) {
    0 to 0
}

private fun restoreScroll(editor: CodeEditor, x: Int, y: Int) {
    if (x == 0 && y == 0) return
    editor.post {
        try {
            editor.scroller.startScroll(0, 0, x, y, 0)
            editor.invalidate()
        } catch (_: Throwable) {
            // Best effort -- cursor placement above still gets the view roughly on-screen.
        }
    }
}

/** What an editor view is currently dressed in; kept in its `tag` to skip redundant work. */
/** Which learned-word snapshot each live editor last received (avoids reapplying every recompose). */
private val appliedWordsVersion = java.util.WeakHashMap<CodeEditor, Int>()

private data class Look(val dark: Boolean, val highlighted: Boolean, val themeId: String)

private fun applyLook(
    editor: CodeEditor,
    buffer: TabBuffer,
    textMate: TextMateSupport,
    dark: Boolean,
    highlightReady: Boolean,
    themeId: String,
) {
    val highlighted = highlightReady && textMate.applyTo(editor, buffer.file, dark, themeId)
    // Plain-text fallback (grammars still loading, or TextMate failed): built-in schemes.
    if (!highlighted) editor.colorScheme = if (dark) SchemeDarcula() else EditorColorScheme()
    editor.tag = Look(dark, highlightReady, themeId)
    val isDark = EditorThemes.resolve(themeId, dark)?.isDark ?: dark
    selectionColors[editor] = if (isDark) SELECTION_DARK else SELECTION_LIGHT
    enforceSelectionColor(editor)
}

/** Opaque, clearly visible selection fill per editor (TextMate themes often ship near-invisible ones). */
private const val SELECTION_DARK = 0xFF3F6FCF.toInt()
private const val SELECTION_LIGHT = 0xFF9CC3FF.toInt()
private val selectionColors = java.util.WeakHashMap<CodeEditor, Int>()

private fun enforceSelectionColor(editor: CodeEditor) {
    if (isFlashing(editor)) return
    val wanted = selectionColors[editor] ?: return
    val scheme = editor.colorScheme
    if (scheme.getColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND) != wanted) {
        scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, wanted)
        editor.invalidate()
    }
}
