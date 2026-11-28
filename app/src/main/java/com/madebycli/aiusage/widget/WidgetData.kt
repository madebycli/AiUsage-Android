package com.madebycli.aiusage.widget

import android.content.Context
import com.madebycli.aiusage.data.*

/** What a widget needs to know about one account: the last reading and the measured history. */
data class WidgetAccount(
    val account: Account,
    val title: String,
    val plan: String?,
    val windows: List<UsageWindow>,
    val fetchedAt: Long?,
    val ledger: UsageLedger = UsageLedger(),
    /** Saved resets the provider reported with the last reading; null when it does not report any. */
    val banked: Int? = null
) {
    val id get() = account.id
    val provider get() = account.provider
    fun window(seconds: Long) = windows.firstOrNull { it.seconds == seconds }
}

object WidgetData {
    /** The last reading, so that a widget being resized is redrawn at once instead of after the encrypted store is read again. */
    @Volatile private var last: List<WidgetAccount>? = null

    fun peek(): List<WidgetAccount>? = last

    /** Reads the stored measurements. Never touches the network. */
    fun load(context: Context): List<WidgetAccount> = read(context).also { last = it }

    private fun read(context: Context): List<WidgetAccount> {
        val accounts = AccountRegistry(context).list()
        return accounts.map { account ->
            val record = runCatching { SecureStore(context, account.namespace).readRecord().migrated() }.getOrNull()
            val usage = record?.usage
            WidgetAccount(account, displayName(account, accounts), usage?.plan?.takeIf { it.isNotBlank() },
                usage?.windows(account.provider).orEmpty().filter { it.seconds == FIVE_HOURS || it.seconds == ONE_WEEK }, usage?.fetchedAt,
                record?.ledger ?: UsageLedger(),
                usage?.let { it.availableResets ?: it.resetCredits.count { c -> c.status == "available" }.takeIf { n -> n > 0 } })
        }
    }

    /** The same data from what the app already holds, for previews inside the app. */
    fun fromStates(states: List<com.madebycli.aiusage.AccountState>): List<WidgetAccount> = states.map { s ->
        WidgetAccount(s.account, s.title, s.plan, s.windows().filter { it.seconds == FIVE_HOURS || it.seconds == ONE_WEEK }, s.usage?.fetchedAt, s.ledger,
            s.usage?.let { it.availableResets ?: it.resetCredits.count { c -> c.status == "available" }.takeIf { n -> n > 0 } })
    }

    /** The accounts a widget shows, in the order the user chose. */
    fun select(all: List<WidgetAccount>, config: WidgetConfig): List<WidgetAccount> =
        if (config.accounts.isEmpty()) all else config.accounts.mapNotNull { id -> all.firstOrNull { it.account.id == id } }
}
