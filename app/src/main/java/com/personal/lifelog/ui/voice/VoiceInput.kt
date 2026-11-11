package com.personal.lifelog.ui.voice

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.personal.lifelog.R

data class VoiceInputAction(val waiting: Boolean, val enabled: Boolean, val onClick: () -> Unit)

internal fun voiceRecognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    // A provider may ignore this preference. It is not an offline guarantee.
    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
}

/** Register above lazy fields. Each request has its own registry key and captured token. */
@Composable
fun BindVoiceInput(session: VoiceInputSession, ready: Boolean = true, onText: (target: String, text: String) -> Unit) {
    val context = LocalContext.current
    val registry = checkNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
    val request by session.request.collectAsState()
    val currentOnText by rememberUpdatedState(onText)
    DisposableEffect(session, registry, request?.token, ready) {
        val pending = request.takeIf { ready }
        val launcher = pending?.let {
            registry.register("description-voice-${it.token}", ActivityResultContracts.StartActivityForResult()) { result ->
                val target = session.consume(it.token) ?: return@register
                when (result.resultCode) {
                    Activity.RESULT_CANCELED -> Unit
                    Activity.RESULT_OK -> {
                        val text = bestVoiceCandidate(result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS))
                        if (text == null) context.voiceMessage(R.string.voice_empty)
                        else currentOnText(target, text)
                    }
                    else -> context.voiceMessage(R.string.voice_failed)
                }
            }
        }
        if (pending != null && launcher != null && session.markLaunched(pending.token)) {
            val intent = voiceRecognitionIntent().putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.voice_prompt))
            try {
                if (intent.resolveActivity(context.packageManager) == null) {
                    session.consume(pending.token)
                    context.voiceMessage(R.string.voice_unavailable)
                } else launcher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                session.consume(pending.token)
                context.voiceMessage(R.string.voice_unavailable)
            } catch (_: SecurityException) {
                session.consume(pending.token)
                context.voiceMessage(R.string.voice_failed)
            }
        }
        onDispose { launcher?.unregister() }
    }
    DisposableEffect(session, context) {
        onDispose {
            // The external recognizer itself stops our Activity; ON_STOP is not navigation.
            // Keep an in-flight request across configuration change, invalidate on leaving the editor.
            if (context.activity()?.isChangingConfigurations != true) session.cancel()
        }
    }
}

@Composable
fun voiceInputAction(session: VoiceInputSession, target: String, enabled: Boolean): VoiceInputAction {
    val pending by session.request.collectAsState()
    val waiting = pending?.target == target
    return VoiceInputAction(waiting, enabled && (pending == null || waiting)) {
        if (waiting) session.cancel() else session.begin(target)
    }
}

private fun Context.voiceMessage(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
