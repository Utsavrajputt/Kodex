package com.invictus.kodex.ai.usage

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Requests + estimated tokens in one period. */
data class Bucket(val requests: Int = 0, val inputTokens: Long = 0, val outputTokens: Long = 0) {
    val tokens: Long get() = inputTokens + outputTokens
    operator fun plus(o: Bucket) = Bucket(requests + o.requests, inputTokens + o.inputTokens, outputTokens + o.outputTokens)
}

/** Counters for one provider+model. [dayKey]/[monthKey] say which period the buckets belong to. */
data class UsageRecord(
    val dayKey: String,
    val day: Bucket,
    val monthKey: String,
    val month: Bucket,
)

/** What the UI shows for one provider+model right now (stale periods already rolled to zero). */
data class UsageView(
    val day: Bucket,
    val month: Bucket,
    val dailyLimit: Int?,
    val limitIsCustom: Boolean,
    val resetAtMillis: Long?,
) {
    /** 0..1+ share of the daily request limit used, or null when no limit is known. */
    val dailyFraction: Float? get() = dailyLimit?.takeIf { it > 0 }?.let { day.requests.toFloat() / it }
}

/**
 * Pure bookkeeping for the AI usage card (no Android, no I/O, so it is unit-tested).
 *
 * A "day" follows the provider's own quota clock: Gemini resets its daily quota at midnight Pacific time,
 * everything else uses the device's local midnight. A "month" is the local calendar month.
 */
object UsageLedger {
    private val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles")

    fun key(provider: String, model: String) = "$provider|$model"

    fun quotaZone(provider: String): ZoneId = if (provider == "gemini") PACIFIC else ZoneId.systemDefault()

    fun dayKey(provider: String, nowMillis: Long, zone: ZoneId = quotaZone(provider)): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone).toLocalDate().toString()

    fun monthKey(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone).let { "%04d-%02d".format(it.year, it.monthValue) }

    /** Epoch millis of the next quota reset (next midnight on the provider's clock). */
    fun nextResetMillis(provider: String, nowMillis: Long, zone: ZoneId = quotaZone(provider)): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
            .truncatedTo(ChronoUnit.DAYS).plusDays(1).toInstant().toEpochMilli()

    /** Returns [rec] with any period that has ended replaced by an empty one. */
    fun rolled(
        rec: UsageRecord?, provider: String, nowMillis: Long,
        monthZone: ZoneId = ZoneId.systemDefault(), dayZone: ZoneId = quotaZone(provider),
    ): UsageRecord {
        val d = dayKey(provider, nowMillis, dayZone)
        val m = monthKey(nowMillis, monthZone)
        return UsageRecord(
            dayKey = d, day = if (rec?.dayKey == d) rec.day else Bucket(),
            monthKey = m, month = if (rec?.monthKey == m) rec.month else Bucket(),
        )
    }

    fun record(
        map: Map<String, UsageRecord>, provider: String, model: String,
        inputTokens: Int, outputTokens: Int, nowMillis: Long,
        monthZone: ZoneId = ZoneId.systemDefault(), dayZone: ZoneId = quotaZone(provider),
    ): Map<String, UsageRecord> {
        val k = key(provider, model)
        val cur = rolled(map[k], provider, nowMillis, monthZone, dayZone)
        val add = Bucket(1, inputTokens.toLong().coerceAtLeast(0), outputTokens.toLong().coerceAtLeast(0))
        return map + (k to cur.copy(day = cur.day + add, month = cur.month + add))
    }

    fun view(
        map: Map<String, UsageRecord>, overrides: Map<String, Int>,
        provider: String, model: String, nowMillis: Long,
        monthZone: ZoneId = ZoneId.systemDefault(), dayZone: ZoneId = quotaZone(provider),
    ): UsageView {
        val k = key(provider, model)
        val rec = rolled(map[k], provider, nowMillis, monthZone, dayZone)
        val custom = overrides[k]?.takeIf { it > 0 }
        val limit = custom ?: UsageLimits.defaultDailyRequests(provider, model)
        return UsageView(
            day = rec.day, month = rec.month,
            dailyLimit = limit, limitIsCustom = custom != null,
            resetAtMillis = if (limit != null) nextResetMillis(provider, nowMillis, dayZone) else null,
        )
    }

    /** "4h 20m", "35m", "<1m". */
    fun formatRemaining(millis: Long): String {
        val mins = (millis / 60_000).coerceAtLeast(0)
        return when {
            mins < 1 -> "<1m"
            mins < 60 -> "${mins}m"
            else -> "${mins / 60}h ${mins % 60}m"
        }
    }
}

/**
 * Daily request caps are the limit that matters on Gemini's free tier (it has no daily token cap, only
 * per-minute ones). These numbers are a rough guide that Google changes without notice, so they are
 * kept in this one place and the user can override each one in the app. Paid providers get no default.
 */
object UsageLimits {
    fun defaultDailyRequests(provider: String, model: String): Int? {
        if (provider != "gemini") return null
        val m = model.lowercase()
        return when {
            "flash-lite" in m -> 1000
            "flash" in m -> 250
            "pro" in m -> 100
            else -> null
        }
    }
}
