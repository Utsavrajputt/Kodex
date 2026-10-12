package com.invictus.kodex.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitParseTest {
    private val geminiPerMinute = """
        {"error":{"code":429,"message":"You exceeded your current quota. Please retry in 34.2s.","status":"RESOURCE_EXHAUSTED",
        "details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},
        {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"34s"}]}}
    """.trimIndent()

    @Test fun retryAfterHeaderWins() {
        assertEquals(12, AiHttp.parseRetryDelaySec("{}", "12"))
        assertEquals(2, AiHttp.parseRetryDelaySec("{}", "1.2"))
    }

    @Test fun geminiRetryDelayFromBody() {
        assertEquals(34, AiHttp.parseRetryDelaySec(geminiPerMinute, null))
    }

    @Test fun geminiMessageFallback() {
        assertEquals(35, AiHttp.parseRetryDelaySec("""{"error":{"message":"Please retry in 34.2s."}}""", null))
    }

    @Test fun unknownIsNull() {
        assertNull(AiHttp.parseRetryDelaySec("{}", null))
    }

    @Test fun perMinute429MapsToShortWait() {
        val e = AiHttp.mapHttpError(429, geminiPerMinute, null)
        assertTrue(e is AiError.RateLimited)
        assertEquals(34, (e as AiError.RateLimited).retryAfterSec)
    }

    @Test fun perDay429MapsToLongWait() {
        val body = """{"error":{"message":"quota","details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}"""
        val e = AiHttp.mapHttpError(429, body, null) as AiError.RateLimited
        assertEquals(AiHttp.DAILY_QUOTA_SEC, e.retryAfterSec)
    }
}
