package io.github.jqssun.airplay

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.ui.TvFormTextField
import io.github.jqssun.airplay.ui.focusRing
import io.github.jqssun.airplay.ui.requestFocusUntilLanded
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvFocusContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class TestFrameClock(private val advance: () -> Unit = {}) : MonotonicFrameClock {
        var frames = 0
            private set

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            frames++
            advance()
            return onFrame(frames * 16_666_667L)
        }
    }

    @After fun checkpointCoverage() {
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Test fun unattachedRequesterStopsAtItsAttemptBudget() {
        val requester = FocusRequester()
        val frameClock = TestFrameClock()
        runBlocking(frameClock) {
            assertFalse(requester.requestFocusUntilLanded(attempts = 0))
            assertEquals(0, frameClock.frames)
            assertFalse(requester.requestFocusUntilLanded(attempts = 3))
            assertEquals(3, frameClock.frames)
            var observations = 0
            assertFalse(requester.requestFocusUntilLanded(attempts = 2) {
                observations++
                false
            })
            assertEquals(5, frameClock.frames)
            assertEquals(4, observations)
        }
    }

    @Test fun observedFocusCanLandBeforeOrAfterTheNextFrame() {
        val requester = FocusRequester()
        var observedFocus = false
        val frameClock = TestFrameClock { observedFocus = true }
        runBlocking(frameClock) {
            assertTrue(requester.requestFocusUntilLanded(attempts = 1) { true })
            assertEquals(0, frameClock.frames)
            assertTrue(requester.requestFocusUntilLanded(attempts = 1) { observedFocus })
            assertEquals(1, frameClock.frames)
        }
    }

    @Test fun attachedRequesterMovesFocusBetweenRealTargets() {
        val result = CompletableDeferred<List<Boolean>>()
        val intent = Intent(context, DebugTvPreviewActivity::class.java).putExtra("screen", "contract_host")
        ActivityScenario.launch<DebugTvPreviewActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    AirPlayTheme {
                        val first = remember { FocusRequester() }
                        val second = remember { FocusRequester() }
                        val focused = remember { BooleanArray(2) }
                        var rejectNextRequest = false
                        var rejectedRequests = 0
                        Row {
                            Box(Modifier.size(40.dp).focusRequester(first).onFocusChanged { focused[0] = it.isFocused }
                                .focusRing().focusable())
                            Box(Modifier.size(40.dp).focusRequester(second).onFocusChanged { focused[1] = it.isFocused }
                                .focusProperties {
                                    if (rejectNextRequest) {
                                        rejectNextRequest = false
                                        rejectedRequests++
                                        error("Transient focus-target failure")
                                    }
                                }
                                .focusRing(RoundedCornerShape(4.dp), Color.White, 2.dp).focusable())
                        }
                        LaunchedEffect(first, second) {
                            val acceptedFirst = first.requestFocusUntilLanded()
                            val landedFirst = focused[0] && !focused[1]
                            rejectNextRequest = true
                            val acceptedSecond = second.requestFocusUntilLanded { focused[1] }
                            result.complete(listOf(acceptedFirst, landedFirst, rejectedRequests == 1,
                                acceptedSecond, focused[1] && !focused[0]))
                        }
                    }
                }
            }
            assertEquals(listOf(true, true, true, true, true), runBlocking { withTimeout(10_000) { result.await() } })
        }
    }

    @Test fun tvFormKeyboardNextMovesFocusAndDoneSubmits() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val firstFocused = CompletableDeferred<Unit>()
        val secondFocused = CompletableDeferred<Unit>()
        val submitted = CompletableDeferred<Unit>()
        val intent = Intent(context, DebugTvPreviewActivity::class.java).putExtra("screen", "contract_host")
        ActivityScenario.launch<DebugTvPreviewActivity>(intent).use { scenario ->
            lateinit var editorHost: View
            scenario.onActivity { activity ->
                activity.setContent {
                    AirPlayTheme {
                        editorHost = LocalView.current
                        val first = remember { FocusRequester() }
                        val second = remember { FocusRequester() }
                        Column {
                            TvFormTextField("Address", "receiver.local", {},
                                modifier = Modifier.focusRequester(first).onFocusChanged {
                                    if (it.isFocused) firstFocused.complete(Unit)
                                }, onNext = { second.requestFocus() })
                            TvFormTextField("Code", "ABCD-EFGH-IJKL", {},
                                modifier = Modifier.focusRequester(second).onFocusChanged {
                                    if (it.isFocused) secondFocused.complete(Unit)
                                }, imeAction = ImeAction.Done, onDone = { submitted.complete(Unit) })
                        }
                        LaunchedEffect(first) { first.requestFocusUntilLanded() }
                    }
                }
            }
            fun openFocusedEditor() {
                scenario.onActivity {
                    // The previous IME can still own input while its hide animation runs.
                    // Deliver the key to the focused Compose host, then verify the real editor.
                    editorHost.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                    editorHost.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                }
            }
            runBlocking { withTimeout(10_000) { firstFocused.await() } }
            instrumentation.waitForIdleSync()
            openFocusedEditor()
            deliverEditorAction(scenario, editorHost, EditorInfo.IME_ACTION_NEXT)
            runBlocking { withTimeout(10_000) { secondFocused.await() } }
            assertFalse(submitted.isCompleted)
            instrumentation.waitForIdleSync()
            openFocusedEditor()
            deliverEditorAction(scenario, editorHost, EditorInfo.IME_ACTION_DONE)
            runBlocking { withTimeout(10_000) { submitted.await() } }
        }
    }

    private fun deliverEditorAction(scenario: ActivityScenario<DebugTvPreviewActivity>, host: View, action: Int) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var windowFocused = false
        var hasTextEditor = false
        var reportedAction: Int? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            var delivered = false
            scenario.onActivity {
                windowFocused = host.hasWindowFocus()
                hasTextEditor = host.onCheckIsTextEditor()
                if (!windowFocused || !hasTextEditor) return@onActivity
                val info = EditorInfo()
                val connection = host.onCreateInputConnection(info) ?: return@onActivity
                reportedAction = info.imeOptions and EditorInfo.IME_MASK_ACTION
                if (reportedAction == action) {
                    delivered = connection.performEditorAction(action)
                }
            }
            if (delivered) return
            SystemClock.sleep(25)
        }
        fail("TV editor did not accept IME action $action: windowFocused=$windowFocused, " +
            "hasTextEditor=$hasTextEditor, reportedAction=$reportedAction")
    }
}
