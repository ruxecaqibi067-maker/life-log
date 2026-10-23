package com.personal.lifelog.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.BuiltinExercises
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.data.repository.TimelineRepository
import com.personal.lifelog.data.repository.WorkoutRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.MealDraft
import com.personal.lifelog.domain.RecordIds
import com.personal.lifelog.domain.SleepDraft
import com.personal.lifelog.domain.TimelineEntry
import com.personal.lifelog.domain.WorkoutDraft
import com.personal.lifelog.domain.WorkoutItemDraft
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Real Room aggregation checks for T10: saved local-date membership, cross-midnight
 * sleep attribution, stable sorting and date edits. Host-only; no device claims.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class TimelineRepositoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private val zone = "Asia/Shanghai"
    private lateinit var db: AppDatabase
    private lateinit var records: RecordRepository
    private lateinit var workouts: WorkoutRepository
    private lateinit var timeline: TimelineRepository

    private fun id() = RecordIds.newId()
    private fun utc(value: String) = Instant.parse(value).toEpochMilli()
    private fun <T> DataResult<T>.value(): T = (this as DataResult.Success).value
    private val running = BuiltinExercises.entries[0].id
    private val bench = BuiltinExercises.entries[1].id

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock))
            .build()
        val writes = WriteCoordinator()
        records = RecordRepository(db, writes, clock) { ZoneId.of(zone) }
        workouts = WorkoutRepository(db, writes, clock)
        timeline = TimelineRepository(records, workouts)
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun crossMidnightSleepBelongsToWakeUpDateAndSortsByWakeTime() = runBlocking<Unit> {
        val sleepId = id()
        records.saveSleep(
            SleepDraft(
                id = sleepId,
                startedAtUtcMs = utc("2026-10-22T15:50:00Z"),
                startZoneId = zone,
                endedAtUtcMs = utc("2026-10-22T23:10:00Z"),
                endZoneId = zone,
            ),
        ).value()
        val mealId = id()
        records.saveMeal(
            MealDraft(mealId, utc("2026-10-23T04:10:00Z"), zone, "牛肉面"),
        ).value()
        val workoutId = id()
        workouts.saveWorkout(
            WorkoutDraft(
                id = workoutId,
                occurredAtUtcMs = utc("2026-10-23T10:00:00Z"),
                zoneId = zone,
                items = listOf(
                    WorkoutItemDraft(id(), bench),
                    WorkoutItemDraft(id(), running),
                ),
            ),
        ).value()

        val onWakeDate = timeline.timelineOn("2026-10-23").first()
        // 18:00 workout, 12:10 meal, 07:10 wake-up (not the 23:50 bedtime).
        assertEquals(listOf(workoutId, mealId, sleepId), onWakeDate.map { it.id })
        assertTrue(timeline.timelineOn("2026-10-22").first().isEmpty())

        val sleep = onWakeDate.filterIsInstance<TimelineEntry.Sleep>().single()
        assertEquals(utc("2026-10-22T23:10:00Z"), sleep.sortUtcMs)
        assertEquals(utc("2026-10-22T15:50:00Z"), sleep.startedAtUtcMs)
        assertEquals(utc("2026-10-22T23:10:00Z"), sleep.endedAtUtcMs)

        val workout = onWakeDate.filterIsInstance<TimelineEntry.Workout>().single()
        assertEquals(listOf("卧推", "跑步"), workout.itemNames)
    }

    @Test
    fun activeSleepStaysOnItsStartDateAndUsesStartForOrdering() = runBlocking<Unit> {
        val active = records.startSleep(id(), utc("2026-10-22T15:50:00Z"), zone).value()
        val day = timeline.timelineOn("2026-10-22").first()
        val entry = day.single() as TimelineEntry.Sleep
        assertEquals(active.id, entry.id)
        assertEquals(active.startedAtUtcMs, entry.sortUtcMs)
        assertNull(entry.endedAtUtcMs)
        assertNull(entry.endZoneId)
        assertTrue(timeline.timelineOn("2026-10-23").first().isEmpty())
    }

    @Test
    fun editingMealDateMovesItWithoutDuplicating() = runBlocking<Unit> {
        val mealId = id()
        records.saveMeal(
            MealDraft(mealId, utc("2026-10-22T04:00:00Z"), zone, "昨天的午饭"),
        ).value()
        assertEquals(listOf(mealId), timeline.timelineOn("2026-10-22").first().map { it.id })

        records.saveMeal(
            MealDraft(mealId, utc("2026-10-23T04:00:00Z"), zone, "改成今天的午饭"),
        ).value()
        assertTrue(timeline.timelineOn("2026-10-22").first().isEmpty())
        val moved = timeline.timelineOn("2026-10-23").first()
        assertEquals(listOf(mealId), moved.map { it.id })
        assertEquals("改成今天的午饭", (moved.single() as TimelineEntry.Meal).text)
        assertEquals(1, db.records().allMeals().size)
    }

    @Test
    fun equalInstantsUseStableIdOrder() = runBlocking<Unit> {
        val first = "00000000-0000-4000-8000-000000000011"
        val second = "00000000-0000-4000-8000-000000000012"
        val at = utc("2026-10-23T04:10:00Z")
        records.saveMeal(MealDraft(second, at, zone, "第二条")).value()
        records.saveMeal(MealDraft(first, at, zone, "第一条")).value()

        val ids = timeline.timelineOn("2026-10-23").first().map { it.id }
        assertEquals(listOf(first, second), ids)
    }

    @Test
    fun emptyDateAndRecordedDateIndexStayConsistent() = runBlocking<Unit> {
        records.saveMeal(MealDraft(id(), utc("2026-10-23T04:10:00Z"), zone, "牛肉面")).value()
        records.saveSleep(
            SleepDraft(id(), utc("2026-10-21T15:50:00Z"), zone, utc("2026-10-21T23:10:00Z"), zone),
        ).value()

        assertTrue(timeline.timelineOn("2026-10-20").first().isEmpty())
        assertEquals(listOf("2026-10-23", "2026-10-22"), timeline.datesWithRecords().first())
    }
}
