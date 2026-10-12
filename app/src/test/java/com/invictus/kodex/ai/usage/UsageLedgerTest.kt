package com.invictus.kodex.ai.usage

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLedgerTest {
    private val utc = ZoneId.of("UTC")
    private val pacific = ZoneId.of("America/Los_Angeles")

    private fun at(z: ZoneId, y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, z).toInstant().toEpochMilli()

    private fun rec(map: Map<String, UsageRecord>, now: Long, i: Int = 100, o: Int = 50) =
        UsageLedger.record(map, "gemini", "gemini-3.8-flash", i, o, now, monthZone = utc, dayZone = pacific)

    @Test fun accumulatesWithinTheSameDayAndMonth() {
        val t = at(pacific, 2026, 10, 12)
        val m = rec(rec(emptyMap(), t), t + 60_000)
        val v = UsageLedger.view(m, emptyMap(), "gemini", "gemini-3.8-flash", t + 120_000, utc, pacific)
        assertEquals(2, v.day.requests)
        assertEquals(300L, v.day.tokens)
        assertEquals(2, v.month.requests)
    }

    @Test fun newDayResetsDayButKeepsMonth() {
        val m = rec(emptyMap(), at(pacific, 2026, 10, 12))
        val v = UsageLedger.view(m, emptyMap(), "gemini", "gemini-3.8-flash", at(pacific, 2026, 10, 13), utc, pacific)
        assertEquals(0, v.day.requests)
        assertEquals(1, v.month.requests)
    }

    @Test fun newMonthResetsBoth() {
        val m = rec(emptyMap(), at(utc, 2026, 10, 31))
        val v = UsageLedger.view(m, emptyMap(), "gemini", "gemini-3.8-flash", at(utc, 2026, 11, 2), utc, pacific)
        assertEquals(0, v.month.requests)
        assertEquals(0, v.day.requests)
    }

    @Test fun geminiDayFollowsPacificMidnight() {
        // 23:30 Pacific and 00:30 Pacific the next day are 1h apart but land on different quota days.
        val a = UsageLedger.dayKey("gemini", at(pacific, 2026, 10, 12, 23, 30), pacific)
        val b = UsageLedger.dayKey("gemini", at(pacific, 2026, 10, 13, 0, 30), pacific)
        assertEquals("2026-10-12", a)
        assertEquals("2026-10-13", b)
    }

    @Test fun nextResetIsNextMidnightOnProviderClock() {
        val now = at(pacific, 2026, 10, 12, 22, 0)
        assertEquals(at(pacific, 2026, 10, 13, 0, 0), UsageLedger.nextResetMillis("gemini", now, pacific))
    }

    @Test fun overrideBeatsDefaultAndGeminiFlashHasDefault() {
        val t = at(pacific, 2026, 10, 12)
        val def = UsageLedger.view(emptyMap(), emptyMap(), "gemini", "gemini-3.8-flash", t, utc, pacific)
        assertEquals(250, def.dailyLimit)
        assertEquals(false, def.limitIsCustom)
        val custom = UsageLedger.view(emptyMap(), mapOf("gemini|gemini-3.8-flash" to 1500), "gemini", "gemini-3.8-flash", t, utc, pacific)
        assertEquals(1500, custom.dailyLimit)
        assertTrue(custom.limitIsCustom)
    }

    @Test fun paidProvidersHaveNoDefaultLimitAndNoReset() {
        val v = UsageLedger.view(emptyMap(), emptyMap(), "claude", "claude-sonnet", 0L, utc, utc)
        assertNull(v.dailyLimit)
        assertNull(v.dailyFraction)
        assertNull(v.resetAtMillis)
    }

    @Test fun fractionAndLimitTiers() {
        assertEquals(1000, UsageLimits.defaultDailyRequests("gemini", "gemini-3.1-flash-lite"))
        assertEquals(100, UsageLimits.defaultDailyRequests("gemini", "gemini-3.1-pro-preview"))
        assertNull(UsageLimits.defaultDailyRequests("gemini", "gemini-embedding"))
        val t = at(pacific, 2026, 10, 12)
        var m: Map<String, UsageRecord> = emptyMap()
        repeat(200) { m = rec(m, t) }
        val v = UsageLedger.view(m, emptyMap(), "gemini", "gemini-3.8-flash", t, utc, pacific)
        assertEquals(0.8f, v.dailyFraction!!, 0.001f)
    }

    @Test fun formatRemaining() {
        assertEquals("<1m", UsageLedger.formatRemaining(30_000))
        assertEquals("35m", UsageLedger.formatRemaining(35 * 60_000L))
        assertEquals("4h 20m", UsageLedger.formatRemaining((4 * 60 + 20) * 60_000L))
        assertEquals("<1m", UsageLedger.formatRemaining(-5))
    }

    @Test fun jsonRoundTrip() {
        val t = at(pacific, 2026, 10, 12)
        val m = rec(emptyMap(), t)
        assertEquals(m, UsageStore.decodeLedger(UsageStore.encodeLedger(m)))
        assertEquals(mapOf("a|b" to 5), UsageStore.decodeLimits(UsageStore.encodeLimits(mapOf("a|b" to 5, "c|d" to 0))))
        assertTrue(UsageStore.decodeLedger("not json").isEmpty())
    }
}
