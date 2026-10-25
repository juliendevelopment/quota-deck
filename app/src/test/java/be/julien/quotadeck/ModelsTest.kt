package be.julien.quotadeck

import be.julien.quotadeck.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ModelsTest {
    @Test fun providersAreComplete() { assertEquals(4, ProviderId.entries.size) }
    @Test fun missingPercentageIsNotZero() { assertNull(QuotaWindow("Unavailable").remainingPercent) }
    @Test(expected = IllegalArgumentException::class) fun rejectsImpossibleQuota() { QuotaWindow("Test", 101.0) }
    @Test(expected = IllegalArgumentException::class) fun rejectsNanQuota() { QuotaWindow("Test", Double.NaN) }
    @Test fun resetFormatting() {
        val now = Instant.parse("2026-10-23T10:00:00Z")
        assertEquals("Resets in 2h 30m", resetLabel("2026-10-23T12:30:00Z", now))
        assertEquals("Reset pending", resetLabel("2026-10-23T09:00:00Z", now))
        assertNull(resetLabel("invalid", now))
        assertNull(resetLabel(null, now))
    }
}
