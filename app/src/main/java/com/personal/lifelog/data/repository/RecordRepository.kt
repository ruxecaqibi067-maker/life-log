package com.personal.lifelog.data.repository

import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.core.validation.MediaRules
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.entity.MealRecord
import com.personal.lifelog.data.local.entity.MediaAsset
import com.personal.lifelog.data.local.entity.SleepRecord
import com.personal.lifelog.domain.*
import java.time.Clock
import java.time.ZoneId

class RecordRepository(
    private val db: AppDatabase,
    private val writes: WriteCoordinator,
    private val clock: Clock,
    private val zoneIdProvider: () -> ZoneId,
) {
    fun datesWithRecords() = db.records().datesWithRecords()
    fun mealsOn(date: String) = db.records().mealsOn(date)
    fun sleepsOn(date: String) = db.records().sleepsOn(date)
    fun activeSleep() = db.records().activeSleep()
    fun sleeps() = db.records().observeSleeps()
    fun observeMeal(id: String) = db.records().observeMeal(Rules.id(id))
    fun observeSleep(id: String) = db.records().observeSleep(Rules.id(id))
    suspend fun meal(id: String) = db.records().meal(Rules.id(id))
    suspend fun sleep(id: String) = db.records().sleep(Rules.id(id))

    /** preparedPhoto is supplied only after T04 commits its immutable file to filesDir. */
    suspend fun saveMeal(draft: MealDraft, preparedPhoto: MediaAsset? = null): DataResult<MealRecord> = write(db, writes) {
        Rules.id(draft.id)
        val text = Rules.text(draft.text, Rules.MEAL_TEXT_LIMIT)
        if (text == null && draft.photoId == null) reject(ErrorCode.EMPTY_MEAL)
        val satietyText = Rules.satietyText(draft.satiety, draft.satietyText)
        val date = RecordTime.localDate(draft.occurredAtUtcMs, draft.zoneId)
        draft.photoId?.let(Rules::id)
        if (preparedPhoto != null) {
            if (preparedPhoto.id != draft.photoId) reject(ErrorCode.INVALID_MEDIA)
            MediaRules.validate(preparedPhoto)
            val oldPhoto = db.metadata().media(preparedPhoto.id)
            if (oldPhoto != null && oldPhoto != preparedPhoto) reject(ErrorCode.IMMUTABLE_MEDIA)
            if (oldPhoto == null) db.metadata().insertMedia(preparedPhoto)
        }
        if (draft.photoId != null && db.metadata().media(draft.photoId) == null) reject(ErrorCode.INVALID_MEDIA)
        val old = db.records().meal(draft.id)
        val now = clock.millis()
        MealRecord(
            id = draft.id,
            occurredAtUtcMs = draft.occurredAtUtcMs,
            zoneId = draft.zoneId,
            localDate = date,
            text = text,
            satiety = draft.satiety,
            photoId = draft.photoId,
            createdAtUtcMs = old?.createdAtUtcMs ?: now,
            updatedAtUtcMs = updatedAt(now, old?.createdAtUtcMs ?: now, old?.updatedAtUtcMs ?: now),
            satietyText = satietyText,
        ).also { db.records().putMeal(it) }
    }

    suspend fun deleteMeal(id: String): DataResult<Boolean> = write(db, writes) { db.records().deleteMeal(Rules.id(id)) > 0 }

    /** Atomic and idempotent while an active record exists, including rapid concurrent taps. */
    suspend fun startSleep(
        id: String,
        startedAtUtcMs: Long = clock.millis(),
        zoneId: String = zoneIdProvider().id,
    ): DataResult<SleepRecord> = write(db, writes) {
        Rules.id(id)
        db.records().activeSleepNow() ?: run {
            // A retry using a finished operation's ID must not reopen it.
            db.records().sleep(id) ?: saveSleepInTransaction(SleepDraft(id, startedAtUtcMs, zoneId))
        }
    }

    suspend fun finishSleep(
        id: String,
        endedAtUtcMs: Long = clock.millis(),
        zoneId: String = zoneIdProvider().id,
        confirmLongInterval: Boolean = false,
        expected: SleepRecord? = null,
    ): DataResult<SleepRecord> = write(db, writes) {
        val old = db.records().sleep(Rules.id(id)) ?: reject(ErrorCode.NOT_FOUND)
        if (expected != null && old != expected) reject(ErrorCode.NOT_FOUND)
        if (old.endedAtUtcMs != null) old else saveSleepInTransaction(
            SleepDraft(id, old.startedAtUtcMs, old.startZoneId, endedAtUtcMs, zoneId, confirmLongInterval),
        )
    }

    suspend fun saveSleep(draft: SleepDraft): DataResult<SleepRecord> = write(db, writes) { saveSleepInTransaction(draft) }
    suspend fun deleteSleep(id: String): DataResult<Boolean> = write(db, writes) { db.records().deleteSleep(Rules.id(id)) > 0 }

    /** Compare the whole saved row inside the transaction; a stale screen cannot resurrect/overwrite it. */
    suspend fun editSleep(draft: SleepDraft, expected: SleepRecord): DataResult<SleepRecord> = write(db, writes) {
        if (draft.id != expected.id || db.records().sleep(Rules.id(expected.id)) != expected) reject(ErrorCode.NOT_FOUND)
        saveSleepInTransaction(draft)
    }

    suspend fun deleteSleep(expected: SleepRecord): DataResult<Boolean> = write(db, writes) {
        if (db.records().sleep(Rules.id(expected.id)) != expected) reject(ErrorCode.NOT_FOUND)
        db.records().deleteSleep(expected.id) > 0
    }

    /** Undo start = delete it; undo finish = restore its open interval, with overlap validation. */
    suspend fun undoSleep(expected: SleepRecord, previous: SleepRecord?): DataResult<Unit> = write(db, writes) {
        if (db.records().sleep(Rules.id(expected.id)) != expected) reject(ErrorCode.NOT_FOUND)
        if (previous == null) {
            if (expected.endedAtUtcMs != null) reject(ErrorCode.INVALID_INTERVAL)
            db.records().deleteSleep(expected.id)
        } else {
            if (previous.id != expected.id || previous.endedAtUtcMs != null) reject(ErrorCode.INVALID_INTERVAL)
            saveSleepInTransaction(SleepDraft(previous.id, previous.startedAtUtcMs, previous.startZoneId))
        }
    }

    private suspend fun saveSleepInTransaction(draft: SleepDraft): SleepRecord {
        Rules.id(draft.id)
        val startDate = RecordTime.localDate(draft.startedAtUtcMs, draft.startZoneId)
        val end = draft.endedAtUtcMs
        val archiveDate = if (end == null) {
            if (draft.endZoneId != null) reject(ErrorCode.INVALID_INTERVAL)
            startDate
        } else {
            RecordTime.durationSeconds(draft.startedAtUtcMs, end)
            if (RecordTime.isLongInterval(draft.startedAtUtcMs, end) && !draft.confirmLongInterval) {
                reject(ErrorCode.LONG_INTERVAL_CONFIRMATION)
            }
            RecordTime.localDate(end, draft.endZoneId ?: reject(ErrorCode.INVALID_INTERVAL))
        }
        if (db.records().hasOverlap(draft.startedAtUtcMs, end, draft.id)) reject(ErrorCode.OVERLAPPING_SLEEP)
        val old = db.records().sleep(draft.id)
        val now = clock.millis()
        return SleepRecord(draft.id, draft.startedAtUtcMs, draft.startZoneId, end, draft.endZoneId,
            archiveDate, if (end == null) 1 else null, old?.createdAtUtcMs ?: now,
            updatedAt(now, old?.createdAtUtcMs ?: now, old?.updatedAtUtcMs ?: now))
            .also { db.records().putSleep(it) }
    }
}
