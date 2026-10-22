package com.invictus.kodex.feature.git

import com.invictus.kodex.core.git.model.GitRemoveRequest

/**
 * A pasted "git rm / git add / git commit -m / git push" snippet, reduced to what the app should do.
 * [message] is null for a snippet without a commit (e.g. only `git rm`). [stageAll] only when
 * `git add` was present, [push] only when `git push` was present. [addFirst] keeps the shell's
 * order between `git add` and `git rm` (add -> rm --cached must not re-stage the file).
 */
internal data class GitCommandScript(
    val message: String?,
    val stageAll: Boolean,
    val push: Boolean,
    val removals: List<GitRemoveRequest> = emptyList(),
    val addFirst: Boolean = false,
)

private val COMMIT_RE = Regex(
    """\bgit\s+commit\b[^\n]*?\s-[A-Za-z]*m\s*(?:"((?:[^"\\]|\\.)*)"|'([^']*)'|(\S+))""",
)
private val ADD_RE = Regex("""\bgit\s+add\b""")
private val PUSH_RE = Regex("""\bgit\s+push\b""")
private val AMEND_RE = Regex("""--amend\b""")
private val ESCAPE_RE = Regex("""\\([\\"$`])""")
private val RM_RE = Regex("""(?:^|[;&|(\n])[ \t]*(?:sudo[ \t]+)?(git[ \t]+)?rm(?=[ \t]|$)""", RegexOption.MULTILINE)

/**
 * Returns null unless [text] contains a `git commit -m "..."` with a non-blank message
 * or at least one `git rm` / `rm` with a path.
 */
internal fun parseGitScript(text: String): GitCommandScript? {
    val commit = COMMIT_RE.find(text)
    if (commit != null && AMEND_RE.containsMatchIn(commit.value)) return null

    val message = commit?.let { m ->
        val raw = m.groups[1]?.value?.let { ESCAPE_RE.replace(it) { e -> e.groupValues[1] } }
            ?: m.groups[2]?.value
            ?: m.groups[3]?.value
        raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    // Blank out the commit command so an "rm" inside the message is never read as a command.
    val scan = if (commit == null) text else text.replaceRange(commit.range, " ".repeat(commit.value.length))
    val removals = ArrayList<GitRemoveRequest>()
    var firstRm = -1
    for (m in RM_RE.findAll(scan)) {
        val spec = parseRemove(scan, m.range.last + 1, viaGit = m.groups[1] != null) ?: continue
        if (firstRm < 0) firstRm = m.range.first
        removals += spec
    }

    val addAt = ADD_RE.find(text)?.range?.first ?: -1
    // A bare "rm old files" is far more likely a commit message than a command: plain `rm`
    // only counts with -r/-f or next to other git commands. `git rm` is always a command.
    val hasGit = message != null || addAt >= 0 || PUSH_RE.containsMatchIn(text) || removals.any { it.viaGit }
    removals.retainAll { it.viaGit || it.recursive || it.force || hasGit }
    if (message == null && removals.isEmpty()) return null
    return GitCommandScript(
        message = message,
        stageAll = addAt >= 0,
        // A push only follows a commit; `git rm x && git push` alone has nothing new to push.
        push = message != null && PUSH_RE.containsMatchIn(text),
        removals = removals,
        addFirst = addAt >= 0 && firstRm >= 0 && addAt < firstRm,
    )
}

private fun parseRemove(s: String, from: Int, viaGit: Boolean): GitRemoveRequest? {
    val args = readArgs(s, from)
    var recursive = false
    var force = false
    var cached = false
    var ignoreUnmatch = false
    var optionsDone = false
    val patterns = ArrayList<String>()
    for (a in args) {
        when {
            optionsDone -> patterns += a
            a == "--" -> optionsDone = true
            a.startsWith("--") -> when (a) {
                "--recursive" -> recursive = true
                "--force" -> force = true
                "--cached" -> cached = true
                "--ignore-unmatch" -> ignoreUnmatch = true
            }
            a.length > 1 && a[0] == '-' -> a.drop(1).forEach { c ->
                when (c) {
                    'r', 'R' -> recursive = true
                    'f' -> force = true
                }
            }
            a.startsWith(">") || a.matches(Regex("""\d>.*""")) -> Unit // redirection, not a path
            else -> patterns += a
        }
    }
    if (patterns.isEmpty()) return null
    return GitRemoveRequest(
        patterns = patterns,
        recursive = recursive,
        force = force,
        cached = cached && viaGit,
        viaGit = viaGit,
        ignoreMissing = ignoreUnmatch || (!viaGit && force),
    )
}

/** Shell-style split of one command's arguments: quotes, backslash escapes, stops at ; & | newline. */
private fun readArgs(s: String, from: Int): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var has = false
    fun flush() {
        if (has) out += cur.toString()
        cur.setLength(0)
        has = false
    }
    var i = from
    loop@ while (i < s.length) {
        val c = s[i]
        when {
            c == '\\' && i + 1 < s.length -> {
                if (s[i + 1] == '\n') flush() else { cur.append(s[i + 1]); has = true }
                i += 2
            }
            c == '"' -> {
                has = true
                i++
                while (i < s.length && s[i] != '"') {
                    if (s[i] == '\\' && i + 1 < s.length && s[i + 1] in "\\\"$`") i++
                    cur.append(s[i])
                    i++
                }
                i++
            }
            c == '\'' -> {
                has = true
                i++
                while (i < s.length && s[i] != '\'') { cur.append(s[i]); i++ }
                i++
            }
            c == '\n' || c == ';' || c == '&' || c == '|' -> break@loop
            c == ' ' || c == '\t' -> { flush(); i++ }
            else -> { cur.append(c); has = true; i++ }
        }
    }
    flush()
    return out
}
