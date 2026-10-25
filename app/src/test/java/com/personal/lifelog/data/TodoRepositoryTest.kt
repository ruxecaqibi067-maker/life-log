package com.personal.lifelog.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.TodoRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class TodoRepositoryTest {
    private var instant = Instant.parse("2026-10-26T15:59:00Z")
    private var zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 26) // Monday
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant, zone)
        override fun instant(): Instant = instant
    }
    private lateinit var db: AppDatabase
    private lateinit var todos: TodoRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock)).build()
        todos = TodoRepository(db, WriteCoordinator(), clock) { zone }
    }
    @After fun close() { db.close() }
    private fun id() = RecordIds.newId()
    private fun <T> DataResult<T>.saved(): T = (this as DataResult.Success).value
    private fun failure(code: ErrorCode, value: DataResult<*>) = assertEquals(code, (value as DataResult.Failure).error.code)
    private suspend fun habit(start: LocalDate = date, frequency: HabitFrequency = HabitFrequency.DAILY,
        interval: Int = 1, mask: Int = 0) = todos.saveHabit(HabitDraft(id(), "虚构习惯", start, 480, frequency, interval, mask)).saved()

    @Test fun twentyOneNaturalDaysAreInclusiveAndPauseDoesNotExtendLifetime() = runBlocking<Unit> {
        val h = habit()
        assertFalse(h.isDueOn(date.minusDays(1)))
        for (offset in 0L..20L) {
            assertTrue(h.isDueOn(date.plusDays(offset)))
            assertTrue(h.isOngoingOn(date.plusDays(offset)))
        }
        assertFalse(h.isDueOn(date.plusDays(21)))
        assertFalse(h.isOngoingOn(date.plusDays(21)))
        assertEquals(1, todos.generateFor().saved())
        val generated = todos.observeOn(date).first().single()
        todos.setCompleted(generated.id, true).saved()
        todos.setHabitStatus(h.id, HabitStatus.PAUSED).saved()
        instant = instant.plus(Duration.ofDays(20))
        assertEquals(0, todos.generateFor().saved())
        assertEquals(1, todos.observeOngoingHabits(date.plusDays(20)).first().size)
        todos.setHabitStatus(h.id, HabitStatus.ACTIVE).saved()
        assertEquals(1, todos.generateFor().saved())
        instant = instant.plus(Duration.ofDays(1))
        assertEquals(0, todos.generateFor().saved())
        assertTrue(todos.observeOngoingHabits(date.plusDays(21)).first().isEmpty())
        assertTrue(todos.observeOn(date).first().single().isCompleted)
        assertEquals(2, db.todos().allTodos().size) // No backfill for the skipped 19 days.
    }

    @Test fun intervalAnchorsToStartAndWeekdaysUseMondayBitZero() = runBlocking<Unit> {
        val interval = habit(frequency = HabitFrequency.INTERVAL, interval = 3)
        for (offset in 0L..21L) assertEquals(offset < 21 && offset % 3 == 0L, interval.isDueOn(date.plusDays(offset)))
        val weekly = habit(frequency = HabitFrequency.WEEKLY, mask = 1 or 64)
        assertTrue(weekly.isDueOn(date)); assertTrue(weekly.isDueOn(date.plusDays(6)))
        assertTrue(weekly.isDueOn(date.plusDays(7))); assertFalse(weekly.isDueOn(date.plusDays(1)))
        assertFalse(weekly.isDueOn(date.plusDays(21)))
        assertFalse(weekly.copy(status = HabitStatus.PAUSED).isDueOn(date))
        assertFalse(weekly.copy(status = HabitStatus.ARCHIVED).isOngoingOn(date))
        val future = habit(start = date.plusDays(5))
        assertTrue(future.isOngoingOn(date)); assertFalse(future.isDueOn(date))
    }

    @Test fun generationReentryConcurrentWritesDeletionAndMovedDatesNeverResurrect() = runBlocking<Unit> {
        val h = habit()
        val results = coroutineScope { (1..8).map { async(Dispatchers.IO) { todos.generateFor().saved() } }.awaitAll() }
        assertEquals(1, results.sum())
        val row = todos.observeOn(date).first().single()
        assertEquals(h.id, row.habitId)
        todos.saveTodo(TodoDraft(row.id, "独立快照", date.plusDays(1), 700)).saved()
        assertEquals(0, todos.generateFor().saved())
        val moved = todos.observeOn(date.plusDays(1)).first().single()
        assertEquals(date.toString(), moved.habitDate)
        assertTrue(todos.deleteTodo(row.id).saved())
        assertFalse(todos.deleteTodo(row.id).saved())
        assertEquals(0, todos.generateFor().saved())
        assertTrue(todos.observeOn(date.plusDays(1)).first().isEmpty())
        assertNotNull(db.todos().allTodos().single().deletedAtUtcMs)
        failure(ErrorCode.NOT_FOUND, todos.saveTodo(TodoDraft(row.id, "不能复活", date)))
        failure(ErrorCode.NOT_FOUND, todos.setCompleted(row.id, true))
    }

    @Test fun explicitCompletionIsIdempotentAndRealRoomFlowPublishesSavedEdits() = runBlocking<Unit> {
        val changes = Channel<List<com.personal.lifelog.data.local.entity.TodoRecord>>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) { todos.observeOn(date).collect { changes.send(it) } }
        try {
            assertTrue(withTimeout(10_000) { changes.receive() }.isEmpty())
            val manual = todos.saveTodo(TodoDraft(id(), "  手动待办  ", date, 0)).saved()
            assertEquals("手动待办", withTimeout(10_000) { changes.receive() }.single().title)
            val complete = todos.setCompleted(manual.id, true).saved()
            instant = instant.plusSeconds(5)
            assertEquals(complete, todos.setCompleted(manual.id, true).saved())
            assertFalse(todos.setCompleted(manual.id, false).saved().isCompleted)
            assertFalse(todos.setCompleted(manual.id, false).saved().isCompleted)
            todos.saveTodo(TodoDraft(manual.id, "改名", date.minusDays(1), 1439)).saved()
            assertEquals("改名", todos.observeOn(date.minusDays(1)).first().single().title)
            assertTrue(todos.observeOn(date).first().isEmpty())
        } finally { job.cancelAndJoin(); changes.close() }
    }

    @Test fun editsAndStatusOnlyAffectUncreatedInstancesAndArchivedCannotRestart() = runBlocking<Unit> {
        val h = habit()
        todos.generateFor().saved()
        val old = todos.observeOn(date).first().single()
        todos.saveHabit(HabitDraft(h.id, "新规则标题", date, 900)).saved()
        assertEquals(old, todos.observeOn(date).first().single())
        todos.setHabitStatus(h.id, HabitStatus.PAUSED).saved()
        instant = instant.plus(Duration.ofDays(1))
        assertEquals(0, todos.generateFor().saved())
        todos.setHabitStatus(h.id, HabitStatus.ACTIVE).saved()
        assertEquals(1, todos.generateFor().saved())
        assertEquals("新规则标题", todos.observeOn(date.plusDays(1)).first().single().title)
        todos.setHabitStatus(h.id, HabitStatus.ARCHIVED).saved()
        assertTrue(todos.observeOngoingHabits(todos.today()).first().isEmpty())
        failure(ErrorCode.NOT_FOUND, todos.setHabitStatus(h.id, HabitStatus.ACTIVE))
        assertEquals(old, todos.observeOn(date).first().single())
    }

    @Test fun currentLocalDateTracksMidnightAndZoneButStoredDatesNeverDrift() = runBlocking<Unit> {
        habit()
        todos.generateFor().saved()
        assertEquals(0, todos.generateFor(date.plusDays(1)).saved())
        instant = Instant.parse("2026-10-26T16:01:00Z")
        assertEquals(date.plusDays(1), todos.today())
        assertEquals(1, todos.generateFor().saved())
        zone = ZoneId.of("America/Los_Angeles")
        assertEquals(date, todos.today())
        assertEquals(0, todos.generateFor().saved())
        assertEquals(0, todos.generateFor(date.minusDays(1)).saved())
        assertEquals(setOf("2026-10-26", "2026-10-27"), db.todos().allTodos().map { it.localDate }.toSet())
    }

    @Test fun validationAndWriteFailurePreserveOldRows() = runBlocking<Unit> {
        val draft = TodoDraft(id(), "合法", date, 1439)
        val old = todos.saveTodo(draft).saved()
        failure(ErrorCode.INVALID_NAME, todos.saveTodo(draft.copy(title = "  ")))
        failure(ErrorCode.TEXT_TOO_LONG, todos.saveTodo(draft.copy(title = "字".repeat(121))))
        failure(ErrorCode.INVALID_NUMBER, todos.saveTodo(draft.copy(scheduledMinute = 1440)))
        failure(ErrorCode.INVALID_NUMBER, todos.saveTodo(draft.copy(scheduledMinute = -1)))
        failure(ErrorCode.INVALID_LOCAL_TIME, todos.saveTodo(draft.copy(date = LocalDate.of(2200, 1, 1))))
        failure(ErrorCode.INVALID_NUMBER, todos.saveHabit(HabitDraft(id(), "间隔", date, frequency = HabitFrequency.INTERVAL, intervalDays = 0)))
        failure(ErrorCode.INVALID_NUMBER, todos.saveHabit(HabitDraft(id(), "每周", date, frequency = HabitFrequency.WEEKLY)))
        assertEquals(365, habit(frequency = HabitFrequency.INTERVAL, interval = 365).intervalDays)
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_test_generated BEFORE INSERT ON TodoRecord WHEN NEW.habitId IS NOT NULL BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        failure(ErrorCode.STORAGE, todos.generateFor())
        assertEquals(listOf(old), db.todos().allTodos())
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_test_todo BEFORE UPDATE ON TodoRecord BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        failure(ErrorCode.STORAGE, todos.setCompleted(old.id, true))
        assertEquals(old, todos.todo(old.id))
    }
}
