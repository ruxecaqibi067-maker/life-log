package com.personal.lifelog.ui.voice

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.speech.RecognizerIntent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** Synthetic recognizer results exercise application routing, never a microphone or real service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], application = Application::class)
class VoiceInputSessionTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun session(saved: SavedStateHandle = SavedStateHandle()) = VoiceInputSession(saved, "test.voice")
    private fun restored(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    @Before fun resetToasts() { ShadowToast.reset() }

    @Test fun repeatedBeginAndLaunchCannotReplaceAnOutstandingTarget() {
        val session = session()
        session.begin("workout-item-a")
        val first = session.request.value!!
        session.begin("workout-item-b")
        assertEquals(first, session.request.value)
        assertTrue(session.markLaunched(first.token))
        assertFalse(session.markLaunched(first.token))
        assertTrue(session.request.value!!.launched)
        assertEquals("workout-item-a", session.consume(first.token))
        assertNull(session.consume(first.token))
        assertNull(session.request.value)
    }

    @Test fun cancelledOrConsumedTokensCannotConsumeAnotherRequest() {
        val session = session()
        session.begin("meal-a")
        val cancelled = session.request.value!!.token
        session.cancel()
        session.begin("meal-b")
        val current = session.request.value!!
        assertNotEquals(cancelled, current.token)
        assertFalse(session.markLaunched(cancelled))
        assertNull(session.consume(cancelled))
        assertEquals(current, session.request.value)
        assertEquals("meal-b", session.consume(current.token))
        session.begin("meal-b")
        val reopened = session.request.value!!
        assertNotEquals(current.token, reopened.token)
        assertNull(session.consume(current.token))
        assertEquals(reopened, session.request.value)
    }

    @Test fun launchedRequestSurvivesSavedStateRestorationWithoutBeingRelaunched() {
        val saved = SavedStateHandle()
        val original = session(saved)
        original.begin("item-stable-id")
        val token = original.request.value!!.token
        assertTrue(original.markLaunched(token))
        val copy = restored(saved)
        val recreated = session(copy)
        assertEquals(original.request.value, recreated.request.value)
        assertFalse(recreated.markLaunched(token))
        assertEquals("item-stable-id", recreated.consume(token))
        // Persisting consumption before draft changes prevents a second append after recreation.
        assertNull(session(restored(copy)).consume(token))
    }

    @Test fun notYetLaunchedRequestCanLaunchOnceAfterRestoration() {
        val saved = SavedStateHandle()
        val original = session(saved)
        original.begin("todo-id")
        val token = original.request.value!!.token
        val recreated = session(restored(saved))
        assertTrue(recreated.markLaunched(token))
        assertFalse(recreated.markLaunched(token))
        recreated.cancel()
        assertNull(recreated.request.value)
    }

    @Test fun independentEditorsAndMalformedSavedStateDoNotShareRequests() {
        val saved = SavedStateHandle(mapOf("bad.voice" to arrayListOf("incomplete")))
        val meal = VoiceInputSession(saved, "meal.voice")
        val todo = VoiceInputSession(saved, "todo.voice")
        meal.begin("description")
        todo.begin("title")
        meal.cancel()
        assertEquals("title", todo.consume(todo.request.value!!.token))
        assertNull(VoiceInputSession(saved, "bad.voice").request.value)
    }

    @Test fun candidatesUseOneUsableHypothesisAndNeverJoinAlternatives() {
        assertEquals("番茄炒蛋", bestVoiceCandidate(listOf("  ", " 番茄炒蛋 ", "番茄炒饭")))
        assertNull(bestVoiceCandidate(null))
        assertNull(bestVoiceCandidate(emptyList()))
        assertNull(bestVoiceCandidate(listOf("", " \n\t")))
    }

    @Test fun limitsMatchTrimmedUnicodeCodePointsWithoutTruncatingText() {
        val text = "  " + "\uD83C\uDF5A".repeat(120) + " \n"
        assertFalse(exceedsVoiceTextLimit(text, 120))
        assertTrue(exceedsVoiceTextLimit(text + "菜", 120))
        assertEquals(244, text.length)
        assertFalse(exceedsVoiceTextLimit(" \t\n", 0))
    }

    @Test fun requestUsesChineseFreeFormAndProviderPreferenceWithoutPinningAVendor() {
        val intent = voiceRecognitionIntent()
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals("zh-CN", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertFalse(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0))
        assertNull(intent.`package`)
        assertNull(intent.component)
        val permissions = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertFalse(permissions.contains(android.Manifest.permission.RECORD_AUDIO))
        assertFalse(permissions.contains(android.Manifest.permission.INTERNET))
    }

    @Test fun missingRecognizerClearsWaitingAndKeepsKeyboardDraft() {
        assertNull(voiceRecognitionIntent().resolveActivity(context.packageManager))
        val session = session()
        val registry = SyntheticRegistry()
        var draft = "原有键盘文字"
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner(registry)) {
                BindVoiceInput(session) { _, text -> draft += text }
            }
        }
        compose.runOnIdle { session.begin("description") }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNull(session.request.value)
            assertTrue(registry.launches.isEmpty())
            assertEquals("原有键盘文字", draft)
            assertEquals(context.getString(R.string.voice_unavailable), ShadowToast.getTextOfLatestToast())
        }
    }

    @Test fun registrySuccessAppendsOnceAndOldCallbackCannotConsumeTheNextField() {
        installSyntheticRecognizer()
        val session = session()
        val registry = SyntheticRegistry()
        val fields = mutableMapOf("session" to "原备注", "item-a" to "项目备注")
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner(registry)) {
                BindVoiceInput(session) { target, text -> fields[target] = fields.getValue(target) + text }
            }
        }
        compose.runOnIdle { session.begin("session"); session.begin("item-a") }
        compose.waitForIdle()
        val oldCode = registry.launches.single().first
        compose.runOnIdle {
            registry.dispatchResult(oldCode, Activity.RESULT_OK, result("轻松完成", "第二候选"))
            // A provider repeating its callback synchronously must be harmless too.
            registry.dispatchResult(oldCode, Activity.RESULT_OK, result("重复"))
        }
        compose.waitForIdle()
        compose.runOnIdle { session.begin("item-a") }
        compose.waitForIdle()
        compose.runOnIdle {
            registry.dispatchResult(oldCode, Activity.RESULT_OK, result("迟到"))
            registry.dispatchResult(registry.launches.last().first, Activity.RESULT_OK, result("再做一组"))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, registry.launches.size)
            assertEquals("原备注轻松完成", fields["session"])
            assertEquals("项目备注再做一组", fields["item-a"])
            assertNull(session.request.value)
        }
    }

    @Test fun cancelEmptyAndFailureCallbacksKeepDraftAndAllowRetry() {
        installSyntheticRecognizer()
        val session = session()
        val registry = SyntheticRegistry()
        var draft = "保留原输入"
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner(registry)) {
                BindVoiceInput(session) { _, text -> draft += text }
            }
        }
        for ((code, data) in listOf(Activity.RESULT_CANCELED to result("取消不追加"), Activity.RESULT_OK to result("  "),
            RecognizerIntent.RESULT_SERVER_ERROR to null)) {
            compose.runOnIdle { session.begin("description") }
            compose.waitForIdle()
            compose.runOnIdle { registry.dispatchResult(registry.launches.last().first, code, data) }
            compose.waitForIdle()
            compose.runOnIdle { assertNull(session.request.value); assertEquals("保留原输入", draft) }
        }
        assertEquals(3, registry.launches.size)
        assertEquals(context.getString(R.string.voice_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test fun resultWaitingDuringLoadingIsDeliveredOnceWhenEditorIsReady() {
        installSyntheticRecognizer()
        val session = session()
        val registry = SyntheticRegistry()
        val ready = mutableStateOf(true)
        val received = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner(registry)) {
                BindVoiceInput(session, ready.value) { _, text -> received += text }
            }
        }
        compose.runOnIdle { session.begin("description") }
        compose.waitForIdle()
        val requestCode = registry.launches.single().first
        compose.runOnIdle { ready.value = false }
        compose.waitForIdle()
        compose.runOnIdle { registry.dispatchResult(requestCode, Activity.RESULT_OK, result("重建后的结果")) }
        compose.runOnIdle {
            assertTrue(received.isEmpty())
            assertNotNull(session.request.value)
            ready.value = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("重建后的结果"), received)
            assertEquals(1, registry.launches.size)
            assertNull(session.request.value)
        }
    }

    @Test fun leavingEditorInvalidatesInFlightResult() {
        installSyntheticRecognizer()
        val session = session()
        val registry = SyntheticRegistry()
        val visible = mutableStateOf(true)
        val received = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner(registry)) {
                if (visible.value) BindVoiceInput(session) { _, text -> received += text }
            }
        }
        compose.runOnIdle { session.begin("description") }
        compose.waitForIdle()
        val requestCode = registry.launches.single().first
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            registry.dispatchResult(requestCode, Activity.RESULT_OK, result("关闭后迟到"))
            assertNull(session.request.value)
            assertTrue(received.isEmpty())
        }
    }

    private fun owner(registry: ActivityResultRegistry) = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
    }

    private fun result(vararg text: String) = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(*text))

    private fun installSyntheticRecognizer() {
        shadowOf(context.packageManager).addResolveInfoForIntent(voiceRecognitionIntent(), ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "example.synthetic"
                name = "example.synthetic.RecognizerActivity"
                exported = true
                enabled = true
                applicationInfo = ApplicationInfo().apply { packageName = "example.synthetic" }
            }
        })
    }

    private class SyntheticRegistry : ActivityResultRegistry() {
        val launches = mutableListOf<Pair<Int, Intent>>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches += requestCode to (input as Intent)
        }
    }
}
