package com.invictus.kodex.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    private val defaults = ".env, .env.*, *.pem, *.key, id_rsa*"

    @Test fun defaultGlobsMatchSecretFilesAtAnyDepth() {
        assertTrue(Redactor.isExcluded(".env", defaults))
        assertTrue(Redactor.isExcluded("app/.env.local", defaults))
        assertTrue(Redactor.isExcluded("certs/server.pem", defaults))
        assertTrue(Redactor.isExcluded("home/.ssh/id_rsa.pub", defaults))
    }

    @Test fun normalSourceFilesAreNotExcluded() {
        assertFalse(Redactor.isExcluded("src/Main.kt", defaults))
        assertFalse(Redactor.isExcluded("docs/environment.md", defaults))
    }

    @Test fun folderGlobsMatchNestedPaths() {
        assertTrue(Redactor.isExcluded("secrets/a.txt", "secrets/**"))
        assertTrue(Redactor.isExcluded("app/secrets/a.txt", "secrets/**"))
        assertFalse(Redactor.isExcluded("app/public/a.txt", "secrets/**"))
    }

    @Test fun scrubRemovesProviderKeysAndPemBlocks() {
        val text = "k=sk-ant-api03-abcdefghijklmnop1234 and AKIAABCDEFGHIJKLMNOP\n" +
            "-----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJB\n-----END RSA PRIVATE KEY-----"
        val out = Redactor.scrub(text)
        assertFalse(out.contains("sk-ant"))
        assertFalse(out.contains("AKIAABCDEFGHIJKLMNOP"))
        assertFalse(out.contains("MIIBOgIBAAJB"))
        assertTrue(out.contains("«redacted»"))
    }

    @Test fun scrubDiffDropsExcludedFilesButKeepsOthers() {
        val diff = "diff --git a/.env b/.env\n+SECRET=1\n" +
            "diff --git a/src/A.kt b/src/A.kt\n+val a = 1\n"
        val out = Redactor.scrubDiff(diff, defaults)
        assertFalse(out.contains(".env"))
        assertTrue(out.contains("src/A.kt"))
        assertEquals(1, Regex("diff --git").findAll(out).count())
    }
}
