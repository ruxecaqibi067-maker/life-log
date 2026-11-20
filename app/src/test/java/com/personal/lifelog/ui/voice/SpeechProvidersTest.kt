package com.personal.lifelog.ui.voice

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.*
import android.speech.RecognitionService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], application = Application::class)
class SpeechProvidersTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun googleAndGoogleNamedComponentsAreNeverAllowed() {
        for (pkg in listOf("com.google.android.googlequicksearchbox", "com.google.android.tts", "com.android.quicksearchbox", "com.google.android.inputmethod.latin")) assertFalse(SpeechProviders.allowed(pkg))
        assertFalse(SpeechProviders.allowed("example.proxy", "com.google.speech.Recognizer"))
        assertTrue(SpeechProviders.allowed("example.domestic"))
    }
    @Test fun googleOnlyPhoneDoesNotLaunchGoogleOrAskForAnImplicitProvider() {
        service("com.google.android.googlequicksearchbox", system = true)
        activity("com.google.android.googlequicksearchbox")
        assertTrue(SpeechProviders.services(context).isEmpty()); assertTrue(SpeechProviders.activities(context).isEmpty())
        val controller = Robolectric.buildActivity(VoiceInputActivity::class.java, VoiceInputActivity.intent(context, false)).setup()
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }
    @Test fun explicitNonGoogleSystemServiceWinsOverThirdPartyAndGoogle() {
        service("com.google.android.tts", system = true); service("example.thirdparty"); service("example.phone", system = true)
        val providers = SpeechProviders.services(context)
        assertEquals(listOf("example.phone", "example.thirdparty"), providers.map { it.component.packageName })
    }
    @Test fun privateDisabledAndIncorrectPermissionServicesCannotBeSelected() {
        service("example.private", exported = false); service("example.disabled", enabled = false)
        service("example.permission", permission = "example.custom")
        assertTrue(SpeechProviders.services(context).isEmpty())
    }
    @Test fun activityCandidatesExcludeGooglePrivateAndDisabledHandlers() {
        activity("com.google.android.googlequicksearchbox"); activity("example.private", exported = false); activity("example.disabled", enabled = false)
        activity("example.phone")
        assertEquals(listOf("example.phone"), SpeechProviders.activities(context).map { it.component.packageName })
    }
    @Test fun googleKeyboardIsDetectedBeforeAnyFocusRequest() {
        android.provider.Settings.Secure.putString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD, "com.google.android.inputmethod.latin/.LatinIME")
        assertTrue(SpeechProviders.googleKeyboardSelected(context))
        android.provider.Settings.Secure.putString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD, "example.domestic/.Ime")
        assertFalse(SpeechProviders.googleKeyboardSelected(context))
    }
    @Test fun nativeCaptureReturnsOneFinalResultAndReleasesTheRecognizer() {
        shadowOf(context.applicationContext as Application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        service("example.phone", system = true)
        val controller = Robolectric.buildActivity(VoiceInputActivity::class.java, VoiceInputActivity.intent(context, false)).setup()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val recognizer = org.robolectric.shadows.ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        assertNotNull(recognizer)
        val shadow = shadowOf(recognizer)
        shadow.triggerOnPartialResults(android.os.Bundle().apply { putStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("中间结果")) })
        assertFalse(controller.get().isFinishing)
        shadow.triggerOnResults(android.os.Bundle().apply { putStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("最终文字", "其他候选")) })
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(shadow.isDestroyed)
        assertEquals(android.app.Activity.RESULT_OK, shadowOf(controller.get()).resultCode)
        assertEquals(listOf("最终文字"), shadowOf(controller.get()).resultIntent.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS))
        controller.pause().stop().destroy()
    }
    @Test fun leavingNativeCaptureDestroysRecognizerAndDoesNotCommitSpeech() {
        shadowOf(context.applicationContext as Application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        service("example.phone", system = true)
        val controller = Robolectric.buildActivity(VoiceInputActivity::class.java, VoiceInputActivity.intent(context, false)).setup()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val recognizer = org.robolectric.shadows.ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        controller.pause().stop().destroy()
        assertTrue(shadowOf(recognizer).isDestroyed)
        assertEquals(android.app.Activity.RESULT_CANCELED, shadowOf(controller.get()).resultCode)
    }
    @Suppress("DEPRECATION")
    private fun service(pkg: String, system: Boolean = false, exported: Boolean = true, enabled: Boolean = true,
        permission: String = "android.permission.BIND_SPEECH_RECOGNITION_SERVICE") {
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), ResolveInfo().apply {
            nonLocalizedLabel = "Synthetic $pkg"
            serviceInfo = ServiceInfo().apply {
                packageName = pkg; name = "$pkg.Speech"; this.exported = exported; this.enabled = enabled; this.permission = permission
                applicationInfo = ApplicationInfo().apply { packageName = pkg; this.enabled = enabled; flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0 }
            }
        })
    }
    @Suppress("DEPRECATION")
    private fun activity(pkg: String, exported: Boolean = true, enabled: Boolean = true) {
        shadowOf(context.packageManager).addResolveInfoForIntent(voiceRecognitionIntent(), ResolveInfo().apply {
            isDefault = true; nonLocalizedLabel = "Synthetic $pkg"
            activityInfo = ActivityInfo().apply {
                packageName = pkg; name = "$pkg.Recognition"; this.exported = exported; this.enabled = enabled
                applicationInfo = ApplicationInfo().apply { packageName = pkg; this.enabled = enabled }
            }
        })
    }
}
