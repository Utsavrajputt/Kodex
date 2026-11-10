package com.invictus.kodex.feature.git

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Editable view of the working-tree file with the same GitHub-style green line tint the diff uses.
 * [addedLines] are 0-based line indexes that differ from HEAD. As the user types, the set is
 * remapped (prefix/suffix of the edit) so tints follow their lines and edited lines get tinted too.
 */
@Composable
internal fun GitDiffEditor(
    text: String,
    onTextChange: (String) -> Unit,
    addedLines: Set<Int>,
    onAddedLinesChange: (Set<Int>) -> Unit,
    fontSize: Float,
    modifier: Modifier = Modifier,
    /** Gutter text for the shown text; null = 1..n. The hunk view numbers lines by their place in the file. */
    numberTextFor: ((String) -> String)? = null,
    /** 0-based lines drawn as muted rows (the "hidden lines" markers of the hunk view). */
    mutedLines: ((String) -> Set<Int>)? = null,
    /** Returns false to refuse an edit (it is then dropped, the field keeps its text). */
    accept: ((String) -> Boolean)? = null,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val green = Color(0xFF2EA043)
    val lineTint = green.copy(alpha = if (dark) 0.15f else 0.18f)
    val gutterTint = green.copy(alpha = if (dark) 0.30f else 0.30f)
    val numberColor = if (dark) Color(0xFF8B949E) else Color(0xFF656D76)

    val style: TextStyle = remember(fontSize) {
        diffCodeStyle(fontSize).copy(
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
        )
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    var value by remember { mutableStateOf(TextFieldValue(text)) }
    // File reloaded from outside (e.g. after save) -> take it as the new baseline.
    if (value.text != text) value = value.copy(text = text)

    val currentAdded by rememberUpdatedState(addedLines)
    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val lineHeightPx = with(density) { style.lineHeight.toPx() }
    val verticalPad = 6.dp
    val verticalPadPx = with(density) { verticalPad.toPx() }
    val gutterPx = with(density) { 60.dp.toPx() }
    val numberText = remember(value.text, numberTextFor) {
        numberTextFor?.invoke(value.text) ?: (1..lineCount).joinToString("\n")
    }
    val muted = remember(value.text, mutedLines) { mutedLines?.invoke(value.text).orEmpty() }
    val mutedTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val v = rememberScrollState()
    val h = rememberScrollState()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val minWidth = maxWidth
        Box(Modifier.fillMaxSize().verticalScroll(v)) {
            Box(Modifier.horizontalScroll(h)) {
                Row(
                    Modifier
                        .widthIn(min = minWidth)
                        .drawBehind {
                            muted.forEach { line ->
                                if (line in 0 until lineCount) {
                                    drawRect(mutedTint, Offset(0f, verticalPadPx + line * lineHeightPx), Size(size.width, lineHeightPx))
                                }
                            }
                            // Full-width line tints, exactly one fixed-height row per line.
                            currentAdded.forEach { line ->
                                if (line in 0 until lineCount) {
                                    val top = verticalPadPx + line * lineHeightPx
                                    drawRect(lineTint, Offset(0f, top), Size(size.width, lineHeightPx))
                                    drawRect(gutterTint, Offset(0f, top), Size(gutterPx, lineHeightPx))
                                }
                            }
                        },
                ) {
                    Text(
                        text = numberText,
                        style = style.copy(color = numberColor),
                        textAlign = TextAlign.End,
                        softWrap = false,
                        modifier = Modifier
                            .widthIn(min = 44.dp)
                            .padding(start = 6.dp, end = 10.dp, top = verticalPad, bottom = verticalPad),
                    )
                    BasicTextField(
                        value = value,
                        onValueChange = { new ->
                            if (new.text != value.text && accept?.invoke(new.text) == false) return@BasicTextField
                            if (new.text != value.text) {
                                onAddedLinesChange(remapAdded(value.text, new.text, currentAdded))
                                onTextChange(new.text)
                            }
                            value = new
                        },
                        textStyle = style.copy(color = onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.padding(end = 24.dp, top = verticalPad, bottom = verticalPad),
                    )
                }
            }
        }
    }
}

/**
 * Moves tinted line indexes through one edit. Unchanged prefix lines keep their index, suffix
 * lines shift by the line delta, lines inside the edited span become tinted. Deleting whole
 * lines tints nothing.
 */
internal fun remapAdded(old: String, new: String, added: Set<Int>): Set<Int> {
    if (old == new) return added
    var prefix = 0
    val maxPrefix = minOf(old.length, new.length)
    while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    val maxSuffix = maxPrefix - prefix
    while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++

    val oldMid = old.substring(prefix, old.length - suffix)
    val newMid = new.substring(prefix, new.length - suffix)
    val startLine = old.substring(0, prefix).count { it == '\n' }
    val oldEnd = startLine + oldMid.count { it == '\n' }
    val newEnd = startLine + newMid.count { it == '\n' }
    val delta = newEnd - oldEnd

    val out = HashSet<Int>()
    added.forEach { line ->
        when {
            line < startLine -> out.add(line)
            line > oldEnd -> out.add(line + delta)
        }
    }
    val wholeLineRemoval = newMid.isEmpty() && oldMid.endsWith("\n") &&
        (prefix == 0 || old[prefix - 1] == '\n')
    if (!wholeLineRemoval) for (l in startLine..newEnd) out.add(l)
    return out
}
