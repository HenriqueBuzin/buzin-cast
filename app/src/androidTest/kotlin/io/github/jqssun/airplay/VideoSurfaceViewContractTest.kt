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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.ui.VideoSurfaceView
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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

    private fun launch(): ActivityScenario<DebugTvPreviewActivity> = ActivityScenario.launch(
        Intent(context, DebugTvPreviewActivity::class.java).putExtra("screen", "contract_host")
    )

    private fun awaitSurface(scenario: ActivityScenario<DebugTvPreviewActivity>, widthDp: Int, heightDp: Int): Surface {
        var match: Surface? = null
        await("Surface did not reach ${widthDp}×${heightDp} dp with a valid buffer") {
            scenario.onActivity { activity ->
                val view = findSurface(activity.window.decorView) ?: return@onActivity
                val density = activity.resources.displayMetrics.density
                val frame = view.holder.surfaceFrame
                if (abs(frame.width() - (widthDp * density).roundToInt()) <= 1 &&
                    abs(frame.height() - (heightDp * density).roundToInt()) <= 1 && view.holder.surface.isValid) {
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
