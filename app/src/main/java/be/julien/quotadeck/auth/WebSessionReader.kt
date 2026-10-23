package be.julien.quotadeck.auth

import android.net.Uri
import android.webkit.WebView
import be.julien.quotadeck.core.*
import be.julien.quotadeck.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

/** Reads the provider JSON in its own authenticated page, without any JavascriptInterface bridge. */
object WebSessionReader {
    suspend fun read(view: WebView, id: ProviderId): ProviderSnapshot = withContext(Dispatchers.Main) {
        val expected = if (id == ProviderId.CLAUDE) "claude.ai" else "opencode.ai"
        if (Uri.parse(view.url).host != expected) throw ProviderException("Terminez la connexion sur $expected avant de continuer.")
        val key = "__quotaDeck" + java.util.UUID.randomUUID().toString().replace("-", "")
        val task = if (id == ProviderId.CLAUDE) """
            const orgs = await get('/api/organizations');
            if (!Array.isArray(orgs)) throw new Error('organizations');
            const org = orgs.find(x => Array.isArray(x.capabilities) && x.capabilities.includes('chat')) || orgs[0];
            if (!org || !org.uuid) throw new Error('organization');
            const data = await get('/api/organizations/' + encodeURIComponent(org.uuid) + '/usage');
            return {data, plan: org.rate_limit_tier};
        """ else """
            const orgs = await get('/console/api/orgs');
            if (!Array.isArray(orgs)) throw new Error('organizations');
            for (const org of orgs.slice(0, 10)) {
                if (!/^(org_|wrk_)[A-Za-z0-9_-]+${'$'}/.test(org.id || '')) continue;
                try {
                    const data = await get('/console/api/go/status', {'x-org-id': org.id});
                    if (data.access && data.access.meters && data.access.meters.fiveHour) return {data, name: org.name};
                } catch (_) { }
            }
            throw new Error('subscription');
        """
        val script = """
            (() => {
                window['$key'] = null;
                const get = async (url, headers = {}) => {
                    const r = await fetch(url, {credentials: 'include', headers: {...headers, 'Accept': 'application/json'}});
                    if (!r.ok) throw new Error('http');
                    return await r.json();
                };
                (async () => { $task })().then(value => { window['$key'] = JSON.stringify(value); })
                    .catch(() => { window['$key'] = JSON.stringify({error: true}); });
            })();
        """
        evaluate(view, script)
        try {
            withTimeout(30_000) {
                while (true) {
                    val result = evaluate(view, "window['$key'] || null")
                    val payload = (runCatching { Json.parseToJsonElement(result) }.getOrNull() as? JsonPrimitive)?.contentOrNull
                    if (payload != null) {
                        val data = Json.parseToJsonElement(payload)
                        if (data.flag("error") == true) throw ProviderException("Le fournisseur n’a pas rendu ses quotas accessibles dans cette session.")
                        val usage = data.obj("data") ?: throw ProviderException("Quotas non communiqués.")
                        return@withTimeout if (id == ProviderId.CLAUDE) QuotaParsers.claude(usage).copy(plan = data.text("plan"))
                            else QuotaParsers.opencode(usage).copy(identity = data.text("name"))
                    }
                    delay(300)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
        } finally { view.evaluateJavascript("delete window['$key']", null) }
    }
    private suspend fun evaluate(view: WebView, script: String): String = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it ?: "null") }
    }
}
