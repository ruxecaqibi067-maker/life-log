package com.personal.lifelog.feature.settings

import com.personal.lifelog.data.backup.export.BackupDestination
import com.personal.lifelog.data.backup.export.BackupExporter
import com.personal.lifelog.data.backup.format.BackupError
import com.personal.lifelog.data.backup.format.BackupResult
import com.personal.lifelog.data.backup.format.backupError
import com.personal.lifelog.data.backup.importer.BackupImporter
import com.personal.lifelog.data.backup.importer.BackupSource
import com.personal.lifelog.data.backup.importer.PreparedBackup
import com.personal.lifelog.data.backup.restore.RecoveryStatus
import com.personal.lifelog.data.backup.restore.RestoreEngine
import com.personal.lifelog.data.backup.restore.RestoreResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application-scoped owner of backup and restore operations. It intentionally runs
 * outside any ViewModel: leaving Settings, rotating the screen, or putting the app
 * in the background must not cancel a restore that is already past its safe point.
 * The only in-memory capability is the importer's staging token inside [Preview];
 * it is never persisted or exposed to the UI layer.
 */
class BackupRestoreController(
    private val exporter: BackupExporter,
    private val importer: BackupImporter,
    private val engine: RestoreEngine,
    private val clock: Clock,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow<BackupRestoreState>(BackupRestoreState.Idle)
    val state: StateFlow<BackupRestoreState> = _state.asStateFlow()

    private var operationJob: Job? = null
    private var refreshJob: Job? = null

    val isRecoveryBlocked: Boolean
        get() = (_state.value as? BackupRestoreState.Result)?.outcome is BackupOutcome.RecoveryRequired

    /** Export a new complete backup to a user-selected SAF destination. */
    fun export(destination: BackupDestination) = runOperation(BackupOperation.EXPORTING) {
        when (val result = exporter.export(destination)) {
            is BackupResult.Success -> BackupRestoreState.Result(
                BackupOutcome.ExportSucceeded(result.value.exportedAtUtcMs, result.value.timestampRecorded),
            )
            is BackupResult.Failure -> BackupRestoreState.Result(BackupOutcome.ExportFailed(result.error))
        }
    }

    /** Copy the selected document into staging and validate it. Never touches the live database. */
    fun selectBackup(source: BackupSource) = runOperation(BackupOperation.PREFLIGHTING) {
        when (val result = importer.preflight(source)) {
            is BackupResult.Success -> BackupRestoreState.Preview(result.value)
            is BackupResult.Failure -> BackupRestoreState.Result(BackupOutcome.PreflightFailed(result.error))
        }
    }

    /** Explicit user confirmation: the only entry point that can replace live data. */
    fun confirmRestore() {
        val prepared = (_state.value as? BackupRestoreState.Preview)?.prepared ?: return
        if (operationJob?.isActive == true) return
        _state.value = BackupRestoreState.Working(BackupOperation.RESTORING)
        operationJob = scope.launch {
            refreshJob?.join()
            val counts = prepared.preview.counts
            val next = try {
                when (val result = engine.restore(prepared.token)) {
                    is RestoreResult.FailedBeforeCommit ->
                        BackupRestoreState.Result(BackupOutcome.RestoreFailed(result.error))
                    is RestoreResult.Committed ->
                        BackupRestoreState.Result(BackupOutcome.RestoreSucceeded(counts))
                    is RestoreResult.CommittedCleanupPending ->
                        BackupRestoreState.Result(BackupOutcome.RestoreCleanupPending(counts))
                    is RestoreResult.RecoveryRequired ->
                        BackupRestoreState.Result(BackupOutcome.RecoveryRequired(result.error))
                }
            } catch (cancelled: CancellationException) {
                BackupRestoreState.Result(BackupOutcome.RestoreCancelled)
            } catch (failure: Exception) {
                // The engine normally converts failures to an explicit result. Reaching
                // this branch means the outcome is unknown, so fail closed.
                BackupRestoreState.Result(BackupOutcome.RecoveryRequired(backupError(failure)))
            }
            // Consume the staging capability on every terminal path. Successful restores
            // already discard it; failures and cancellation can leave the token registered.
            withContext(NonCancellable) { runCatching { importer.discard(prepared.token) } }
            _state.value = next
        }
    }

    fun exportRollback(destination: BackupDestination) = runOperation(BackupOperation.ROLLBACK_EXPORTING) {
        when (val result = engine.exportRollback(destination)) {
            is BackupResult.Success -> BackupRestoreState.Result(
                BackupOutcome.RollbackExportSucceeded(clock.millis()),
            )
            is BackupResult.Failure -> BackupRestoreState.Result(BackupOutcome.RollbackExportFailed(result.error))
        }
    }

    /** Cancels the current operation. The engine reports the real post-commit state if cancellation is too late. */
    fun cancelCurrent() {
        val working = _state.value as? BackupRestoreState.Working ?: return
        if (working.cancelling) return
        if (working.operation !in CANCELABLE) return
        _state.value = working.copy(cancelling = true)
        operationJob?.cancel()
    }

    /** Cancel the preview dialog without ever touching live data, and release staging. */
    fun dismissPreview() {
        val prepared = (_state.value as? BackupRestoreState.Preview)?.prepared ?: return
        if (operationJob?.isActive == true) return
        _state.value = BackupRestoreState.Working(BackupOperation.DISCARDING)
        operationJob = scope.launch {
            importer.discard(prepared.token)
            _state.value = BackupRestoreState.Result(BackupOutcome.PreviewCancelled)
        }
    }

    /** Dismiss a completed message. RecoveryRequired stays visible until reconciliation succeeds. */
    fun dismissResult() {
        if (_state.value is BackupRestoreState.Result && !isRecoveryBlocked) {
            _state.value = BackupRestoreState.Idle
        }
    }

    /**
     * Query the durable journal after page recreation or a process restart. This is
     * silent when there is nothing pending so ordinary visits to Settings do not
     * manufacture a status message.
     */
    fun refreshRecoveryState() {
        if (_state.value != BackupRestoreState.Idle || refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            val next = try {
                when (val result = engine.recoverPending()) {
                    is BackupResult.Success -> when (result.value) {
                        RecoveryStatus.NO_PENDING_RESTORE -> null
                        RecoveryStatus.OLD_STATE_RETAINED ->
                            BackupRestoreState.Result(BackupOutcome.RecoveryCompleted(result.value))
                        RecoveryStatus.NEW_STATE_RETAINED ->
                            BackupRestoreState.Result(BackupOutcome.RecoveryCompleted(result.value))
                    }
                    is BackupResult.Failure ->
                        BackupRestoreState.Result(BackupOutcome.RecoveryRequired(result.error))
                }
            } catch (cancelled: CancellationException) {
                null
            } catch (failure: Exception) {
                BackupRestoreState.Result(BackupOutcome.RecoveryRequired(backupError(failure)))
            }
            if (next != null && _state.value == BackupRestoreState.Idle) _state.value = next
        }
    }

    /** Retry startup reconciliation / post-commit cleanup. Never bypasses the engine barrier. */
    fun retryRecovery() {
        if (operationJob?.isActive == true) return
        _state.value = BackupRestoreState.Working(BackupOperation.RECOVERING)
        operationJob = scope.launch {
            val next = try {
                when (val result = engine.recoverPending()) {
                    is BackupResult.Success ->
                        BackupRestoreState.Result(BackupOutcome.RecoveryCompleted(result.value))
                    is BackupResult.Failure ->
                        BackupRestoreState.Result(BackupOutcome.RecoveryRequired(result.error))
                }
            } catch (cancelled: CancellationException) {
                BackupRestoreState.Result(BackupOutcome.RecoveryRequired(null))
            } catch (failure: Exception) {
                BackupRestoreState.Result(BackupOutcome.RecoveryRequired(backupError(failure)))
            }
            _state.value = next
        }
    }

    fun suggestedFileName(rollback: Boolean, zoneId: ZoneId): String {
        val stamp = Instant.ofEpochMilli(clock.millis()).atZone(zoneId).format(FILE_STAMP)
        return (if (rollback) "life-log-rollback-$stamp.zip" else "life-log-$stamp.zip")
    }

    private fun runOperation(operation: BackupOperation, block: suspend () -> BackupRestoreState) {
        if (operationJob?.isActive == true) return
        if (_state.value is BackupRestoreState.Working || _state.value is BackupRestoreState.Preview) return
        if (isRecoveryBlocked) return
        _state.value = BackupRestoreState.Working(operation)
        val pendingRefresh = refreshJob
        operationJob = scope.launch {
            pendingRefresh?.join()
            val next = try {
                block()
            } catch (cancelled: CancellationException) {
                BackupRestoreState.Result(cancelledOutcome(operation))
            } catch (failure: Exception) {
                BackupRestoreState.Result(failedOutcome(operation, backupError(failure)))
            }
            _state.value = next
        }
    }

    private fun cancelledOutcome(operation: BackupOperation): BackupOutcome = when (operation) {
        BackupOperation.EXPORTING -> BackupOutcome.ExportCancelled
        BackupOperation.PREFLIGHTING -> BackupOutcome.PreflightCancelled
        BackupOperation.RESTORING -> BackupOutcome.RestoreCancelled
        BackupOperation.ROLLBACK_EXPORTING -> BackupOutcome.RollbackExportCancelled
        BackupOperation.DISCARDING -> BackupOutcome.PreviewCancelled
        BackupOperation.RECOVERING -> BackupOutcome.RecoveryRequired(null)
    }

    private fun failedOutcome(operation: BackupOperation, error: BackupError): BackupOutcome = when (operation) {
        BackupOperation.EXPORTING -> BackupOutcome.ExportFailed(error)
        BackupOperation.PREFLIGHTING -> BackupOutcome.PreflightFailed(error)
        BackupOperation.RESTORING -> BackupOutcome.RecoveryRequired(error)
        BackupOperation.ROLLBACK_EXPORTING -> BackupOutcome.RollbackExportFailed(error)
        BackupOperation.DISCARDING -> BackupOutcome.PreviewCancelled
        BackupOperation.RECOVERING -> BackupOutcome.RecoveryRequired(error)
    }

    private companion object {
        val CANCELABLE = setOf(
            BackupOperation.EXPORTING,
            BackupOperation.PREFLIGHTING,
            BackupOperation.RESTORING,
            BackupOperation.ROLLBACK_EXPORTING,
        )
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
    }
}

