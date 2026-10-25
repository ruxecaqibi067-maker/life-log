package com.personal.lifelog.feature.sleep

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.personal.lifelog.R
import com.personal.lifelog.ui.theme.LogDimens
import com.personal.lifelog.ui.theme.LifeLogTheme
import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.ui.components.ActionButton
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val bedtimeClockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE)

/** A foreground-only clock. The saved sleep interval remains owned by Room. */
@Composable
fun BedtimeScreen(clock: Clock, zoneIdProvider: () -> ZoneId, viewModel: SleepViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    BedtimeSystemBars()
    // This page stays dark even when the app's saved theme is light, including feedback/dialogs.
    LifeLogTheme(ThemeMode.DARK) {
        BedtimeContent(clock, zoneIdProvider, viewModel, onBack)
    }
}

@Composable
private fun BedtimeContent(clock: Clock, zoneIdProvider: () -> ZoneId, viewModel: SleepViewModel, onBack: () -> Unit) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val pending by viewModel.longFinish.collectAsStateWithLifecycle()
    val record = status.active
    var sawActiveRecord by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(status.loaded, status.failed, record?.id) {
        if (status.loaded && !status.failed) {
            if (record != null) sawActiveRecord = true
            // Wait for Room to confirm the end. Initial start-flow delivery can lag navigation.
            else if (sawActiveRecord) onBack()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(clock.instant(), clock, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = clock.instant()
                delay(60_000L - Math.floorMod(clock.millis(), 60_000L))
            }
        }
    }
    val messages = stringArrayResource(R.array.bedtime_messages)
    // Stable while reading, rotating, or refreshing the minute; random again on a new visit.
    val selected = rememberSaveable { ArrayList(messages.indices.shuffled().take(3)) }
    val density = LocalDensity.current
    Column(Modifier.fillMaxSize().background(Color.Black).testTag("bedtime-screen")
        .windowInsetsPadding(WindowInsets.safeDrawing), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val fontSize = minOf(if (maxHeight < 360.dp) 56f else 88f,
                (maxWidth.value - 48f) / (3.2f * density.fontScale)).coerceAtLeast(24f).sp
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = bedtimeClockFormat.format(now.atZone(zoneIdProvider())),
                    modifier = Modifier.fillMaxWidth().testTag("bedtime-clock"),
                    color = Color(0xFFE0E0E0),
                    style = TextStyle(fontSize = fontSize, fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Light, fontFeatureSettings = "tnum", letterSpacing = 0.sp),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.height(32.dp))
                Column(Modifier.widthIn(max = 360.dp).fillMaxWidth().testTag("bedtime-messages"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    selected.forEach { index ->
                        Text(messages[index], modifier = Modifier.fillMaxWidth(), color = Color(0xFFAAAAAA),
                            style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Heading), textAlign = TextAlign.Center)
                    }
                }
                if (status.failed || error != null) {
                    Spacer(Modifier.height(LogDimens.section))
                    SleepError(error ?: ErrorCode.STORAGE_UNAVAILABLE)
                    if (status.failed) ActionButton(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) }
                } else if (!status.loaded) {
                    Spacer(Modifier.height(LogDimens.section))
                    Text(stringResource(R.string.sleep_loading), color = Color(0xFFAAAAAA))
                }
            }
        }
        OutlinedButton(
            onClick = { record?.let(viewModel::finish) },
            enabled = status.loaded && !status.failed && record != null && !busy && pending == null,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp).widthIn(max = 360.dp)
                .fillMaxWidth().heightIn(min = 56.dp).testTag("bedtime-wake"),
            border = BorderStroke(1.dp, Color(0xFF696969)),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF151515),
                contentColor = Color(0xFFE0E0E0), disabledContentColor = Color(0xFF999999)),
        ) {
            Text(stringResource(if (busy) R.string.saving else R.string.sleep_finish),
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
    SleepFinishConfirmation(viewModel)
}

@Composable
private fun BedtimeSystemBars() {
    val view = LocalView.current
    val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()
    val window = activity?.window ?: return
    val controller = WindowCompat.getInsetsController(window, view)
    DisposableEffect(window, view) {
        val wasLightStatus = controller.isAppearanceLightStatusBars
        val wasLightNavigation = controller.isAppearanceLightNavigationBars
        val behavior = controller.systemBarsBehavior
        val insets = ViewCompat.getRootWindowInsets(view)
        val statusWasVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigationWasVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.systemBarsBehavior = behavior
            if (statusWasVisible) controller.show(WindowInsetsCompat.Type.statusBars())
            if (navigationWasVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
            controller.isAppearanceLightStatusBars = wasLightStatus
            controller.isAppearanceLightNavigationBars = wasLightNavigation
        }
    }
    SideEffect {
        // Parent theme updates must not leave dark system icons on the black page.
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
    }
}
