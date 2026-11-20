package com.personal.lifelog.ui.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.speech.RecognitionService
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat

internal data class SpeechProvider(val component: ComponentName, val label: String, val system: Boolean)

/** Never ask Android to choose its default provider: on mainland phones that can be Google. */
internal object SpeechProviders {
    fun allowed(packageName: String, className: String = ""): Boolean =
        packageName != "com.google" && !packageName.startsWith("com.google.") &&
            packageName != "com.android.quicksearchbox" && !className.startsWith("com.google.")

    @Suppress("DEPRECATION")
    fun services(context: Context): List<SpeechProvider> = context.packageManager
        .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).mapNotNull { resolved ->
            val info = resolved.serviceInfo ?: return@mapNotNull null
            if (!info.enabled || !info.exported || !info.applicationInfo.enabled ||
                info.permission != "android.permission.BIND_SPEECH_RECOGNITION_SERVICE" ||
                !allowed(info.packageName, info.name)) return@mapNotNull null
            SpeechProvider(ComponentName(info.packageName, info.name), resolved.loadLabel(context.packageManager).toString(),
                info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        }.sortedWith(compareByDescending<SpeechProvider> { it.system }.thenBy { it.component.flattenToString() })

    @Suppress("DEPRECATION")
    fun activities(context: Context): List<SpeechProvider> = context.packageManager
        .queryIntentActivities(voiceRecognitionIntent(), PackageManager.MATCH_DEFAULT_ONLY).mapNotNull { resolved ->
            val info = resolved.activityInfo ?: return@mapNotNull null
            if (!info.enabled || !info.exported || !info.applicationInfo.enabled || !allowed(info.packageName, info.name) ||
                (info.permission != null && ContextCompat.checkSelfPermission(context, info.permission) != PackageManager.PERMISSION_GRANTED)) return@mapNotNull null
            SpeechProvider(ComponentName(info.packageName, info.name), resolved.loadLabel(context.packageManager).toString(),
                info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        }.sortedWith(compareByDescending<SpeechProvider> { it.system }.thenBy { it.component.flattenToString() })

    fun googleKeyboardSelected(context: Context): Boolean {
        val manager = context.getSystemService(InputMethodManager::class.java)
        val selected = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
        val packageName = manager.enabledInputMethodList.firstOrNull { it.id == selected }?.packageName
            ?: ComponentName.unflattenFromString(selected.orEmpty())?.packageName
        return packageName != null && !allowed(packageName)
    }
}
