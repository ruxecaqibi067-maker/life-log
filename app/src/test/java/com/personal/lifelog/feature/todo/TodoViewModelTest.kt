package com.personal.lifelog.feature.todo

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.TodoRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.ErrorCode
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real Room + UI state transitions. Device rendering and keyboard behavior are checked separately. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class TodoViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: TodoRepository
    private lateinit var vm: TodoViewModel
    private val saved = SavedStateHandle()
    private val start = LocalDate.of(2026, 10, 26)
    private val date = MutableStateFlow(start)
    private var now = Instant.parse("2026-10-26T02:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
    }

    @Before fun setup() {
        // No Compose/IME clock: Main runs state transitions immediately while Room keeps its real executors.
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock)).build()
        repository = TodoRepository(db, WriteCoordinator(), clock) { zone }
        vm = TodoViewModel(repository, date, saved, clock) { zone }
    }

    @After fun close() {
        vm.viewModelScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }
    private suspend fun idle() = withTimeout(5_000) { vm.commands.first { !it.busy } }
    private suspend fun day(predicate: (TodoDayState) -> Boolean): TodoDayState = withTimeout(5_000) {
        vm.home.first { !it.loading && predicate(it) }
    }

    @Test fun draftCancellationRecreationValidationCompletionAndDeleteArePersisted() = runBlocking<Unit> {
        day { !it.failed }
        vm.addTodo()
        vm.update(vm.editor.value!!.copy(title = "取消的草稿"))
        vm.cancelEditor()
        assertTrue(db.todos().allTodos().isEmpty())

        vm.addTodo()
        vm.saveEditor(); idle()
        assertEquals(ErrorCode.INVALID_NAME, vm.commands.value.error)
        assertNotNull(vm.editor.value)
        vm.update(vm.editor.value!!.copy(title = "读书十分钟", scheduledMinute = 19 * 60 + 30))
        val id = vm.editor.value!!.id
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        vm.viewModelScope.cancel()
        vm = TodoViewModel(repository, date, restored, clock) { zone }
        assertEquals("读书十分钟", vm.editor.value!!.title)
        assertEquals(19 * 60 + 30, vm.editor.value!!.scheduledMinute)
        assertTrue(db.todos().allTodos().isEmpty())
        vm.saveEditor(); idle()
        assertNull(vm.editor.value)
        assertNull(vm.commands.value.error)
        val row = day { it.items.size == 1 }.items.single()
        assertEquals(id, row.id)
        assertEquals(start.toString(), row.localDate)
        vm.complete(row, true); idle()
        assertNotNull(repository.todo(id)!!.completedAtUtcMs)
        val completed = day { it.items.single().isCompleted }.items.single()
        vm.complete(completed, false); idle()
        assertNull(repository.todo(id)!!.completedAtUtcMs)

        vm.editTodo(repository.todo(id)!!)
        vm.update(vm.editor.value!!.copy(title = "未保存修改", date = start.plusDays(1)))
        vm.cancelEditor()
        assertEquals("读书十分钟", repository.todo(id)!!.title)
        assertEquals(start.toString(), repository.todo(id)!!.localDate)
        vm.requestDelete(id, row.title, habit = false); vm.cancelDelete()
        assertNotNull(repository.todo(id))
        vm.requestDelete(id, row.title, habit = false); vm.confirmDelete(); idle()
        assertNull(repository.todo(id))
        day { it.items.isEmpty() }
    }

    @Test fun sameDayFlowGeneratesHabitsExpiresAtDayTwentyTwoAndRetainsOldHistory() = runBlocking<Unit> {
        val habits = launch(Dispatchers.Unconfined) { vm.habits.collect() }
        val history = launch(Dispatchers.Unconfined) { vm.history.collect() }
        try {
            day { !it.failed }
            vm.addHabit()
            vm.update(vm.editor.value!!.copy(title = "拉伸五分钟"))
            vm.saveEditor(); idle()
            day { it.items.size == 1 }
            val habit = db.todos().allHabits().single()
            withTimeout(5_000) { vm.habits.first { !it.loading && it.items.size == 1 } }
            vm.refresh(); day { it.items.size == 1 }
            assertEquals(1, db.todos().allTodos().size)

            now = now.plusSeconds(20L * 86400); date.value = start.plusDays(20)
            day { it.date == start.plusDays(20) && it.items.size == 1 }
            withTimeout(5_000) { vm.habits.first { !it.loading && it.items.single().id == habit.id } }
            now = now.plusSeconds(86400); date.value = start.plusDays(21)
            day { it.date == start.plusDays(21) && it.items.isEmpty() }
            withTimeout(5_000) { vm.habits.first { !it.loading && it.items.isEmpty() } }
            // Only the two dates entered were generated; no backfill across the 19 missed dates.
            assertEquals(2, db.todos().allTodos().size)
            assertNotNull(repository.habit(habit.id))
            vm.selectDate(start)
            val oldDay = withTimeout(5_000) { vm.history.first { !it.loading && it.date == start && it.items.size == 1 } }
            assertEquals("拉伸五分钟", oldDay.items.single().title)
            assertEquals(2, db.todos().allTodos().size)
        } finally { habits.cancel(); history.cancel() }
    }
}
