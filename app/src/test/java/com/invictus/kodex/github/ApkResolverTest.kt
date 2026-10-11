package com.invictus.kodex.github

import com.invictus.kodex.github.api.GhArtifact
import com.invictus.kodex.github.data.ApkResolution
import com.invictus.kodex.github.data.ApkResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ApkResolverTest {
    private val now = Instant.parse("2026-10-10T12:00:00Z")

    private fun art(
        id: Long, name: String, ageDays: Long, branch: String? = "main", run: Long? = id * 10,
        expired: Boolean = false, expiresInDays: Long = 14 - ageDays,
    ) = GhArtifact(
        id = id, name = name, sizeBytes = 1000, expired = expired,
        createdAt = now.minusSeconds(ageDays * 86_400),
        expiresAt = now.plusSeconds(expiresInDays * 86_400),
        runId = run, branch = branch, sha = "abcdef$id",
    )

    private fun resolve(list: List<GhArtifact>, ok: (Long) -> Boolean = { true }) =
        runBlocking { ApkResolver.resolve(list, "main", now) { ok(it) } }

    @Test fun picksNewestArm64() {
        val r = resolve(
            listOf(
                art(1, "kodex-arm64-v8a-aaa", 3), art(2, "kodex-arm64-v8a-bbb", 1), art(3, "kodex-universal-bbb", 1),
            ),
        ) as ApkResolution.Found
        assertEquals(2L, r.candidate.artifact.id)
        assertEquals(false, r.candidate.universal)
    }

    @Test fun fallsBackToUniversal() {
        val r = resolve(listOf(art(1, "kodex-armeabi-v7a-aaa", 1), art(2, "kodex-universal-aaa", 1))) as ApkResolution.Found
        assertEquals(2L, r.candidate.artifact.id)
        assertTrue(r.candidate.universal)
    }

    @Test fun skipsOtherBranches() {
        val r = resolve(listOf(art(1, "kodex-arm64-v8a-aaa", 1, branch = "feature"), art(2, "kodex-arm64-v8a-bbb", 5)))
            as ApkResolution.Found
        assertEquals(2L, r.candidate.artifact.id)
    }

    @Test fun skipsFailedRuns() {
        val r = resolve(
            listOf(art(1, "kodex-arm64-v8a-aaa", 1), art(2, "kodex-arm64-v8a-bbb", 2)),
            ok = { it != 10L },
        ) as ApkResolution.Found
        assertEquals(2L, r.candidate.artifact.id)
    }

    @Test fun expiredFlagAndTimestampBothCount() {
        val r = resolve(
            listOf(
                art(1, "kodex-arm64-v8a-aaa", 1, expired = true),
                art(2, "kodex-arm64-v8a-bbb", 2, expiresInDays = -1),
            ),
        )
        assertTrue(r is ApkResolution.AllExpired)
        assertEquals(now.minusSeconds(86_400), (r as ApkResolution.AllExpired).newestAt)
    }

    @Test fun noApkArtifacts() {
        assertEquals(ApkResolution.None, resolve(listOf(art(1, "coverage-report", 1))))
        assertEquals(ApkResolution.None, resolve(emptyList()))
    }

    @Test fun anyAppNamePrefixWorks() {
        assertTrue(ApkResolver.isArm64("xcode-arm64-v8a-1234567"))
        assertTrue(ApkResolver.isArm64("my-app-arm64-v8a-1234567"))
        assertTrue(ApkResolver.isUniversal("kodex-universal-1234567"))
        assertEquals(false, ApkResolver.isArm64("kodex-armeabi-v7a-1234567"))
    }

    // ---- variants of one build -------------------------------------------------------------

    @Test fun siblingsIncludeBothVariantsOfTheSameRun() {
        val list = listOf(
            art(1, "Xmd-lite-arm64-v8a-aaa", 1, run = 500), art(2, "Xmd-full-arm64-v8a-aaa", 1, run = 500),
            art(3, "Xmd-full-arm64-v8a-old", 3, run = 400), art(4, "Xmd-full-armeabi-v7a-aaa", 1, run = 500),
        )
        val r = resolve(list) as ApkResolution.Found
        val names = ApkResolver.siblingsOf(r.candidate, list, now).map { it.name }
        assertEquals(listOf("Xmd-full-arm64-v8a-aaa", "Xmd-lite-arm64-v8a-aaa"), names)
    }

    @Test fun siblingsSkipExpiredVariants() {
        val list = listOf(
            art(1, "Xmd-lite-arm64-v8a-aaa", 1, run = 500),
            art(2, "Xmd-full-arm64-v8a-aaa", 1, run = 500, expired = true),
        )
        val r = resolve(list) as ApkResolution.Found
        assertEquals(listOf(1L), ApkResolver.siblingsOf(r.candidate, list, now).map { it.id })
    }

    @Test fun latestBuildGroupsTheNewestRunOnly() {
        val list = listOf(
            art(1, "Xmd-lite-arm64-v8a-new", 1, run = 500), art(2, "Xmd-full-arm64-v8a-new", 1, run = 500),
            art(3, "Xmd-lite-arm64-v8a-old", 5, run = 400), art(4, "Xmd-full-arm64-v8a-old", 5, run = 400),
            art(5, "Xmd-full-armeabi-v7a-new", 1, run = 500),
        )
        assertEquals(listOf(2L, 1L), ApkResolver.latestBuild(list).map { it.id })
    }

    @Test fun latestBuildEmptyWithoutArm64() {
        assertTrue(ApkResolver.latestBuild(listOf(art(1, "kodex-universal-aaa", 1))).isEmpty())
    }
}
