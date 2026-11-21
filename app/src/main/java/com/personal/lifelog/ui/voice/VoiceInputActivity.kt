package com.personal.lifelog.ui.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.personal.lifelog.R
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.ui.components.*
import com.personal.lifelog.ui.theme.LifeLogTheme
import com.personal.lifelog.ui.theme.LogDimens

/** Private result bridge: our UI + explicitly selected non-Google providers only. */
class VoiceInputActivity : ComponentActivity() {
    companion object {
        internal const val KEYBOARD = "lifelog.voice.keyboard"
        internal const val DARK = "lifelog.voice.dark"
        internal fun intent(context: android.content.Context, dark: Boolean) =
            Intent(context, VoiceInputActivity::class.java).putExtra(DARK, dark)
    }
    private var status by mutableStateOf("")
    private var providerLabel by mutableStateOf("")
    private var recording by mutableStateOf(false)
    private var delegated by mutableStateOf(false)
    private var permissionPending = false
    private var delivered = false
    private var capture: SpeechCapture? = null
    private var selected: ComponentName? = null
    private val failedProviders = mutableSetOf<ComponentName>()
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { fail(R.string.voice_timeout, providerFailed = true) }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending = false
        if (!delivered && !isFinishing) {
            if (granted) begin() else fail(R.string.voice_permission_denied)
        }
    }
    private val delegate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        delegated = false
        if (!delivered && !isFinishing) {
            when {
                result.resultCode == Activity.RESULT_CANCELED -> cancelResult()
                result.resultCode == Activity.RESULT_OK -> {
                    val text = bestVoiceCandidate(result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS))
                    if (text == null) fail(R.string.voice_empty) else textResult(text)
                }
                else -> fail(R.string.voice_failed, providerFailed = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        delegated = savedInstanceState?.getBoolean("delegatePending") == true
        permissionPending = savedInstanceState?.getBoolean("permissionPending") == true
        savedInstanceState?.getStringArrayList("failedProviders")?.mapNotNull(ComponentName::unflattenFromString)?.let(failedProviders::addAll)
        selected = savedInstanceState?.getString("selected")?.let(ComponentName::unflattenFromString)
        setContent {
            LifeLogTheme(if (intent.getBooleanExtra(DARK, false)) ThemeMode.DARK else ThemeMode.LIGHT) {
                Scaffold(topBar = { RecordTopBar(title = { Text(stringResource(R.string.voice_title)) }) }) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(LogDimens.page),
                        verticalArrangement = Arrangement.spacedBy(LogDimens.page),
                    ) {
                        TonalCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                            Column(
                                Modifier.fillMaxWidth().padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(
                                    modifier = Modifier.size(56.dp),
                                    shape = CircleShape,
                                    color = if (recording || delegated) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (recording || delegated) {
                                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                                        } else {
                                            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(26.dp),
                                                tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                                Text(status, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                                if (providerLabel.isNotEmpty()) {
                                    Text(getString(R.string.voice_provider, providerLabel),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center)
                                }
                                Text(stringResource(R.string.voice_network_note), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }
                        if (recording) PrimaryButton({ capture?.stop(); status = getString(R.string.voice_processing) }, Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.voice_finish_speaking))
                        }
                        if (!recording && !delegated) SecondaryButton(::begin, Modifier.fillMaxWidth()) { Text(stringResource(R.string.voice_retry)) }
                        SecondaryButton(::keyboardResult, Modifier.fillMaxWidth()) { Text(stringResource(R.string.voice_use_keyboard)) }
                        ActionButton(::cancelResult, Modifier.align(Alignment.End)) { Text(stringResource(R.string.picker_cancel)) }
                    }
                }
            }
        }
        if (savedInstanceState == null) begin()
        else status = getString(if (delegated || permissionPending) R.string.voice_waiting else R.string.voice_interrupted)
    }

    private fun begin() {
        if (delivered || capture != null || delegated || permissionPending) return
        val service = SpeechProviders.services(this).firstOrNull { it.component !in failedProviders }
        if (service != null) {
            selected = service.component
            providerLabel = service.label
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionPending = true
                permission.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            try {
                val engine = AndroidSpeechEngine(this, service.component)
                status = getString(R.string.voice_waiting)
                recording = true
                capture = SpeechCapture(engine, { text ->
                    capture = null
                    handler.removeCallbacks(timeout)
                    recording = false
                    if (!delivered && !isFinishing) {
                        if (text == null) fail(R.string.voice_empty) else textResult(text)
                    }
                }, { _ -> if (!delivered && !isFinishing) fail(R.string.voice_failed, providerFailed = true) }, {
                    status = getString(R.string.voice_listening)
                })
                handler.postDelayed(timeout, 30_000)
                capture?.start()
            } catch (_: Exception) { fail(R.string.voice_failed, providerFailed = true) }
            return
        }
        val activity = SpeechProviders.activities(this).firstOrNull { it.component !in failedProviders }
        if (activity == null) { fail(R.string.voice_no_domestic_provider); return }
        providerLabel = activity.label
        selected = activity.component
        try {
            delegated = true
            status = getString(R.string.voice_waiting)
            delegate.launch(voiceRecognitionIntent().setComponent(activity.component).putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt)))
        } catch (_: Exception) { delegated = false; fail(R.string.voice_failed, providerFailed = true) }
    }

    private fun fail(message: Int, providerFailed: Boolean = false) {
        if (providerFailed) selected?.let(failedProviders::add)
        release()
        status = getString(message)
    }
    private fun release() {
        val old = capture
        capture = null
        recording = false
        handler.removeCallbacks(timeout)
        old?.cancel()
    }
    private fun textResult(text: String) = deliver(Activity.RESULT_OK,
        Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text)))
    private fun keyboardResult() = deliver(Activity.RESULT_OK, Intent().putExtra(KEYBOARD, true))
    private fun cancelResult() = deliver(Activity.RESULT_CANCELED, null)
    private fun deliver(code: Int, data: Intent?) {
        if (delivered) return
        delivered = true
        release()
        setResult(code, data)
        finish()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("delegatePending", delegated)
        outState.putBoolean("permissionPending", permissionPending)
        outState.putStringArrayList("failedProviders", ArrayList(failedProviders.map { it.flattenToString() }))
        outState.putString("selected", selected?.flattenToString())
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        if (capture != null) fail(R.string.voice_interrupted)
        super.onStop()
    }
    override fun onDestroy() { release(); super.onDestroy() }
}
