package com.madebycli.aiusage.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

// Same-process foreground/background account transactions, including rotation and logout. One lock per account.
object AccountAccess {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun lock(accountId: String): Mutex = locks.getOrPut(accountId) { Mutex() }
    /** Emits the id of an account whose stored measurement was changed by background work. */
    val changes = MutableSharedFlow<String>(extraBufferCapacity = 16)
}
