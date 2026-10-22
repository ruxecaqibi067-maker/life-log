package com.personal.lifelog.feature.settings

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.SettingsRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.ThemeMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Theme wiring against the real Room AppSettings row (T02 contract), not a fake. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class SettingsViewModelTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-22T15:00:00Z"), ZoneOffset.UTC)
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock))
            .build()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel() = SettingsViewModel(SettingsRepository(db, WriteCoordinator(), clock))

    @Test
    fun setThemePersistsToRoomAndSurvivesViewModelRecreation() = runTest {
        val viewModel = viewModel()
        viewModel.setTheme(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, viewModel.themeMode.first { it == ThemeMode.DARK })
        assertNull(viewModel.themeError.value)
        assertEquals(ThemeMode.DARK, db.metadata().settingsNow().themeMode)

        // A fresh ViewModel over the same database reads the persisted choice.
        val reloaded = viewModel()
        assertEquals(ThemeMode.DARK, reloaded.themeMode.first { it == ThemeMode.DARK })
    }

    @Test
    fun setThemeCanReturnToSystemMode() = runTest {
        val viewModel = viewModel()
        viewModel.setTheme(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, viewModel.themeMode.first { it == ThemeMode.LIGHT })
        viewModel.setTheme(ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, viewModel.themeMode.first { it == ThemeMode.SYSTEM })
        assertEquals(ThemeMode.SYSTEM, db.metadata().settingsNow().themeMode)
    }
}
