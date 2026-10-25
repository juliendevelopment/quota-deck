package be.julien.quotadeck

import be.julien.quotadeck.core.*
import be.julien.quotadeck.data.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class EnglishLocalizationTest {
    @Test fun resetLabelsAreEnglish() {
        assertEquals("Resets in 2h 30m", resetLabel("2026-10-25T12:30:00Z", Instant.parse("2026-10-25T10:00:00Z")))
        assertEquals("Reset pending", resetLabel("2026-10-25T09:00:00Z", Instant.parse("2026-10-25T10:00:00Z")))
    }
    @Test fun providerQuotaLabelsAreEnglish() {
        val snapshot = QuotaParsers.claude(Json.parseToJsonElement("""{"five_hour":{"utilization":25},"seven_day":{"utilization":50}}"""))
        assertEquals(listOf("Session · 5 hours", "Week · all models"), snapshot.windows.map { it.label })
    }
    @Test fun supportDiagnosticsAreEnglishAndKeepMachineIdentifiers() {
        val report = FailureReport.format(ProviderId.CODEX, AuthPhase.DEVICE_POLL, ProviderException("fixture"), "0.1.4", 34)
        assertTrue(report.contains("Provider: CODEX"))
        assertTrue(report.contains("Stage: DEVICE_POLL"))
        assertTrue(report.contains("Network:"))
    }
}
