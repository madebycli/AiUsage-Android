package com.madebycli.aiusage.widget

import android.content.Context
import com.madebycli.aiusage.data.*

/** What a widget needs to know about one account. */
data class WidgetAccount(
    val account: Account,
    val title: String,
    val plan: String?,
    val windows: List<UsageWindow>,
    val fetchedAt: Long?
) {
    val provider get() = account.provider
    fun window(seconds: Long) = windows.firstOrNull { it.seconds == seconds }
}

object WidgetData {
    /** Reads the stored measurements. Never touches the network. */
    fun load(context: Context): List<WidgetAccount> {
        val accounts = AccountRegistry(context).list()
        return accounts.map { account ->
            val usage = runCatching { SecureStore(context, account.namespace).readRecord().usage }.getOrNull()
            WidgetAccount(account, displayName(account, accounts), usage?.plan?.takeIf { it.isNotBlank() },
                usage?.windows(account.provider).orEmpty().filter { it.seconds == FIVE_HOURS || it.seconds == ONE_WEEK }, usage?.fetchedAt)
        }
    }

    /** The accounts a widget shows, in the order the user chose. */
    fun select(all: List<WidgetAccount>, config: WidgetConfig): List<WidgetAccount> =
        if (config.accounts.isEmpty()) all else config.accounts.mapNotNull { id -> all.firstOrNull { it.account.id == id } }
}
