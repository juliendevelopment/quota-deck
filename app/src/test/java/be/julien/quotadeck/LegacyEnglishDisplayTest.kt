package be.julien.quotadeck

import be.julien.quotadeck.core.*
import org.junit.Assert.*
import org.junit.Test

class LegacyEnglishDisplayTest {
    @Test fun oldCachedLabelsAreTranslatedWithoutLosingQuotaData() {
        val original = ProviderSnapshot(ProviderId.CODEX, plan = "plus", identity = "Mon espace",
            windows = listOf(QuotaWindow("Session · 5 heures", 45.0, "2026-10-25T14:00:00Z"),
                QuotaWindow("Revue · Semaine", null), QuotaWindow("Crédits", detail = "Solde communiqué : 0")), fetchedAt = 12345)
        val display = original.toEnglishDisplay()
        assertEquals(listOf("Session · 5 hours", "Code review · Week", "Credits"), display.windows.map { it.label })
        assertEquals("Reported balance: 0", display.windows.last().detail)
        assertEquals(original.identity, display.identity)
        assertEquals(original.fetchedAt, display.fetchedAt)
        assertEquals(original.windows.first().remainingPercent, display.windows.first().remainingPercent)
        assertEquals(original.windows.first().resetAt, display.windows.first().resetAt)
        assertNull(display.windows[1].remainingPercent)
        assertEquals(display, display.toEnglishDisplay())
    }
    @Test fun providerOwnedNamesAndUserWorkspaceNamesAreNotTranslated() {
        val snapshot = ProviderSnapshot(ProviderId.ANTIGRAVITY, windows = listOf(QuotaWindow("Semaine", 80.0)))
        assertEquals("Semaine", snapshot.toEnglishDisplay().windows.single().label)
        val workspace = ProviderSnapshot(ProviderId.OPENCODE, identity = "Espace privé", notice = "Espace Go : Espace privé.")
        assertEquals("Espace privé", workspace.toEnglishDisplay().identity)
        assertEquals("Go workspace: Espace privé.", workspace.toEnglishDisplay().notice)
    }
}
