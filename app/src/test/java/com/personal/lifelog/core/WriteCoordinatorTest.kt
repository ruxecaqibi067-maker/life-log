package com.personal.lifelog.core

import com.personal.lifelog.data.repository.WriteCoordinator
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WriteCoordinatorTest {
    @Test fun composedWriteDoesNotDeadlockAndStillExcludesOtherOperations() = runBlocking<Unit> {
        val writes = WriteCoordinator()
        assertEquals("committed", withTimeout(5000) {
            writes.withExclusiveAccess { writes.withWrite { writes.withWrite { "committed" } } }
        })
    }

    @Test fun exclusiveFilePhaseBlocksBusinessWrites() = runBlocking<Unit> {
        val writes = WriteCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempted = CompletableDeferred<Unit>()
        val backup = async { writes.withExclusiveAccess { entered.complete(Unit); release.await() } }
        entered.await()
        val writer = async { attempted.complete(Unit); writes.withWrite { "saved" } }
        attempted.await()
        assertFalse(writer.isCompleted)
        release.complete(Unit)
        backup.await()
        assertEquals("saved", writer.await())
    }

    @Test fun cancellationAndFailureReleaseTheWriteLock() = runBlocking<Unit> {
        val writes = WriteCoordinator()
        val entered = CompletableDeferred<Unit>()
        val job = launch { writes.withExclusiveAccess { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        job.cancelAndJoin()
        try { writes.withWrite { throw IllegalStateException("failure") } } catch (_: IllegalStateException) { }
        assertEquals("available", withTimeout(1000) { writes.withWrite { "available" } })
    }
}
