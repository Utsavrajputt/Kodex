package com.invictus.kodex.core.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.invictus.kodex.R
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File

/**
 * Plan 3.2 "quick actions menu" -- the "action registry pattern": each action is its own
 * small class implementing [EditorAction], and [EditorActionRegistry] is the single place
 * that lists them for whatever UI surface wants to show them (today: the top bar menu in
 * [com.invictus.kodex.feature.editor.QuickActionsMenu]; a selection popup can read from the
 * same list later without duplicating any action logic).
 *
 * LSP-only actions from the ACSIDE reference (format, organize imports, rename, quick fix)
 * are out of this app's scope per PLAN.md 3.2 and are not modelled here.
 */
interface EditorAction {
    val id: String
    val labelRes: Int

    /** Whether this action makes sense right now (e.g. paste needs clipboard text). */
    fun isAvailable(editor: CodeEditor, filePath: String?, context: Context): Boolean = true

    fun perform(editor: CodeEditor, filePath: String?, context: Context)
}

object EditorActionRegistry {
    /** Bolt menu only: line-level actions. Select all/cut/copy/paste live in the selection popup. */
    val actions: List<EditorAction> = listOf(
        CopyFileAction,
        DuplicateLineAction,
        DeleteLineAction,
        MoveLineUpAction,
        MoveLineDownAction,
        ToggleCommentAction,
    )
}

private data class LineRange(val startLine: Int, val endLine: Int)

/**
 * Whole document as lines. `Content.toString()` is the one text-extraction call this codebase
 * already relies on elsewhere (EditorViewModel's dirty-check snapshot), so actions build on it
 * too instead of a less certain per-line accessor.
 */
private fun linesOf(editor: CodeEditor): List<String> = editor.text.toString().split("\n")

private fun selectionOrCurrentLine(editor: CodeEditor): LineRange {
    val cursor = editor.cursor
    return if (cursor.isSelected) {
        LineRange(cursor.leftLine, cursor.rightLine)
    } else {
        LineRange(cursor.leftLine, cursor.leftLine)
    }
}

private fun selectedText(editor: CodeEditor): String? {
    val cursor = editor.cursor
    if (!cursor.isSelected) return null
    val lines = linesOf(editor)
    val startLine = cursor.leftLine
    val startCol = cursor.leftColumn
    val endLine = cursor.rightLine
    val endCol = cursor.rightColumn
    return if (startLine == endLine) {
        lines[startLine].substring(startCol, endCol)
    } else {
        buildString {
            append(lines[startLine].substring(startCol))
            for (l in (startLine + 1) until endLine) append('\n').append(lines[l])
            append('\n').append(lines[endLine].substring(0, endCol))
        }
    }
}

private fun clipboardOf(context: Context): ClipboardManager? =
    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

object SelectAllAction : EditorAction {
    override val id = "select_all"
    override val labelRes = R.string.qa_select_all

    override fun perform(editor: CodeEditor, filePath: String?, context: Context) {
        try {
            val lines = linesOf(editor)
            val lastLine = (lines.size - 1).coerceAtLeast(0)
            editor.setSelectionRegion(0, 0, lastLine, lines[lastLine].length)
        } catch (_: Exception) {
        }
    }
}

/** Copies the whole open document (not just the selection) to the clipboard. */
object CopyFileAction : EditorAction {
    override val id = "copy_file"
    override val labelRes = R.string.qa_copy_file

    override fun perform(editor: CodeEditor, filePath: String?, context: Context) {
        try {
            clipboardOf(context)?.setPrimaryClip(ClipData.newPlainText("kodex", editor.text.toString()))
            android.widget.Toast.makeText(context, R.string.qa_copy_file_done, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        } catch (_: OutOfMemoryError) {
        }
    }
}

object CutAction : EditorAction {
    override val id = "cut"
    override val labelRes = R.string.qa_cut

    override fun isAvailable(editor: CodeEditor, filePath: String?, context: Context) =
        editor.cursor.isSelected

    override fun perform(editor: CodeEditor, filePath: String?, context: Context) {
        try {
            val text = selectedText(editor) ?: return
            clipboardOf(context)?.setPrimaryClip(ClipData.newPlainText("kodex", text))
            val cursor = editor.cursor
            editor.text.replace(cursor.leftLine, cursor.leftColumn, cursor.rightLine, cursor.rightColumn, "")
        } catch (_: Exception) {
        }
    }
}

object CopyAction : EditorAction {
    override val id = "copy"
    override val labelRes = R.string.qa_copy

    override fun isAvailable(editor: CodeEditor, filePath: String?, context: Context) =
        editor.cursor.isSelected

    override fun perform(editor: CodeEditor, filePath: String?, context: Context) {
        try {
            val text = selectedText(editor) ?: return
            clipboardOf(context)?.setPrimaryClip(ClipData.newPlainText("kodex", text))
        } catch (_: Exception) {
        }
    }
}

object PasteAction : EditorAction {
    override val id = "paste"
    override val labelRes = R.string.qa_paste

    override fun isAvailable(editor: CodeEditor, filePath: String?, context: Context): Boolean {
        val clip = clipboardOf(context)?.primaryClip ?: return false
        return clip.itemCount > 0
    }

