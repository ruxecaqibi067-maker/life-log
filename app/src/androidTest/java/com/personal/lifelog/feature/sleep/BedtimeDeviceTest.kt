package com.personal.lifelog.feature.sleep

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.feature.home.HomeViewModel
import com.personal.lifelog.feature.settings.SettingsViewModel
import com.personal.lifelog.ui.navigation.LifelogApp
import com.personal.lifelog.ui.theme.LifeLogTheme
import java.io.File
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual navigation + in-memory synthetic records; never touches the installed release's data. */
@RunWith(AndroidJUnit4::class)
class BedtimeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: AppDatabase
    private val clock = Clock.systemDefaultZone()
    private val fontScaleMultiplier = mutableFloatStateOf(1f)

    @Before fun setup() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.creationCallback(clock)).build()
        val writes = WriteCoordinator()
        val records = RecordRepository(db, writes, clock, ZoneId::systemDefault)
        val workouts = WorkoutRepository(db, writes, clock)
        val timeline = TimelineRepository(records, workouts)
        val home = HomeViewModel(clock, ZoneId::systemDefault, timeline)
        val settings = SettingsViewModel(SettingsRepository(db, writes, clock))
        val sleep = SleepViewModel(records, clock, ZoneId::systemDefault)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontScaleMultiplier.floatValue)) {
                LifeLogTheme(ThemeMode.LIGHT) {
                    LifelogApp(home, settings, records, PhotoStorage(context, db, records, writes, clock),
                        clock, ZoneId::systemDefault, sleep, workouts, timeline)
                }
            }
        }
        waitText("准备睡了")
    }

    @After fun close() { db.close() }
    private fun waitText(text: String) = compose.waitUntil(10_000) {
        try { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false }
    }
    private fun waitForBedtime() = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("bedtime-screen").fetchSemanticsNodes().isNotEmpty()
    }
    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private fun captureBlackScreenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(800) // Platform system-bar and navigation transition animations.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Android's first immersive-mode entry can show its own onboarding window.
        // Acknowledge it as a user would; do not change global device settings.
        instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Got it")
            ?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        android.os.SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            val file = File(instrumentation.targetContext.getExternalFilesDir(null), name)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val destination = "/data/local/tmp/${instrumentation.targetContext.packageName}-$name"
            // Separate package names avoid stale screenshots left by another test installation.
            shell("cp ${file.absolutePath} $destination")
            shell("chmod 644 $destination")
            val copiedSize = shell("stat -c %s $destination").trim().toLong()
            assertEquals(file.length(), copiedSize)
            assertEquals(Color.BLACK, bitmap.getPixel(0, 0))
            assertEquals(Color.BLACK, bitmap.getPixel(bitmap.width - 1, bitmap.height - 1))
        } finally { bitmap.recycle() }
    }

    @Test fun startsPersistThenShowsClockMessagesAndWakeButtonAndBackKeepsActiveRecord() {
        compose.onNodeWithText("准备睡了").performClick()
        waitForBedtime()
        val saved = runBlocking { db.records().allSleeps().single() }
        assertNull(saved.endedAtUtcMs)
        compose.onNodeWithTag("bedtime-clock").assertExists()
        compose.onNodeWithTag("bedtime-wake").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("bedtime-messages").onChildren().assertCountEquals(3)
        compose.onNodeWithText("记饮食").assertDoesNotExist()
        compose.onNodeWithText("回顾").assertDoesNotExist()
        captureBlackScreenshot("bedtime.png")
        pressBack()
        waitText("起床了")
        assertEquals(saved, runBlocking { db.records().allSleeps().single() })
        compose.onNodeWithText("回顾").assertExists()
        compose.runOnIdle {
            assertTrue(ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!.isVisible(WindowInsetsCompat.Type.statusBars()))
        }
    }

    @Test fun doubledComposeFontKeepsClockReadableAndAllMessagesReachable() {
        compose.runOnIdle { fontScaleMultiplier.floatValue = 2f }
        compose.onNodeWithText("准备睡了").performClick()
        waitForBedtime()
        captureBlackScreenshot("bedtime-font2x.png")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("bedtime-clock").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
            assertTrue(get(layouts))
        }
        val layout = layouts.single()
        // A single line's paragraph can reserve extra width. Check the visible line bounds,
        // allowing one pixel for Compose's float-to-integer size rounding.
        val bounds = "Clock: size=${layout.size}, left=${layout.getLineLeft(0)}, right=${layout.getLineRight(0)}, bottom=${layout.getLineBottom(0)}"
        assertEquals(1, layout.lineCount)
        assertTrue(bounds, layout.getLineLeft(0) >= -1f)
        assertTrue(bounds, layout.getLineRight(0) <= layout.size.width + 1f)
        assertTrue(bounds, layout.getLineBottom(0) <= layout.size.height + 1f)
        assertFalse(layout.isLineEllipsized(0))
        compose.onNodeWithTag("bedtime-messages").onChildren().assertCountEquals(3)
        compose.onNodeWithTag("bedtime-messages").onChildren()[2].performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("bedtime-wake").assertIsDisplayed().performClick()
        waitText("准备睡了")
        compose.onNodeWithTag("bedtime-screen").assertDoesNotExist()
        assertNotNull(runBlocking { db.records().allSleeps().single().endedAtUtcMs })
    }

    @Test fun wakeFromDarkPageKeepsSameRecordAndRestoresNavigation() {
        compose.onNodeWithText("准备睡了").performClick()
        waitForBedtime()
        val started = runBlocking { db.records().allSleeps().single() }
        compose.onNodeWithTag("bedtime-wake").assertIsDisplayed().assertIsEnabled().performClick()
        waitText("准备睡了")
        compose.onNodeWithTag("bedtime-screen").assertDoesNotExist()
        compose.onNodeWithText("回顾").assertIsDisplayed()
        val completed = runBlocking { db.records().allSleeps().single() }
        assertEquals(started.id, completed.id)
        assertEquals(started.startedAtUtcMs, completed.startedAtUtcMs)
        assertNotNull(completed.endedAtUtcMs)
    }

    @Test fun failedStartDoesNotEnterAndWakeStillFinishesTheSameRecordAfterReturning() {
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_sleep BEFORE INSERT ON SleepRecord BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        compose.onNodeWithText("准备睡了").performClick()
        waitText("无法读写作息记录，请重试；输入仍保留。")
        compose.onNodeWithTag("bedtime-screen").assertDoesNotExist()
        assertTrue(runBlocking { db.records().allSleeps().isEmpty() })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_sleep")
        compose.onNodeWithText("准备睡了").performClick()
        waitForBedtime()
        val id = runBlocking { db.records().allSleeps().single().id }
        pressBack()
        waitText("起床了")
        compose.onNodeWithText("起床了").performClick()
        waitText("准备睡了")
        val done = runBlocking { db.records().allSleeps().single() }
        assertEquals(id, done.id)
        assertNotNull(done.endedAtUtcMs)
        compose.onNodeWithTag("bedtime-screen").assertDoesNotExist()
    }
}
