package com.invictus.kodex.ai.diff

import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator

enum class LineType { CONTEXT, ADD, REMOVE }

data class DiffLine(val type: LineType, val text: String)

/** oldStart/oldEnd: 0-based half-open line range into the ORIGINAL text. */
data class DiffHunk(
    val oldStart: Int,
    val oldEnd: Int,
    val newStart: Int,
    val lines: List<DiffLine>,
    val included: Boolean = true,
) {
    val changedOnly: Boolean get() = lines.any { it.type != LineType.CONTEXT }
}

object DiffEngine {
    private const val CONTEXT = 3

    /** Line-level unified diff with context, overlapping hunks merged. Pure JGit, no repo needed. */
    fun diff(oldText: String, newText: String): List<DiffHunk> {
        val a = RawText(oldText.toByteArray(Charsets.UTF_8))
        val b = RawText(newText.toByteArray(Charsets.UTF_8))
        val edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
            .diff(RawTextComparator.DEFAULT, a, b)
        if (edits.isEmpty()) return emptyList()

        data class Win(var s: Int, var e: Int)
        val wins = edits.map { e ->
            Win((e.beginA - CONTEXT).coerceAtLeast(0), (e.endA + CONTEXT).coerceAtMost(a.size()))
        }.sortedBy { it.s }
        val merged = mutableListOf<Win>()
        for (w in wins) {
            val last = merged.lastOrNull()
            if (last != null && w.s <= last.e) last.e = maxOf(last.e, w.e) else merged.add(w)
        }

        val hunks = mutableListOf<DiffHunk>()
        for (w in merged) {
            val lines = mutableListOf<DiffLine>()
            var lineA = w.s
            for (e in edits.filter { it.beginA < w.e && it.endA > w.s }) {
                while (lineA < e.beginA) lines += DiffLine(LineType.CONTEXT, a.getString(lineA++))
                while (lineA < e.endA) lines += DiffLine(LineType.REMOVE, a.getString(lineA++))
                for (i in e.beginB until e.endB) lines += DiffLine(LineType.ADD, b.getString(i))
            }
            while (lineA < w.e) lines += DiffLine(LineType.CONTEXT, a.getString(lineA++))
            hunks += DiffHunk(w.s, w.e, w.s, lines)
        }
        return hunks
    }

    /** Renders hunks back to unified-diff text (used to feed ExplainDiffTask, M20). */
    fun toUnifiedText(hunks: List<DiffHunk>): String = buildString {
        hunks.forEach { h ->
            append("@@ -").append(h.oldStart + 1).append(',').append(h.oldEnd - h.oldStart)
                .append(" +").append(h.newStart + 1).append(" @@\n")
            h.lines.forEach { l ->
                append(
                    when (l.type) {
                        LineType.ADD -> "+"
                        LineType.REMOVE -> "-"
                        LineType.CONTEXT -> " "
                    },
                ).append(l.text).append('\n')
            }
        }
    }

    /** Applies hunks marked included; excluded hunks keep their original lines. */
    fun applySelected(oldText: String, hunks: List<DiffHunk>): String {
        val oldLines = oldText.split('\n').let { if (oldText.endsWith("\n")) it.dropLast(1) else it }
        val sb = StringBuilder(oldText.length)
        var cursor = 0
        for (h in hunks.filter { it.changedOnly }.sortedBy { it.oldStart }) {
            while (cursor < h.oldStart.coerceAtMost(oldLines.size)) sb.appendLine(oldLines[cursor++])
            // Braces matter: an unbraced `else` here would bind to the inner `if` and corrupt the output.
            if (h.included) {
                for (l in h.lines) if (l.type != LineType.REMOVE) sb.appendLine(l.text)
            } else {
                for (l in h.lines) if (l.type != LineType.ADD) sb.appendLine(l.text)
            }
            cursor = h.oldEnd.coerceAtMost(oldLines.size)
        }
        while (cursor < oldLines.size) sb.appendLine(oldLines[cursor++])
        val out = sb.toString()
        return if (oldText.endsWith("\n")) out else out.removeSuffix("\n")
    }
}
