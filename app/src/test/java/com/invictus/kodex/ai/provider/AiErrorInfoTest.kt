package com.invictus.kodex.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiErrorInfoTest {
    @Test fun formatWait() {
        assertEquals("45s", AiErrorPresenter.formatWait(45))
        assertEquals("2m 10s", AiErrorPresenter.formatWait(130))
        assertEquals("5m", AiErrorPresenter.formatWait(300))
        assertEquals("1h", AiErrorPresenter.formatWait(3600))
        assertEquals("1h 30m", AiErrorPresenter.formatWait(5400))
    }

    @Test fun dailyQuotaSuggestsSwitching() {
        val info = AiErrorPresenter.describe(AiError.RateLimited(AiHttp.DAILY_QUOTA_SEC, daily = true), "Gemini")
        assertEquals("Daily quota used up", info.title)
        assertTrue(ErrorAction.SWITCH_MODEL in info.actions)
        assertTrue(ErrorAction.RETRY !in info.actions)
    }

    @Test fun shortLimitOffersRetry() {
        val info = AiErrorPresenter.describe(AiError.RateLimited(30), "Claude")
        assertEquals("Rate limit reached", info.title)
        assertTrue(ErrorAction.RETRY in info.actions)
        assertTrue("30s" in info.explanation)
    }

    @Test fun dailyFlagComesFromHttpMapping() {
        val body = """{"error":{"message":"quota","details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}"""
        assertTrue((AiHttp.mapHttpError(429, body, null) as AiError.RateLimited).daily)
        assertTrue(!(AiHttp.mapHttpError(429, "{}", "5") as AiError.RateLimited).daily)
    }

    @Test fun tiers() {
        assertEquals(ModelTier.FAST, ModelInfo.tier("claude-haiku-5-5"))
        assertEquals(ModelTier.FAST, ModelInfo.tier("gemini-3.8-flash"))
        assertEquals(ModelTier.BALANCED, ModelInfo.tier("claude-sonnet-5-5"))
        assertEquals(ModelTier.POWERFUL, ModelInfo.tier("gemini-3.1-pro-preview"))
        assertNull(ModelInfo.tier("gpt-5.6-luna"))
    }

    @Test fun tokenLabels() {
        assertEquals("~200K context", ModelInfo.contextLabel(800_000))
        assertEquals("~1M context", ModelInfo.contextLabel(4_000_000))
        assertEquals("1.5K", ModelInfo.formatTokens(1500))
    }
}
