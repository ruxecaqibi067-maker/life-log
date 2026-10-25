package com.personal.lifelog.feature.sleep

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.espresso.Espresso.pressBack
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.feature.home.HomeViewModel
import com.personal.lifelog.feature.settings.SettingsViewModel
import com.personal.lifelog.ui.navigation.LifelogApp
import com.personal.lifelog.ui.theme.LifeLogTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real Compose navigation and Room on the host; device/system lifecycle evidence is separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class SleepUiTest {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private lateinit var db: AppDatabase
    private val store = ViewModelStore()
    private var now = Instant.parse("2026-10-22T15:50:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
    }
    private fun <T : ViewModel> vm(type: Class<T>, factory: () -> T): T = ViewModelProvider(store, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <V : ViewModel> create(modelClass: Class<V>): V = factory() as V
    })[type]

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.creationCallback(clock)).build()
        val writes = WriteCoordinator()
        val repository = RecordRepository(db, writes, clock) { ZoneId.of("Asia/Shanghai") }
        val workouts = WorkoutRepository(db, writes, clock)
        val timelineRepository = TimelineRepository(repository, workouts)
        restoration.setContent {
            LifeLogTheme(ThemeMode.SYSTEM) {
                val home = vm(HomeViewModel::class.java) { HomeViewModel(clock, { ZoneId.of("Asia/Shanghai") }, timelineRepository) }
                val settings = vm(SettingsViewModel::class.java) { SettingsViewModel(SettingsRepository(db, writes, clock)) }
                val sleep = vm(SleepViewModel::class.java) { SleepViewModel(repository, clock) { ZoneId.of("Asia/Shanghai") } }
                LifelogApp(
                    homeViewModel = home,
                    settingsViewModel = settings,
                    sleepViewModel = sleep,
                    records = repository,
                    photos = PhotoStorage(context, db, repository, writes, clock),
                    clock = clock,
                    zoneIdProvider = { ZoneId.of("Asia/Shanghai") },
                    workoutRepository = workouts,
                    timelineRepository = timelineRepository,
                )
            }
        }
        awaitText("准备睡了")
    }

    @After fun close() { store.clear(); db.close() }
    private fun awaitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun rows() = runBlocking { db.records().allSleeps() }

    @Test fun oneTapPersistsStartAndFinishAndUndoAndDeleteHaveSafeCancelPaths() {
        compose.onNodeWithText("准备睡了").performClick()
        awaitText("23:50")
        compose.onNodeWithTag("bedtime-screen").assertExists()
        assertNull(rows().single().endedAtUtcMs)
        pressBack()
        awaitText("起床了")
        assertNull(rows().single().endedAtUtcMs)
        assertEquals(1, rows().single().activeSlot)
        now = Instant.parse("2026-10-22T23:10:00Z")
        compose.onNodeWithText("起床了").performClick()
        awaitText("准备睡了")
        assertEquals("2026-10-23", rows().single().archiveLocalDate)
        compose.onNodeWithText("撤销上次作息操作").performClick()
        compose.onNodeWithText("取消").performClick()
        assertNotNull(rows().single().endedAtUtcMs)
        compose.onNodeWithText("撤销上次作息操作").performClick()
        compose.onNodeWithText("确认").performClick()
        awaitText("起床了")
        assertNull(rows().single().endedAtUtcMs)
        compose.onNodeWithText("编辑作息").performClick()
        compose.onNodeWithText("删除作息").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, rows().size)
        compose.onNodeWithText("删除作息").performScrollTo().performClick()
        compose.onNodeWithText("确认").performClick()
        awaitText("准备睡了")
        assertTrue(rows().isEmpty())
    }

    @Test fun bedtimeRestorationKeepsItsMessagesAndReturningDoesNotFinishOrDuplicateSleep() {
        compose.onNodeWithText("准备睡了").performClick()
        awaitText("23:50")
        val initial = rows().single()
        compose.onNodeWithTag("bedtime-messages").onChildren().assertCountEquals(3)
        val messages = compose.onNodeWithTag("bedtime-messages").onChildren().fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].single().text }
        restoration.emulateSavedInstanceStateRestore()
        awaitText("23:50")
        assertEquals(messages, compose.onNodeWithTag("bedtime-messages").onChildren().fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].single().text })
        assertEquals(initial, rows().single())
        pressBack()
        awaitText("起床了")
        assertEquals(initial, rows().single())
        compose.onNodeWithTag("bedtime-screen").assertDoesNotExist()
    }

    @Test fun failedStartStaysOnHomeAndRetryEntersBedtimeOnlyAfterDatabaseCommit() {
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_sleep BEFORE INSERT ON SleepRecord BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        compose.onNodeWithText("准备睡了").performClick()
        awaitText("无法读写作息记录，请重试；输入仍保留。")
        compose.onNodeWithTag("bedtime-screen").assertDoesNotExist()
        assertTrue(rows().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_sleep")
        compose.onNodeWithText("准备睡了").performClick()
        awaitText("23:50")
        assertEquals(1, rows().size)
    }

    @Test fun wakeOnBedtimeAfterRecreationFinishesSameRecordAndHomeUndoStillWorks() {
        compose.onNodeWithText("准备睡了").performClick()
        awaitText("23:50")
        val started = rows().single()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("bedtime-wake").assertIsDisplayed().assertIsEnabled()
        now = Instant.parse("2026-10-22T23:10:00.789Z")
        compose.onNodeWithTag("bedtime-wake").performClick()
        awaitText("准备睡了")
        compose.onNodeWithTag("bedtime-screen").assertDoesNotExist()
        val completed = rows().single()
        assertEquals(started.id, completed.id)
        assertEquals(started.startedAtUtcMs, completed.startedAtUtcMs)
        assertEquals(now.toEpochMilli(), completed.endedAtUtcMs)
        assertEquals("2026-10-23", completed.archiveLocalDate)
        compose.onNodeWithText("撤销上次作息操作").performClick()
        compose.onNodeWithText("确认").performClick()
        awaitText("起床了")
        assertNull(rows().single().endedAtUtcMs)
        assertEquals(started.id, rows().single().id)
    }

    @Test fun failedWakeKeepsDarkPageAndRecordThenRetrySavesOnce() {
        compose.onNodeWithText("准备睡了").performClick()
        awaitText("23:50")
        val started = rows().single()
        now = Instant.parse("2026-10-22T23:10:00Z")
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_wake BEFORE UPDATE ON SleepRecord BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        compose.onNodeWithTag("bedtime-wake").performClick()
        awaitText("无法读写作息记录，请重试；输入仍保留。")
        compose.onNodeWithTag("bedtime-screen").assertExists()
        compose.onNodeWithTag("bedtime-wake").assertIsEnabled()
        assertEquals(started, rows().single())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_wake")
        compose.onNodeWithTag("bedtime-wake").performClick()
        awaitText("准备睡了")
        assertEquals(started.id, rows().single().id)
        assertEquals(now.toEpochMilli(), rows().single().endedAtUtcMs)
    }

    @Test fun longWakeCancelKeepsBedtimeAndConfirmUsesCapturedInstant() {
        compose.onNodeWithText("准备睡了").performClick()
        awaitText("23:50")
        val started = rows().single()
        now = Instant.parse("2026-10-24T00:00:00Z")
        compose.onNodeWithTag("bedtime-wake").performClick()
        awaitText("请检查就寝时段")
        compose.onNodeWithText("取消").performClick()
        assertEquals(started, rows().single())
        compose.onNodeWithTag("bedtime-wake").assertIsDisplayed().assertIsEnabled().performClick()
        awaitText("请检查就寝时段")
        val captured = now.toEpochMilli()
        restoration.emulateSavedInstanceStateRestore()
        awaitText("请检查就寝时段")
        now = now.plusSeconds(1800)
        compose.onNodeWithText("确认").performClick()
        awaitText("准备睡了")
        assertEquals(started.id, rows().single().id)
        assertEquals(captured, rows().single().endedAtUtcMs)
    }

    @Test fun supplementShowsCompleteDatesAndDurationAndEditingMovesArchive() {
        compose.onNodeWithText("查看作息 / 补录").performClick()
        compose.onNodeWithText("补录作息").performClick()
        awaitText("就寝日期时间")
        compose.pickSleepTime("就寝日期时间", "2026-10-31T23:50")
        compose.pickSleepTime("起床日期时间", "2026-11-01T07:10")
        compose.onNodeWithText("保存作息").performScrollTo().performClick()
        awaitText("卧床时长：7小时20分")
        compose.onNodeWithText("就寝时间：2026年10月31日", substring = true).assertExists()
        compose.onNodeWithText("起床时间：2026年11月1日", substring = true).assertExists()
        assertEquals("2026-11-01", rows().single().archiveLocalDate)
        compose.onNodeWithText("卧床时长：7小时20分").performClick()
        awaitText("就寝日期时间")
        compose.pickSleepTime("就寝日期时间", "2026-12-31T23:50")
        compose.pickSleepTime("起床日期时间", "2027-01-01T07:10")
        compose.onNodeWithText("保存作息").performScrollTo().performClick()
        awaitText("归档日期：2027-01-01")
        assertEquals("2027-01-01", rows().single().archiveLocalDate)
        assertEquals(1, rows().size)
    }

    @Test fun leavingDirtySupplementRequiresConfirmationAndNeverSavesOnCancel() {
        compose.onNodeWithText("查看作息 / 补录").performClick()
        compose.onNodeWithText("补录作息").performClick()
        awaitText("就寝日期时间")
        compose.pickSleepTime("就寝日期时间", "2026-10-22T12:30")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃修改？").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("2026年10月22日 12:30").assertExists()
        assertTrue(rows().isEmpty())
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("确认").performClick()
        awaitText("还没有作息记录")
        assertTrue(rows().isEmpty())
    }
}
