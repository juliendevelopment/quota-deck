package be.julien.quotadeck

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import be.julien.quotadeck.auth.*
import be.julien.quotadeck.core.*
import be.julien.quotadeck.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class LoginState(val provider: ProviderId, val stage: String = "choose", val error: String? = null, val url: String? = null,
    val device: DeviceLogin? = null, val phase: AuthPhase = AuthPhase.PREPARATION, val diagnostic: String? = null)

class DashboardViewModel @JvmOverloads constructor(application: Application,
    private val store: AccountStore = SecureStore(application),
    private val service: ProviderReader = ProviderService(),
    private val oauth: OAuthService = OAuthService(),
) : AndroidViewModel(application) {
    private val uiActive = MutableStateFlow(true)
    fun setUiActive(active: Boolean) { uiActive.value = active }
    private val jobs = mutableMapOf<ProviderId, Job>()
    private var loginJob: Job? = null
    private val _states = MutableStateFlow(ProviderId.entries.map { ProviderState(it, store.credential(it) != null, snapshot = store.snapshot(it)) })
    val states = _states.asStateFlow()
    private val _login = MutableStateFlow<LoginState?>(null)
    val login = _login.asStateFlow()
    init { refreshAll() }
    private fun state(id: ProviderId, update: (ProviderState) -> ProviderState) { _states.update { rows -> rows.map { if (it.id == id) update(it) else it } } }
    fun refreshAll() { ProviderId.entries.forEach { if (store.credential(it) != null && jobs[it]?.isActive != true) refresh(it) } }
    private fun refresh(id: ProviderId) {
        jobs[id] = viewModelScope.launch {
            var phase = AuthPhase.LOAD_QUOTAS
            state(id) { it.copy(loading = true, error = null, diagnostic = null) }
            try {
                var credential = store.credential(id) ?: return@launch
                if (credential.expiresAt > 0 && credential.expiresAt < System.currentTimeMillis() + 60_000) {
                    phase = AuthPhase.REFRESH_TOKEN
                    credential = oauth.refresh(id, credential)
                    phase = AuthPhase.SAVE_SESSION
                    store.save(id, credential) // Save rotating refresh token even if quota retrieval fails later.
                }
                phase = AuthPhase.LOAD_QUOTAS
                val result = try { service.fetch(id, credential) } catch (e: ProviderException) {
                    if (!e.needsLogin || credential.refreshToken.isBlank()) throw e
                    phase = AuthPhase.REFRESH_TOKEN
                    credential = oauth.refresh(id, credential)
                    phase = AuthPhase.SAVE_SESSION
                    store.save(id, credential)
                    phase = AuthPhase.LOAD_QUOTAS
                    service.fetch(id, credential)
                }
                ensureActive()
                phase = AuthPhase.SAVE_SESSION
                store.save(id, result.first); store.save(result.second)
                state(id) { it.copy(connected = true, snapshot = result.second, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state(id) { it.copy(error = userMessage(e), diagnostic = diagnostic(id, phase, e)) } }
            finally { state(id) { it.copy(loading = false) } }
        }
    }
    fun disconnect(id: ProviderId) {
        jobs.remove(id)?.cancel()
        if (_login.value?.provider == id) cancelLogin()
        store.forget(id)
        state(id) { ProviderState(id) }
    }
    fun beginConnection(id: ProviderId) { cancelLogin(); _login.value = LoginState(id) }
    fun cancelLogin() { loginJob?.cancel(); loginJob = null; _login.value = null }
    fun startConnection(openUrl: (String) -> Unit) {
        val id = _login.value?.provider ?: return
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            var phase = AuthPhase.PREPARATION
            try {
                when (id) {
                    ProviderId.CLAUDE -> {
                        _login.value = LoginState(id, "working")
                        withContext(Dispatchers.IO) { LoopbackReceiver("/callback", "localhost") }.use { receiver ->
                            val verifier = OAuthConfig.random(); val state = OAuthConfig.random()
                            val url = oauth.claudeUrl(receiver.redirectUri, state, verifier)
                            phase = AuthPhase.BROWSER
                            _login.value = LoginState(id, "claude", url = url, phase = phase)
                            openUrl(url)
                            val code = receiver.receive(state)
                            phase = AuthPhase.CALLBACK_RECEIVED
                            awaitUiReturn(id)
                            phase = AuthPhase.TOKEN_EXCHANGE
                            _login.value = LoginState(id, "working", phase = phase)
                            val credential = oauth.finishClaude(code, receiver.redirectUri, state, verifier)
                            phase = AuthPhase.SAVE_SESSION
                            _login.value = LoginState(id, "working", phase = phase)
                            saveConnection(id, credential)
                        }
                    }
                    ProviderId.OPENCODE -> _login.value = LoginState(id, "choose")
                    ProviderId.CODEX -> {
                        _login.value = LoginState(id, "working")
                        val device = oauth.startCodex()
                        _login.value = LoginState(id, "device", device = device, url = "https://auth.openai.com/codex/device")
                        phase = AuthPhase.TOKEN_EXCHANGE
                        val credential = oauth.finishCodex(device)
                        phase = AuthPhase.SAVE_SESSION
                        saveConnection(id, credential)
                    }
                    ProviderId.ANTIGRAVITY -> {
                        _login.value = LoginState(id, "working")
                        withContext(Dispatchers.IO) { LoopbackReceiver() }.use { receiver ->
                            val verifier = OAuthConfig.random(); val state = OAuthConfig.random()
                            val url = oauth.googleUrl(receiver.redirectUri, state, verifier)
                            phase = AuthPhase.BROWSER
                            _login.value = LoginState(id, "google", url = url, phase = phase)
                            openUrl(url)
                            val code = receiver.receive(state)
                            phase = AuthPhase.CALLBACK_RECEIVED
                            awaitUiReturn(id)
                            phase = AuthPhase.TOKEN_EXCHANGE
                            _login.value = LoginState(id, "working", phase = phase)
                            val credential = oauth.finishGoogle(code, receiver.redirectUri, verifier)
                            phase = AuthPhase.SAVE_SESSION
                            _login.value = LoginState(id, "working", phase = phase)
                            saveConnection(id, credential)
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _login.value = errorState(id, phase, e) }
        }
    }
    private suspend fun awaitUiReturn(id: ProviderId) {
        _login.value = LoginState(id, "callback", phase = AuthPhase.CALLBACK_RECEIVED)
        val returned = withTimeoutOrNull(120_000) { uiActive.first { it }; true } ?: false
        if (!returned) throw ProviderException("Retour dans QuotaDeck attendu avant expiration. Relancez la connexion puis utilisez le bouton Revenir dans l’app.")
    }
    fun startEmailConnection() {
        val id = _login.value?.provider ?: return
        if (id != ProviderId.CLAUDE) return
        loginJob?.cancel()
        _login.value = LoginState(id, "web")
    }
    fun browserCredential(cookie: String, userAgent: String, snapshot: ProviderSnapshot?) {
        val id = _login.value?.provider ?: return
        if (id != ProviderId.CLAUDE && id != ProviderId.OPENCODE) return
        val allowedNames = if (id == ProviderId.CLAUDE) setOf("sessionKey") else setOf("auth", "__Host-auth", "__Host-console_session")
        val hasSession = cookie.split(';').any { pair -> pair.substringBefore('=').trim() in allowedNames && pair.substringAfter('=', "").isNotBlank() }
        if (!hasSession || cookie.contains('\n') || cookie.contains('\r')) {
            _login.value = LoginState(id, "web", error = "Aucune session détectée. Terminez la connexion chez le fournisseur."); return
        }
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            _login.value = LoginState(id, "working")
            try { saveConnection(id, Credential(sessionCookie = cookie, userAgent = userAgent), snapshot) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _login.value = LoginState(id, "web", error = userMessage(e)) }
        }
    }
    fun importApiKey(key: String) {
        if (_login.value?.provider != ProviderId.OPENCODE) return
        if (key.isBlank() || key.any { it.isWhitespace() }) { _login.value = LoginState(ProviderId.OPENCODE, error = "Clé invalide."); return }
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            _login.value = LoginState(ProviderId.OPENCODE, "working")
            try {
                val (credential, snapshot) = service.fetch(ProviderId.OPENCODE, Credential(accessToken = key))
                saveConnection(ProviderId.OPENCODE, credential, snapshot)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _login.value = LoginState(ProviderId.OPENCODE, error = userMessage(e)) }
        }
    }
    private suspend fun saveConnection(id: ProviderId, credential: Credential, snapshot: ProviderSnapshot? = null) {
        // Keep the established session even when a provider temporarily refuses quota access.
        // The error stays explicit: connected never means quota successfully read.
        jobs.remove(id)?.cancelAndJoin()
        store.forget(id)
        store.save(id, credential)
        if (snapshot != null) store.save(snapshot)
        state(id) { ProviderState(id, connected = true, snapshot = snapshot) }
        _login.value = null
        if (snapshot == null) refresh(id)
    }
    private fun diagnostic(id: ProviderId, phase: AuthPhase, e: Exception) = FailureReport.format(id, phase, e, BuildConfig.VERSION_NAME, android.os.Build.VERSION.SDK_INT)
    private fun errorState(id: ProviderId, phase: AuthPhase, e: Exception) = LoginState(id, error = userMessage(e), phase = phase, diagnostic = diagnostic(id, phase, e))
    private fun userMessage(e: Exception): String = if (e is ProviderException) e.message ?: "Erreur du fournisseur." else "Connexion interrompue ou format inattendu. Réessayez ou consultez la page officielle."
}
