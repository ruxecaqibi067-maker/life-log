package com.personal.lifelog.data.backup.export

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.util.Log
import com.personal.lifelog.data.backup.format.BackupJson
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.SnapshotRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Application-scoped observer. File I/O never depends on an Activity's lifetime. */
class LatestJsonExporter(
    private val db: AppDatabase,
    private val snapshots: SnapshotRepository,
    private val writes: WriteCoordinator,
    private val destination: LatestJsonDestination,
    private val preferences: SharedPreferences,
    private val clock: Clock,
    private val appVersion: String,
) {
    private val gate = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val revision = AtomicLong()
    private var publishedRevision = 0L
    private val _enabled = MutableStateFlow(preferences.getBoolean(ENABLED, true))
    val enabled = _enabled.asStateFlow()
    private val _writeFailed = MutableStateFlow(false)
    val writeFailed = _writeFailed.asStateFlow()
    private var observer: Job? = null

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (observer != null) return
        observer = scope.launch(Dispatchers.IO) {
            merge(
                db.invalidationTracker.createFlow(
                    "MealRecord", "MealPhoto", "SleepRecord", "WorkRecord", "TodoRecord", "HabitRecord",
                    "ExerciseDefinition", "WorkoutSession", "WorkoutItem", "WorkoutSet",
                    "MediaAsset", "AppSettings", "RestoreState",
                    emitInitialState = false,
                ).map {
                    revision.incrementAndGet()
                    Unit
                },
                requests.receiveAsFlow().onStart { emit(Unit) },
            ).retryWhen { cause, _ ->
                if (cause is CancellationException) throw cause
                reportFailure(cause)
                delay(1000)
                true
            }.debounce(600).collect { writeLatest() }
        }
    }

    fun requestWrite() { requests.trySend(Unit) }

    /** A completed disable waits for any current publication and persists across process death. */
    @SuppressLint("UseKtx") // The commit result is needed to report a persistence failure.
    suspend fun setEnabled(value: Boolean) = withContext(Dispatchers.IO + NonCancellable) {
        gate.withLock {
            try {
                check(preferences.edit().putBoolean(ENABLED, value).commit())
                _enabled.value = value
                _writeFailed.value = false
                if (value) requestWrite()
            } catch (e: Exception) {
                reportFailure(e)
            }
        }
    }

    private suspend fun writeLatest() = gate.withLock {
        if (!_enabled.value) return@withLock
        try {
            val snapshotRevision = revision.get()
            // Recovery and snapshot reads use the same lease as existing backup/restore operations.
            val json = writes.withExclusiveAccess {
                BackupJson.encode(snapshots.read(includeMedia = false)).apply { remove("media") }
            }.put("appVersion", appVersion)
            val previous = destination.read()?.let {
                try { JSONObject(it) } catch (_: org.json.JSONException) { null }
            }
            val hasTimestamp = previous?.has("generatedAtUtcMs") == true
            previous?.remove("generatedAtUtcMs")
            // Also repairs writes lost to process death and data changed while permission was absent.
            if (snapshotRevision != publishedRevision || !hasTimestamp || previous.toString() != json.toString()) {
                destination.write(json.put("generatedAtUtcMs", clock.millis()).toString())
            }
            publishedRevision = snapshotRevision
            _writeFailed.value = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportFailure(e)
        }
    }

    private fun reportFailure(cause: Throwable) {
        _writeFailed.value = true
        // No record contents or paths are logged.
        Log.w("LatestJson", "Automatic JSON export failed (${cause.javaClass.simpleName})")
    }

    private companion object { const val ENABLED = "enabled" }
}