    override fun perform(editor: CodeEditor, filePath: String?, context: Context) {
        try {
            val clip = clipboardOf(context)?.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
            val cursor = editor.cursor
            if (cursor.isSelected) {
                editor.text.replace(cursor.leftLine, cursor.leftColumn, cursor.rightLine, cursor.rightColumn, text)
            } else {
                editor.text.insert(cursor.leftLine, cursor.leftColumn, text)
            }
        } catch (_: Exception) {
        }
    }
}

object DuplicateLineAction : EditorAction {
    override val id = "duplicate_line"
    override val labelRes = R.string.qa_duplicate_line

    override fun perform(editor: CodeEditor, filePath: String?, context: Context) {
        try {
            val range = selectionOrCurrentLine(editor)
            val lines = linesOf(editor)
            val block = (range.startLine..range.endLine).joinToString("\n") { lines[it] }
            val insertCol = editor.text.getColumnCount(range.endLine)
            editor.text.insert(range.endLine, insertCol, "\n$block")
        } catch (_: Exception) {
        }
    }
}

object CopyLineUpAction : EditorAction {
    override val id = "copy_line_up"
    override val labelRes = R.string.shortcut_copy_line_up

    override fun perform(editor: CodeEditor, filePath: String?, context: Context) {
        try {
            val range = selectionOrCurrentLine(editor)
            val col = editor.cursor.leftColumn
            val lines = linesOf(editor)
            val block = (range.startLine..range.endLine).joinToString("\n") { lines[it] }
            editor.text.insert(range.startLine, 0, "$block\n")
            editor.setSelection(range.startLine, col.coerceAtMost(lines[range.startLine].length), false)
        } catch (_: Exception) {
        }
    }
}

object InsertLineBelowAction : EditorAction {
    override val id = "insert_line_below"
    override val labelRes = R.string.shortcut_insert_line_below

    override fun perform(editor: CodeEditor, filePath: String?, context: Context) {
        try {
            val line = editor.cursor.rightLine
            val endCol = editor.text.getColumnCount(line)
            editor.text.insert(line, endCol, "\n")
            editor.setSelection(line + 1, 0, false)
        } catch (_: Exception) {
        }
    }
}

object InsertLineAboveAction : EditorAction {
    override val id = "insert_line_above"
    override val labelRes = R.string.shortcut_insert_line_above

    override fun perform(editor: CodeEditor, filePath: String?, context: Context) {
        try {
            val line = editor.cursor.leftLine
            editor.text.insert(line, 0, "\n")
            editor.setSelection(line, 0, false)
        } catch (_: Exception) {
        }
    }
}

object IndentLineAction : EditorAction {
    override val id = "indent_line"
    override val labelRes = R.string.shortcut_indent_line

    override fun perform(editor: CodeEditor, filePath: String?, context: Context) {
        try {
            val range = selectionOrCurrentLine(editor)
            for (line in range.startLine..range.endLine) {
                editor.text.insert(line, 0, "    ")
            }
        } catch (_: Exception) {
        }
    }
}

object OutdentLineAction : EditorAction {
    override val id = "outdent_line"
    override val labelRes = R.string.shortcut_outdent_line

    override fun perform(editor: CodeEditor, filePath: String?, context: Context) {
        try {
            val range = selectionOrCurrentLine(editor)
            val lines = linesOf(editor)
            for (line in range.startLine..range.endLine) {
                val text = lines.getOrNull(line) ?: continue
                val removeCount = when {
                    text.startsWith("\t") -> 1
                    else -> text.take(4).takeWhile { it == ' ' }.length
                }
                if (removeCount > 0) {
                    editor.text.replace(line, 0, line, removeCount, "")
                }
            }
        } catch (_: Exception) {
        }
    }
}

object SelectLineAction : EditorAction {
    override val id = "select_line"
    override val labelRes = R.string.shortcut_select_line

    override fun perform(editor: CodeEditor, filePath: String?, context: Context) {
        try {
            val lines = linesOf(editor)
            val cursor = editor.cursor
            val startLine = cursor.leftLine
            val endLine = if (cursor.isSelected && cursor.rightLine + 1 < lines.size) {
                cursor.rightLine + 1
            } else {
                cursor.rightLine
            }
            if (endLine + 1 < lines.size) {
                editor.setSelectionRegion(startLine, 0, endLine + 1, 0)
            } else {
                editor.setSelectionRegion(startLine, 0, endLine, lines[endLine].length)
            }
        } catch (_: Exception) {
        }
    }
}

object DeleteLineAction : EditorAction {
    override val id = "delete_line"
    override val labelRes = R.string.qa_delete_line

