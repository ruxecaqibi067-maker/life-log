package com.personal.lifelog.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Presentation values only. Completed intervals are supplied by T20, never summed here. */
data class WorkCardContent(
    val targetMinutes: Int?,
    val recordedDurationMs: Long?,
    val completedSegmentCount: Int,
    val hasActiveWork: Boolean,
)

data class WorkCardState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val content: WorkCardContent? = null,
)

@Composable
fun WorkSummaryCard(
    state: WorkCardState,
    onOpenHistory: () -> Unit,
    onEditTarget: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DurationSummaryCard(
        kind = DurationKind.WORK,
        targetMinutes = state.content?.targetMinutes,
        recordedDurationMs = state.content?.recordedDurationMs,
        active = state.content?.hasActiveWork == true,
        hasContent = state.content != null,
        loading = state.loading,
        failed = state.failed,
        onOpenHistory = onOpenHistory,
        onEditTarget = onEditTarget,
        onRetry = onRetry,
        modifier = modifier,
    )
}
