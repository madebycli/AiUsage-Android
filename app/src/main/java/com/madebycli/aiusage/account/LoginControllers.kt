package com.madebycli.aiusage.account

import android.app.Application
import android.util.Log
import com.madebycli.aiusage.LoginState
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.SignedIn
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import java.time.Instant

const val CODEX_DEVICE_PAGE = "https://auth.openai.com/codex/device"

internal fun MutableStateFlow<UiState>.updateLogin(provider: String, change: LoginState.() -> LoginState) =
    update { it.copy(logins = it.logins + (provider to it.login(provider).change())) }

/** Marks an open attempt as no longer busy; a closed attempt stays closed. */
internal fun MutableStateFlow<UiState>.idleLogin(provider: String) =
    update { if (it.login(provider).open) it.copy(logins = it.logins + (provider to it.login(provider).copy(busy = false))) else it }

internal fun MutableStateFlow<UiState>.closeLogin(provider: String) = update { it.copy(logins = it.logins - provider) }

private val signInCounter = java.util.concurrent.atomic.AtomicLong()
internal fun MutableStateFlow<UiState>.finishSignIn(provider: String, account: Account) =
    update { it.copy(logins = it.logins - provider, signedIn = SignedIn(account.id, signInCounter.incrementAndGet())) }

/**
 * Codex sign-in with the OpenAI device code. The attempt lives in its own staging store, so a failed
 * or abandoned attempt never touches an existing account. A finished attempt becomes a new account,
 * or renews the account it belongs to.
 */
class CodexLogin(
    private val app: Application,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    http: HttpTransport,
    private val accounts: AccountsController
) {
    private val staging = SecureStore(app, AccountRegistry.stagingNamespace(Providers.CODEX))
    private val auth = CodexAuth(http, staging)
    private val generation = SessionGeneration()
    private var job: Job? = null
    private val login get() = state.value.login(Providers.CODEX)

    /** Continues an attempt that survived a restart of the app. */
    fun resume() = scope.launch(Dispatchers.IO) {
        val pending = runCatching { staging.readDeviceLogin() }.getOrNull()?.takeIf { it.expiresAt > Instant.now().epochSecond }
        if (pending == null) { runCatching { staging.clearDeviceLogin() }; return@launch }
        if (job?.isActive != true) begin(pending, pending.replaceId)
    }

    fun start(replaceId: String? = null) {
        if (job?.isActive == true) return
        begin(null, replaceId)
    }

    private fun begin(resume: PendingDeviceLogin?, replaceId: String?) {
        val gen = generation.advance()
        state.updateLogin(Providers.CODEX) { LoginState(open = true, busy = true, replaceAccountId = replaceId) }
        val previous = job
        job = scope.launch(Dispatchers.IO) {
            previous?.join()
            run(gen, resume, replaceId)
        }
    }

    private suspend fun run(gen: Long, resume: PendingDeviceLogin?, replaceId: String?) {
        fun edit(change: LoginState.() -> LoginState) { if (generation.isCurrent(gen)) state.updateLogin(Providers.CODEX, change) }
        try {
            val pending = resume ?: PendingDeviceLogin(auth.begin(), Instant.now().epochSecond + DEVICE_CODE_LIFETIME_SECONDS, replaceId)
                .also { staging.writeDeviceLogin(it) }
            edit { copy(phase = LoginPhase.WAITING, url = CODEX_DEVICE_PAGE, deviceCode = pending.device.code) }
            auth.complete(pending.device,
                onRetry = { error ->
                    Log.w(TAG, diagnosticFailure("login_poll_retry", error))
                    edit { copy(hint = "Verbindung kurz unterbrochen. Die App versucht es mit demselben Code erneut.") }
                },
                onPhase = { phase -> edit { copy(phase = phase, hint = null, deviceCode = if (phase == LoginPhase.WAITING) deviceCode else null) } },
                timeoutMillis = ((pending.expiresAt - Instant.now().epochSecond) * 1000).coerceAtLeast(1_000))
            runCatching { staging.clearDeviceLogin() }
            currentCoroutineContext().ensureActive()
            if (!generation.isCurrent(gen)) { runCatching { staging.clear() }; return }
            val tokens = staging.readTokens() ?: throw IllegalStateException()
            val account = accounts.attach(Providers.CODEX, staging, tokens.accountId?.let { "codex:$it" }, tokens.email, replaceId)
            if (generation.isCurrent(gen)) state.finishSignIn(Providers.CODEX, account)
        } catch (e: TimeoutCancellationException) {
            runCatching { staging.clearDeviceLogin() }
            edit { copy(busy = false, url = null, deviceCode = null, error = "Der Anmeldecode ist abgelaufen. Bitte einen neuen anfordern.") }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val phase = login.phase
            Log.w(TAG, diagnosticFailure("login_${phase.name}", e))
            runCatching { staging.clearDeviceLogin() }
            edit { copy(busy = false, url = null, deviceCode = null, error = loginFailure(phase, e)) }
        } finally {
            if (generation.isCurrent(gen)) state.idleLogin(Providers.CODEX)
        }
    }

    fun cancel() {
        generation.advance()
        job?.cancel()
        state.closeLogin(Providers.CODEX)
        scope.launch(Dispatchers.IO) { runCatching { staging.clearDeviceLogin() } }
    }

    private companion object { const val TAG = "AiUsage" }
}

