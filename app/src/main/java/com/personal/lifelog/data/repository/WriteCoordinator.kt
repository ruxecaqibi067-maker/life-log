package com.personal.lifelog.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One logical operation may compose repository writes without opening a gap for other writers. */
class WriteCoordinator {
    private val mutex = Mutex()
    private class Lease(val owner: WriteCoordinator) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Lease>
        @Volatile var active = true
    }

    suspend fun <T> withWrite(block: suspend () -> T): T {
        val inherited = currentCoroutineContext()[Lease]
        if (inherited != null && inherited.owner === this && inherited.active) return block()
        return mutex.withLock {
            val lease = Lease(this)
            try {
                withContext(lease) { coroutineScope { block() } }
            } finally {
                lease.active = false
            }
        }
    }

    /** Hold through file placement + DB commit, or snapshot + photo copy. Use structured coroutines. */
    suspend fun <T> withExclusiveAccess(block: suspend () -> T): T = withWrite(block)
}
