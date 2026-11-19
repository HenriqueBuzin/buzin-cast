package io.github.jqssun.airplay

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.IntSize
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
import java.util.concurrent.TimeUnit

/** State contracts exercise production gesture logic without binding a receiver. */
@RunWith(AndroidJUnit4::class)
class GestureStateContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var root: File
    private lateinit var model: MainViewModel
    private lateinit var store: ViewModelStore
    private lateinit var scope: CoroutineScope

    private class IsolatedApplication(base: Context) : Application() {
        init { attachBaseContext(base) }
    }

    @Before fun setup() {
        root = File(context.cacheDir, "gesture-contract-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getCacheDir() = root
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("gesture-contract", mode)
        }
        instrumentation.runOnMainSync {
            store = ViewModelStore()
            model = MainViewModel(IsolatedApplication(isolated))
            store.put("gesture", model)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync { scope.cancel(); store.clear() }
        context.getSharedPreferences("gesture-contract", Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(name: String, value: T) {
        val flow = MainViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(model)
        (flow as MutableStateFlow<T>).value = value
    }

    private fun change(x: Float, y: Float = 0f) = PointerInputChange(
        id = PointerId(1), uptimeMillis = 1, position = Offset(x, y), pressed = true,
        previousUptimeMillis = 0, previousPosition = Offset.Zero, previousPressed = true,
        isInitiallyConsumed = false,
    )

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            var satisfied = false
            instrumentation.runOnMainSync { satisfied = condition() }
            if (satisfied) return
            Thread.sleep(20)
        }
        fail(message)
    }

    @Test fun unavailableTimelineRejectsTapSeekDragAndZoom() {
        instrumentation.runOnMainSync {
            val tap = TapGestureState(model, scope)
            val seek = SeekGestureState(model)
            val zoom = ZoomState(model, scope)
            for (duration in listOf(0L, -1L)) {
                state("_videoDurationMs", duration)
                tap.handleDoubleTap(Offset.Zero, IntSize(1000, 1000))
                seek.onDragStart(Offset.Zero)
                seek.onDrag(change(100f), 100f)
                seek.onSeek(5000)
                seek.onSeekEnd()
                zoom.onZoom(2f)
                assertEquals(0L, tap.seekMillis)
                assertNull(seek.startPositionMs)
                assertNull(seek.targetPositionMs)
                assertNull(seek.seekAmountMs)
                assertEquals(1f, zoom.zoom, 0f)
                assertFalse(zoom.isZooming)
            }
        }
    }

    @Test fun doubleTapAccumulatesOneDirectionAndRestartsAfterDirectionChange() {
        lateinit var tap: TapGestureState
        instrumentation.runOnMainSync {
            state("_videoDurationMs", 100_000L)
            state("_videoPlaybackActive", true)
            tap = TapGestureState(model, scope)
            val size = IntSize(1000, 1000)
            for ((x, expected) in listOf(100f to -10_000L, 349f to -20_000L,
                651f to 10_000L, 900f to 20_000L, 0f to -10_000L)) {
                tap.handleDoubleTap(Offset(x, 0f), size)
                assertEquals(expected, tap.seekMillis)
            }
            assertTrue(model.videoPlaying.value)
            tap.handleDoubleTap(Offset(350f, 0f), size)
            assertFalse(model.videoPlaying.value)
            tap.handleDoubleTap(Offset(650f, 0f), size)
            assertTrue(model.videoPlaying.value)
            assertEquals("Center tap changed the seek indicator", -10_000L, tap.seekMillis)
            assertEquals("Gesture unexpectedly opened transport controls", 0L, model.videoOverlayTick.value)
        }
        await("Seek indicator did not expire") { tap.seekMillis == 0L }
    }

    @Test fun seekbarClampsTargetsAndResumesAnExistingScrub() {
        instrumentation.runOnMainSync {
            state("_videoDurationMs", 100_000L)
            state("_videoPositionMs", 20_000L)
            val seek = SeekGestureState(model)
            assertNull(seek.seekAmountMs)
            seek.onSeek(-100)
            assertEquals(20_000L, seek.startPositionMs)
            assertEquals(0L, seek.targetPositionMs)
            assertEquals(-20_000L, seek.seekAmountMs)
            assertEquals(0L, model.videoScrubPositionMs.value)
            seek.onSeek(200_000)
            assertEquals(100_000L, seek.targetPositionMs)
            assertEquals(80_000L, seek.seekAmountMs)
            seek.onSeekEnd()
            assertNull(seek.startPositionMs)
            assertNull(seek.targetPositionMs)
            assertNull(seek.seekAmountMs)
            seek.onDragStart(Offset(100f, 0f))
            assertEquals("New drag ignored the pending scrub target", 100_000L, seek.startPositionMs)
            seek.onSeekEnd()
            seek.onSeekEnd()
        }
    }

    @Test fun dragSeeksRelativeToItsStartAndRejectsMotionBeyondTimelineEnds() {
        instrumentation.runOnMainSync {
            state("_videoDurationMs", 100_000L)
            state("_videoPositionMs", 20_000L)
            val seek = SeekGestureState(model)
            seek.onDrag(change(500f), 500f)
            assertNull(seek.targetPositionMs)
            seek.onDragStart(Offset(100f, 0f))
            seek.onDrag(change(300f), 200f)
            assertEquals(30_000L, seek.targetPositionMs)
            seek.onDrag(change(-1000f), -1300f)
            assertEquals(0L, seek.targetPositionMs)
            state("_videoPositionMs", 0L)
            seek.onDrag(change(1000f), -1f)
            assertEquals(0L, seek.targetPositionMs)
            state("_videoPositionMs", 100_000L)
            seek.onDrag(change(-1000f), 1f)
            assertEquals(0L, seek.targetPositionMs)
            state("_videoDurationMs", 0L)
            seek.onDrag(change(1000f), 1f)
            assertEquals(0L, seek.targetPositionMs)
            seek.onSeekEnd()
        }
    }

    @Test fun zoomClampsCyclesAllScalesResetsAndExpiresItsIndicator() {
        lateinit var zoom: ZoomState
        instrumentation.runOnMainSync {
            state("_videoDurationMs", 100_000L)
            zoom = ZoomState(model, scope)
            zoom.onZoom(2f)
            assertTrue(zoom.isZooming)
            assertEquals(2f, zoom.zoom, 0f)
            zoom.onZoom(100f)
            assertEquals(4f, zoom.zoom, 0f)
            zoom.onZoom(0.001f)
            assertEquals(0.25f, zoom.zoom, 0f)
            zoom.onZoomEnd()
            assertFalse(zoom.isZooming)
            for (expected in listOf(VideoContentScale.STRETCH, VideoContentScale.CROP,
                VideoContentScale.HUNDRED_PERCENT, VideoContentScale.BEST_FIT)) {
                zoom.switchToNextContentScale()
                assertEquals(expected, zoom.contentScale)
                assertEquals(1f, zoom.zoom, 0f)
                assertTrue(zoom.showContentScaleIndicator)
            }
            zoom.onZoom(3f)
            zoom.switchToNextContentScale()
            zoom.reset()
            assertEquals(VideoContentScale.BEST_FIT, zoom.contentScale)
            assertEquals(1f, zoom.zoom, 0f)
            assertFalse(zoom.isZooming)
        }
        await("Content scale indicator did not expire") { !zoom.showContentScaleIndicator }
    }

    @Test fun verticalGesturesClampBrightnessAndVolumeAndExpireTheirIndicator() {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val original = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val intent = Intent(context, DebugTvPreviewActivity::class.java).putExtra("screen", "contract_host").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<DebugTvPreviewActivity>(intent).use { activity ->
                lateinit var gesture: VolumeAndBrightnessGestureState
                activity.onActivity { target ->
                    val brightness = BrightnessState(target.window)
                    brightness.update(50)
                    assertEquals(0.5f, target.window.attributes.screenBrightness, 0f)
                    assertEquals(50, BrightnessState(target.window).percentage)
                    brightness.clearOverride()
                    assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, target.window.attributes.screenBrightness, 0f)
                    assertTrue(BrightnessState(target.window).percentage in 0..100)
                    brightness.update(50)
                    val volume = ServerVolumeState(context)
                    volume.update(-10)
                    assertEquals(0, volume.percentage)
                    volume.update(110)
                    assertEquals(100, volume.percentage)
                    volume.adjust(false)
                    volume.adjust(true)
                    volume.sync()
                    assertEquals(manager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 /
                        manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), volume.percentage)
                    gesture = VolumeAndBrightnessGestureState(volume, brightness, scope)
                    gesture.onDrag(change(0f, 10f), 10f)
                    assertNull(gesture.activeGesture)
                    gesture.onDragStart(Offset(100f, 1000f), IntSize(1000, 1000))
                    assertEquals(VerticalGesture.BRIGHTNESS, gesture.activeGesture)
                    gesture.onDrag(change(100f, -2000f), -3000f)
                    assertEquals(100, brightness.percentage)
                    gesture.onDrag(change(100f, 4000f), 3000f)
                    assertEquals(0, brightness.percentage)
                    gesture.onDragEnd()
                    gesture.onDragStart(Offset(500f, 1000f), IntSize(1000, 1000))
                    assertEquals(VerticalGesture.VOLUME, gesture.activeGesture)
                    gesture.onDrag(change(500f, -2000f), -3000f)
                    assertEquals(100, volume.percentage)
                    gesture.onDrag(change(500f, 4000f), 3000f)
                    assertEquals(0, volume.percentage)
                    gesture.onDragEnd()
                    gesture.onDragEnd()
                    val withoutBrightness = VolumeAndBrightnessGestureState(volume, null, scope)
                    withoutBrightness.onDragStart(Offset.Zero, IntSize(1000, 1000))
                    withoutBrightness.onDrag(change(0f, 1000f), 1000f)
                    assertNull(withoutBrightness.activeGesture)
                }
                await("Vertical gesture indicator did not expire") { gesture.activeGesture == null }
            }
        } finally { manager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0) }
    }
}
