package com.personal.lifelog.feature.sleep

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
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
        compose.setContent {
            LifeLogTheme(ThemeMode.SYSTEM) {
                val home = vm(HomeViewModel::class.java) { HomeViewModel(clock, { ZoneId.of("Asia/Shanghai") }, repository::datesWithRecords) }
                val settings = vm(SettingsViewModel::class.java) { SettingsViewModel(SettingsRepository(db, writes, clock)) }
                val sleep = vm(SleepViewModel::class.java) { SleepViewModel(repository, clock) { ZoneId.of("Asia/Shanghai") } }
                LifelogApp(home, settings, sleep)
            }
        }
        awaitText("准备睡了")
    }

    @After fun close() { store.clear(); db.close() }
    private fun awaitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun rows() = runBlocking { db.records().allSleeps() }

    @Test fun oneTapPersistsStartAndFinishAndUndoAndDeleteHaveSafeCancelPaths() {
        compose.onNodeWithText("准备睡了").performClick()
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

    @Test fun supplementShowsCompleteDatesAndDurationAndEditingMovesArchive() {
        compose.onNodeWithText("查看作息 / 补录").performClick()
        compose.onNodeWithText("补录作息").performClick()
        awaitText("就寝日期时间")
        compose.onNodeWithText("就寝日期时间").performTextReplacement("2026-10-31T23:50")
        compose.onNodeWithText("起床日期时间").performScrollTo().performTextReplacement("2026-11-01T07:10")
        compose.onNodeWithText("保存作息").performScrollTo().performClick()
        awaitText("卧床时长：7小时20分")
        compose.onNodeWithText("就寝时间：2026-10-31", substring = true).assertExists()
        compose.onNodeWithText("起床时间：2026-11-01", substring = true).assertExists()
        assertEquals("2026-11-01", rows().single().archiveLocalDate)
        compose.onNodeWithText("卧床时长：7小时20分").performClick()
        awaitText("就寝日期时间")
        compose.onNodeWithText("就寝日期时间").performTextReplacement("2026-12-31T23:50")
        compose.onNodeWithText("起床日期时间").performScrollTo().performTextReplacement("2027-01-01T07:10")
        compose.onNodeWithText("保存作息").performScrollTo().performClick()
        awaitText("归档日期：2027-01-01")
        assertEquals("2027-01-01", rows().single().archiveLocalDate)
        assertEquals(1, rows().size)
    }

    @Test fun leavingDirtySupplementRequiresConfirmationAndNeverSavesOnCancel() {
        compose.onNodeWithText("查看作息 / 补录").performClick()
        compose.onNodeWithText("补录作息").performClick()
        awaitText("就寝日期时间")
        compose.onNodeWithText("就寝日期时间").performTextReplacement("2026-10-22T12:30")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃修改？").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("2026-10-22T12:30").assertExists()
        assertTrue(rows().isEmpty())
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("确认").performClick()
        awaitText("还没有作息记录")
        assertTrue(rows().isEmpty())
    }
}
