package com.invictus.kodex.github.data

/**
 * Release-tag helpers for workflows that ask for a tag (`tag_name` and the like): a ready-made format,
 * next-version suggestions from the repo's existing tags, and a format check. Pure and Android-free.
 */
object TagSuggestions {
    private val STABLE = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$")
    private val PRE = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)-([0-9A-Za-z.]+)$")
    private val TRAILING_NUMBER = Regex("^(.*?)(\\d+)$")

    private data class Ver(val major: Int, val minor: Int, val patch: Int) : Comparable<Ver> {
        override fun compareTo(other: Ver) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        fun nextPatch() = Ver(major, minor, patch + 1)
        fun nextMinor() = Ver(major, minor + 1, 0)
        fun nextMajor() = Ver(major + 1, 0, 0)
        override fun toString() = "v$major.$minor.$patch"
    }

    private fun versionOf(tag: String): Ver? =
        (STABLE.matchEntire(tag) ?: PRE.matchEntire(tag))?.let { m ->
            val (a, b, c) = m.groupValues.drop(1).take(3).map { it.toIntOrNull() ?: return null }
            Ver(a, b, c)
        }

    /** A manual-run input that wants a release tag. */
    fun isTagInput(input: WorkflowInput): Boolean {
        val n = input.name.lowercase()
        return input.type == "string" && ("tag" in n || "version" in n)
    }

    /** "Make pre-release" style workflows take `v1.2.0-beta.1`; plain release workflows take `v1.2.0`. */
    fun isPrerelease(workflowName: String, workflowPath: String = ""): Boolean {
        val s = "$workflowName $workflowPath".lowercase()
        return "pre-release" in s || "prerelease" in s || "pre release" in s
    }

    fun isValid(tag: String, prerelease: Boolean): Boolean =
        (if (prerelease) PRE else STABLE).matches(tag.trim())

    /** The format to show as a hint / starting point. */
    fun format(prerelease: Boolean) = if (prerelease) "v1.2.0-beta.1" else "v1.2.0"

    /** Tags that already exist in the repo and fit this workflow's format, newest first. */
    fun existing(tags: List<String>, prerelease: Boolean, limit: Int = 6): List<String> =
        tags.filter { isValid(it, prerelease) }
            .sortedWith(compareByDescending<String> { versionOf(it) ?: Ver(0, 0, 0) }.thenByDescending { it })
            .take(limit)

    /** New tags worth releasing next, best guess first. */
    fun suggest(tags: List<String>, prerelease: Boolean): List<String> {
        val parsed = tags.mapNotNull { t -> versionOf(t)?.let { t to it } }
        val latestStable = parsed.filter { STABLE.matches(it.first) }.maxByOrNull { it.second }?.second
        if (!prerelease) {
            return if (latestStable == null) listOf("v1.0.0")
            else listOf(latestStable.nextPatch(), latestStable.nextMinor(), latestStable.nextMajor()).map { it.toString() }
        }
        val latest = parsed.maxWithOrNull(compareBy<Pair<String, Ver>> { it.second }.thenBy { STABLE.matches(it.first) })
            ?: return listOf("v1.0.0-beta.1")
        val out = LinkedHashSet<String>()
        if (!STABLE.matches(latest.first)) {
            // Already on a pre-release line: bump its counter, and offer the next stage.
            val ver = latest.second
            val label = PRE.matchEntire(latest.first)!!.groupValues[4]
            TRAILING_NUMBER.matchEntire(label)?.let { m ->
                out += "$ver-${m.groupValues[1]}${m.groupValues[2].toInt() + 1}"
            }
            if (!label.startsWith("rc")) out += "$ver-rc.1"
            out += "${ver.nextMinor()}-beta.1"
        } else {
            val ver = latest.second
            out += "${ver.nextMinor()}-beta.1"
            out += "${ver.nextPatch()}-beta.1"
            out += "${ver.nextMajor()}-beta.1"
        }
        return out.toList()
    }
}
