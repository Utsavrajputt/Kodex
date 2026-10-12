package com.invictus.kodex.ai

/** Strips obvious secrets from anything sent to a provider (plan 9.2/9.3). */
object Redactor {
    private val patterns = listOf(
        Regex("""(?i)(api[_-]?key|secret|token|password|authorization)\s*[=:]\s*["']?[\w\-./+=]{8,}"""),
        Regex("""Bearer\s+[A-Za-z0-9._\-]{10,}"""),
        Regex("""sk-[A-Za-z0-9_\-]{16,}"""),
        Regex("""AKIA[0-9A-Z]{16}"""),
        Regex("""eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}"""),
        Regex("""xox[abprs]-[A-Za-z0-9\-]{10,}"""),
        Regex("""github_pat_[A-Za-z0-9_]{20,}"""),
        Regex("""AIza[0-9A-Za-z_\-]{20,}"""),
        // Whole PEM block first (header + body + footer), then a bare header as a fallback.
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
        Regex("""gh[pousr]_[A-Za-z0-9]{20,}"""),
    )

    fun scrub(text: String): String = patterns.fold(text) { acc, re -> acc.replace(re, "«redacted»") }

    /** Translates one glob into a regex: `*` stays inside a path segment, a double star crosses `/`, `?` is one char. */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && glob.getOrNull(i + 1) == '*' -> {
                    sb.append(".*")
                    i++
                    if (glob.getOrNull(i + 1) == '/') i++
                }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(sb.toString())
    }

    /**
     * True when [path] matches any glob in [globsCsv]. A glob without `/` (".env", "*.pem") matches
     * any single path segment, so it applies at every depth; a glob with `/` matches the whole path
     * or any trailing sub-path of it.
     */
    fun isExcluded(path: String, globsCsv: String): Boolean {
        val p = path.replace('\\', '/').trimStart('/')
        return globsCsv.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.any { glob ->
            val re = globToRegex(glob.trimStart('/'))
            if ('/' !in glob) p.split('/').any { re.matches(it) }
            else re.matches(p) || p.split('/').indices.any { i -> re.matches(p.split('/').drop(i).joinToString("/")) }
        }
    }

    private val diffHeader = Regex("^diff --git a/(.+?) b/(.+)$", RegexOption.MULTILINE)

    /** Drops whole per-file sections of a unified diff whose path is on the exclusion list, then scrubs secrets. */
    fun scrubDiff(diff: String, globsCsv: String): String {
        val kept = diff.split(Regex("(?m)^(?=diff --git )")).filter { block ->
            val m = diffHeader.find(block) ?: return@filter true
            !isExcluded(m.groupValues[2], globsCsv)
        }.joinToString("")
        return scrub(kept)
    }
}
