package com.personal.lifelog.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.backup.export.BackupDestination
import com.personal.lifelog.data.backup.importer.BackupSource
import com.personal.lifelog.data.repository.SettingsRepository
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.ThemeMode
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Theme preference state backed by the Room AppSettings row via SettingsRepository,
 * plus the settings entry point for backup/restore. Backup operations themselves
 * live in the application-scoped [BackupRestoreController] so they survive page
 * recreation; this ViewModel only mirrors its state and delegates user intent.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val backups: BackupRestoreController? = null,
    val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settings.settings()
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    /** Non-null while the last theme write failed; the screen shows a generic retry message. */
    private val _themeError = MutableStateFlow<String?>(null)
    val themeError: StateFlow<String?> = _themeError.asStateFlow()

    val lastExportAtUtcMs: StateFlow<Long?> = settings.settings()
        .map { it.lastSuccessfulExportAtUtcMs }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val backupState: StateFlow<BackupRestoreState> =
        backups?.state ?: MutableStateFlow<BackupRestoreState>(BackupRestoreState.Idle)

    val backupAvailable: Boolean get() = backups != null

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch {
            when (val result = settings.setTheme(mode)) {
                is DataResult.Success -> _themeError.value = null
                is DataResult.Failure -> _themeError.value = result.error.code.name
            }
        }
    }

    fun exportBackup(destination: BackupDestination) {
        backups?.export(destination)
    }

    fun selectBackup(source: BackupSource) {
        backups?.selectBackup(source)
    }

    fun exportRollback(destination: BackupDestination) {
        backups?.exportRollback(destination)
    }

    fun confirmRestore() {
        backups?.confirmRestore()
    }

    fun cancelBackupTask() {
        backups?.cancelCurrent()
    }

    fun dismissBackupPreview() {
        backups?.dismissPreview()
    }

    fun dismissBackupResult() {
        backups?.dismissResult()
    }

    fun retryRecovery() {
        backups?.retryRecovery()
    }

    fun refreshRecoveryState() {
        backups?.refreshRecoveryState()
    }

    fun suggestedBackupName(rollback: Boolean): String =
        backups?.suggestedFileName(rollback, zoneIdProvider()) ?: "life-log-backup.zip"
}
