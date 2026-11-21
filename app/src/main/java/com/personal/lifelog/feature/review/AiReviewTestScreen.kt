package com.personal.lifelog.feature.review

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.lifelog.R
import com.personal.lifelog.domain.DeepSeekError
import com.personal.lifelog.ui.components.ActionButton
import com.personal.lifelog.ui.components.LoadingState
import com.personal.lifelog.ui.components.PrimaryButton
import com.personal.lifelog.ui.components.RecordCard
import com.personal.lifelog.ui.components.RecordTopBar
import com.personal.lifelog.ui.components.SecondaryButton
import com.personal.lifelog.ui.components.SectionHeader
import com.personal.lifelog.ui.components.TonalCard
import com.personal.lifelog.ui.theme.LogDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiReviewTestScreen(
    viewModel: AiReviewTestViewModel,
    onBack: () -> Unit,
) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val sampleState by viewModel.sampleState.collectAsStateWithLifecycle()
    val hasKey = apiKey.trim().isNotEmpty()

    Scaffold(
        topBar = {
            RecordTopBar(
                title = { Text(stringResource(R.string.ai_review_test_title)) },
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LogDimens.page, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(LogDimens.page),
        ) {
            // Step 1: Process-scoped masked API key input & clear button.
            RecordCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(LogDimens.card),
                    verticalArrangement = Arrangement.spacedBy(LogDimens.gap),
                ) {
                    SectionHeader(title = stringResource(R.string.ai_review_key_section))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = viewModel::updateApiKey,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai-review-key-input"),
                        singleLine = true,
                        label = { Text(stringResource(R.string.ai_review_key_label)) },
                        placeholder = { Text(stringResource(R.string.ai_review_key_placeholder)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                        ),
                        shape = MaterialTheme.shapes.large,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(
                                if (hasKey) R.string.ai_review_key_status_set else R.string.ai_review_key_status_empty,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                                .testTag("ai-review-key-status"),
                        )
                        ActionButton(
                            onClick = viewModel::clearApiKey,
                            enabled = apiKey.isNotEmpty(),
                            modifier = Modifier.testTag("ai-review-key-clear"),
                        ) {
                            Text(stringResource(R.string.ai_review_key_clear))
                        }
                    }
                    Text(
                        text = stringResource(R.string.ai_review_key_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Step 2: Built-in fictional sample connection test (never reads Room).
            RecordCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(LogDimens.card),
                    verticalArrangement = Arrangement.spacedBy(LogDimens.gap),
                ) {
                    SectionHeader(title = stringResource(R.string.ai_review_sample_section))
                    if (sampleState is SampleConnectionState.Idle) {
                        SecondaryButton(
                            onClick = viewModel::openSampleConfirm,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai-review-sample-open"),
                        ) {
                            Text(stringResource(R.string.ai_review_sample_action))
                        }
                    }

                    when (val state = sampleState) {
                        SampleConnectionState.Idle -> Unit
                        is SampleConnectionState.Confirming -> {
                            Text(
                                text = stringResource(R.string.ai_review_sample_quota_notice),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (!hasKey) {
                                Text(
                                    text = stringResource(R.string.ai_review_sample_need_key),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            SampleJsonPreviewBox(state.sampleJson)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(LogDimens.gap),
                            ) {
                                ActionButton(
                                    onClick = viewModel::cancelSampleTest,
                                    modifier = Modifier.testTag("ai-review-sample-cancel"),
                                ) {
                                    Text(stringResource(R.string.ai_review_cancel))
                                }
                                PrimaryButton(
                                    onClick = viewModel::confirmSampleSend,
                                    enabled = hasKey,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("ai-review-sample-confirm"),
                                ) {
                                    Text(stringResource(R.string.ai_review_sample_confirm_send))
                                }
                            }
                        }
                        is SampleConnectionState.Sending -> {
                            LoadingState(
                                message = stringResource(R.string.ai_review_sample_sending),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai-review-sample-loading"),
                            )
                            ActionButton(
                                onClick = viewModel::cancelSampleTest,
                                modifier = Modifier.testTag("ai-review-sample-cancel"),
                            ) {
                                Text(stringResource(R.string.ai_review_cancel))
                            }
                        }
                        is SampleConnectionState.Succeeded -> {
                            TonalCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai-review-sample-result"),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.ai_review_sample_result_title),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = state.content,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(LogDimens.gap),
                            ) {
                                SecondaryButton(
                                    onClick = viewModel::openSampleConfirm,
                                    modifier = Modifier.testTag("ai-review-sample-open"),
                                ) {
                                    Text(stringResource(R.string.ai_review_sample_action))
                                }
                                ActionButton(
                                    onClick = viewModel::cancelSampleTest,
                                    modifier = Modifier.testTag("ai-review-sample-close"),
                                ) {
                                    Text(stringResource(R.string.review_ai_preview_close))
                                }
                            }
                        }
                        is SampleConnectionState.Failed -> {
                            Text(
                                text = stringResource(deepSeekErrorMessageRes(state.error)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("ai-review-sample-error"),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(LogDimens.gap),
                            ) {
                                ActionButton(
                                    onClick = viewModel::cancelSampleTest,
                                    modifier = Modifier.testTag("ai-review-sample-cancel"),
                                ) {
                                    Text(stringResource(R.string.ai_review_cancel))
                                }
                                SecondaryButton(
                                    onClick = viewModel::retrySampleSend,
                                    enabled = hasKey,
                                    modifier = Modifier.testTag("ai-review-sample-retry"),
                                ) {
                                    Text(stringResource(R.string.review_ai_send_retry))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SampleJsonPreviewBox(sampleJson: String) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = sampleJson,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
                .testTag("ai-review-sample-json"),
        )
    }
}

@StringRes
internal fun deepSeekErrorMessageRes(error: DeepSeekError): Int = when (error) {
    DeepSeekError.MISSING_API_KEY -> R.string.ai_review_err_missing_key
    DeepSeekError.INVALID_API_KEY -> R.string.ai_review_err_invalid_key
    DeepSeekError.UNAUTHORIZED -> R.string.ai_review_err_unauthorized
    DeepSeekError.INSUFFICIENT_BALANCE -> R.string.ai_review_err_balance
    DeepSeekError.RATE_LIMITED -> R.string.ai_review_err_rate_limit
    DeepSeekError.BAD_REQUEST -> R.string.ai_review_err_bad_request
    DeepSeekError.TIMEOUT -> R.string.ai_review_err_timeout
    DeepSeekError.NETWORK -> R.string.ai_review_err_network
    DeepSeekError.SERVER -> R.string.ai_review_err_server
    DeepSeekError.EMPTY_CONTENT -> R.string.ai_review_err_empty
    DeepSeekError.TRUNCATED -> R.string.ai_review_err_truncated
    DeepSeekError.MALFORMED_RESPONSE -> R.string.ai_review_err_malformed
    DeepSeekError.ASSET_UNAVAILABLE -> R.string.ai_review_err_asset
}
