package com.personal.lifelog.data.repository

import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.core.time.WorkTime
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.entity.WorkRecord
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.domain.*
import java.time.Clock
import java.time.ZoneId

/** Independent work intervals. All writes share the restore barrier and one Room transaction. */
class WorkRepository(
    private val db: AppDatabase,
    private val writes: WriteCoordinator,
    private val clock: Clock,
    private val zoneIdProvider: () -> ZoneId,
) {
    fun datesWithRecords() = db.work().datesWithRecords()
    fun worksOn(date: String) = db.work().worksOn(date)
    fun works() = db.work().observeWorks()
    fun activeWork() = db.work().activeWork()
    fun observeWork(id: String) = db.work().observeWork(Rules.id(id))
    suspend fun work(id: String) = db.work().work(Rules.id(id))

    /** Atomic and idempotent while an active record exists, including rapid concurrent taps. */
    suspend fun startWork(
        id: String,
        startedAtUtcMs: Long = clock.millis(),
        zoneId: String = zoneIdProvider().id,
    ): DataResult<WorkRecord> = write(db, writes) {
        Rules.id(id)
        db.work().activeWorkNow() ?: run {
            // A retry using a finished operation's ID must not reopen it.
            db.work().work(id) ?: saveWorkInTransaction(WorkDraft(id, startedAtUtcMs, zoneId))
        }
    }

    suspend fun finishWork(
        id: String,
        endedAtUtcMs: Long = clock.millis(),
        zoneId: String = zoneIdProvider().id,
        confirmLongInterval: Boolean = false,
        expected: WorkRecord? = null,
    ): DataResult<WorkRecord> = write(db, writes) {
        val old = db.work().work(Rules.id(id)) ?: reject(ErrorCode.NOT_FOUND)
        if (expected != null && old != expected) reject(ErrorCode.NOT_FOUND)
        if (old.endedAtUtcMs != null) old else saveWorkInTransaction(
            WorkDraft(id, old.startedAtUtcMs, old.startZoneId, endedAtUtcMs, zoneId, confirmLongInterval),
        )
    }

    suspend fun saveWork(draft: WorkDraft): DataResult<WorkRecord> = write(db, writes) { saveWorkInTransaction(draft) }
    suspend fun deleteWork(id: String): DataResult<Boolean> = write(db, writes) { db.work().deleteWork(Rules.id(id)) > 0 }

    /** Compare the whole saved row inside the transaction; a stale screen cannot resurrect/overwrite it. */
    suspend fun editWork(draft: WorkDraft, expected: WorkRecord): DataResult<WorkRecord> = write(db, writes) {
        if (draft.id != expected.id || db.work().work(Rules.id(expected.id)) != expected) reject(ErrorCode.NOT_FOUND)
        saveWorkInTransaction(draft)
    }

    suspend fun deleteWork(expected: WorkRecord): DataResult<Boolean> = write(db, writes) {
        if (db.work().work(Rules.id(expected.id)) != expected) reject(ErrorCode.NOT_FOUND)
        db.work().deleteWork(expected.id) > 0
    }

    /** Undo start = delete it; undo finish = restore its open interval, with overlap validation. */
    suspend fun undoWork(expected: WorkRecord, previous: WorkRecord?): DataResult<Unit> = write(db, writes) {
        if (db.work().work(Rules.id(expected.id)) != expected) reject(ErrorCode.NOT_FOUND)
        if (previous == null) {
            if (expected.endedAtUtcMs != null) reject(ErrorCode.INVALID_INTERVAL)
            db.work().deleteWork(expected.id)
        } else {
            if (previous.id != expected.id || previous.endedAtUtcMs != null) reject(ErrorCode.INVALID_INTERVAL)
            saveWorkInTransaction(WorkDraft(previous.id, previous.startedAtUtcMs, previous.startZoneId))
        }
    }

    /** Undo a deletion only while its ID is absent; all interval/overlap checks still apply. */
    suspend fun restoreDeletedWork(deleted: WorkRecord): DataResult<WorkRecord> = write(db, writes) {
        if (db.work().work(Rules.id(deleted.id)) != null) reject(ErrorCode.NOT_FOUND)
        val restored = saveWorkInTransaction(WorkDraft(deleted.id, deleted.startedAtUtcMs, deleted.startZoneId,
            deleted.endedAtUtcMs, deleted.endZoneId, confirmLongInterval = true))
        restored.copy(createdAtUtcMs = deleted.createdAtUtcMs,
            updatedAtUtcMs = updatedAt(clock.millis(), deleted.createdAtUtcMs, deleted.updatedAtUtcMs))
            .also { db.work().putWork(it) }
    }

    private suspend fun saveWorkInTransaction(draft: WorkDraft): WorkRecord {
        Rules.id(draft.id)
        val startDate = RecordTime.localDate(draft.startedAtUtcMs, draft.startZoneId)
        val end = draft.endedAtUtcMs
        val archiveDate = if (end == null) {
            if (draft.endZoneId != null) reject(ErrorCode.INVALID_INTERVAL)
            startDate
        } else {
            WorkTime.validateInterval(draft.startedAtUtcMs, end)
            if (RecordTime.isLongInterval(draft.startedAtUtcMs, end) && !draft.confirmLongInterval) {
                reject(ErrorCode.LONG_INTERVAL_CONFIRMATION)
            }
            RecordTime.localDate(end, draft.endZoneId ?: reject(ErrorCode.INVALID_INTERVAL))
        }
        if (db.work().hasOverlap(draft.startedAtUtcMs, end, draft.id)) reject(ErrorCode.OVERLAPPING_WORK)
        val old = db.work().work(draft.id)
        val now = clock.millis()
        return WorkRecord(draft.id, draft.startedAtUtcMs, draft.startZoneId, end, draft.endZoneId,
            archiveDate, if (end == null) 1 else null, old?.createdAtUtcMs ?: now,
            updatedAt(now, old?.createdAtUtcMs ?: now, old?.updatedAtUtcMs ?: now))
            .also { db.work().putWork(it) }
    }
}
