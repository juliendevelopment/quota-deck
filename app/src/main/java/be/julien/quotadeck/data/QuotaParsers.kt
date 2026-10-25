package be.julien.quotadeck.data

import be.julien.quotadeck.core.*
import kotlinx.serialization.json.*
import java.time.Instant

/** Provider responses are intentionally parsed defensively: absent != 0 and absent != unlimited. */
object QuotaParsers {
    private fun percent(value: Double?): Double? = value?.takeIf { it.isFinite() && it in 0.0..100.0 }
    private fun remaining(used: Double?): Double? = percent(used)?.let { 100.0 - it }
    fun timestamp(value: String?): String? {
        if (value == null) return null
        return runCatching { Instant.parse(value).toString() }.getOrNull() ?: value.toDoubleOrNull()?.let {
            if (!it.isFinite() || it < 0) null else runCatching { Instant.ofEpochMilli(if (it > 100_000_000_000) it.toLong() else (it * 1000).toLong()).toString() }.getOrNull()
        }
    }
    fun claude(data: JsonElement): ProviderSnapshot {
        if (data !is JsonObject) throw ProviderException("Unexpected Claude quota response format.")
        val windows = buildList {
            data.forEach { (key, value) ->
                if (value is JsonObject && (key == "five_hour" || key.startsWith("seven_day"))) {
                    val label = when (key) {
                        "five_hour" -> "Session · 5 hours"
                        "seven_day" -> "Week · all models"
                        else -> "Week · " + key.removePrefix("seven_day_").replace('_', ' ')
                    }
                    add(QuotaWindow(label, remaining(value.number("utilization")), timestamp(value.text("resets_at"))))
                }
            }
            data.arr("limits")?.forEach { value ->
                val model = value.obj("scope")?.obj("model")?.text("display_name") ?: value.text("display_name") ?: "Additional limit"
                add(QuotaWindow(model, remaining(value.number("utilization")), timestamp(value.text("resets_at"))))
            }
            data.obj("extra_usage")?.takeIf { it.flag("is_enabled") == true }?.let {
                add(QuotaWindow("Extra usage", remaining(it.number("utilization")), detail = "Extra usage is enabled in Claude; this app does not change that setting."))
            }
        }
        if (windows.isEmpty()) throw ProviderException("Claude did not report any quota windows for this account.")
        return ProviderSnapshot(ProviderId.CLAUDE, windows = windows)
    }
    fun codex(data: JsonElement): ProviderSnapshot {
        val windows = buildList {
            fun append(limits: JsonObject?, prefix: String = "") {
                listOf("primary_window" to "Session", "secondary_window" to "Week").forEach { (key, fallback) ->
                    limits?.obj(key)?.let { lane ->
                        val duration = lane.long("limit_window_seconds")
                        val label = when (duration) { 18000L -> "Session · 5 hours"; 604800L -> "Week"; else -> fallback }
                        add(QuotaWindow(prefix + label, remaining(lane.number("used_percent")), timestamp(lane.text("reset_at"))))
                    }
                }
            }
            append(data.obj("rate_limit"))
            data.arr("additional_rate_limits")?.forEach { item -> append(item.obj("rate_limit"), (item.text("limit_name") ?: item.text("metered_feature") ?: "Other") + " · ") }
            data.obj("code_review_rate_limit")?.let { append(it, "Code review · ") }
            data.obj("credits")?.let { credits ->
                credits.text("balance")?.let { balance -> add(QuotaWindow("Credits", detail = "Reported balance: $balance")) }
            }
        }
        if (windows.isEmpty()) throw ProviderException("No Codex quotas were reported for this account.")
        return ProviderSnapshot(ProviderId.CODEX, plan = data.text("plan_type"), windows = windows)
    }
    fun antigravitySummary(data: JsonElement): List<QuotaWindow> {
        val root = data.obj("response") ?: data.obj("summary") ?: data
        return root.arr("groups")?.flatMap { group ->
            group.arr("buckets")?.map { bucket ->
                val nested = bucket.obj("remaining")
                val fraction = bucket.number("remainingFraction") ?: nested?.number("remainingFraction") ?:
                    nested?.takeIf { it.text("case") == "remainingFraction" }?.number("value")
                QuotaWindow(
                    listOfNotNull(group.text("displayName") ?: group.text("name"), bucket.text("displayName") ?: bucket.text("name") ?: bucket.text("bucketId")).joinToString(" · ").ifBlank { "Quota" },
                    if (bucket.flag("disabled") == true) null else fraction?.times(100)?.let(::percent),
                    timestamp(bucket.text("resetTime")),
                    if (bucket.flag("disabled") == true) "Not available on this plan" else bucket.text("description"),
                )
            } ?: emptyList()
        } ?: emptyList()
    }
    fun antigravityModels(data: JsonElement): List<QuotaWindow> {
        return data.obj("models")?.mapNotNull { (id, model) ->
            val quota = model.obj("quotaInfo") ?: return@mapNotNull null
            QuotaWindow(model.text("displayName") ?: id, quota.number("remainingFraction")?.times(100)?.let(::percent), timestamp(quota.text("resetTime")))
        } ?: emptyList()
    }
    fun opencode(data: JsonElement, apiKey: Boolean = false, now: Instant = Instant.now()): ProviderSnapshot {
        val access = data.obj("access")
        val meters = if (apiKey) data.obj("usage") else access?.obj("meters")
        val keys = if (apiKey) listOf("rolling" to "Session · 5 hours", "weekly" to "Week", "monthly" to "Month")
            else listOf("fiveHour" to "Session · 5 hours", "week" to "Week", "month" to "Month")
        val windows = keys.mapNotNull { (key, label) ->
            val meter = meters?.obj(key) ?: return@mapNotNull null
            val used = listOf("usedMicroCents", "used", "usage", "consumed", "count", "usedTokens").firstNotNullOfOrNull { meter.number(it) }
            val limit = listOf("limitMicroCents", "limit", "total", "quota", "max", "cap", "tokenLimit").firstNotNullOfOrNull { meter.number(it) }
            val direct = listOf("usagePercent", "usedPercent", "percentUsed", "percent", "usage_percent", "used_percent", "utilization").firstNotNullOfOrNull { meter.number(it) }
            // The public API's direct values are already percentages (0.5 means 0.5%, not 50%).
            val usedPercent = if (used != null && limit != null && limit > 0) used / limit * 100 else direct
            val reset = listOf("resetsAt", "resetAt", "reset_at", "resets_at").firstNotNullOfOrNull { timestamp(meter.text(it)) }
                ?: listOf("resetInSec", "resetInSeconds", "reset_in_sec").firstNotNullOfOrNull { meter.long(it) }?.takeIf { it >= 0 }?.let { now.plusSeconds(it).toString() }
                ?: if (key == "month" || key == "monthly") timestamp(access?.text("endsAt")) else null
            QuotaWindow(label, remaining(usedPercent), reset)
        }
        if (windows.isEmpty()) throw ProviderException("No Go quotas were reported. Check your subscription and workspace in OpenCode.")
        return ProviderSnapshot(ProviderId.OPENCODE, plan = "Go", windows = windows)
    }
}