    override fun perform(editor: CodeEditor, filePath: String?, context: Context) {
        try {
            val range = selectionOrCurrentLine(editor)
            val lines = linesOf(editor)
            val lastIndex = lines.size - 1
            when {
                range.endLine < lastIndex -> {
                    // Lines below shift up to take startLine's place; also eat the newline
                    // right after the deleted block.
                    editor.text.replace(range.startLine, 0, range.endLine + 1, 0, "")
                }
                range.startLine > 0 -> {
                    // Deleting the tail of the document: eat the newline *before* the block
                    // instead, since there's nothing below to pull up into its place.
                    val prevCol = editor.text.getColumnCount(range.startLine - 1)
                    val endCol = editor.text.getColumnCount(range.endLine)
                    editor.text.replace(range.startLine - 1, prevCol, range.endLine, endCol, "")
                }
                else -> {
                    // The only line in the document: a line-based buffer always has at least
                    // one line, so clear its content instead of removing it outright.
                    editor.text.replace(0, 0, 0, editor.text.getColumnCount(0), "")
                }
            }
        } catch (_: Exception) {
        }
    }
}

object MoveLineUpAction : EditorAction {
    override val id = "move_line_up"
    override val labelRes = R.string.qa_move_line_up

    override fun isAvailable(editor: CodeEditor, filePath: String?, context: Context) =
        editor.cursor.leftLine > 0

    override fun perform(editor: CodeEditor, filePath: String?, context: Context) {
        try {
            val line = editor.cursor.leftLine
            if (line <= 0) return
            val column = editor.cursor.leftColumn
            val lines = linesOf(editor)
            val above = lines[line - 1]
            val current = lines[line]
            val endCol = editor.text.getColumnCount(line)
            editor.text.replace(line - 1, 0, line, endCol, "$current\n$above")
            editor.setSelection(line - 1, column.coerceAtMost(current.length), false)
        } catch (_: Exception) {
        }
    }
}

object MoveLineDownAction : EditorAction {
    override val id = "move_line_down"
    override val labelRes = R.string.qa_move_line_down

    override fun isAvailable(editor: CodeEditor, filePath: String?, context: Context): Boolean {
        val lines = linesOf(editor)
        return editor.cursor.leftLine < lines.size - 1
    }

    override fun perform(editor: CodeEditor, filePath: String?, context: Context) {
        try {
            val line = editor.cursor.leftLine
            val column = editor.cursor.leftColumn
            val lines = linesOf(editor)
            if (line >= lines.size - 1) return
            val below = lines[line + 1]
            val current = lines[line]
            val endCol = editor.text.getColumnCount(line + 1)
            editor.text.replace(line, 0, line + 1, endCol, "$below\n$current")
            editor.setSelection(line + 1, column.coerceAtMost(current.length), false)
        } catch (_: Exception) {
        }
    }
}

object ToggleCommentAction : EditorAction {
    override val id = "toggle_comment"
    override val labelRes = R.string.qa_toggle_comment

    override fun isAvailable(editor: CodeEditor, filePath: String?, context: Context): Boolean =
        filePath?.let { CommentSyntax.styleFor(File(it)) } != null

    override fun perform(editor: CodeEditor, filePath: String?, context: Context) {
        try {
            val style = filePath?.let { CommentSyntax.styleFor(File(it)) } ?: return
            val range = selectionOrCurrentLine(editor)
            val lines = linesOf(editor)
            val block = (range.startLine..range.endLine).map { lines[it] }
            val edited = when (style) {
                is CommentStyle.Line -> toggleLine(block, style.prefix)
                is CommentStyle.Block -> toggleBlock(block, style.open, style.close)
            }
            val endCol = editor.text.getColumnCount(range.endLine)
            editor.text.replace(range.startLine, 0, range.endLine, endCol, edited.joinToString("\n"))
        } catch (_: Exception) {
        }
    }

    private fun toggleLine(block: List<String>, prefix: String): List<String> {
        val nonBlank = block.filter { it.isNotBlank() }
        val trimmedPrefix = prefix.trimEnd()
        val allCommented = nonBlank.isNotEmpty() && nonBlank.all { it.trimStart().startsWith(trimmedPrefix) }
        return block.map { rawLine ->
            if (rawLine.isBlank()) return@map rawLine
            val indent = rawLine.takeWhile { it == ' ' || it == '\t' }
            val rest = rawLine.substring(indent.length)
            when {
                !allCommented -> indent + prefix + rest
                rest.startsWith(prefix) -> indent + rest.removePrefix(prefix)
                else -> indent + rest.removePrefix(trimmedPrefix)
            }
        }
    }

    /** Wraps each line in its own block comment; lines already wrapped are left alone when commenting. */
    private fun toggleBlock(block: List<String>, open: String, close: String): List<String> {
        fun isWrapped(line: String): Boolean {
            val t = line.trim()
            return t.length >= open.length + close.length && t.startsWith(open) && t.endsWith(close)
        }
        val nonBlank = block.filter { it.isNotBlank() }
        val allCommented = nonBlank.isNotEmpty() && nonBlank.all(::isWrapped)
        return block.map { rawLine ->
            if (rawLine.isBlank()) return@map rawLine
            val indent = rawLine.takeWhile { it == ' ' || it == '\t' }
            val rest = rawLine.substring(indent.length).trimEnd()
            when {
                allCommented -> indent + rest.removePrefix(open).removeSuffix(close).removePrefix(" ").removeSuffix(" ")
                isWrapped(rest) -> rawLine
                else -> "$indent$open $rest $close"
            }
        }
    }
}
