package com.madebycli.aiusage.data

import android.content.Context
import com.madebycli.aiusage.Providers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.SecureRandom
import java.time.Instant

/**
 * One connected sign-in. Holds no secret: tokens and measurements live encrypted in the store named by
 * [namespace]. The registry order is the order on the home screen.
 */
@Serializable
data class Account(
    val id: String,
    val provider: String,
    val namespace: String,
    /** Name chosen by the user. */
    val label: String? = null,
    /** E-mail or other identifier the provider reported at sign-in. */
    val detail: String? = null,
    /** Stable provider identity, used to recognise the same account when it signs in again. */
    val identity: String? = null,
    val createdAt: Long = 0
)

/** The name shown for [account], numbering unnamed accounts of one provider: "Codex 1", "Codex 2". */
fun displayName(account: Account, all: List<Account>): String {
    account.label?.takeIf { it.isNotBlank() }?.let { return it }
    account.detail?.takeIf { it.isNotBlank() }?.let { return it }
    val siblings = all.filter { it.provider == account.provider }
    val shortName = Providers.short(account.provider)
    return if (siblings.size <= 1) shortName else "$shortName ${siblings.indexOfFirst { it.id == account.id } + 1}"
}

/** Account list in a plain preferences file. It only holds names and ids, never credentials. */
class AccountRegistry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("accounts", 0)
    private val app = context.applicationContext

    @Synchronized fun list(): List<Account> {
        migrateLegacy()
        return read()
    }

    private fun read(): List<Account> = prefs.getString(KEY, null)?.let { runCatching { appJson.decodeFromString<List<Account>>(it) }.getOrNull() }.orEmpty()

    @Synchronized private fun write(accounts: List<Account>) {
        prefs.edit().putString(KEY, appJson.encodeToString(accounts)).commit()
    }

    @Synchronized fun add(account: Account): List<Account> = (list().filter { it.id != account.id } + account).also(::write)

    @Synchronized fun remove(id: String): List<Account> = list().filter { it.id != id }.also(::write)

    @Synchronized fun update(id: String, change: (Account) -> Account): List<Account> =
        list().map { if (it.id == id) change(it) else it }.also(::write)

    /** Puts the accounts in [order] first; accounts missing from it keep their relative place at the end. */
    @Synchronized fun reorder(order: List<String>): List<Account> {
        val current = list()
        val ranked = order.mapNotNull { id -> current.firstOrNull { it.id == id } }
        return (ranked + current.filter { it !in ranked }).also(::write)
    }

    fun find(id: String): Account? = list().firstOrNull { it.id == id }

    /** Finds the account an identity belongs to, so signing in again updates it instead of adding a copy. */
    fun byIdentity(provider: String, identity: String?): Account? =
        identity?.let { wanted -> list().firstOrNull { it.provider == provider && it.identity == wanted } }

    /**
     * Versions before 0.9 held exactly one Codex and one Claude account in fixed storage namespaces.
     * They become the first accounts of the list; their stored data is kept where it is.
     */
    @Synchronized private fun migrateLegacy() {
        if (prefs.contains(KEY)) return
        val found = buildList {
            if (runCatching { SecureStore(app, LEGACY_CODEX).hasCredentials() }.getOrDefault(false))
                add(Account("codex-legacy", Providers.CODEX, LEGACY_CODEX, createdAt = Instant.now().epochSecond))
            if (runCatching { SecureStore(app, LEGACY_CLAUDE).hasCredentials() }.getOrDefault(false))
                add(Account("claude-legacy", Providers.CLAUDE, LEGACY_CLAUDE, createdAt = Instant.now().epochSecond))
        }
        write(found)
    }

    companion object {
        private const val KEY = "list"
        const val LEGACY_CODEX = "account"
        const val LEGACY_CLAUDE = "claude"

        fun newAccount(provider: String, label: String? = null, detail: String? = null, identity: String? = null): Account {
            val id = ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            return Account(id, provider, "acc.$id", label, detail, identity, Instant.now().epochSecond)
        }

        /** Namespace of the not yet finished sign-in of [provider]. Moved into the account on success. */
        fun stagingNamespace(provider: String) = "login.${if (provider == Providers.CODEX) "codex" else "claude"}"
    }
}

fun secureStoreFor(context: Context, account: Account) = SecureStore(context, account.namespace)
