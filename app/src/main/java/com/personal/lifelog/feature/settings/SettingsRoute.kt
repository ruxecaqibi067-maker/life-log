package com.personal.lifelog.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.lifelog.data.backup.export.SafBackupDestination
import com.personal.lifelog.data.backup.importer.SafBackupSource

/**
 * SAF adapter for the settings screen. The screen itself is a pure composable so
 * host tests can drive the same state machine without a document provider; drag
 * and drop / provider picker behavior still needs device verification.
 */
@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    onOpenExercises: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themeError by viewModel.themeError.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val lastExportAtUtcMs by viewModel.lastExportAtUtcMs.collectAsStateWithLifecycle()
    var pendingExport by rememberSaveable { mutableStateOf<PendingExport?>(null) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val pending = pendingExport
        pendingExport = null
        if (uri != null && pending != null) {
            val destination = SafBackupDestination(resolver, uri)
            when (pending) {
                PendingExport.BACKUP -> viewModel.exportBackup(destination)
                PendingExport.ROLLBACK -> viewModel.exportRollback(destination)
            }
        }
    }
    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.selectBackup(SafBackupSource(resolver, uri))
    }

    // Re-query the durable journal when this page is (re)entered. A normal restore
    // result stays in the app-scoped controller; only an unfinished journal is
    // converted into a visible recovery status here.
    LaunchedEffect(viewModel) {
        viewModel.refreshRecoveryState()
    }

    SettingsScreen(
        themeMode = themeMode,
        themeWriteFailed = themeError != null,
        onThemeSelected = viewModel::setTheme,
        onOpenExercises = onOpenExercises,
        onBack = onBack,
        backupState = backupState,
        backupAvailable = viewModel.backupAvailable,
        lastExportAtUtcMs = lastExportAtUtcMs,
        zoneId = viewModel.zoneIdProvider(),
        onExportBackup = {
            if (pendingExport == null) {
                pendingExport = PendingExport.BACKUP
                createDocument.launch(viewModel.suggestedBackupName(rollback = false))
            }
        },
        onSelectBackup = {
            openDocument.launch(
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
            )
        },
        onExportRollback = {
            if (pendingExport == null) {
                pendingExport = PendingExport.ROLLBACK
                createDocument.launch(viewModel.suggestedBackupName(rollback = true))
            }
        },
        onCancelBackupTask = viewModel::cancelBackupTask,
        onConfirmRestore = viewModel::confirmRestore,
        onCancelBackupPreview = viewModel::dismissBackupPreview,
        onDismissBackupResult = viewModel::dismissBackupResult,
        onRetryRecovery = viewModel::retryRecovery,
    )
}

private enum class PendingExport { BACKUP, ROLLBACK }
