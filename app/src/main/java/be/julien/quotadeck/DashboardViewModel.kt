package be.julien.quotadeck

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import be.julien.quotadeck.auth.*
import be.julien.quotadeck.core.*
import be.julien.quotadeck.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class LoginState(val provider: ProviderId, val stage: String = "choose", val error: String? = null, val url: String? = null, val device: DeviceLogin? = null)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureStore(application)
    private val service = ProviderService()
    private val oauth = OAuthService()
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
            state(id) { it.copy(loading = true, error = null) }
            try {
                var credential = store.credential(id) ?: return@launch
                if (credential.expiresAt > 0 && credential.expiresAt < System.currentTimeMillis() + 60_000) {
                    credential = oauth.refresh(id, credential)
                    store.save(id, credential) // Save rotating refresh token even if quota retrieval fails later.
                }
                val result = try { service.fetch(id, credential) } catch (e: ProviderException) {
                    if (!e.needsLogin || credential.refreshToken.isBlank()) throw e
                    credential = oauth.refresh(id, credential)
                    store.save(id, credential)
                    service.fetch(id, credential)
                }
                ensureActive()
                store.save(id, result.first); store.save(result.second)
                state(id) { it.copy(connected = true, snapshot = result.second, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state(id) { it.copy(error = userMessage(e)) } }
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
            try {
                when (id) {
                    ProviderId.CLAUDE, ProviderId.OPENCODE -> _login.value = LoginState(id, "web")
                    ProviderId.CODEX -> {
                        _login.value = LoginState(id, "working")
                        val device = oauth.startCodex()
                        _login.value = LoginState(id, "device", device = device, url = "https://auth.openai.com/codex/device")
                        val credential = oauth.finishCodex(device)
                        saveConnection(id, credential)
                    }
                    ProviderId.ANTIGRAVITY -> {
                        _login.value = LoginState(id, "working")
                        withContext(Dispatchers.IO) { LoopbackReceiver() }.use { receiver ->
                            val verifier = OAuthConfig.random(); val state = OAuthConfig.random()
                            val url = oauth.googleUrl(receiver.redirectUri, state, verifier)
                            _login.value = LoginState(id, "google", url = url)
                            openUrl(url)
                            val code = receiver.receive(state)
                            val credential = oauth.finishGoogle(code, receiver.redirectUri, verifier)
                            saveConnection(id, credential)
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _login.value = LoginState(id, error = userMessage(e)) }
        }
    }
    fun browserCredential(cookie: String) {
        val id = _login.value?.provider ?: return
        if (id != ProviderId.CLAUDE && id != ProviderId.OPENCODE) return
        if (cookie.isBlank() || cookie.contains('\n') || cookie.contains('\r')) {
            _login.value = LoginState(id, "web", error = "Aucune session détectée. Terminez la connexion chez le fournisseur."); return
        }
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            _login.value = LoginState(id, "working")
            try { saveConnection(id, Credential(sessionCookie = cookie)) }
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
            try { saveConnection(ProviderId.OPENCODE, Credential(accessToken = key)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _login.value = LoginState(ProviderId.OPENCODE, error = userMessage(e)) }
        }
    }
    private suspend fun saveConnection(id: ProviderId, credential: Credential) {
        // Keep the established session even when a provider temporarily refuses quota access.
        // The error stays explicit: connected never means quota successfully read.
        jobs.remove(id)?.cancelAndJoin()
        store.save(id, credential)
        store.snapshot(id)?.let { store.forget(id); store.save(id, credential) }
        state(id) { ProviderState(id, connected = true, loading = true) }
        _login.value = null
        refresh(id)
    }
    private fun userMessage(e: Exception): String = if (e is ProviderException) e.message ?: "Erreur du fournisseur." else "Connexion interrompue ou format inattendu. Réessayez ou consultez la page officielle."
}
