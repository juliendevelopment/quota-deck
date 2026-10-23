package be.julien.quotadeck

import be.julien.quotadeck.core.*
import be.julien.quotadeck.data.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Synthetic protocol fixtures, never production/demo account data. */
class QuotaParsersTest {
    private fun json(text: String) = Json.parseToJsonElement(text)
    @Test fun claudeConvertsUsedIntoRemainingAndPreservesMissing() {
        val snapshot = QuotaParsers.claude(json("""{"five_hour":{"utilization":25,"resets_at":"2026-10-24T10:00:00Z"},"seven_day":{"utilization":null},"seven_day_sonnet":{"utilization":100}}"""))
        assertEquals(3, snapshot.windows.size)
        assertEquals(75.0, snapshot.windows[0].remainingPercent!!, 0.01)
        assertNull(snapshot.windows[1].remainingPercent)
        assertEquals(0.0, snapshot.windows[2].remainingPercent!!, 0.01)
        assertEquals("2026-10-24T10:00:00Z", snapshot.windows[0].resetAt)
    }
    @Test(expected = ProviderException::class) fun claudeRejectsAbsentQuota() { QuotaParsers.claude(json("{}")) }
    @Test fun codexUsesEpochAndNeverAssumesAnAbsentWeekIsFull() {
        val snapshot = QuotaParsers.codex(json("""{"plan_type":"plus","rate_limit":{"primary_window":{"used_percent":64,"reset_at":1792836000,"limit_window_seconds":18000}}}"""))
        assertEquals("plus", snapshot.plan)
        assertEquals(1, snapshot.windows.size)
        assertEquals(36.0, snapshot.windows.single().remainingPercent!!, 0.01)
        assertEquals(Instant.ofEpochSecond(1792836000).toString(), snapshot.windows.single().resetAt)
    }
    @Test fun codexMissingPercentageStaysUnknown() {
        val snapshot = QuotaParsers.codex(json("""{"rate_limit":{"primary_window":{}}}"""))
        assertNull(snapshot.windows.single().remainingPercent)
    }
    @Test(expected = ProviderException::class) fun codexRejectsAbsentQuota() { QuotaParsers.codex(json("{}")) }
    @Test fun antigravityParsesNestedSummaryAndDisabledLane() {
        val result = QuotaParsers.antigravitySummary(json("""{"response":{"groups":[{"displayName":"Pro","buckets":[{"bucketId":"model-a","displayName":"Gemini","remaining":{"case":"remainingFraction","value":0.42}},{"bucketId":"model-b","disabled":true,"remainingFraction":1}]}]}}"""))
        assertEquals(42.0, result[0].remainingPercent!!, 0.01)
        assertNull(result[1].remainingPercent)
    }
    @Test fun antigravityMissingFractionDoesNotBecomeFullOrEmpty() {
        val result = QuotaParsers.antigravityModels(json("""{"models":{"model-a":{"displayName":"Gemini","quotaInfo":{"resetTime":"2026-10-24T10:00:00Z"}}}}"""))
        assertNull(result.single().remainingPercent)
    }
    @Test fun openCodeConsoleReadsMicroCentsAndBillingReset() {
        val snapshot = QuotaParsers.opencode(json("""{"access":{"endsAt":"2026-11-01T00:00:00Z","meters":{"fiveHour":{"usedMicroCents":30,"limitMicroCents":100},"week":{"usedMicroCents":150,"limitMicroCents":200},"month":{"usedMicroCents":100,"limitMicroCents":1000}}}}"""))
        assertEquals(listOf(70.0,25.0,90.0),snapshot.windows.map { it.remainingPercent })
        assertEquals("2026-11-01T00:00:00Z", snapshot.windows.last().resetAt)
    }
    @Test fun openCodeApiLowPercentIsNotMistakenForFraction() {
        val snapshot = QuotaParsers.opencode(json("""{"usage":{"rolling":{"usagePercent":0.5,"resetInSec":60}}}"""), true, Instant.parse("2026-10-23T10:00:00Z"))
        assertEquals(99.5, snapshot.windows.single().remainingPercent!!, 0.01)
        assertEquals("2026-10-23T10:01:00Z", snapshot.windows.single().resetAt)
    }
    @Test fun invalidNumbersStayUnknown() {
        val snapshot = QuotaParsers.opencode(json("""{"usage":{"rolling":{"usagePercent":-10}}}"""), true)
        assertNull(snapshot.windows.single().remainingPercent)
    }
    @Test(expected = ProviderException::class) fun openCodeRejectsAbsentSubscription() { QuotaParsers.opencode(json("{\"access\":null}")) }
    @Test fun timestampSupportsSecondsMillisAndIso() {
        assertEquals(QuotaParsers.timestamp("1792836000"), QuotaParsers.timestamp("1792836000000"))
        assertNull(QuotaParsers.timestamp("bad"))
    }
}
