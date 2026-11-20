package io.github.jqssun.airplay

import android.content.Intent
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.CropLandscape
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.PhotoSizeSelectActual
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.ui.VideoContentFrame
import io.github.jqssun.airplay.ui.VideoSurfaceView
import io.github.jqssun.airplay.ui.formatVideoTime
import io.github.jqssun.airplay.ui.gestures.VideoContentScale
import io.github.jqssun.airplay.ui.icon
import io.github.jqssun.airplay.ui.nameRes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class VideoSurfaceViewContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun checkpointCoverage() {
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Test fun portraitMirroringResizesTheExistingSurfaceAndReleasesItOnRemoval() {
        val visible = mutableStateOf(true)
        val aspect = mutableStateOf(2f)
        val available = CopyOnWriteArrayList<Surface>()
        val destroyed = CopyOnWriteArrayList<Surface>()
        launch().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.size(240.dp, 160.dp)) {
                        if (visible.value) VideoSurfaceView(
                            onSurfaceAvailable = { available.add(it) },
                            onSurfaceDestroyed = { destroyed.add(it) },
                            aspectRatio = aspect.value,
                        )
                    }
                }
            }
            val landscape = awaitSurface(scenario, widthDp = 240, heightDp = 120)
            await("Landscape surface was not delivered to the renderer") { available.any { it === landscape } }
            scenario.onActivity { aspect.value = 0.5f }
            val portrait = awaitSurface(scenario, widthDp = 80, heightDp = 160)
            assertSame(landscape, portrait)
            assertTrue("Resizing destroyed the live surface", destroyed.isEmpty())
            scenario.onActivity { visible.value = false }
            await("Removing the portrait surface did not release the renderer") { destroyed.any { it === portrait } }
        }
    }

    @Test fun playbackCanProvideItsOwnSurfaceDimensions() {
        val available = CopyOnWriteArrayList<Surface>()
        launch().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.size(240.dp, 160.dp)) {
                        VideoSurfaceView(
                            onSurfaceAvailable = { available.add(it) },
                            onSurfaceDestroyed = {},
                            applyAspectRatio = false,
                            modifier = Modifier.size(110.dp, 70.dp),
                        )
                    }
                }
            }
            val surface = awaitSurface(scenario, widthDp = 110, heightDp = 70)
            await("The caller-sized surface was not delivered to playback") { available.any { it === surface } }
        }
    }

    @Test fun playbackScaleModesResizeTheLiveSurfaceForBothAspects() {
        val aspect = mutableStateOf(2f)
        val scale = mutableStateOf(VideoContentScale.BEST_FIT)
        val videoSize = mutableStateOf<Pair<Int, Int>?>(null)
        val destroyed = CopyOnWriteArrayList<Surface>()
        launch().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.size(240.dp, 160.dp)) {
                        VideoContentFrame(aspect.value, videoSize.value, scale.value, zoom = 1f) { dimensions ->
                            VideoSurfaceView(
                                onSurfaceAvailable = {},
                                onSurfaceDestroyed = { destroyed.add(it) },
                                applyAspectRatio = false,
                                modifier = dimensions,
                            )
                        }
                    }
                }
            }
            val surface = awaitSurface(scenario, widthDp = 240, heightDp = 120)
            scenario.onActivity { scale.value = VideoContentScale.STRETCH }
            assertSame(surface, awaitSurface(scenario, widthDp = 240, heightDp = 160))
            scenario.onActivity { scale.value = VideoContentScale.CROP }
            assertSame(surface, awaitSurface(scenario, widthDp = 320, heightDp = 160))
            scenario.onActivity { aspect.value = 0.5f }
            assertSame(surface, awaitSurface(scenario, widthDp = 240, heightDp = 480))
            scenario.onActivity { scale.value = VideoContentScale.HUNDRED_PERCENT }
            assertSame(surface, awaitSurface(scenario, widthDp = 80, heightDp = 160))
            scenario.onActivity { videoSize.value = 137 to 93 }
            assertSame(surface, awaitSurfacePx(scenario, widthPx = 137, heightPx = 93))
            assertTrue("Changing the scale released the renderer surface", destroyed.isEmpty())
        }
    }

    @Test fun everyPlaybackScaleHasItsOwnLabelAndIcon() {
        assertEquals(R.string.scale_best_fit, VideoContentScale.BEST_FIT.nameRes())
        assertEquals(Icons.Rounded.FitScreen, VideoContentScale.BEST_FIT.icon())
        assertEquals(R.string.scale_stretch, VideoContentScale.STRETCH.nameRes())
        assertEquals(Icons.Rounded.AspectRatio, VideoContentScale.STRETCH.icon())
        assertEquals(R.string.scale_crop, VideoContentScale.CROP.nameRes())
        assertEquals(Icons.Rounded.CropLandscape, VideoContentScale.CROP.icon())
        assertEquals(R.string.scale_hundred_percent, VideoContentScale.HUNDRED_PERCENT.nameRes())
        assertEquals(Icons.Rounded.PhotoSizeSelectActual, VideoContentScale.HUNDRED_PERCENT.icon())
    }

    @Test fun playbackClockShowsHoursOnlyWhenNeeded() {
        val locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("0:00", formatVideoTime(-1_000))
            assertEquals("0:00", formatVideoTime(999))
            assertEquals("1:01", formatVideoTime(61_500))
            assertEquals("59:59", formatVideoTime(3_599_999))
            assertEquals("1:00:00", formatVideoTime(3_600_000))
            assertEquals("27:04:09", formatVideoTime(97_449_500))
        } finally {
            Locale.setDefault(locale)
        }
    }

    private fun launch(): ActivityScenario<DebugTvPreviewActivity> = ActivityScenario.launch(
        Intent(context, DebugTvPreviewActivity::class.java).putExtra("screen", "contract_host")
    )

    private fun awaitSurface(scenario: ActivityScenario<DebugTvPreviewActivity>, widthDp: Int, heightDp: Int): Surface {
        var density = 0f
        scenario.onActivity { density = it.resources.displayMetrics.density }
        return awaitSurfacePx(scenario, (widthDp * density).roundToInt(), (heightDp * density).roundToInt(), tolerance = 1)
    }

    private fun awaitSurfacePx(
        scenario: ActivityScenario<DebugTvPreviewActivity>, widthPx: Int, heightPx: Int, tolerance: Int = 0,
    ): Surface {
        var match: Surface? = null
        await("Surface did not reach ${widthPx}×${heightPx} pixels with a valid buffer") {
            scenario.onActivity { activity ->
                val view = findSurface(activity.window.decorView) ?: return@onActivity
                val frame = view.holder.surfaceFrame
                if (abs(frame.width() - widthPx) <= tolerance &&
                    abs(frame.height() - heightPx) <= tolerance && view.holder.surface.isValid) {
                    match = view.holder.surface
                }
            }
            match != null
        }
        return requireNotNull(match)
    }

    private fun findSurface(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findSurface(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        assertTrue(message, condition())
    }
}
