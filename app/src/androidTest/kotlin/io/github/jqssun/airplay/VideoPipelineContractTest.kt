package io.github.jqssun.airplay

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.buzincast.media.NalUnits
import io.github.jqssun.airplay.renderer.EglCore
import io.github.jqssun.airplay.renderer.VideoPipeline
import io.github.jqssun.airplay.renderer.VideoRenderer
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
            ImageReader.newInstance(96, size, format, 3).use { second ->
                fun awaitWideDisplay(insideOriginalPicture: Int) {
                    awaitColors(second, listOf(Color.MAGENTA, Color.BLACK, insideOriginalPicture, Color.MAGENTA)) { frame ->
                        listOf(48, 1, 17, 23).map { x -> pixel(frame, x, 32) }
                    }
                }
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
                    awaitWideDisplay(Color.MAGENTA)
                    assertSame(input, pipeline.inputSurface)
                    pipeline.setMarginPercent(15)
                    awaitWideDisplay(Color.BLACK)
                    pipeline.setMarginPercent(-5)
                    awaitWideDisplay(Color.MAGENTA)
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
        drawOrientationPattern(input, size)
        awaitFirstConsumedFrame()

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

    @Test fun aDisplayAttachedBeforeItsFirstFrameRendersTheSameOrientation() {
        val size = 64
        val format = windowPixelFormat()
        pipeline.setVideoSize(size, size)
        pipeline.start()
        val input = requireNotNull(pipeline.inputSurface)

        ImageReader.newInstance(size, size, format, 3).use { reader ->
            try {
                pipeline.setDisplaySurface(reader.surface)
                drawOrientationPattern(input, size)
                awaitColors(reader, Color.RED to Color.BLUE) { image ->
                    pixel(image, 32, 16) to pixel(image, 32, 48)
                }
            } finally {
                pipeline.release()
            }
        }
    }

    @Test fun releasedDisplayDoesNotPreventRenderingToTheNextSurface() {
        val size = 64
        val format = windowPixelFormat()
        pipeline.start()
        pipeline.setVideoSize(size, size)
        val input = requireNotNull(pipeline.inputSurface)

        ImageReader.newInstance(size, size, format, 3).use { retired ->
            val invalidDisplay = retired.surface
            invalidDisplay.release()
            assertFalse(invalidDisplay.isValid)
            pipeline.setDisplaySurface(invalidDisplay)

            val canvas = input.lockCanvas(null)
            try {
                canvas.drawColor(Color.CYAN)
            } finally {
                input.unlockCanvasAndPost(canvas)
            }
            awaitFirstConsumedFrame()
        }

        ImageReader.newInstance(size, size, format, 3).use { replacement ->
            try {
                pipeline.setDisplaySurface(replacement.surface)
                awaitPixels(replacement, Color.CYAN, Color.CYAN)
                assertSame(input, pipeline.inputSurface)
            } finally {
                pipeline.release()
            }
        }
    }

    @Test fun avcFramesReachTheDisplayWithScheduledAndImmediateRelease() {
        // Legacy OMX requires the constrained-baseline fixture. The newer
        // Goldfish decoder accepts it but yields no frames at that size.
        val legacy = Build.VERSION.SDK_INT < 26
        val width = if (legacy) 32 else 64
        val height = if (legacy) 32 else 48
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val frames = avcFrames(legacy)

        val format = windowPixelFormat()
        for (scheduled in listOf(true, false)) {
            ImageReader.newInstance(width, height, format, 3).use { reader ->
                val renderer = VideoRenderer(context)
                try {
                    renderer.scheduledOutputBufferRelease = scheduled
                    renderer.selectDecoders(width, height, fps = 10, h265 = false)
                    renderer.setResolution(width, height)
                    renderer.setSurface(reader.surface)
                    renderer.startSession()
                    var coloredFrame = false
                    var images = 0
                    fun readFrame() {
                        reader.acquireLatestImage()?.use {
                            images++
                            val color = pixel(it, width / 2, if (legacy) height / 2 else 10)
                            val matches = if (legacy) Color.blue(color) > 160 && Color.red(color) < 80
                                else Color.red(color) > 80
                            if (matches) coloredFrame = true
                        }
                    }
                    var sent = 0L
                    // Allow extra input for decoders that buffer multiple access units before rendering.
                    while (!coloredFrame && sent < frames.size * 3L) {
                        for (frame in frames) {
                            renderer.feedFrame(frame, 1_000_000_000L + sent * 100_000_000L, isH265 = false)
                            sent++
                            val nextFrame = SystemClock.elapsedRealtime() + 100L
                            do {
                                readFrame()
                                SystemClock.sleep(10)
                            } while (SystemClock.elapsedRealtime() < nextFrame)
                        }
                    }
                    val deadline = SystemClock.elapsedRealtime() + 5_000L
                    while (!coloredFrame && SystemClock.elapsedRealtime() < deadline) {
                        readFrame()
                        SystemClock.sleep(20)
                    }
                    assertTrue("No decoded color for scheduled=$scheduled, images=$images, codec=${renderer.codecName}",
                        coloredFrame)
                    assertTrue(renderer.codecName.startsWith("H.264"))
                    assertEquals(sent, renderer.frameCount)
                } finally {
                    renderer.release()
                }
            }
        }
    }

    @Test fun switchingCodecsWaitsForANewKeyframeAndKeepsThePipeline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderer = VideoRenderer(context)
        val codec = VideoRenderer::class.java.getDeclaredField("codec").apply { isAccessible = true }
        val inputPipeline = VideoRenderer::class.java.getDeclaredField("pipeline")
            .apply { isAccessible = true }.get(renderer) as VideoPipeline
        val (keyframe, reference) = avcFrames()
        val hevcReference = context.assets.open("mirror/sample.h265").use { input ->
            val slice = NalUnits.split(input.readBytes()).first { NalUnits.type(it, hevc = true) == 1 }
            byteArrayOf(0, 0, 0, 1) + slice
        }
        try {
            renderer.selectDecoders(32, 32, fps = 10, h265 = false)
            renderer.startSession()
            renderer.feedFrame(keyframe, 0, isH265 = false)
            assertNull("A decoder opened before receiving a size", codec.get(renderer))
            renderer.setResolution(32, 32)
            renderer.feedFrame(reference, 100_000_000L, isH265 = false)
            assertNull("A reference frame opened a decoder", codec.get(renderer))

            renderer.feedFrame(keyframe, 200_000_000L, isH265 = false)
            val original = requireNotNull(codec.get(renderer))
            val surface = requireNotNull(inputPipeline.inputSurface)
            renderer.feedFrame(hevcReference, 300_000_000L, isH265 = true)
            assertNull("The old AVC decoder survived the codec change", codec.get(renderer))
            assertSame(surface, inputPipeline.inputSurface)

            renderer.feedFrame(reference, 400_000_000L, isH265 = false)
            assertNull(codec.get(renderer))
            renderer.feedFrame(keyframe, 500_000_000L, isH265 = false)
            assertNotSame(original, requireNotNull(codec.get(renderer)))
            assertSame(surface, inputPipeline.inputSurface)
        } finally {
            renderer.release()
        }
    }

    private fun drawOrientationPattern(input: Surface, size: Int) {
        val paint = Paint().apply { color = Color.RED }
        val canvas = input.lockCanvas(null)
        try {
            canvas.drawColor(Color.BLUE)
            canvas.drawRect(0f, 0f, size.toFloat(), size / 2f, paint)
        } finally {
            input.unlockCanvasAndPost(canvas)
        }
    }

    private fun awaitFirstConsumedFrame() {
        val texture = VideoPipeline::class.java.getDeclaredField("surfaceTexture")
            .apply { isAccessible = true }.get(pipeline) as SurfaceTexture
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (texture.timestamp == 0L && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("The frame was not consumed before display attachment", texture.timestamp != 0L)
    }

    private fun avcFrames(legacy: Boolean = true): List<ByteArray> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // The blue legacy fixture is the Annex B version of the baseline MP4.
        val assets = if (legacy) instrumentation.context.assets else instrumentation.targetContext.assets
        val path = if (legacy) "playback/mirror-baseline.h264" else "mirror/sample.h264"
        val nals = assets.open(path).use { NalUnits.split(it.readBytes()) }
        val units = mutableListOf<MutableList<ByteArray>>()
        for (nal in nals) {
            if (units.isEmpty() || NalUnits.type(nal, hevc = false) == 9) units += mutableListOf<ByteArray>()
            units.last().add(nal)
        }
        val startCode = byteArrayOf(0, 0, 0, 1)
        val frames = units.map { unit -> unit.fold(byteArrayOf()) { frame, nal -> frame + startCode + nal } }
        assertEquals(10, frames.size)
        return frames
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

    private fun <T> awaitColors(reader: ImageReader, expected: T, sample: (Image) -> T) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var observed: T? = null
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
