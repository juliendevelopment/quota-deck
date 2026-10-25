package be.julien.quotadeck.core

/** Display-only compatibility for snapshots saved before the English UI. Never rewrites credentials or user names. */
private val legacyText = mapOf(
    "Session · 5 heures" to "Session · 5 hours",
    "Semaine · tous les modèles" to "Week · all models",
    "Semaine" to "Week", "Mois" to "Month", "Crédits" to "Credits",
    "Limite supplémentaire" to "Additional limit", "Usage supplémentaire" to "Extra usage",
    "Non disponible pour cet abonnement" to "Not available on this plan",
    "Dépassements activés chez Claude — cette app ne les modifie pas." to "Extra usage is enabled in Claude; this app does not change that setting.",
    "Google renvoie des valeurs maximales non vérifiables : consultez la page officielle." to "Google returned unverified maximum values. Check the official page.",
)

private fun legacyLabel(value: String): String {
    legacyText[value]?.let { return it }
    for ((old, english) in listOf("Semaine · " to "Week · ", "Revue · " to "Code review · ", "Autre · " to "Other · ")) {
        if (value.startsWith(old)) return english + legacyLabel(value.removePrefix(old))
    }
    for ((old, english) in listOf(" · Session · 5 heures" to " · Session · 5 hours", " · Semaine" to " · Week")) {
        if (value.endsWith(old)) return value.removeSuffix(old) + english
    }
    return value
}

private fun legacyDetail(value: String?): String? = value?.let {
    when {
        it.startsWith("Solde communiqué : ") -> "Reported balance: " + it.removePrefix("Solde communiqué : ")
        it.startsWith("Espace Go : ") -> "Go workspace: " + it.removePrefix("Espace Go : ")
        else -> legacyText[it] ?: it
    }
}

fun ProviderSnapshot.toEnglishDisplay(): ProviderSnapshot = copy(
    windows = windows.map { it.copy(label = if (provider == ProviderId.ANTIGRAVITY) it.label else legacyLabel(it.label), detail = legacyDetail(it.detail)) },
    notice = legacyDetail(notice),
)
