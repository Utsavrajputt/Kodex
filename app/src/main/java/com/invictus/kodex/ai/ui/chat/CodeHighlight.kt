package com.invictus.kodex.ai.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

class CodePalette(val keyword: Color, val string: Color, val comment: Color, val number: Color, val type: Color) {
    companion object {
        val Dark = CodePalette(Color(0xFFFF7B72), Color(0xFFA5D6FF), Color(0xFF8B949E), Color(0xFF79C0FF), Color(0xFFFFA657))
        val Light = CodePalette(Color(0xFFCF222E), Color(0xFF0A3069), Color(0xFF6E7781), Color(0xFF0550AE), Color(0xFF953800))
    }
}

/** Small regex-based syntax colouring for chat code blocks (not a parser; good enough to read code at a glance). */
object CodeHighlight {
    private enum class Kind { COMMENT, STRING, NUMBER, WORD, KEYWORD, TAG }

    private class Family(val regex: Regex, val kinds: List<Kind>, val keywords: Set<String>)

    private val cKeywords = (
        "fun val var class object interface enum data sealed override private public protected internal static final abstract open " +
            "if else when while for do return break continue try catch finally throw throws import package new this super null true false " +
            "in is as void int long double float boolean char byte short const let function async await export from default switch case " +
            "struct impl pub fn mut use mod trait match self nil func defer go chan type typealias extends implements instanceof typeof " +
            "lateinit suspend inline companion init constructor get set where yield operator reified vararg by readonly delete with var " +
            "namespace using virtual template typename auto unsigned signed extern volatile goto enum union sizeof lazy unit"
        ).split(' ').toSet()

    private val pyKeywords = (
        "def class return if elif else for while in not and or is import from as with try except finally raise pass break continue " +
            "lambda yield None True False global nonlocal async await del assert self " +
            "if then fi do done case esac function echo export local exit set unset readonly shift"
        ).split(' ').toSet()

    private val C_LIKE = Family(
        Regex(
            """(//[^\n]*|/\*[\s\S]*?(?:\*/|$))|("(?:\\.|[^"\\\n])*"?|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`?)""" +
                """|(\b0[xX][0-9a-fA-F]+\b|\b\d+(?:\.\d+)?[fFLuU]*\b)|([A-Za-z_][A-Za-z0-9_]*)""",
        ),
        listOf(Kind.COMMENT, Kind.STRING, Kind.NUMBER, Kind.WORD),
        cKeywords,
    )

    private val HASH = Family(
        Regex(
            "(#[^\\n]*)|(\"{3}[\\s\\S]*?(?:\"{3}|$)|'{3}[\\s\\S]*?(?:'{3}|$)|\"(?:\\\\.|[^\"\\\\\\n])*\"?|'(?:\\\\.|[^'\\\\\\n])*'?)" +
                "|(\\b\\d+(?:\\.\\d+)?\\b)|([A-Za-z_][A-Za-z0-9_]*)",
        ),
        listOf(Kind.COMMENT, Kind.STRING, Kind.NUMBER, Kind.WORD),
        pyKeywords,
    )

    private val JSON = Family(
        Regex("""("(?:\\.|[^"\\\n])*"?)|(\b-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)|(\b(?:true|false|null)\b)"""),
        listOf(Kind.STRING, Kind.NUMBER, Kind.KEYWORD),
        emptySet(),
    )

    private val MARKUP = Family(
        Regex("""(<!--[\s\S]*?(?:-->|$))|("[^"\n]*"|'[^'\n]*')|(</?[A-Za-z][\w:.-]*)"""),
        listOf(Kind.COMMENT, Kind.STRING, Kind.TAG),
        emptySet(),
    )

    private val PLAIN = setOf("", "text", "txt", "plaintext", "markdown", "md", "diff", "log", "console", "output")
    private val HASH_LANGS = setOf(
        "py", "python", "sh", "bash", "shell", "zsh", "yaml", "yml", "toml", "ruby", "rb", "dockerfile", "properties", "ini", "makefile", "perl",
    )

    private fun familyFor(lang: String): Family? {
        val l = lang.lowercase()
        return when {
            l in PLAIN -> null
            l in HASH_LANGS -> HASH
            l == "json" || l == "jsonc" || l == "json5" -> JSON
            l in setOf("xml", "html", "svg", "xhtml") -> MARKUP
            else -> C_LIKE
        }
    }

    fun highlight(code: String, lang: String, p: CodePalette): AnnotatedString {
        val family = familyFor(lang) ?: return AnnotatedString(code)
        return buildAnnotatedString {
            var last = 0
            for (m in family.regex.findAll(code)) {
                if (m.range.first > last) append(code.substring(last, m.range.first))
                val groupIndex = (1..family.kinds.size).firstOrNull { m.groups[it] != null }
                var kind = if (groupIndex == null) Kind.WORD else family.kinds[groupIndex - 1]
                val text = m.value
                if (kind == Kind.WORD && text in family.keywords) kind = Kind.KEYWORD
                val color = when {
                    kind == Kind.COMMENT -> p.comment
                    kind == Kind.STRING -> p.string
                    kind == Kind.NUMBER -> p.number
                    kind == Kind.KEYWORD || kind == Kind.TAG -> p.keyword
                    kind == Kind.WORD && text.length > 1 && text[0].isUpperCase() && text.any { it.isLowerCase() } -> p.type
                    else -> null
                }
                if (color == null) append(text) else withStyle(SpanStyle(color = color)) { append(text) }
                last = m.range.last + 1
            }
            if (last < code.length) append(code.substring(last))
        }
    }
}
