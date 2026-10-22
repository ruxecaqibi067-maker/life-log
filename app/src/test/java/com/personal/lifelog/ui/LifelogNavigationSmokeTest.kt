package com.personal.lifelog.ui

import android.app.Application
import android.content.Context
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.SettingsRepository
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.feature.sleep.SleepViewModel
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.feature.home.HomeViewModel
import com.personal.lifelog.feature.settings.SettingsViewModel
import com.personal.lifelog.ui.navigation.LifelogApp
import com.personal.lifelog.ui.navigation.LifelogRoutes
import com.personal.lifelog.ui.theme.LifeLogTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Host-side navigation smoke test (Robolectric + Compose UI test, no device):
 * covers 今天→回顾→设置 and the reserved editor routes plus back behavior.
 * This is not a substitute for on-device checks; it only guards dead ends and
 * the stable route wiring on the host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class LifelogNavigationSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private val clock = Clock.fixed(Instant.parse("2026-10-22T15:00:00Z"), ZoneOffset.UTC)
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock))
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun setAppContent() {
        val settingsViewModel = SettingsViewModel(SettingsRepository(db, WriteCoordinator(), clock))
        val homeViewModel = HomeViewModel(clock, { ZoneId.of("Asia/Shanghai") }, db.records()::datesWithRecords)
        val sleepViewModel = SleepViewModel(RecordRepository(db, WriteCoordinator(), clock) { ZoneId.of("Asia/Shanghai") }, clock) { ZoneId.of("Asia/Shanghai") }
        compose.setContent {
            LifeLogTheme(ThemeMode.SYSTEM) {
                LifelogApp(homeViewModel = homeViewModel, settingsViewModel = settingsViewModel, sleepViewModel = sleepViewModel)
            }
        }
    }

    @Test
    fun todayReviewSettingsAndBackPathsStayConsistent() {
        setAppContent()
        // Start destination is today.
        compose.onNodeWithText("记饮食").assertExists()
        // today -> review via bottom tab.
        compose.onNodeWithText("回顾").performClick()
        compose.onNodeWithText("日期选择").assertExists()
        // review -> settings via top bar, and settings content is reachable.
        compose.onNodeWithContentDescription("打开设置").performClick()
        compose.onNodeWithText("主题").assertExists()
        compose.onNodeWithText("备份与恢复").assertExists()
        // Back from settings returns to the tab that opened it (review).
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("日期选择").assertExists()
        // Back to today, open settings from there, and return to today.
        compose.onNodeWithText("今天").performClick()
        compose.onNodeWithText("记饮食").assertExists()
        compose.onNodeWithContentDescription("打开设置").performClick()
        compose.onNodeWithText("主题").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("记饮食").assertExists()
    }

    @Test
    fun reservedEditorRoutesShowPlaceholderAndReturn() {
        setAppContent()
        compose.onNodeWithText("记饮食").performClick()
        compose.onNodeWithText("导航路由：meal/edit", substring = true).assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("记饮食").assertExists()

        compose.onNodeWithText("查看作息 / 补录").performClick()
        compose.onNodeWithText("补录作息").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()

        compose.onNodeWithText("记运动").performClick()
        compose.onNodeWithText("导航路由：workout/edit", substring = true).assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("记饮食").assertExists()
    }

    @Test
    fun editorArgumentReachesDestinationAndIsShown() {
        val mealId = "7e9d4c8b-2f1a-4e6b-9c3d-0a1b2c3d4e5f"
        // Navigate through the real NavController, not by clicking the card,
        // so the with-argument path of the reserved routes is exercised.
        compose.setContent {
            LifeLogTheme(ThemeMode.SYSTEM) {
                val navController = rememberNavController()
                Button(onClick = { navController.navigate(LifelogRoutes.mealEditor(mealId)) }) {
                    Text("open-with-id")
                }
                NavHost(navController, startDestination = LifelogRoutes.TODAY) {
                    composable(LifelogRoutes.TODAY) { }
                    composable(
                        route = LifelogRoutes.MEAL_EDIT_PATTERN,
                        arguments = listOf(LifelogRoutes.mealIdArgument),
                    ) { entry ->
                        val received = entry.arguments?.getString(LifelogRoutes.ARG_MEAL_ID)
                        Text("received:$received")
                    }
                }
            }
        }
        compose.onNodeWithText("open-with-id").performClick()
        compose.onNodeWithText("received:$mealId").assertExists()
    }
}
