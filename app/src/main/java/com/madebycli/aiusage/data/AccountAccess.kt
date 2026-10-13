package com.madebycli.aiusage.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex

// Same-process foreground/background account transactions, including rotation and logout.
object AccountAccess {
    val codex = Mutex()
    val claude = Mutex()
    val changes = MutableSharedFlow<String>(extraBufferCapacity = 8)
}
