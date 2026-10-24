package com.personal.lifelog.feature.sleep

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.personal.lifelog.R
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val bedtimeClockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE)

/** A foreground-only clock. The saved sleep interval remains owned by Room. */
@Composable
fun BedtimeScreen(clock: Clock, zoneIdProvider: () -> ZoneId, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    BedtimeSystemBars()
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
    var clockHeight by remember { mutableStateOf(0.dp) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("bedtime-screen")) {
        val fontSize = minOf(if (maxHeight < 420.dp) 48f else 76f,
            (maxWidth.value - 48f) / (3.4f * density.fontScale)).coerceAtLeast(24f).sp
        Text(
            text = bedtimeClockFormat.format(now.atZone(zoneIdProvider())),
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp)
                .onSizeChanged { clockHeight = with(density) { it.height.toDp() } }.testTag("bedtime-clock"),
            color = Color(0xFFD8D8D8),
            style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"),
            maxLines = 1,
            softWrap = false,
        )
        val quoteTop = maxHeight / 2 + clockHeight / 2 + 28.dp
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = quoteTop, start = 28.dp, end = 28.dp, bottom = 24.dp)
                .verticalScroll(rememberScrollState()).testTag("bedtime-messages"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            selected.forEach { index ->
                Text(messages[index], color = Color(0xFF999999), fontSize = 16.sp, lineHeight = 26.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun BedtimeSystemBars() {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
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
