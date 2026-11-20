package io.github.jqssun.airplay

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.ui.BuzinLaunch
import io.github.jqssun.airplay.ui.BuzinLogoReveal
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Real Compose rendering/input; restores the emulator's animator scale. */
@RunWith(AndroidJUnit4::class)
class BrandLaunchContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var originalScale = 1f
    private var controlledComposition: CoroutineScope? = null

    @Before fun animations() {
        originalScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        animatorScale(1f)
    }
    private fun animatorScale(value: Float) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "settings put global animator_duration_scale $value")).use { it.readBytes() }
        await("Animator scale was not applied") {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, -1f) == value
        }
    }
    @After fun cleanup() {
        controlledComposition?.cancel()
        animatorScale(originalScale)
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail(message)
    }
    private fun activity(block: (ActivityScenario<DebugTvPreviewActivity>) -> Unit) {
        ActivityScenario.launch<DebugTvPreviewActivity>(Intent(context, DebugTvPreviewActivity::class.java).putExtra("screen", "contract_host")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use(block)
    }
    private fun shot(bounds: Rect): Bitmap {
        val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        var crop: Bitmap? = null
        return try {
            crop = Bitmap.createBitmap(screen, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
            requireNotNull(crop.copy(Bitmap.Config.ARGB_8888, false))
        } finally { if (crop !== screen) crop?.recycle(); screen.recycle() }
    }
    private fun Bitmap.point(x: Float, y: Float) = getPixel((width * x).toInt(), (height * y).toInt())
    private fun close(actual: Int, expected: Int): Boolean =
        abs(android.graphics.Color.red(actual) - android.graphics.Color.red(expected)) <= 5 &&
            abs(android.graphics.Color.green(actual) - android.graphics.Color.green(expected)) <= 5 &&
            abs(android.graphics.Color.blue(actual) - android.graphics.Color.blue(expected)) <= 5

    @Test fun logoTracesThenFillsWithoutStretchingAndClampsItsProgress() = activity { scenario ->
        val progress = mutableStateOf(0f)
        val drawn = AtomicReference<Float>()
        val bounds = AtomicReference<Rect>()
        scenario.onActivity { target -> target.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                BuzinLogoReveal({ progress.value.also { drawn.set(it) } }, Modifier.size(200.dp).onGloballyPositioned { bounds.set(it.boundsInWindow()) })
            }
        } }
        await("Logo did not lay out") { bounds.get() != null }
        fun at(value: Float, predicate: (Bitmap) -> Boolean): Bitmap {
            instrumentation.runOnMainSync { progress.value = value }
            await("Canvas did not draw progress $value") { drawn.get() == value }
            var accepted: Bitmap? = null
            await("Logo pixels did not reach progress $value") {
                val bitmap = shot(bounds.get())
                if (predicate(bitmap)) { accepted = bitmap; true } else { bitmap.recycle(); false }
            }
            return requireNotNull(accepted)
        }
        fun illuminated(bitmap: Bitmap): Int {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return pixels.count { !close(it, android.graphics.Color.BLACK) }
        }
        at(0f) { illuminated(it) == 0 }.recycle()
        val trace = at(0.12f) { illuminated(it) > 0 && close(it.point(0.15f, 0.5f), android.graphics.Color.BLACK) }
        val earlyCount = illuminated(trace); trace.recycle()
        at(0.4f) { illuminated(it) > earlyCount && close(it.point(0.15f, 0.5f), android.graphics.Color.BLACK) }.recycle()
        at(0.57f) { android.graphics.Color.green(it.point(0.15f, 0.5f)) in 20..230 }.recycle()
        val mint = android.graphics.Color.rgb(124, 244, 205)
        val ink = android.graphics.Color.rgb(9, 42, 36)
        val finished = at(1f) { close(it.point(0.15f, 0.5f), mint) && close(it.point(0.35f, 0.5f), ink) }
        try {
            assertEquals("Logo was rendered with unequal dimensions", finished.width, finished.height)
            assertTrue("Rounded tile corner was filled", close(finished.point(0.01f, 0.01f), android.graphics.Color.BLACK))
            assertTrue("B counter lost its cutout", close(finished.point(0.5f, 0.35f), mint))
            val over = at(2f) { close(it.point(0.15f, 0.5f), mint) }
            try { assertTrue("Progress beyond completion changed the mark", finished.sameAs(over)) }
            finally { over.recycle() }
        } finally { finished.recycle() }
        at(-1f) { illuminated(it) == 0 }.recycle()
    }

    private class LaunchState {
        val composed = AtomicInteger()
        val disposed = AtomicInteger()
        val backs = AtomicInteger()
        val keys = AtomicInteger()
        val firstComposition = CountDownLatch(1)
        val durationScale = AtomicReference<Float>()
        val bounds = AtomicReference<Rect>()
    }
    private fun launch(enabled: Boolean = true, slowIntro: Boolean = false,
                       action: (DebugTvPreviewActivity, LaunchState) -> Unit) = activity { scenario ->
        val state = LaunchState()
        lateinit var target: DebugTvPreviewActivity
        val content: @Composable () -> Unit = {
            AirPlayTheme {
                BackHandler { state.backs.incrementAndGet() }
                SideEffect { state.firstComposition.countDown() }
                LaunchedEffect(Unit) {
                    state.durationScale.set(coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f)
                }
                Box(Modifier.fillMaxSize().onGloballyPositioned { state.bounds.set(it.boundsInWindow()) }) {
                    BuzinLaunch(enabled) {
                        DisposableEffect(Unit) {
                            state.composed.incrementAndGet()
                            onDispose { state.disposed.incrementAndGet() }
                        }
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        Box(Modifier.fillMaxSize().background(Color.Green).onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown) state.keys.incrementAndGet()
                            false
                        }.focusRequester(focus).focusable()) { Text("Launch contract ready") }
                    }
                }
            }
        }
        scenario.onActivity { host ->
            target = host
            if (slowIntro) {
                val scale = object : MotionDurationScale { override val scaleFactor = 20f }
                val scope = CoroutineScope(AndroidUiDispatcher.Main + scale)
                controlledComposition = scope
                val recomposer = Recomposer(scope.coroutineContext)
                scope.launch { recomposer.runRecomposeAndApplyChanges() }
                host.setContentView(ComposeView(host).apply {
                    setParentCompositionContext(recomposer)
                    setContent(content)
                })
            } else {
                host.setContent(content = content)
            }
        }
        assertTrue("First intro composition missing", state.firstComposition.await(15, TimeUnit.SECONDS))
        if (slowIntro) await("Compose did not receive the test animator scale") { state.durationScale.get() == 20f }
        action(target, state)
        await("Home never composed") { state.composed.get() == 1 }
        await("Intro did not uncover home") {
            val bounds = state.bounds.get() ?: return@await false
            val bitmap = shot(bounds)
            try { close(bitmap.point(0.8f, 0.8f), android.graphics.Color.GREEN) }
            finally { bitmap.recycle() }
        }
        assertEquals("Home composition restarted", 1, state.composed.get())
        assertEquals(0, state.disposed.get())
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        assertEquals("Finished intro swallowed Back", 1, state.backs.get())
        scenario.close()
        assertEquals("Home composition leaked after activity destruction", 1, state.disposed.get())
    }
    @Test fun disabledIntroShowsHomeImmediately() = launch(false) { _, state ->
        await("Disabled intro delayed content") { state.composed.get() == 1 }
    }
    @Test fun naturalAnimationCompletesAndReleasesBackHandling() = launch { _, _ -> }
    @Test fun remoteKeySkipsIntroAndDoesNotActivateTheNewHome() = launch(slowIntro = true) { target, state ->
        instrumentation.runOnMainSync {
            assertEquals("Intro had already finished before key test", 0, state.composed.get())
            target.window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
            assertEquals("Key-up skipped the intro", 0, state.composed.get())
            target.window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        await("Remote key failed to skip intro") { state.composed.get() == 1 }
        assertEquals("Skip key activated home", 0, state.keys.get())
    }
    @Test fun backSkipsIntroBeforeDelegatingSubsequentBack() = launch(slowIntro = true) { target, state ->
        instrumentation.runOnMainSync {
            assertEquals(0, state.composed.get())
            target.onBackPressedDispatcher.onBackPressed()
        }
        assertEquals("Back escaped the active intro", 0, state.backs.get())
        await("Back failed to skip intro") { state.composed.get() == 1 }
    }
    @Test fun touchSkipsIntroAndReleasesItsPointerHandler() = launch(slowIntro = true) { target, state ->
        await("Intro did not lay out") { state.bounds.get() != null }
        val point = state.bounds.get().center
        instrumentation.runOnMainSync {
            assertEquals("Intro had already finished before tap", 0, state.composed.get())
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
                try { target.window.decorView.dispatchTouchEvent(event) }
                finally { event.recycle() }
            }
        }
        await("Tap failed to skip intro") { state.composed.get() == 1 }
    }
}