/** Claude Code sign-in: browser PKCE with a pasted code, or credentials copied from a computer. */
class ClaudeLogin(
    private val app: Application,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    private val http: HttpTransport,
    private val accounts: AccountsController
) {
    private val staging = SecureStore(app, AccountRegistry.stagingNamespace(Providers.CLAUDE))
    private val auth = ClaudeAuth(http, staging)
    private val repository = ClaudeRepository(http)
    private val generation = SessionGeneration()
    private val lock = AccountAccess.lock("staging.claude")
    private var job: Job? = null
    private var replaceId: String? = null
    private val login get() = state.value.login(Providers.CLAUDE)

    /** Shows an attempt that survived a restart of the app. */
    fun resume() = scope.launch(Dispatchers.IO) {
        lock.withLock {
            val url = runCatching { auth.pendingUrl() }.getOrNull() ?: return@withLock
            replaceId = runCatching { auth.pendingReplaceId() }.getOrNull()
            if (!login.open) state.updateLogin(Providers.CLAUDE) { LoginState(open = true, url = url, replaceAccountId = replaceId) }
        }
    }

    fun begin(replace: String? = null) {
        if (login.busy) return
        replaceId = replace
        val gen = generation.advance()
        state.updateLogin(Providers.CLAUDE) { LoginState(open = true, busy = true, replaceAccountId = replace) }
        val previous = job
        job = scope.launch(Dispatchers.IO) {
            previous?.join()
            lock.withLock {
                try {
                    val url = auth.begin(replace)
                    if (generation.isCurrent(gen)) state.updateLogin(Providers.CLAUDE) { copy(url = url) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (generation.isCurrent(gen)) state.updateLogin(Providers.CLAUDE) { copy(error = safeError(e)) } }
                finally { if (generation.isCurrent(gen)) state.updateLogin(Providers.CLAUDE) { copy(busy = false) } }
            }
        }
    }

    fun complete(code: String) {
        val before = login
        if (!before.open || before.busy || before.url == null) return
        val gen = generation.current()
        state.updateLogin(Providers.CLAUDE) { copy(busy = true, error = null) }
        job = scope.launch(Dispatchers.IO) {
            lock.withLock {
                try {
                    val issued = auth.complete(code)
                    val profile = try { withTimeoutOrNull(4_000) { repository.profile(issued) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                    val credential = ClaudeCredential(issued.access, issued.expiresAt, profile?.plan ?: issued.plan, issued.refresh, issued.phoneLogin)
                    currentCoroutineContext().ensureActive()
                    if (!generation.isCurrent(gen)) return@withLock
                    staging.writeClaude(credential)
                    val account = accounts.attach(Providers.CLAUDE, staging, profile?.id?.let { "claude:$it" }, profile?.email, replaceId)
                    if (generation.isCurrent(gen)) state.finishSignIn(Providers.CLAUDE, account)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val pending = runCatching { auth.pendingUrl() }.getOrNull()
                    val message = when {
                        e is ClaudeLoginFailure -> e.message.orEmpty()
                        e is HttpFailure && e.status in listOf(400, 401) -> "Browser-Freigabe konnte nicht übernommen werden. Bitte neu anmelden."
                        else -> safeError(e)
                    }
                    if (generation.isCurrent(gen)) state.updateLogin(Providers.CLAUDE) { copy(error = message, url = pending) }
                } finally { if (generation.isCurrent(gen)) state.idleLogin(Providers.CLAUDE) }
            }
        }
    }

    /** Uses a credential copied from Claude Code on a computer. It is only read, never refreshed. */
    fun import(raw: String) {
        if (login.busy) return
        val gen = generation.advance()
        val previous = job
        previous?.cancel()
        state.updateLogin(Providers.CLAUDE) { copy(open = true, busy = true, error = null) }
        job = scope.launch(Dispatchers.IO) {
            previous?.join()
            lock.withLock {
                try {
                    val credential = ClaudeParser.credential(raw)
                    val usage = repository.fetch(credential)
                    val profile = try { withTimeoutOrNull(4_000) { repository.profile(credential) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                    currentCoroutineContext().ensureActive()
                    if (!generation.isCurrent(gen)) return@withLock
                    staging.clearClaudeLogin()
                    staging.writeClaude(ClaudeCredential(credential.access, credential.expiresAt, credential.plan ?: profile?.plan, credential.refresh, credential.phoneLogin))
                    val account = accounts.attach(Providers.CLAUDE, staging, profile?.id?.let { "claude:$it" }, profile?.email, replaceId, usage)
                    if (generation.isCurrent(gen)) state.finishSignIn(Providers.CLAUDE, account)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (generation.isCurrent(gen)) state.updateLogin(Providers.CLAUDE) { copy(error = "Import fehlgeschlagen. ${safeError(e)}") }
                } finally { if (generation.isCurrent(gen)) state.idleLogin(Providers.CLAUDE) }
            }
        }
    }

    fun cancel() {
        generation.advance()
        val previous = job
        previous?.cancel()
        replaceId = null
        state.closeLogin(Providers.CLAUDE)
        job = scope.launch(Dispatchers.IO) {
            previous?.join()
            lock.withLock {
                try { auth.cancel(); staging.clear() }
                catch (_: Exception) { state.update { it.copy(storageWarning = "Anmeldeversuch konnte nicht gelöscht werden.") } }
            }
        }
    }
}
