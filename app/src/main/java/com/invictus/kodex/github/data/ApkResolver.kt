package com.invictus.kodex.github.data

import com.invictus.kodex.github.api.GhArtifact
import java.time.Instant

/** The artifact the APK card offers (plan 8.3). */
data class ApkCandidate(val artifact: GhArtifact, val universal: Boolean)

sealed interface ApkResolution {
    data class Found(val candidate: ApkCandidate) : ApkResolution

    /** Builds exist but every one is past the 14-day retention. [newestAt] = when the newest was made. */
    data class AllExpired(val newestAt: Instant?) : ApkResolution

    data object None : ApkResolution
}

/**
 * Picks the newest non-expired ARM64 artifact built from the default branch by a successful run;
 * falls back to the universal build. Pure: the run check is injected. Names are matched by their
 * ABI token (`<app>-arm64-v8a-<sha>`) so a renamed app keeps working.
 */
object ApkResolver {
    private val ARM64 = Regex("^.+-arm64-v8a-.+$")
    private val UNIVERSAL = Regex("^.+-universal-.+$")
    private const val MAX_RUN_CHECKS = 5

    /** Same build = same workflow run, else same commit (a build uploads one artifact per variant: light, full, ...). */
    private fun sameBuild(a: GhArtifact, c: GhArtifact): Boolean = a.id == c.id || when {
        c.runId != null -> a.runId == c.runId
        !c.sha.isNullOrEmpty() -> a.sha == c.sha
        else -> false
    }

    /** Every live artifact of [candidate]'s build with the same ABI kind (e.g. Light + Full arm64), by name. */
    fun siblingsOf(candidate: ApkCandidate, artifacts: List<GhArtifact>, now: Instant): List<GhArtifact> {
        val c = candidate.artifact
        val regex = if (candidate.universal) UNIVERSAL else ARM64
        return (artifacts.filter { regex.matches(it.name) && !it.isExpiredAt(now) && sameBuild(it, c) } + c)
            .distinctBy { it.id }
            .sortedBy { it.name.lowercase() }
    }

    /** The arm64 artifacts of the newest build in [artifacts] (all variants of it), by name. Empty = none. */
    fun latestBuild(artifacts: List<GhArtifact>): List<GhArtifact> {
        val arm = artifacts.filter { isArm64(it.name) }
        val newest = arm.maxWithOrNull(compareBy<GhArtifact>({ it.createdAt ?: Instant.EPOCH }, { it.id })) ?: return emptyList()
        return arm.filter { sameBuild(it, newest) }.sortedBy { it.name.lowercase() }
    }

    private val APK_NAME = Regex("(?i)(-arm64-v8a|-armeabi-v7a|-universal|-x86_64|-x86)(-|$)|\\.apk$")

    /** An artifact that carries an installable APK (named after its ABI), as opposed to logs, reports, ... */
    fun isApkArtifact(name: String) = APK_NAME.containsMatchIn(name)

    /** Every artifact of the same build (run, else commit) as [anchor], whatever its name. */
    fun buildOf(artifacts: List<GhArtifact>, anchor: GhArtifact): List<GhArtifact> = artifacts.filter { sameBuild(it, anchor) }

    /** Every live artifact of the newest build in [artifacts], whatever its name. */
    fun newestBuild(artifacts: List<GhArtifact>, now: Instant): List<GhArtifact> {
        val live = artifacts.filter { !it.isExpiredAt(now) }
        val newest = live.maxWithOrNull(compareBy<GhArtifact>({ it.createdAt ?: Instant.EPOCH }, { it.id })) ?: return emptyList()
        return live.filter { sameBuild(it, newest) }
    }

    fun isArm64(name: String) = ARM64.matches(name)
    fun isUniversal(name: String) = UNIVERSAL.matches(name)

    suspend fun resolve(
        artifacts: List<GhArtifact>,
        defaultBranch: String?,
        now: Instant,
        runSucceeded: suspend (Long) -> Boolean,
    ): ApkResolution {
        val newestFirst = artifacts.sortedByDescending { it.createdAt ?: Instant.EPOCH }
        for ((regex, universal) in listOf(ARM64 to false, UNIVERSAL to true)) {
            val live = newestFirst.filter {
                regex.matches(it.name) && !it.isExpiredAt(now) &&
                    (defaultBranch == null || it.branch == defaultBranch)
            }
            for (a in live.take(MAX_RUN_CHECKS)) {
                val run = a.runId
                if (run == null || runSucceeded(run)) return ApkResolution.Found(ApkCandidate(a, universal))
            }
        }
        val expired = newestFirst.filter {
            (ARM64.matches(it.name) || UNIVERSAL.matches(it.name)) && it.isExpiredAt(now) &&
                (defaultBranch == null || it.branch == defaultBranch)
        }
        return if (expired.isNotEmpty()) ApkResolution.AllExpired(expired.first().createdAt) else ApkResolution.None
    }
}
