package io.github.jqssun.airplay

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.renderer.EglCore
import io.github.jqssun.airplay.renderer.VideoPipeline
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoPipelineContractTest {
    private val pipeline = VideoPipeline()

    @After fun cleanup() {
        pipeline.release()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Test fun retainedVideoFrameRepaintsOnDisplayReplacementAndMarginChanges() {
        val size = 64
        val format = windowPixelFormat()
        pipeline.setVideoSize(size, size)
        pipeline.start()
        val input = requireNotNull(pipeline.inputSurface)
        assertTrue(input.isValid)
        pipeline.start()
        assertSame(input, pipeline.inputSurface)
        ImageReader.newInstance(size, size, format, 3).use { first ->
            ImageReader.newInstance(size, size, format, 3).use { second ->
                try {
                    pipeline.setDisplaySurface(first.surface)
                    val canvas = input.lockCanvas(null)
                    try {
                        canvas.drawColor(Color.MAGENTA)
                    } finally {
                        input.unlockCanvasAndPost(canvas)
                    }
                    awaitPixels(first, Color.MAGENTA, Color.MAGENTA)

                    pipeline.setDisplaySurface(second.surface)
                    awaitPixels(second, Color.MAGENTA, Color.MAGENTA)
                    assertSame(input, pipeline.inputSurface)
                    pipeline.setMarginPercent(10)
                    awaitPixels(second, Color.MAGENTA, Color.BLACK)
                    pipeline.setMarginPercent(0)
                    awaitPixels(second, Color.MAGENTA, Color.MAGENTA)
                } finally {
                    pipeline.release()
                }
            }
        }
        assertNull(pipeline.inputSurface)
        assertFalse(input.isValid)
    }

    @Test fun firstFrameConsumedWithoutADisplayKeepsItsOrientationWhenOneAttaches() {
        val size = 64
        val format = windowPixelFormat()
        pipeline.setVideoSize(size, size)
        pipeline.start()
        val input = requireNotNull(pipeline.inputSurface)
        val texture = VideoPipeline::class.java.getDeclaredField("surfaceTexture")
            .apply { isAccessible = true }.get(pipeline) as SurfaceTexture
        val paint = Paint().apply { color = Color.RED }
        val canvas = input.lockCanvas(null)
        try {
            canvas.drawColor(Color.BLUE)
            canvas.drawRect(0f, 0f, size.toFloat(), size / 2f, paint)
        } finally {
            input.unlockCanvasAndPost(canvas)
        }
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (texture.timestamp == 0L && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("The frame was not consumed before display attachment", texture.timestamp != 0L)

        ImageReader.newInstance(size, size, format, 3).use { reader ->
            try {
                pipeline.setDisplaySurface(reader.surface)
                awaitColors(reader, Color.RED to Color.BLUE) { image ->
                    pixel(image, 32, 16) to pixel(image, 32, 48)
                }
            } finally {
                pipeline.release()
            }
        }
    }

    private fun windowPixelFormat(): Int = EglCore().use { egl ->
        // EGL can select RGBX or RGBA. ImageReader must match the producer's native format.
        val config = EglCore::class.java.getDeclaredField("config")
            .apply { isAccessible = true }.get(egl) as EGLConfig
        val value = IntArray(1)
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglGetConfigAttrib(display, config, EGL14.EGL_NATIVE_VISUAL_ID, value, 0))
        check(value[0] in listOf(PixelFormat.RGBX_8888, PixelFormat.RGBA_8888, PixelFormat.RGB_888))
        value[0]
    }

    private fun awaitPixels(reader: ImageReader, center: Int, edge: Int) =
        awaitColors(reader, center to edge) { image -> pixel(image, 32, 32) to pixel(image, 1, 32) }

    private fun awaitColors(reader: ImageReader, expected: Pair<Int, Int>, sample: (Image) -> Pair<Int, Int>) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var observed: Pair<Int, Int>? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            reader.acquireLatestImage()?.use { observed = sample(it) }
            if (observed == expected) return
            SystemClock.sleep(20)
        }
        assertEquals("Last rendered pixel colors", expected, observed)
    }

    private fun pixel(image: Image, x: Int, y: Int): Int {
        val plane = image.planes.single()
        val index = y * plane.rowStride + x * plane.pixelStride
        val data = plane.buffer
        return Color.rgb(data.get(index).toInt() and 0xff,
            data.get(index + 1).toInt() and 0xff, data.get(index + 2).toInt() and 0xff)
    }
}
