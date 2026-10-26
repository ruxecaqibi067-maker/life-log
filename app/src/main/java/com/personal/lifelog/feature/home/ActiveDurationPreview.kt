package com.personal.lifelog.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** A foreground-only projection; the persisted completed total is never modified. */
internal fun activeElapsedMs(startedAtUtcMs: Long?, nowUtcMs: Long): Long? {
    if (startedAtUtcMs == null) return null
    if (nowUtcMs <= startedAtUtcMs) return 0L
    return try { Math.subtractExact(nowUtcMs, startedAtUtcMs) }
    catch (_: ArithmeticException) { Long.MAX_VALUE }
}

internal fun previewDurationMs(completedMs: Long?, activeMs: Long?): Long? {
    if (activeMs == null) return completedMs
    val completed = completedMs?.coerceAtLeast(0L) ?: 0L
    val elapsed = activeMs.coerceAtLeast(0L)
    return if (Long.MAX_VALUE - completed < elapsed) Long.MAX_VALUE else completed + elapsed
}

/** No background loop. Re-entering STARTED reads the real clock immediately. */
@Composable
internal fun rememberActiveElapsed(startedAtUtcMs: Long?, clock: Clock): State<Long?> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(activeElapsedMs(startedAtUtcMs, clock.millis()), startedAtUtcMs, clock, lifecycle) {
        if (startedAtUtcMs == null) { value = null; return@produceState }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = activeElapsedMs(startedAtUtcMs, clock.millis())
                delay(1_000)
            }
        }
    }
}
