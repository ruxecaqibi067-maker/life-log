package com.personal.lifelog.data.repository

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import androidx.room.withTransaction
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.domain.DataError
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.DataViolation
import com.personal.lifelog.domain.ErrorCode
import kotlinx.coroutines.CancellationException

internal suspend fun <T> write(
    db: AppDatabase,
    coordinator: WriteCoordinator,
    operation: suspend () -> T,
): DataResult<T> = try {
    coordinator.withWrite { db.withTransaction { DataResult.Success(operation()) } }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (violation: DataViolation) {
    DataResult.Failure(violation.error)
} catch (_: SQLiteFullException) {
    DataResult.Failure(DataError(ErrorCode.STORAGE_FULL))
} catch (_: SQLiteCantOpenDatabaseException) {
    DataResult.Failure(DataError(ErrorCode.STORAGE_UNAVAILABLE))
} catch (_: SQLiteException) {
    // No record contents or raw SQL exceptions cross into presentation code.
    DataResult.Failure(DataError(ErrorCode.STORAGE))
} catch (_: java.io.IOException) {
    // Startup recovery must fail closed without allowing an ordinary editor write to crash the UI.
    DataResult.Failure(DataError(ErrorCode.STORAGE_UNAVAILABLE))
} catch (_: SecurityException) {
    DataResult.Failure(DataError(ErrorCode.STORAGE_UNAVAILABLE))
} catch (_: android.system.ErrnoException) {
    DataResult.Failure(DataError(ErrorCode.STORAGE_UNAVAILABLE))
}

internal fun updatedAt(now: Long, created: Long, previous: Long = created): Long = maxOf(now, created, previous)
