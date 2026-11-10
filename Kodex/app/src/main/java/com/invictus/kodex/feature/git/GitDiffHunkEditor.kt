package com.invictus.kodex.feature.git

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * The editable file as the diff preview shows it: only the changed lines plus [CONTEXT_LINES] around them.
 * Everything else is folded into one read-only "N unchanged lines" row. The text is edited in this short form
 * and spliced back into the whole file on every change, so saving still writes the complete file.
 *
 * Edits that would touch a folded row are refused (the field keeps its text), so the splice can never go wrong.
 */
@Composable
internal fun GitDiffHunkEditor(
    text: String,
    onTextChange: (String) -> Unit,
    addedLines: Set<Int>,
    onAddedLinesChange: (Set<Int>) -> Unit,
    /** 0-based lines of the file that sit at a change (added lines, and the neighbours of removed ones). */
    anchors: Set<Int>,
    fontSize: Float,
    modifier: Modifier = Modifier,
) {
    val layout = remember { buildLayout(text, anchors) }
    if (layout == null) {
        // Nothing to fold around (no known change): fall back to the whole file.
        GitDiffEditor(text, onTextChange, addedLines, onAddedLinesChange, fontSize, modifier)
        return
    }
    val counts = remember(layout) { layout.hidden.map { it.size } }
    var window by remember(layout) { mutableStateOf(layout.window) }
    // The whole file as of the last accepted edit, to remap the green tints through each change.
    var full by remember(layout) { mutableStateOf(text) }

    val shownAdded = remember(window, addedLines) {
        val out = HashSet<Int>()
        scan(window, counts) { windowLine, real -> if (real >= 0 && real in addedLines) out.add(windowLine) }
        out as Set<Int>
    }

    GitDiffEditor(
        text = window,
        onTextChange = { new ->
            val newFull = reconstruct(new, layout.hidden) ?: return@GitDiffEditor
            onAddedLinesChange(remapAdded(full, newFull, addedLines))
            full = newFull
            window = new
            onTextChange(newFull)
        },
        addedLines = shownAdded,
        onAddedLinesChange = {},
        fontSize = fontSize,
        modifier = modifier,
        numberTextFor = { numbersFor(it, counts) },
        mutedLines = { mutedFor(it, counts) },
        accept = { reconstruct(it, layout.hidden) != null },
    )
}

private const val CONTEXT_LINES = 3
private const val MARK = "⋯⋯"

private fun isMarker(line: String) = line.startsWith("$MARK ") && line.endsWith(" $MARK")

private class HunkLayout(val window: String, val hidden: List<List<String>>)

/** Null when there is nothing to anchor on. */
private fun buildLayout(text: String, anchors: Set<Int>): HunkLayout? {
    val lines = text.split("\n")
    val keep = BooleanArray(lines.size)
    var any = false
    for (a in anchors) {
        if (a !in lines.indices) continue
        any = true
        for (l in maxOf(0, a - CONTEXT_LINES)..minOf(lines.lastIndex, a + CONTEXT_LINES)) keep[l] = true
    }
    if (!any) return null
    val out = ArrayList<String>()
    val hidden = ArrayList<List<String>>()
    var i = 0
    while (i < lines.size) {
        if (keep[i]) {
            out += lines[i]
            i++
        } else {
            var j = i
            while (j < lines.size && !keep[j]) j++
            val chunk = lines.subList(i, j).toList()
            hidden += chunk
            out += "$MARK ${chunk.size} unchanged lines $MARK"
            i = j
        }
    }
    return HunkLayout(out.joinToString("\n"), hidden)
}

/** Puts the folded lines back in place of their markers; null when a marker was lost, added or changed. */
private fun reconstruct(window: String, hidden: List<List<String>>): String? {
    val out = ArrayList<String>()
    var k = 0
    for (line in window.split("\n")) {
        if (isMarker(line)) {
            if (k >= hidden.size) return null
            out.addAll(hidden[k++])
        } else {
            out.add(line)
        }
    }
    return if (k == hidden.size) out.joinToString("\n") else null
}

/** Walks the shown lines with their 0-based place in the whole file (-1 for a marker row). */
private inline fun scan(window: String, hiddenCounts: List<Int>, onLine: (windowLine: Int, real: Int) -> Unit) {
    var real = 0
    var k = 0
    window.split("\n").forEachIndexed { idx, line ->
        if (isMarker(line) && k < hiddenCounts.size) {
            onLine(idx, -1)
            real += hiddenCounts[k++]
        } else {
            onLine(idx, real)
            real++
        }
    }
}

private fun numbersFor(window: String, counts: List<Int>): String {
    val sb = StringBuilder()
    scan(window, counts) { idx, real ->
        if (idx > 0) sb.append('\n')
        sb.append(if (real < 0) "⋯" else (real + 1).toString())
    }
    return sb.toString()
}

private fun mutedFor(window: String, counts: List<Int>): Set<Int> {
    val out = HashSet<Int>()
    scan(window, counts) { idx, real -> if (real < 0) out.add(idx) }
    return out
}
