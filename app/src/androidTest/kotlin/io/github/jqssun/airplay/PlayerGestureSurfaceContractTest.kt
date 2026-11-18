package io.github.jqssun.airplay

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.ui.gestures.*
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** MotionEvents traverse the production gesture surface and its production states. */
@RunWith(AndroidJUnit4::class)
class PlayerGestureSurfaceContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var root: File
    private lateinit var preferences: String
    private lateinit var model: MainViewModel
    private lateinit var store: ViewModelStore
    private lateinit var scope: CoroutineScope
    private lateinit var audio: AudioManager
    private var originalVolume = 0
    private var target: android.view.View? = null
    private var downTime = 0L
    private class TestApplication(base: Context) : Application() { init { attachBaseContext(base) } }

    @Before fun setup() {
        preferences = "player-gesture-${System.nanoTime()}"
        root = File(context.cacheDir, preferences).apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getCacheDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(preferences, mode)
        }
        audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2, 0)
        instrumentation.runOnMainSync {
            model = MainViewModel(TestApplication(isolated))
            store = ViewModelStore().also { it.put("surface", model) }
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            state("_videoDurationMs", 100_000L)
            state("_videoPositionMs", 20_000L)
            state("_videoPlaybackActive", true)
        }
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { scope.cancel(); store.clear() }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> state(name: String, value: T) {
        (MainViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(model) as MutableStateFlow<T>).value = value
    }
    private fun <T> read(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail(message)
    }
    private class Surface {
        val enabled = mutableStateOf(true)
        val locked = mutableStateOf(false)
        val bounds = AtomicReference<Rect>()
        val taps = AtomicInteger()
        lateinit var tap: TapGestureState
        lateinit var seek: SeekGestureState
        lateinit var vertical: VolumeAndBrightnessGestureState
        lateinit var zoom: ZoomState
        lateinit var brightness: BrightnessState
        lateinit var volume: ServerVolumeState
        fun point(x: Float, y: Float = .5f): Offset {
            val b = requireNotNull(bounds.get())
            return Offset(b.left + b.width * x, b.top + b.height * y)
        }
    }
    private fun surface(block: (Surface) -> Unit) {
        ActivityScenario.launch<DebugTvPreviewActivity>(Intent(context, DebugTvPreviewActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            val state = Surface()
            var originalBrightness = -1f
            scenario.onActivity { activity ->
                target = activity.window.decorView
                originalBrightness = activity.window.attributes.screenBrightness
                activity.window.attributes = activity.window.attributes.apply { screenBrightness = .5f }
                state.tap = TapGestureState(model, scope)
                state.seek = SeekGestureState(model)
                state.brightness = BrightnessState(activity.window)
                state.volume = ServerVolumeState(context)
                state.vertical = VolumeAndBrightnessGestureState(state.volume, state.brightness, scope)
                state.zoom = ZoomState(model, scope)
                activity.setContent {
                    VideoPlayerGestures(state.enabled.value, state.locked.value, { state.taps.incrementAndGet() },
                        state.tap, state.seek, state.vertical, state.zoom,
                        Modifier.onGloballyPositioned { state.bounds.set(it.boundsInWindow()) })
                }
            }
            try {
                await("Gesture surface did not lay out") { state.bounds.get()?.let { it.width > 0 && it.height > 0 } == true }
                instrumentation.waitForIdleSync()
                Thread.sleep(50)
                block(state)
            } finally {
                scenario.onActivity { it.window.attributes = it.window.attributes.apply { screenBrightness = originalBrightness } }
                target = null
            }
        }
    }
    private fun mode(surface: Surface, enabled: Boolean, locked: Boolean) {
        instrumentation.runOnMainSync { surface.enabled.value = enabled; surface.locked.value = locked }
        instrumentation.waitForIdleSync()
        Thread.sleep(50)
    }
    private fun send(action: Int, first: Offset, second: Offset? = null) {
        val points = listOfNotNull(first, second)
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val properties = points.indices.map { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = points.map { point -> MotionEvent.PointerCoords().apply {
            x = point.x; y = point.y; pressure = 1f; size = 1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, points.size,
            properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { instrumentation.runOnMainSync { requireNotNull(target).dispatchTouchEvent(event) } }
        finally { event.recycle() }
        instrumentation.waitForIdleSync()
        Thread.sleep(20)
    }
    private fun tap(point: Offset) { send(MotionEvent.ACTION_DOWN, point); send(MotionEvent.ACTION_UP, point) }
    private fun doubleTap(point: Offset) { tap(point); Thread.sleep(60); tap(point) }
    private fun drag(point: Offset, delta: Offset) {
        send(MotionEvent.ACTION_DOWN, point)
        send(MotionEvent.ACTION_MOVE, point + delta / 2f)
        send(MotionEvent.ACTION_MOVE, point + delta)
        send(MotionEvent.ACTION_UP, point + delta)
    }
    private fun pinch(surface: Surface, during: () -> Unit = {}) {
        val center = surface.point(.5f)
        val left = center + Offset(-40f, 0f); val right = center + Offset(40f, 0f)
        send(MotionEvent.ACTION_DOWN, left)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), left, right)
        val expandedLeft = center + Offset(-120f, 0f); val expandedRight = center + Offset(120f, 0f)
        send(MotionEvent.ACTION_MOVE, expandedLeft, expandedRight)
        during()
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), expandedLeft, expandedRight)
        send(MotionEvent.ACTION_UP, expandedLeft)
    }

    @Test fun singleTapsRemainAvailableAndBlockedDoubleTapsDoNotSeek() = surface { surface ->
        for ((enabled, locked) in listOf(false to false, true to true, true to false)) {
            mode(surface, enabled, locked)
            val count = surface.taps.get()
            tap(surface.point(.5f))
            await("Single tap lost in mode enabled=$enabled locked=$locked") { surface.taps.get() == count + 1 }
            if (!enabled || locked) {
                doubleTap(surface.point(.9f))
                Thread.sleep(400)
                assertEquals("Blocked double tap opened controls", count + 1, surface.taps.get())
                assertEquals("Blocked double tap sought", 0L, read { surface.tap.seekMillis })
            }
        }
    }

    @Test fun activeDoubleTapSuppressesControlTapUntilSeekIndicatorExpires() = surface { surface ->
        doubleTap(surface.point(.9f))
        await("Enabled double tap did not seek") { read { surface.tap.seekMillis } == 10_000L }
        tap(surface.point(.5f))
        Thread.sleep(400)
        assertEquals("Tap during accumulated seeking toggled controls", 0, surface.taps.get())
        await("Seek indicator never expired") { read { surface.tap.seekMillis } == 0L }
        tap(surface.point(.5f))
        await("Normal tapping did not recover") { surface.taps.get() == 1 }
    }

    @Test fun dragAndPinchReachProductionStatesOnlyWhenUnlockedAndEnabled() = surface { surface ->
        val initialVolume = read { surface.volume.percentage }
        for ((enabled, locked) in listOf(false to false, true to true)) {
            mode(surface, enabled, locked)
            drag(surface.point(.5f), Offset(160f, 0f))
            drag(surface.point(.25f), Offset(0f, 200f))
            drag(surface.point(.75f), Offset(0f, 200f))
            pinch(surface)
            read {
                assertNull(surface.seek.startPositionMs); assertNull(surface.seek.targetPositionMs)
                assertNull(model.videoScrubPositionMs.value)
                assertNull(surface.vertical.activeGesture)
                assertEquals(50, surface.brightness.percentage)
                assertEquals(initialVolume, surface.volume.percentage)
                assertEquals(1f, surface.zoom.zoom, .001f); assertFalse(surface.zoom.isZooming)
            }
        }
        mode(surface, true, false)
        drag(surface.point(.5f), Offset(160f, 0f))
        read {
            assertNull("Finished drag retained seek ownership", surface.seek.startPositionMs)
            assertNull(surface.seek.targetPositionMs)
            assertEquals("Horizontal delta did not reach seek state", 24_000L, model.videoScrubPositionMs.value)
        }
        drag(surface.point(.25f), Offset(0f, 200f))
        read {
            assertEquals(VerticalGesture.BRIGHTNESS, surface.vertical.activeGesture)
            assertEquals(45, surface.brightness.percentage)
        }
        val volumeBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        drag(surface.point(.75f), Offset(0f, 200f))
        read {
            assertEquals(VerticalGesture.VOLUME, surface.vertical.activeGesture)
            assertTrue(surface.volume.percentage < initialVolume)
        }
        assertTrue("Vertical volume gesture did not reach AudioManager", audio.getStreamVolume(AudioManager.STREAM_MUSIC) < volumeBefore)
        pinch(surface) { read {
            assertTrue("Pinch did not acquire zoom", surface.zoom.isZooming)
            assertEquals(3f, surface.zoom.zoom, .01f)
        } }
        assertFalse("Released pinch retained zoom ownership", read { surface.zoom.isZooming })
    }
}
