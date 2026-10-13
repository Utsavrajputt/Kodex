package com.invictus.kodex.ai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    data class Code(val lang: String, val code: String, val closed: Boolean) : MdBlock
    data class Item(val indent: Int, val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val RULE = Regex("^([-*_])(?:\\s*\\1){2,}$")
private val TABLE_SEP = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")
private val LIST = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")

private fun cells(line: String): List<String> = line.trim().trim('|').split('|').map { it.trim() }

/** Block-level Markdown parse. Tolerates a still-streaming (unclosed) code fence. */
fun parseMarkdown(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = src.replace("\r\n", "\n").split('\n')
    val para = StringBuilder()
    fun flush() {
        if (para.isNotEmpty()) { out += MdBlock.Para(para.toString()); para.clear() }
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        val fence = when { t.startsWith("```") -> "```"; t.startsWith("~~~") -> "~~~"; else -> null }
        when {
            fence != null -> {
                flush()
                val lang = t.removePrefix(fence).trim().substringBefore(' ').lowercase()
                val body = ArrayList<String>()
                i++
                var closed = false
                while (i < lines.size) {
                    if (lines[i].trim().startsWith(fence)) { closed = true; break }
                    body += lines[i]
                    i++
                }
                out += MdBlock.Code(lang, body.joinToString("\n"), closed)
            }
            t.isEmpty() -> flush()
            HEADING.matches(t) -> {
                flush()
                val m = HEADING.find(t)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
            }
            RULE.matches(t) -> { flush(); out += MdBlock.Rule }
            t.startsWith(">") -> {
                flush()
                val q = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    q.appendLine(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                i--
                out += MdBlock.Quote(q.toString().trim())
            }
            t.startsWith("|") && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1].trim()) -> {
                flush()
                val header = cells(t)
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows += cells(lines[i])
                    i++
                }
                i--
                out += MdBlock.Table(header, rows)
            }
            LIST.matches(line) -> {
                flush()
                val m = LIST.find(line)!!
                val marker = m.groupValues[2].let { if (it[0].isDigit()) it else "•" }
                out += MdBlock.Item(m.groupValues[1].length / 2, marker, m.groupValues[3])
            }
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(t)
            }
        }
        i++
    }
    flush()
    return out
}

class InlineStyles(val code: SpanStyle, val link: SpanStyle)

/** Inline Markdown (code, bold, italic, strike, links) to an AnnotatedString. */
fun inlineMarkdown(text: String, st: InlineStyles): AnnotatedString = buildAnnotatedString { appendInline(text, st) }

private fun AnnotatedString.Builder.appendInline(s: String, st: InlineStyles) {
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) { append(plain.toString()); plain.clear() }
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_{}[]()#+-.!|~" -> { plain.append(s[i + 1]); i += 2 }
            c == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i) { flush(); withStyle(st.code) { append(s.substring(i + 1, end)) }; i = end + 1 } else { plain.append(c); i++ }
            }
            s.startsWith("**", i) || s.startsWith("__", i) -> {
                val m = s.substring(i, i + 2)
                val end = s.indexOf(m, i + 2)
                if (end > i + 2) {
                    flush()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(s.substring(i + 2, end), st) }
                    i = end + 2
                } else { plain.append(c); i++ }
            }
            s.startsWith("~~", i) -> {
                val end = s.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flush()
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(s.substring(i + 2, end), st) }
                    i = end + 2
                } else { plain.append(c); i++ }
            }
            (c == '*' || c == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() &&
                !(c == '_' && i > 0 && s[i - 1].isLetterOrDigit()) -> {
                var end = -1
                var j = i + 1
                while (j < s.length) {
                    if (s[j] == c && !s[j - 1].isWhitespace() && (c == '*' || j + 1 >= s.length || !s[j + 1].isLetterOrDigit())) { end = j; break }
                    j++
                }
                if (end > i + 1) {
                    flush()
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(s.substring(i + 1, end), st) }
                    i = end + 1
                } else { plain.append(c); i++ }
            }
            c == '[' -> {
                val mid = s.indexOf("](", i + 1)
                val close = if (mid > 0) s.indexOf(')', mid + 2) else -1
                if (mid > 0 && close > 0) {
                    flush()
                    val url = s.substring(mid + 2, close).trim()
                    val label = s.substring(i + 1, mid)
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        withLink(LinkAnnotation.Url(url, TextLinkStyles(style = st.link))) { append(label) }
                    } else {
                        withStyle(st.link) { append(label) }
                    }
                    i = close + 1
                } else { plain.append(c); i++ }
            }
            else -> { plain.append(c); i++ }
        }
    }
    flush()
}

@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val scheme = MaterialTheme.colorScheme
    val styles = remember(scheme) {
        InlineStyles(
            code = SpanStyle(fontFamily = FontFamily.Monospace, background = scheme.surfaceVariant, color = scheme.onSurface, fontSize = 13.sp),
            link = SpanStyle(color = scheme.primary, textDecoration = TextDecoration.Underline),
        )
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    inlineMarkdown(b.text, styles),
                    style = when (b.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is MdBlock.Para -> Text(inlineMarkdown(b.text, styles), style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Item -> Row(Modifier.padding(start = (b.indent * 14).dp)) {
                    Text(b.marker, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(22.dp))
                    Text(inlineMarkdown(b.text, styles), style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(scheme.outline))
                    Text(
                        inlineMarkdown(b.text, styles),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                is MdBlock.Code -> CodeBlock(b.lang, b.code)
                is MdBlock.Table -> TableBlock(b, styles)
                MdBlock.Rule -> HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TableBlock(t: MdBlock.Table, styles: InlineStyles) {
    val scheme = MaterialTheme.colorScheme
    val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
    Column(Modifier.horizontalScroll(rememberScrollState()).border(0.5.dp, scheme.outlineVariant, RoundedCornerShape(6.dp))) {
        (listOf(t.header) + t.rows).forEachIndexed { r, row ->
            Row(Modifier.then(if (r == 0) Modifier.background(scheme.surfaceVariant) else Modifier)) {
                for (c in 0 until cols) {
                    Text(
                        inlineMarkdown(row.getOrElse(c) { "" }, styles),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.width(140.dp).border(0.25.dp, scheme.outlineVariant).padding(6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val styled = remember(code, lang, dark) {
        if (code.length > 20_000) AnnotatedString(code)
        else CodeHighlight.highlight(code, lang, if (dark) CodePalette.Dark else CodePalette.Light)
    }
    Surface(shape = RoundedCornerShape(10.dp), color = scheme.surfaceVariant.copy(alpha = 0.55f)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    lang.ifEmpty { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Copy")
                }
            }
            Text(
                styled,
                color = scheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 10.dp),
            )
        }
    }
}
