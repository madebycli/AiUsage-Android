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
                    edit { copy(hint = com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.err_poll_retry)) }
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
            edit { copy(busy = false, url = null, deviceCode = null, error = com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.err_code_expired)) }
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
                        e is HttpFailure && e.status in listOf(400, 401) -> com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.err_browser_approval)
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
                    if (generation.isCurrent(gen)) state.updateLogin(Providers.CLAUDE) { copy(error = com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.err_import_failed, safeError(e))) }
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
                catch (_: Exception) { state.update { it.copy(storageWarning = com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.err_attempt_cleanup)) } }
            }
        }
    }
}

/**
 * Gemini sign-in: Google in the browser, which returns to a loopback address this app listens on. When the browser
 * cannot reach it (the app was closed meanwhile), the address it shows can be pasted instead. Nothing is stored until
 * the first quota read worked.
 */
class GeminiLogin(
    private val app: Application,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    private val http: HttpTransport,
    private val accounts: AccountsController
) {
    private val staging = SecureStore(app, AccountRegistry.stagingNamespace(Providers.GEMINI))
    private val repository = GeminiRepository(http)
    private val generation = SessionGeneration()
    private var attempt: GeminiOAuth.Attempt? = null
    private var replaceId: String? = null
    private var listener: Job? = null
    @Volatile private var server: java.net.ServerSocket? = null
    private var job: Job? = null
    private val login get() = state.value.login(Providers.GEMINI)

    private fun stopListening() { runCatching { server?.close() }; server = null; listener?.cancel() }

    fun begin(replace: String? = null) {
        if (login.busy && login.phase != LoginPhase.WAITING) return
        val gen = generation.advance()
        replaceId = replace
        val next = GeminiOAuth.attempt()
        attempt = next
        state.updateLogin(Providers.GEMINI) { LoginState(open = true, busy = true, phase = LoginPhase.WAITING, url = next.url, replaceAccountId = replace) }
        stopListening()
        listener = scope.launch(Dispatchers.IO) {
            val address = try { GeminiOAuth.awaitCallback(10 * 60_000) { server = it } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (address != null && generation.isCurrent(gen)) finish(gen, address)
            else if (generation.isCurrent(gen)) state.idleLogin(Providers.GEMINI)
        }
    }

    /** The address the browser shows after the sign-in, pasted by hand. */
    fun complete(address: String) {
        val gen = generation.current()
        if (attempt == null || !login.open) return
        stopListening()
        scope.launch(Dispatchers.IO) { finish(gen, address) }
    }

    private suspend fun finish(gen: Long, address: String) {
        val current = attempt ?: return
        state.updateLogin(Providers.GEMINI) { copy(busy = true, phase = LoginPhase.EXCHANGING, error = null) }
        job?.cancelAndJoin()
        job = scope.launch(Dispatchers.IO) {
            try {
                val code = GeminiOAuth.codeFrom(address, current.state)
                val credential = GeminiOAuth.exchange(http, code, current.verifier)
                if (generation.isCurrent(gen)) state.updateLogin(Providers.GEMINI) { copy(phase = LoginPhase.LOADING_USAGE) }
                val usage = repository.fetch(credential)
                currentCoroutineContext().ensureActive()
                if (!generation.isCurrent(gen)) return@launch
                staging.writeGemini(credential)
                val account = accounts.attach(Providers.GEMINI, staging, credential.id?.let { "gemini:$it" } ?: credential.email?.let { "gemini:$it" }, credential.email, replaceId, usage)
                attempt = null
                if (generation.isCurrent(gen)) state.finishSignIn(Providers.GEMINI, account)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w("AiUsage", diagnosticFailure("login_gemini", e))
                val message = when {
                    e is GeminiLoginFailure && e.reason == GeminiLoginFailure.Reason.STATE -> com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.gemini_error_state)
                    e is GeminiLoginFailure && e.reason == GeminiLoginFailure.Reason.DENIED -> com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.gemini_error_denied)
                    e is GeminiLoginFailure -> com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.gemini_error_address)
                    e is HttpFailure && e.status == 403 -> com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.gemini_error_subscription)
                    else -> safeError(e)
                }
                if (generation.isCurrent(gen)) state.updateLogin(Providers.GEMINI) { copy(error = message, busy = false, phase = LoginPhase.WAITING) }
            }
        }
    }

    fun cancel() {
        generation.advance()
        stopListening()
        job?.cancel()
        attempt = null
        replaceId = null
        state.closeLogin(Providers.GEMINI)
        scope.launch(Dispatchers.IO) { runCatching { staging.clear() } }
    }
}
