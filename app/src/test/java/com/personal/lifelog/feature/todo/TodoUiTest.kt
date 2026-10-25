package com.personal.lifelog.feature.todo

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.TodoRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.ui.theme.LifeLogTheme
import java.time.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Two end-to-end checklist flows backed by real Room, without publishing or touching an installed app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class TodoUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: AppDatabase
    private lateinit var repository: TodoRepository
    private lateinit var viewModel: TodoViewModel
    private val store = ViewModelStore()
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
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock)).build()
        repository = TodoRepository(db, WriteCoordinator(), clock) { zone }
        viewModel = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(c: Class<T>): T =
                TodoViewModel(repository, date, clock = clock, zoneIdProvider = { zone }) as T
        })[TodoViewModel::class.java]
        compose.setContent { LifeLogTheme(ThemeMode.LIGHT) {
            var management by remember { mutableStateOf(false) }
            if (management) TodoScreen(viewModel) { management = false }
            else TodoHomeSection(viewModel) { management = true }
            TodoDialogs(viewModel)
        } }
        compose.waitUntil(10_000) { !viewModel.home.value.loading }
    }

    @After fun close() { store.clear(); db.close() }
    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        try { node.assertIsDisplayed() } catch (_: AssertionError) { node.performScrollTo() }
        node.performClick()
    }
    private fun add(habit: Boolean, title: String) {
        compose.onNodeWithTag("todo-add-menu").performClick()
        click(if (habit) "21天习惯" else "手动待办")
        compose.onNodeWithTag("todo-title-input").performTextInput(title)
    }
    private fun save() {
        compose.onNodeWithTag("todo-save").performClick()
        compose.waitUntil(10_000) { viewModel.editor.value == null && !viewModel.commands.value.busy }
        assertNull(viewModel.commands.value.error)
    }

    @Test fun formCancelWheelTimeSaveCompleteUndoAndConfirmedDeleteUseRoom() {
        add(false, "取消的草稿")
        click("取消")
        assertTrue(runBlocking { db.todos().allTodos().isEmpty() })
        add(false, "读书十分钟")
        click("不指定时间")
        compose.onNodeWithTag("record-time-picker").assertExists()
        compose.onNodeWithTag("todo-wheel-时").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(19f) }
        compose.onNodeWithTag("todo-wheel-分").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(30f) }
        click("确认")
        save()
        compose.waitUntil(10_000) { viewModel.home.value.items.size == 1 }
        val item = runBlocking { db.todos().allTodos().single() }
        assertEquals(19 * 60 + 30, item.scheduledMinute)
        assertEquals(start.toString(), item.localDate)
        compose.onNodeWithTag("todo-check-${item.id}").performClick()
        compose.waitUntil(10_000) { viewModel.home.value.items.single().isCompleted && !viewModel.commands.value.busy }
        assertNotNull(runBlocking { repository.todo(item.id) }!!.completedAtUtcMs)
        compose.onNodeWithTag("todo-check-${item.id}").performClick()
        compose.waitUntil(10_000) { !viewModel.home.value.items.single().isCompleted && !viewModel.commands.value.busy }
        assertNull(runBlocking { repository.todo(item.id) }!!.completedAtUtcMs)
        compose.onNodeWithTag("todo-row-${item.id}").performClick()
        compose.onNodeWithTag("todo-title-input").performTextClearance()
        compose.onNodeWithTag("todo-title-input").performTextInput("未保存修改")
        click("取消")
        assertEquals("读书十分钟", runBlocking { repository.todo(item.id) }!!.title)
        compose.onNodeWithTag("todo-row-${item.id}").performClick()
        click("删除待办"); click("取消")
        assertNotNull(runBlocking { repository.todo(item.id) })
        click("删除待办"); click("确认")
        compose.waitUntil(10_000) { !viewModel.commands.value.busy && viewModel.home.value.items.isEmpty() }
        assertNull(runBlocking { repository.todo(item.id) })
    }

    @Test fun habitGeneratesTodayThenDisappearsAfterTwentyOneCalendarDaysAndKeepsHistory() {
        add(true, "拉伸五分钟")
        save()
        compose.waitUntil(10_000) { viewModel.home.value.items.size == 1 }
        val habit = runBlocking { db.todos().allHabits().single() }
        compose.onNodeWithTag("todo-open-all").performClick()
        compose.waitUntil(10_000) { !viewModel.habits.value.loading }
        compose.onNodeWithTag("habit-row-${habit.id}").performScrollTo().assertExists()
        compose.runOnIdle { now = now.plusSeconds(20L * 86400); date.value = start.plusDays(20) }
        compose.waitUntil(10_000) { viewModel.home.value.date == start.plusDays(20) && viewModel.home.value.items.size == 1 }
        compose.onNodeWithTag("habit-row-${habit.id}").assertExists()
        compose.runOnIdle { now = now.plusSeconds(86400); date.value = start.plusDays(21) }
        compose.waitUntil(10_000) { viewModel.home.value.date == start.plusDays(21) && !viewModel.home.value.loading && viewModel.habits.value.items.isEmpty() }
        compose.onNodeWithTag("habit-row-${habit.id}").assertDoesNotExist()
        // Only the two days actually entered were materialized; neither history nor a day jump backfills missed dates.
        assertEquals(2, runBlocking { db.todos().allTodos().size })
        assertNotNull(runBlocking { repository.habit(habit.id) })
        compose.runOnIdle { viewModel.selectDate(start) }
        compose.waitUntil(10_000) { viewModel.history.value.date == start && viewModel.history.value.items.size == 1 }
        assertEquals("拉伸五分钟", viewModel.history.value.items.single().title)
    }
}
