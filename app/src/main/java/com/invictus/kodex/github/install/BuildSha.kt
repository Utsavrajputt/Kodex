package com.invictus.kodex.github.install

/** What the primary button of a build should do, given what is installed. */
enum class ApkAction { Install, Update, Open }

/** Pure commit-comparison rules so they can be unit-tested without PackageManager. */
object BuildSha {
    /** Shortest prefix still trusted as a commit id (git's default short length). */
    private const val MIN_LENGTH = 7

    private val NAME_SUFFIX = Regex("-([0-9a-fA-F]{7,40})(?:\\.apk)?$")

    /** Full or abbreviated commit ids that refer to the same commit. Blank / too short never match. */
    fun same(a: String?, b: String?): Boolean {
        val x = a?.trim()?.lowercase().orEmpty()
        val y = b?.trim()?.lowercase().orEmpty()
        if (x.length < MIN_LENGTH || y.length < MIN_LENGTH) return false
        return x.startsWith(y) || y.startsWith(x)
    }

    /** "kodex-arm64-v8a-1aaad17" -> "1aaad17"; null when the name carries no commit suffix. */
    fun fromArtifactName(name: String): String? = NAME_SUFFIX.find(name.trim())?.groupValues?.get(1)

    /**
     * Not installed -> Install. Installed from the same commit -> Open. Anything else (older commit,
     * or an installed copy whose commit is unknown, e.g. not installed through Kodex) -> Update.
     */
    fun actionFor(installed: Boolean, installedSha: String?, buildSha: String?): ApkAction = when {
        !installed -> ApkAction.Install
        same(installedSha, buildSha) -> ApkAction.Open
        else -> ApkAction.Update
    }
}
