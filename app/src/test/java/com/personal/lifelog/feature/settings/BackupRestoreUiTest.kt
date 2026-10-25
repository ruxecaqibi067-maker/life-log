package com.personal.lifelog.feature.settings

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.personal.lifelog.data.backup.BackupFixture
import com.personal.lifelog.data.backup.MemoryDestination
import com.personal.lifelog.data.backup.format.BackupJson
import com.personal.lifelog.data.backup.importer.BackupImporter
import com.personal.lifelog.data.backup.importer.BackupSource
import com.personal.lifelog.data.backup.restore.RestoreEngine
import com.personal.lifelog.data.repository.SettingsRepository
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.ui.theme.LifeLogTheme
import java.io.ByteArrayInputStream
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/**
 * Minimal recovery-path UI test on the host: the real settings composable and the
 * real preflight/restore engine, with the SAF callback replaced by a synthetic
 * source. It proves the dialog gates replacement, cancellation leaves the old
 * dataset intact, a rejected package can be retried, and export success is shown.
 * It does not exercise a real document provider (T15 device check).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupRestoreUiTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var f: BackupFixture
    private lateinit var g: BackupFixture
    private lateinit var importer: BackupImporter
    private lateinit var scope: CoroutineScope
    private lateinit var controller: BackupRestoreController
    private lateinit var viewModel: SettingsViewModel
    private var nextSource: BackupSource = BackupSource { error("no source configured") }
    private val exportDestinations = mutableListOf<MemoryDestination>()

    @Before
    fun before() {
        f = BackupFixture()
        g = BackupFixture()
        importer = BackupImporter(f.root, f.clock)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine = RestoreEngine(
            f.db,
            f.writes,
            f.snapshots,
            f.exporter,
            importer,
            f.root,
            f.clock,
            syncDirectory = {},
            availableBytes = { Long.MAX_VALUE },
        )
        controller = BackupRestoreController(f.exporter, importer, engine, f.clock, scope)
        viewModel = SettingsViewModel(
            SettingsRepository(f.db, f.writes, f.clock),
            controller,
            { ZoneId.of("UTC") },
        )
    }

    @After
    fun after() {
        scope.cancel()
        f.close()
        g.close()
    }

    private fun setContent() {
        compose.setContent {
            val state by viewModel.backupState.collectAsState()
            val lastExport by viewModel.lastExportAtUtcMs.collectAsState()
            LifeLogTheme(ThemeMode.SYSTEM) {
                SettingsScreen(
                    themeMode = ThemeMode.SYSTEM,
                    themeWriteFailed = false,
                    onThemeSelected = {},
                    onOpenExercises = {},
                    onBack = {},
                    backupState = state,
                    backupAvailable = true,
                    lastExportAtUtcMs = lastExport,
                    zoneId = ZoneId.of("UTC"),
                    onExportBackup = {
                        val destination = MemoryDestination()
                        exportDestinations += destination
                        viewModel.exportBackup(destination)
                    },
                    onSelectBackup = { viewModel.selectBackup(nextSource) },
                    onExportRollback = {
                        val destination = MemoryDestination()
                        exportDestinations += destination
                        viewModel.exportRollback(destination)
                    },
                    onCancelBackupTask = viewModel::cancelBackupTask,
                    onConfirmRestore = viewModel::confirmRestore,
                    onCancelBackupPreview = viewModel::dismissBackupPreview,
                    onDismissBackupResult = viewModel::dismissBackupResult,
                    onRetryRecovery = viewModel::retryRecovery,
                )
            }
        }
    }

    private fun waitForState(predicate: (BackupRestoreState) -> Boolean) {
        compose.waitUntil(15_000) { predicate(viewModel.backupState.value) }
    }

    private fun semantic(snapshot: com.personal.lifelog.data.repository.DataSnapshot): String =
        BackupJson.encode(snapshot).toString()

    @Test
    fun previewShowsCountsAndCancelLeavesDataUntouched() {
        val old = runBlocking { f.seedWithWork("虚构旧记录", 480) }
        val zip = runBlocking {
            g.seedWithWork("虚构新记录", 600)
            g.zip()
        }
        nextSource = BackupSource { zip.inputStream() }
        setContent()

        compose.onNodeWithText("从备份恢复").performClick()
        waitForState { it is BackupRestoreState.Preview }
        compose.onNodeWithText("确认恢复此备份？").assertExists()
        compose.onNodeWithText("饮食记录：1 条").assertExists()
        compose.onNodeWithText("工作记录：2 段").assertExists()
        compose.onNodeWithText("包含进行中的工作").assertExists()
        compose.onNodeWithText("期望睡眠：10小时").assertExists()
        compose.onNodeWithText("期望工作：10小时15分").assertExists()
        compose.onNodeWithText("照片：1 张").assertExists()
        compose.onNodeWithText("导出备份").assertIsNotEnabled()
        assertEquals(semantic(old), runBlocking { semantic(f.snapshots.read()) })

        compose.onNodeWithText("取消").performClick()
        waitForState { it is BackupRestoreState.Result }
        compose.onNodeWithText("已取消恢复").assertExists()
        assertEquals(semantic(old), runBlocking { semantic(f.snapshots.read()) })
    }

    @Test
    fun workOnlyBackupHasVisibleWorkGoalAndCanBeRestored() {
        val old = runBlocking { f.seedWithWork("虚构旧记录", 480) }
        val incoming = runBlocking {
            g.work.startWork(com.personal.lifelog.domain.RecordIds.newId(), 1000, "UTC")
            g.settings.setTargetWorkMinutes(300)
            g.snapshots.read()
        }
        val zip = runBlocking { g.zip() }
        nextSource = BackupSource { zip.inputStream() }
        setContent()
        compose.onNodeWithText("从备份恢复").performClick()
        waitForState { it is BackupRestoreState.Preview }
        compose.onNodeWithText("工作记录：1 段").assertExists()
        compose.onNodeWithText("饮食记录：0 条").assertExists()
        compose.onNodeWithText("期望工作：5小时").assertExists()
        compose.onNodeWithText("包含进行中的工作").assertExists()
        assertEquals(semantic(old), runBlocking { semantic(f.snapshots.read()) })
        compose.onNodeWithText("覆盖并恢复").performClick()
        waitForState { it is BackupRestoreState.Result }
        compose.onNodeWithText("恢复完成").assertExists()
        compose.onNodeWithText("饮食 0 条 · 作息 0 条 · 训练 0 场 · 工作 1 段 · 照片 0 张", substring = true).assertExists()
        assertEquals(semantic(incoming), runBlocking { semantic(f.snapshots.read()) })
    }

    @Test
    fun confirmingRestoreReplacesDataAndShowsCompletion() {
        runBlocking {
            f.seedWithWork("虚构旧记录", 480)
            g.seedWithWork("虚构新记录", 600)
        }
        val incoming = runBlocking { g.snapshots.read() }
        val zip = runBlocking { g.zip() }
        nextSource = BackupSource { zip.inputStream() }
        setContent()

        compose.onNodeWithText("从备份恢复").performClick()
        waitForState { it is BackupRestoreState.Preview }
        compose.onNodeWithText("覆盖并恢复").performClick()
        waitForState { it is BackupRestoreState.Result }
        compose.onNodeWithText("恢复完成").assertExists()
        assertEquals(semantic(incoming), runBlocking { semantic(f.snapshots.read()) })
    }

    @Test
    fun rejectedPreflightShowsErrorAndCanRetryWithAnotherFile() {
        runBlocking {
            f.seedWithWork("虚构旧记录", 480)
            g.seedWithWork("虚构新记录", 600)
        }
        val valid = runBlocking { g.zip() }
        nextSource = BackupSource { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        setContent()

        compose.onNodeWithText("从备份恢复").performClick()
        waitForState { it is BackupRestoreState.Result }
        compose.onNodeWithText("无法使用该备份").assertExists()

        nextSource = BackupSource { valid.inputStream() }
        compose.onNodeWithText("重新选择备份").performClick()
        waitForState { it is BackupRestoreState.Preview }
        compose.onNodeWithText("确认恢复此备份？").assertExists()
    }

    @Test
    fun exportShowsSuccessAndPersistsLastExportTime() {
        runBlocking { f.seed() }
        setContent()

        compose.onNodeWithText("导出备份").performClick()
        waitForState { it is BackupRestoreState.Result }
        compose.onNodeWithText("备份已导出").assertExists()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("上次成功导出", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(exportDestinations.single().stream.size() > 0)
    }
}
