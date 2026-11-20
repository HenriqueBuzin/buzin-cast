package io.github.jqssun.airplay

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
}
