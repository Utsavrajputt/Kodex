package com.invictus.kodex.github

import com.invictus.kodex.github.install.ApkAction
import com.invictus.kodex.github.install.BuildSha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildShaTest {
    private val full = "1aaad17c0ffee1234567890abcdef1234567890a"

    @Test fun fullAndShortOfSameCommitMatch() {
        assertTrue(BuildSha.same(full, "1aaad17"))
        assertTrue(BuildSha.same("1aaad17", full))
        assertTrue(BuildSha.same(full, full))
    }

    @Test fun caseAndWhitespaceAreIgnored() {
        assertTrue(BuildSha.same(" 1AAAD17 ", full))
    }

    @Test fun differentCommitsDoNotMatch() {
        assertFalse(BuildSha.same("1aaad17", "92886fe"))
        assertFalse(BuildSha.same(full, "1aaad18"))
    }

    @Test fun blankOrTooShortNeverMatches() {
        assertFalse(BuildSha.same(null, null))
        assertFalse(BuildSha.same("", ""))
        assertFalse(BuildSha.same("", full))
        assertFalse(BuildSha.same("1aaa", "1aaa"))
        assertFalse(BuildSha.same("1aaad1", full))
    }

    @Test fun commitFromArtifactName() {
        assertEquals("1aaad17", BuildSha.fromArtifactName("kodex-arm64-v8a-1aaad17"))
        assertEquals("1aaad17", BuildSha.fromArtifactName("kodex-universal-1aaad17.apk"))
        assertEquals(full, BuildSha.fromArtifactName("kodex-universal-$full"))
        assertNull(BuildSha.fromArtifactName("kodex-universal"))
        assertNull(BuildSha.fromArtifactName("kodex-arm64-v8a-abc12"))
    }

    @Test fun notInstalledIsInstall() {
        assertEquals(ApkAction.Install, BuildSha.actionFor(false, null, "1aaad17"))
        assertEquals(ApkAction.Install, BuildSha.actionFor(false, "1aaad17", "1aaad17"))
    }

    @Test fun installedSameCommitIsOpen() {
        assertEquals(ApkAction.Open, BuildSha.actionFor(true, "1aaad17", full))
    }

    @Test fun installedOlderCommitIsUpdate() {
        assertEquals(ApkAction.Update, BuildSha.actionFor(true, "92886fe", "1aaad17"))
    }

    @Test fun installedWithUnknownCommitIsUpdate() {
        assertEquals(ApkAction.Update, BuildSha.actionFor(true, null, "1aaad17"))
        assertEquals(ApkAction.Update, BuildSha.actionFor(true, "", "1aaad17"))
    }

    @Test fun unknownBuildCommitIsUpdateNotOpen() {
        assertEquals(ApkAction.Update, BuildSha.actionFor(true, "1aaad17", null))
    }
}