enum class BackupOperation { EXPORTING, PREFLIGHTING, RESTORING, ROLLBACK_EXPORTING, DISCARDING, RECOVERING }

sealed interface BackupRestoreState {
    data object Idle : BackupRestoreState
    data class Working(val operation: BackupOperation, val cancelling: Boolean = false) : BackupRestoreState
    data class Preview(val prepared: PreparedBackup) : BackupRestoreState
    data class Result(val outcome: BackupOutcome) : BackupRestoreState
}

sealed interface BackupOutcome {
    data class ExportSucceeded(val exportedAtUtcMs: Long, val timestampRecorded: Boolean) : BackupOutcome
    data class ExportFailed(val error: BackupError) : BackupOutcome
    data object ExportCancelled : BackupOutcome
    data class PreflightFailed(val error: BackupError) : BackupOutcome
    data object PreflightCancelled : BackupOutcome
    data object PreviewCancelled : BackupOutcome
    data class RestoreSucceeded(val counts: Map<String, Int>) : BackupOutcome
    data class RestoreCleanupPending(val counts: Map<String, Int>) : BackupOutcome
    data object RestoreCancelled : BackupOutcome
    data class RestoreFailed(val error: BackupError) : BackupOutcome
    data class RecoveryRequired(val error: BackupError?) : BackupOutcome
    data class RecoveryCompleted(val status: RecoveryStatus) : BackupOutcome
    data class RollbackExportSucceeded(val exportedAtUtcMs: Long) : BackupOutcome
    data class RollbackExportFailed(val error: BackupError) : BackupOutcome
    data object RollbackExportCancelled : BackupOutcome
}
