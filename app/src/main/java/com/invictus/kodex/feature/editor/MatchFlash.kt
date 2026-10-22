package com.invictus.kodex.feature.editor

import android.animation.ValueAnimator
import android.graphics.Color
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.util.WeakHashMap

/** A "go to this match" request coming from code search (line is 1-based, column 0-based). */
data class JumpRequest(val path: String, val line: Int, val column: Int = -1, val length: Int = 0)

private const val HOLD_MS = 2500L
private const val FADE_MS = 800L

private val running = WeakHashMap<CodeEditor, ValueAnimator>()

/** True while a search-match flash owns the selection colour (so nobody else should touch it). */
internal fun isFlashing(editor: CodeEditor): Boolean = running.containsKey(editor)

/**
 * Highlights a search match: selects it in a strong accent colour, keeps it for ~2.5s, then fades
 * the highlight out and drops the selection. Touching the editor's selection in the meantime
 * simply leaves the user's own selection alone.
 */
internal fun flashMatch(editor: CodeEditor, line: Int, column: Int, length: Int) {
    runCatching {
        val content = editor.text
        if (line !in 0 until content.lineCount) return
        val lineLen = content.getColumnCount(line)
        val start = column.coerceIn(0, lineLen)
        val end = (start + length).coerceIn(start, lineLen)
        if (end <= start) return
        flashRegion(editor, line, start, line, end)
    }
}

/**
 * Same highlight for a (possibly multi-line) region, e.g. the code a merge-conflict choice just wrote:
 * scrolls it into view, selects it in the accent colour, then fades out. Lines are 0-based.
 */
internal fun flashRegion(editor: CodeEditor, startLine: Int, startCol: Int, endLine: Int, endCol: Int) {
    runCatching {
        running.remove(editor)?.cancel()
        val line = startLine
        val start = startCol
        val end = endCol
        val content = editor.text
        if (startLine !in 0 until content.lineCount || endLine !in startLine until content.lineCount) return

        // Programmatic selection must not pop the cut/copy toolbar over the match.
        suppressSelectionPopup(editor)
        editor.setSelectionRegion(startLine, startCol, endLine, endCol)
        suppressSelectionPopup(editor)

        val scheme = editor.colorScheme
        val original = scheme.getColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND)
        val accent = 0xFFFFB300.toInt() // amber: reads on both dark and light themes
        val peakAlpha = 0x99

        fun apply(alpha: Int) {
            scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent)))
            editor.invalidate()
        }

        apply(peakAlpha)
        val animator = ValueAnimator.ofInt(peakAlpha, 0).apply {
            startDelay = HOLD_MS
            duration = FADE_MS
            addUpdateListener { apply(it.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = finish()
                override fun onAnimationCancel(animation: android.animation.Animator) = finish()

                private fun finish() {
                    running.remove(editor)
                    scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, original)
                    // Only clear the selection if it is still our match (user hasn't selected something else).
                    val cursor = editor.cursor
                    if (cursor.isSelected && cursor.leftLine == startLine && cursor.leftColumn == startCol &&
                        cursor.rightLine == endLine && cursor.rightColumn == endCol
                    ) {
                        editor.setSelection(line, start)
                    }
                    editor.invalidate()
                }
            })
        }
        running[editor] = animator
        animator.start()
    }
}
