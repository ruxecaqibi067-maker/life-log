package com.personal.lifelog.feature.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.personal.lifelog.R
import com.personal.lifelog.data.backup.format.BackupError
import com.personal.lifelog.data.backup.importer.BackupPreview
import com.personal.lifelog.data.backup.restore.RecoveryStatus
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.ui.components.ActionButton
import com.personal.lifelog.ui.components.ErrorState
import com.personal.lifelog.ui.components.RecordCard
import com.personal.lifelog.ui.components.RecordTopBar
import com.personal.lifelog.ui.components.SecondaryButton
import com.personal.lifelog.ui.theme.LogDimens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Settings shell. Theme and exercise management keep their existing behavior;
 * T14 adds the full backup/restore surface. Selecting a document only produces a
 * preview here — live data is replaced only after the user taps the explicit
 * confirmation inside the preview dialog, and only through the T13 engine.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    themeWriteFailed: Boolean,
    onThemeSelected: (ThemeMode) -> Unit,
    onOpenExercises: () -> Unit,
    onBack: () -> Unit,
    backupState: BackupRestoreState = BackupRestoreState.Idle,
    backupAvailable: Boolean = false,
    lastExportAtUtcMs: Long? = null,
    zoneId: ZoneId = ZoneId.systemDefault(),
    onExportBackup: () -> Unit = {},
    onSelectBackup: () -> Unit = {},
    onExportRollback: () -> Unit = {},
    onCancelBackupTask: () -> Unit = {},
    onConfirmRestore: () -> Unit = {},
    onCancelBackupPreview: () -> Unit = {},
    onDismissBackupResult: () -> Unit = {},
    onRetryRecovery: () -> Unit = {},
) {
    val backupBlocked = backupState is BackupRestoreState.Working ||
        backupState is BackupRestoreState.Preview ||
        (backupState as? BackupRestoreState.Result)?.outcome is BackupOutcome.RecoveryRequired
    val canStartBackup = backupAvailable && !backupBlocked

    Scaffold(
        topBar = {
            RecordTopBar(
                title = { Text(stringResource(R.string.nav_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LogDimens.page, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(LogDimens.group),
        ) {
            SectionLabel(stringResource(R.string.settings_theme))
            ThemeSelector(themeMode, onThemeSelected)
            if (themeWriteFailed) {
                ErrorState(message = stringResource(R.string.settings_theme_error))
            }

            SecondaryButton(onClick = onOpenExercises, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FitnessCenter, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.settings_exercises))
            }

            SectionLabel(stringResource(R.string.settings_backup))
            Text(
                text = stringResource(R.string.settings_backup_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondaryButton(
                onClick = onExportBackup,
                enabled = canStartBackup,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Backup, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.settings_backup_export))
            }
            Text(
                text = lastExportAtUtcMs?.let {
                    stringResource(R.string.settings_last_export, formatBackupTime(it, zoneId))
                } ?: stringResource(R.string.settings_never_exported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondaryButton(
                onClick = onSelectBackup,
                enabled = canStartBackup,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Restore, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.settings_backup_restore))
            }
            SecondaryButton(
                onClick = onExportRollback,
                enabled = canStartBackup,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.History, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.settings_backup_rollback_export))
            }

            when (val state = backupState) {
                BackupRestoreState.Idle -> Unit
                is BackupRestoreState.Working -> BackupProgressCard(
                    operation = state.operation,
                    cancelling = state.cancelling,
                    onCancel = onCancelBackupTask,
                )
                is BackupRestoreState.Preview -> BackupPreviewDialog(
                    preview = state.prepared.preview,
                    zoneId = zoneId,
                    onConfirm = onConfirmRestore,
                    onCancel = onCancelBackupPreview,
                )
                is BackupRestoreState.Result -> BackupResultCard(
                    outcome = state.outcome,
                    zoneId = zoneId,
                    onDismiss = onDismissBackupResult,
                    onRetryRecovery = onRetryRecovery,
                    onExportBackup = onExportBackup,
                    onSelectBackup = onSelectBackup,
                    onExportRollback = onExportRollback,
                )
            }

            SectionLabel(stringResource(R.string.settings_about))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LogDimens.group),
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_version),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = versionName(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.settings_local_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class ThemeChoice(val mode: ThemeMode, val labelRes: Int) {
    SYSTEM(ThemeMode.SYSTEM, R.string.theme_system),
    LIGHT(ThemeMode.LIGHT, R.string.theme_light),
    DARK(ThemeMode.DARK, R.string.theme_dark),
}

/** Keep full labels and touch targets when the three-way control cannot fit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSelector(themeMode: ThemeMode, onSelected: (ThemeMode) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp || fontScale > 1.2f) {
            RecordCard {
                ThemeChoice.entries.forEach { choice ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = LogDimens.touch)
                            .selectable(themeMode == choice.mode, role = Role.RadioButton,
                                onClick = { onSelected(choice.mode) }).padding(horizontal = LogDimens.gap),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LogDimens.gap),
                    ) {
                        RadioButton(selected = themeMode == choice.mode, onClick = null)
                        Text(stringResource(choice.labelRes), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        } else {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeChoice.entries.forEachIndexed { index, choice ->
                    SegmentedButton(selected = themeMode == choice.mode, onClick = { onSelected(choice.mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeChoice.entries.size),
                        modifier = Modifier.weight(1f).heightIn(min = LogDimens.touch)) {
                        Text(stringResource(choice.labelRes))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun BackupProgressCard(
    operation: BackupOperation,
    cancelling: Boolean,
    onCancel: () -> Unit,
) {
    val title = when (operation) {
        BackupOperation.EXPORTING -> stringResource(R.string.backup_exporting_title)
        BackupOperation.PREFLIGHTING -> stringResource(R.string.backup_preflighting_title)
        BackupOperation.RESTORING -> stringResource(R.string.backup_restoring_title)
        BackupOperation.ROLLBACK_EXPORTING -> stringResource(R.string.backup_rollback_exporting_title)
        BackupOperation.DISCARDING -> stringResource(R.string.backup_discarding_title)
        BackupOperation.RECOVERING -> stringResource(R.string.backup_recovering_title)
    }
    val body = when (operation) {
        BackupOperation.EXPORTING -> stringResource(R.string.backup_exporting_body)
        BackupOperation.PREFLIGHTING -> stringResource(R.string.backup_preflighting_body)
        BackupOperation.RESTORING -> stringResource(R.string.backup_restoring_body)
        else -> null
    }
    BackupStatusCard(
        title = title,
        body = body,
        icon = null,
        showProgress = true,
    ) {
        if (operation in CANCELABLE_OPERATIONS) {
            ActionButton(onClick = onCancel, enabled = !cancelling) {
                Text(stringResource(if (cancelling) R.string.backup_cancelling else R.string.backup_cancel))
            }
        }
    }
}

@Composable
private fun BackupPreviewDialog(
    preview: BackupPreview,
    zoneId: ZoneId,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val counts = preview.counts
    val range = if (preview.earliestUtcMs != null && preview.latestUtcMs != null) {
        stringResource(
            R.string.backup_preview_range,
            formatBackupTime(preview.earliestUtcMs, zoneId),
            formatBackupTime(preview.latestUtcMs, zoneId),
        )
    } else {
        stringResource(R.string.backup_preview_no_range)
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.backup_preview_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.backup_preview_replace_warning),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.backup_preview_counts),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(countsLine(R.string.backup_preview_meals, counts["meals"] ?: 0))
                Text(countsLine(R.string.backup_preview_sleeps, counts["sleeps"] ?: 0))
                Text(countsLine(R.string.backup_preview_sessions, counts["sessions"] ?: 0))
                Text(countsLine(R.string.backup_preview_exercises, counts["exercises"] ?: 0))
                Text(countsLine(R.string.backup_preview_items, counts["items"] ?: 0))
                Text(countsLine(R.string.backup_preview_sets, counts["sets"] ?: 0))
                Text(countsLine(R.string.backup_preview_media, counts["media"] ?: 0))
                Text(range, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = stringResource(
                        if (preview.hasActiveSleep) {
                            R.string.backup_preview_has_active_sleep
                        } else {
                            R.string.backup_preview_no_active_sleep
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(
                        R.string.backup_preview_exported_at,
                        formatBackupTime(preview.exportedAtUtcMs, zoneId),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.backup_preview_app_version, preview.appVersion),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.backup_preview_rollback_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            ActionButton(onClick = onConfirm, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.backup_confirm_replace))
            }
        },
        dismissButton = {
            ActionButton(onClick = onCancel) {
                Text(stringResource(R.string.backup_cancel))
            }
        },
    )
}

@Composable
private fun BackupResultCard(
    outcome: BackupOutcome,
    zoneId: ZoneId,
    onDismiss: () -> Unit,
    onRetryRecovery: () -> Unit,
    onExportBackup: () -> Unit,
    onSelectBackup: () -> Unit,
    onExportRollback: () -> Unit,
) {
    when (outcome) {
        is BackupOutcome.ExportSucceeded -> BackupStatusCard(
            title = stringResource(R.string.backup_export_succeeded_title),
            body = if (outcome.timestampRecorded) {
                stringResource(
                    R.string.backup_export_succeeded_with_time,
                    formatBackupTime(outcome.exportedAtUtcMs, zoneId),
                )
            } else {
                stringResource(R.string.backup_export_succeeded_body) + " " +
                    stringResource(R.string.backup_export_timestamp_missing)
            },
            icon = Icons.Filled.CheckCircle,
            success = true,
        ) {
            ActionButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dismiss)) }
        }

        is BackupOutcome.ExportFailed -> BackupStatusCard(
            title = stringResource(R.string.backup_export_failed_title),
            body = stringResource(R.string.backup_export_failed_body),
            error = backupErrorMessage(outcome.error),
            icon = Icons.Filled.Warning,
            isError = true,
        ) {
            if (outcome.error == BackupError.RECOVERY_REQUIRED) {
                ActionButton(onClick = onRetryRecovery) {
                    Text(stringResource(R.string.backup_retry_recovery))
                }
            } else {
                ActionButton(onClick = onExportBackup) {
                    Text(stringResource(R.string.backup_retry_export))
                }
            }
            ActionButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dismiss)) }
        }

        BackupOutcome.ExportCancelled -> BackupStatusCard(
            title = stringResource(R.string.backup_export_cancelled_title),
            body = stringResource(R.string.backup_export_cancelled_body),
        ) {
            ActionButton(onClick = onExportBackup) { Text(stringResource(R.string.backup_retry_export)) }
            ActionButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dismiss)) }
        }

        is BackupOutcome.PreflightFailed -> BackupStatusCard(
            title = stringResource(R.string.backup_preflight_failed_title),
            body = stringResource(R.string.backup_preflight_failed_body),
            error = backupErrorMessage(outcome.error),
            icon = Icons.Filled.Warning,
            isError = true,
        ) {
            ActionButton(onClick = onSelectBackup) { Text(stringResource(R.string.backup_choose_again)) }
            ActionButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dismiss)) }
        }

        BackupOutcome.PreflightCancelled -> BackupStatusCard(
            title = stringResource(R.string.backup_preflight_cancelled_title),
            body = stringResource(R.string.backup_preflight_cancelled_body),
        ) {
            ActionButton(onClick = onSelectBackup) { Text(stringResource(R.string.backup_choose_again)) }
            ActionButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dismiss)) }
        }

        BackupOutcome.PreviewCancelled -> BackupStatusCard(
            title = stringResource(R.string.backup_preview_cancelled_title),
            body = stringResource(R.string.backup_preview_cancelled_body),
        ) {
            ActionButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dismiss)) }
        }

        is BackupOutcome.RestoreSucceeded -> BackupStatusCard(
            title = stringResource(R.string.backup_restore_succeeded_title),
            body = stringResource(R.string.backup_restore_succeeded_body) + "\n" +
                restoreCountsLine(outcome.counts),
            icon = Icons.Filled.CheckCircle,
            success = true,
        ) {
            ActionButton(onClick = onExportRollback) {
                Text(stringResource(R.string.settings_backup_rollback_export))
            }
            ActionButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dismiss)) }
        }

        is BackupOutcome.RestoreCleanupPending -> BackupStatusCard(
            title = stringResource(R.string.backup_restore_cleanup_pending_title),
            body = stringResource(R.string.backup_restore_cleanup_pending_body) + "\n" +
                restoreCountsLine(outcome.counts),
            icon = Icons.Filled.Warning,
        ) {
            ActionButton(onClick = onRetryRecovery) { Text(stringResource(R.string.backup_retry_cleanup)) }
            ActionButton(onClick = onExportRollback) {
                Text(stringResource(R.string.settings_backup_rollback_export))
            }
            ActionButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dismiss)) }
        }

        BackupOutcome.RestoreCancelled -> BackupStatusCard(
            title = stringResource(R.string.backup_restore_cancelled_title),
            body = stringResource(R.string.backup_restore_cancelled_body),
        ) {
            ActionButton(onClick = onSelectBackup) { Text(stringResource(R.string.backup_choose_again)) }
            ActionButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dismiss)) }
        }

        is BackupOutcome.RestoreFailed -> BackupStatusCard(
            title = stringResource(R.string.backup_restore_failed_title),
            body = stringResource(R.string.backup_restore_failed_body),
            error = backupErrorMessage(outcome.error),
            icon = Icons.Filled.Warning,
            isError = true,
        ) {
            ActionButton(onClick = onSelectBackup) { Text(stringResource(R.string.backup_choose_again)) }
            ActionButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dismiss)) }
        }

        is BackupOutcome.RecoveryRequired -> BackupStatusCard(
            title = stringResource(R.string.backup_recovery_required_title),
            body = stringResource(R.string.backup_recovery_required_body),
            error = outcome.error?.let { backupErrorMessage(it) },
            icon = Icons.Filled.Warning,
            isError = true,
        ) {
            ActionButton(onClick = onRetryRecovery) { Text(stringResource(R.string.backup_retry_recovery)) }
        }

        is BackupOutcome.RecoveryCompleted -> {
            val (title, body) = when (outcome.status) {
                RecoveryStatus.NO_PENDING_RESTORE ->
                    stringResource(R.string.backup_recovery_none_title) to
                        stringResource(R.string.backup_recovery_none_body)
                RecoveryStatus.OLD_STATE_RETAINED ->
                    stringResource(R.string.backup_recovery_old_title) to
                        stringResource(R.string.backup_recovery_old_body)
                RecoveryStatus.NEW_STATE_RETAINED ->
                    stringResource(R.string.backup_recovery_new_title) to
                        stringResource(R.string.backup_recovery_new_body)
            }
            BackupStatusCard(
                title = title,
                body = body,
                icon = Icons.Filled.CheckCircle,
                success = true,
            ) {
                ActionButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dismiss)) }
            }
        }

        is BackupOutcome.RollbackExportSucceeded -> BackupStatusCard(
            title = stringResource(R.string.backup_rollback_exported_title),
            body = stringResource(
                R.string.backup_rollback_exported_body,
            ) + "\n" + stringResource(
                R.string.backup_export_succeeded_with_time,
                formatBackupTime(outcome.exportedAtUtcMs, zoneId),
            ),
            icon = Icons.Filled.CheckCircle,
            success = true,
        ) {
            ActionButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dismiss)) }
        }

        is BackupOutcome.RollbackExportFailed -> BackupStatusCard(
            title = stringResource(R.string.backup_rollback_export_failed_title),
            body = stringResource(R.string.backup_rollback_export_failed_body),
            error = if (outcome.error == BackupError.EXPIRED_TOKEN) null else backupErrorMessage(outcome.error),
            icon = Icons.Filled.Warning,
            isError = true,
        ) {
            when (outcome.error) {
                BackupError.RECOVERY_REQUIRED -> ActionButton(onClick = onRetryRecovery) {
                    Text(stringResource(R.string.backup_retry_recovery))
                }
                BackupError.EXPIRED_TOKEN -> Unit
                else -> ActionButton(onClick = onExportRollback) {
                    Text(stringResource(R.string.backup_retry))
                }
            }
            ActionButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dismiss)) }
        }

        BackupOutcome.RollbackExportCancelled -> BackupStatusCard(
            title = stringResource(R.string.backup_rollback_export_cancelled_title),
            body = stringResource(R.string.backup_rollback_export_cancelled_body),
        ) {
            ActionButton(onClick = onExportRollback) { Text(stringResource(R.string.backup_retry)) }
            ActionButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dismiss)) }
        }
    }
}

@Composable
private fun BackupStatusCard(
    title: String,
    body: String?,
    error: String? = null,
    icon: ImageVector? = null,
    isError: Boolean = false,
    success: Boolean = false,
    showProgress: Boolean = false,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = when { isError -> MaterialTheme.colorScheme.errorContainer
            success -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow },
        contentColor = when { isError -> MaterialTheme.colorScheme.onErrorContainer
            success -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface },
    ) {
        Column(
            modifier = Modifier.padding(LogDimens.page),
            verticalArrangement = Arrangement.spacedBy(LogDimens.gap),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LogDimens.gap),
            ) {
                if (showProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = when {
                            isError -> MaterialTheme.colorScheme.error
                            success -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(text = title, style = MaterialTheme.typography.titleSmall)
            }
            if (body != null) {
                Text(text = body, style = MaterialTheme.typography.bodyMedium)
            }
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.Start,
                content = actions,
            )
        }
    }
}

@Composable
private fun countsLine(labelRes: Int, count: Int): String =
    stringResource(labelRes, count)

@Composable
private fun restoreCountsLine(counts: Map<String, Int>): String = stringResource(
    R.string.backup_restore_counts,
    counts["meals"] ?: 0,
    counts["sleeps"] ?: 0,
    counts["sessions"] ?: 0,
    counts["media"] ?: 0,
)

@Composable
private fun backupErrorMessage(error: BackupError?): String = stringResource(
    when (error) {
        BackupError.IO -> R.string.backup_error_io
        BackupError.STORAGE_FULL -> R.string.backup_error_storage_full
        BackupError.ACCESS_DENIED -> R.string.backup_error_access_denied
        BackupError.LIMIT -> R.string.backup_error_limit
        BackupError.INVALID_ARCHIVE -> R.string.backup_error_invalid_archive
        BackupError.UNSUPPORTED_VERSION -> R.string.backup_error_unsupported_version
        BackupError.INVALID_DATA -> R.string.backup_error_invalid_data
        BackupError.HASH_MISMATCH -> R.string.backup_error_hash_mismatch
        BackupError.INVALID_PHOTO -> R.string.backup_error_invalid_photo
        BackupError.EXPIRED_TOKEN -> R.string.backup_error_expired_token
        BackupError.RECOVERY_REQUIRED -> R.string.backup_error_recovery_required
        BackupError.DATABASE -> R.string.backup_error_database
        null -> R.string.backup_error_unknown
    },
)

@Composable
private fun versionName(): String {
    val context = LocalContext.current
    return try {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            ?: ""
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }
}

private val CANCELABLE_OPERATIONS = setOf(
    BackupOperation.EXPORTING,
    BackupOperation.PREFLIGHTING,
    BackupOperation.RESTORING,
    BackupOperation.ROLLBACK_EXPORTING,
)

private val backupTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE)

private fun formatBackupTime(utcMs: Long, zoneId: ZoneId): String =
    Instant.ofEpochMilli(utcMs).atZone(zoneId).format(backupTimeFormatter)
