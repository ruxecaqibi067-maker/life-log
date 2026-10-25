package com.personal.lifelog.feature.work

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.domain.*
import com.personal.lifelog.feature.home.*
import com.personal.lifelog.feature.sleep.SleepViewModel
import com.personal.lifelog.feature.settings.SettingsViewModel
import com.personal.lifelog.ui.navigation.LifelogApp
import com.personal.lifelog.ui.theme.LifeLogTheme
import kotlinx.coroutines.runBlocking
import java.time.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class WorkUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: AppDatabase
    private lateinit var work: WorkRepository
    private lateinit var settings: SettingsRepository
    private val store = ViewModelStore()
    private var now = Instant.parse("2026-10-22T09:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant() = now
    }
    private fun <T : ViewModel> vm(type: Class<T>, factory: () -> T): T = ViewModelProvider(store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <V : ViewModel> create(c: Class<V>): V = factory() as V
        })[type]
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.creationCallback(clock)).build()
        val writes = WriteCoordinator()
        val records = RecordRepository(db, writes, clock) { zone }
        val workouts = WorkoutRepository(db, writes, clock)
        work = WorkRepository(db, writes, clock) { zone }
        settings = SettingsRepository(db, writes, clock)
        val timeline = TimelineRepository(records, workouts, work)
        val home = vm(HomeViewModel::class.java) { HomeViewModel(clock, { zone }, timeline,
            SleepSummaryRepository(db)::observe, settings::setTargetSleepMinutes) }
        val workVM = vm(WorkViewModel::class.java) { WorkViewModel(work, clock) { zone } }
        val workHome = vm(WorkHomeViewModel::class.java) { WorkHomeViewModel(home.today, WorkSummaryRepository(db)::observe, settings::setTargetWorkMinutes) }
        val sleep = vm(SleepViewModel::class.java) { SleepViewModel(records, clock) { zone } }
        val settingsVM = vm(SettingsViewModel::class.java) { SettingsViewModel(settings) }
        compose.setContent { LifeLogTheme(ThemeMode.LIGHT) {
            LifelogApp(home, settingsVM, records, PhotoStorage(context, db, records, writes, clock), clock,
                { zone }, sleep, workouts, timeline, workVM, workHome)
        } }
        compose.waitUntil(10_000) { workVM.status.value.loaded && !workHome.work.value.loading }
        waitText("今日睡眠")
    }
    @After fun close() { store.clear(); db.close() }
    private fun waitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun workButton() {
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("work-start-finish").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("work-start-finish").assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun click(text: String) {
        waitText(text)
        val node = compose.onNodeWithText(text)
        try { node.assertIsDisplayed() } catch (_: AssertionError) { node.performScrollTo() }
        node.performClick()
    }

    @Test fun startStaysHomeFinishRefreshesHistoryDeleteCancelAndUndoAreSafe() {
        workButton()
        compose.waitUntil(10_000) { runBlocking { db.work().activeWorkNow() != null } }
        compose.onNodeWithTag("bedtime-screen").assertDoesNotExist()
        compose.onNodeWithText("记饮食").assertExists()
        assertTrue(runBlocking { db.records().allSleeps().isEmpty() })
        now = now.plusSeconds(5 * 3600)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("结束工作").fetchSemanticsNodes().isNotEmpty() }
        workButton()
        compose.waitUntil(10_000) { runBlocking { db.work().allWorks().single().endedAtUtcMs != null } }
        compose.onNodeWithTag("home-timeline").performScrollToNode(hasTestTag("work-summary-history"))
        compose.onNodeWithTag("work-summary-history").performClick()
        waitText("补录工作")
        click("查看 / 编辑")
        waitText("开始日期时间")
        click("删除工作"); click("取消")
        assertEquals(1, runBlocking { db.work().allWorks().size })
        click("删除工作"); click("确认")
        waitText("补录工作")
        assertTrue(runBlocking { db.work().allWorks().isEmpty() })
        click("撤销上次工作操作"); click("确认")
        compose.waitUntil(10_000) { runBlocking { db.work().allWorks().size == 1 } }
        assertTrue(runBlocking { db.records().allSleeps().isEmpty() })
    }

}
