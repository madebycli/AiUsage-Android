package com.madebycli.aiusage.background

import android.content.Context
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.data.*
import java.time.Instant

/** One account read for background work. It honours a stored 429 wait before touching the network. */
object UsageReader {
    suspend fun read(context: Context, http: HttpTransport, account: Account, store: SecureStore): UsageSnapshot? {
        val prefs = context.getSharedPreferences("alerts", 0)
        val now = Instant.now().epochSecond
        val until = prefs.getLong("retry.${account.id}", 0)
        if (until > now) throw HttpFailure(429, until - now)
        return try {
            if (!store.hasCredentials()) null
            else if (account.provider == Providers.CODEX) UsageRepository(CodexAuth(http, store)).fetch()
            else {
                val auth = ClaudeAuth(http, store)
                val repository = ClaudeRepository(http)
                val credential = auth.credential()
                try { repository.fetch(credential) } catch (e: HttpFailure) {
                    if (e.status != 401 || !credential.phoneLogin || credential.refresh == null) throw e
                    repository.fetch(auth.credential(forceRefresh = true))
                }
            }
        } catch (e: HttpFailure) {
            if (e.status == 429) prefs.edit().putLong("retry.${account.id}", now + (e.retrySeconds ?: 1800).coerceIn(1, 604800)).apply()
            throw e
        }
    }
}
