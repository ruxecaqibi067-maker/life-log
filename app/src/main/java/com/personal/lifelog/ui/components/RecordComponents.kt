package com.personal.lifelog.ui.components

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.personal.lifelog.ui.voice.VoiceInputAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.personal.lifelog.R
import com.personal.lifelog.ui.theme.LogDimens

/** Presentation only: callers keep ownership of events, state and enabled rules. */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) = Button(onClick, modifier.heightIn(min = LogDimens.action), enabled = enabled,
    shape = MaterialTheme.shapes.large, colors = colors, content = content)

@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(onClick, modifier.heightIn(min = LogDimens.touch), enabled = enabled,
    shape = MaterialTheme.shapes.large, colors = colors, content = content)

@Composable
fun ActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    content: @Composable RowScope.() -> Unit,
) = TextButton(onClick, modifier.heightIn(min = LogDimens.touch), enabled = enabled,
    shape = MaterialTheme.shapes.medium, colors = colors, content = content)

@Composable
fun RecordCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable ColumnScope.() -> Unit,
) = OutlinedCard(modifier, shape = shape,
    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(LogDimens.border, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)), content = content)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable ColumnScope.() -> Unit,
) = OutlinedCard(onClick, modifier, enabled = enabled, shape = shape,
    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(LogDimens.border, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)), content = content)

/** Borderless tonal container for nested rows or grouped sections without double borders. */
@Composable
fun TonalCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) = Surface(modifier = modifier, shape = shape, color = color) {
    Column(content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TonalCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) = Surface(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, color = color) {
    Column(content = content)
}

/** Shared section heading with primary tint so every form and settings block reads uniformly. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) = Text(
    text = title,
    modifier = modifier,
    style = MaterialTheme.typography.titleSmall,
    color = color,
)

/** Unified confirmation dialog with scrollable content and clear primary/destructive emphasis. */
@Composable
fun ConfirmActionDialog(
    @StringRes titleRes: Int,
    @StringRes confirmRes: Int,
    @StringRes cancelRes: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes messageRes: Int? = null,
    messageText: String? = null,
    destructive: Boolean = false,
    busy: Boolean = false,
    extraContent: @Composable (ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = modifier,
        title = { Text(stringResource(titleRes)) },
        text = if (messageRes != null || messageText != null || extraContent != null) {
            {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(LogDimens.gap),
                ) {
                    if (messageRes != null) Text(stringResource(messageRes))
                    if (messageText != null) Text(messageText)
                    if (extraContent != null) extraContent()
                }
            }
        } else null,
        confirmButton = {
            if (destructive) {
                Button(
                    onClick = onConfirm,
                    enabled = !busy,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.heightIn(min = LogDimens.touch),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(if (busy) R.string.saving else confirmRes))
                }
            } else {
                PrimaryButton(
                    onClick = onConfirm,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = LogDimens.touch),
                ) {
                    Text(stringResource(if (busy) R.string.saving else confirmRes))
                }
            }
        },
        dismissButton = {
            ActionButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(cancelRes))
            }
        },
    )
}

@Composable
fun RecordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    voiceInput: VoiceInputAction? = null,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(TextFieldValue(value)) }
    LaunchedEffect(value, voiceInput != null) {
        if (voiceInput != null && editing.text != value) editing = TextFieldValue(value, TextRange(value.length))
    }
    LaunchedEffect(voiceInput?.keyboardRequested, enabled) {
        if (enabled && voiceInput?.keyboardRequested == true) {
            editing = TextFieldValue(value, TextRange(value.length))
            focus.requestFocus()
            keyboard?.show()
            voiceInput.keyboardHandled()
        }
    }
    val trailing: @Composable (() -> Unit)? = voiceInput?.let { action -> {
        IconButton(onClick = action.onClick, enabled = enabled && action.enabled) {
            Icon(if (action.waiting) Icons.Default.Close else Icons.Default.Mic,
                stringResource(if (action.waiting) R.string.voice_cancel else R.string.voice_append),
                tint = if (enabled && action.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        }
    } }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
    if (voiceInput != null) {
        val displayValue = if (editing.text == value) editing else TextFieldValue(value, TextRange(value.length))
        OutlinedTextField(displayValue, { next -> editing = next; if (next.text != value) onValueChange(next.text) }, modifier.focusRequester(focus), enabled = enabled,
            textStyle = MaterialTheme.typography.bodyLarge, label = label, placeholder = placeholder,
            supportingText = supportingText, isError = isError, keyboardOptions = keyboardOptions,
            singleLine = singleLine, maxLines = maxLines, minLines = minLines,
            shape = MaterialTheme.shapes.large, colors = colors, trailingIcon = trailing)
    } else OutlinedTextField(value, onValueChange, modifier.focusRequester(focus), enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge, label = label, placeholder = placeholder,
        supportingText = supportingText, isError = isError, keyboardOptions = keyboardOptions,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        shape = MaterialTheme.shapes.large, colors = colors, trailingIcon = trailing)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) = TopAppBar(title = title, modifier = modifier, navigationIcon = navigationIcon, actions = actions,
    colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ))

/** Native date/time dialogs must follow the stored app theme, including API 28. */
@Composable
fun rememberPickerContext(): Context {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return remember(context, dark) { createPickerContext(context, dark) }
}

internal fun createPickerContext(context: Context, dark: Boolean): Context =
    ContextThemeWrapper(context, if (dark) R.style.Theme_LifeLog_Picker_Dark else R.style.Theme_LifeLog_Picker_Light)

/** Android 9 has an opaque navigation scrim; its background must match manual theme icons. */
@Composable
internal fun SyncLegacySystemBarBackground(bedtime: Boolean) {
    val view = LocalView.current
    val background = if (bedtime) android.graphics.Color.BLACK else MaterialTheme.colorScheme.background.toArgb()
    SideEffect {
        if (Build.VERSION.SDK_INT == 28) {
            (view.context as? Activity)?.window?.navigationBarColor = background
        }
    }
}
