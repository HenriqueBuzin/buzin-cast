package io.github.jqssun.airplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.media.PhotoActivity
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Actual activity lifecycle, controls, HTTP loading and Android bitmap decoding. */
@RunWith(AndroidJUnit4::class)
class PhotoViewerContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun intent(url: String? = null) = Intent(context, PhotoActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { if (url != null) putExtra("url", url) }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: PhotoActivity, name: String): T =
        PhotoActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    private fun click(activity: PhotoActivity, label: Int) {
        val button = views(activity.window.decorView).filterIsInstance<Button>()
            .single { it.text.toString() == activity.getString(label) }
        assertTrue("Photo control did not handle its click", button.performClick())
    }

    private fun await(scenario: ActivityScenario<PhotoActivity>, message: String, condition: (PhotoActivity) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            Thread.sleep(20)
        }
        fail(message)
    }

    private fun photo(scenario: ActivityScenario<PhotoActivity>, position: Int, count: Int, color: Int) {
        await(scenario, "Photo pixels/position did not update") { activity ->
            val bitmap = field<Bitmap?>(activity, "bitmap")
            bitmap != null && !bitmap.isRecycled && bitmap.width == 64 && bitmap.height == 48 &&
                bitmap.getPixel(0, 0) == color && field<TextView>(activity, "status").text.toString() ==
                activity.getString(R.string.photo_position, position, count)
        }
    }

    private fun png(color: Int): ByteArray {
        val image = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        return try {
            image.eraseColor(color)
            ByteArrayOutputStream().apply { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray()
        } finally { image.recycle() }
    }

    private fun awaitClosed() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            var visible = true
            instrumentation.runOnMainSync { visible = PhotoActivity.isVisible() }
            if (!visible) return
            Thread.sleep(20)
        }
        fail("Photo activity did not close")
    }

    private class Server(private val red: ByteArray, private val blue: ByteArray) : Closeable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        private val failure = AtomicReference<Throwable?>()
        fun url(path: String) = "http://127.0.0.1:${socket.localPort}$path"
        private val thread = Thread({
            while (running.get()) try {
                socket.accept().use { client ->
                    client.soTimeout = 5000
                    val input = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val path = requireNotNull(input.readLine()).split(' ')[1]
                    while (input.readLine()?.isNotEmpty() == true) { }
                    val number = path.substringAfter("i=", "0").toIntOrNull() ?: 0
                    val data = when {
                        path.startsWith("/invalid") -> byteArrayOf(1, 2, 3)
                        number % 2 == 0 -> red
                        else -> blue
                    }
                    val headers = when {
                        path == "/missing" -> "HTTP/1.0 404 Not Found\r\nContent-Length: 0"
                        path == "/redirect" -> "HTTP/1.0 302 Found\r\nContent-Length: 0"
                        path == "/large" -> "HTTP/1.0 200 OK\r\nContent-Length: ${16 * 1024 * 1024 + 1}"
                        else -> "HTTP/1.0 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${data.size}"
                    }
                    client.getOutputStream().apply {
                        write((headers + "\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        if (path !in listOf("/missing", "/redirect", "/large")) write(data)
                        flush()
                    }
                }
            } catch (error: Throwable) {
                if (running.get() || error !is IOException) failure.set(error)
                break
            }
        }, "photo-viewer-contract-http").apply { isDaemon = true; start() }
        override fun close() {
            running.set(false); socket.close(); thread.join(6000)
            assertFalse("Photo HTTP fixture did not stop", thread.isAlive)
            assertNull("Photo HTTP fixture failed", failure.get())
        }
    }

    @After fun cleanup() {
        PhotoActivity.closePhoto()
        instrumentation.waitForIdleSync()
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Test fun emptyViewerHandlesControlsAndClosesWithoutRetainingTheActivity() {
        PhotoActivity.closePhoto()
        ActivityScenario.launch<PhotoActivity>(intent()).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(PhotoActivity.isVisible())
                assertNull(field<Bitmap?>(activity, "bitmap"))
                assertEquals(activity.getString(R.string.photo_loading), field<TextView>(activity, "status").text)
                click(activity, R.string.media_previous)
                click(activity, R.string.media_next)
                assertEquals(-1, field<Int>(activity, "index"))
                val image = field<ImageView>(activity, "image")
                repeat(5) { click(activity, R.string.photo_rotate) }
                assertEquals(90f, image.rotation, 0f)
                click(activity, R.string.photo_scale)
                assertEquals(ImageView.ScaleType.CENTER_CROP, image.scaleType)
                click(activity, R.string.photo_scale)
                assertEquals(ImageView.ScaleType.FIT_CENTER, image.scaleType)
                click(activity, R.string.photo_slideshow)
                assertTrue(field<Job>(activity, "slideShow").isActive)
                click(activity, R.string.btn_stop)
                assertFalse(field<Job>(activity, "slideShow").isActive)
                click(activity, R.string.buzin_close)
            }
        }
        assertFalse("Closed photo activity remained visible", PhotoActivity.isVisible())
        PhotoActivity.closePhoto()
    }

    @Test fun newPhotosRecycleOldBitmapsBoundHistoryWrapAndAdvanceTheSlideshow() {
        Server(png(Color.RED), png(Color.BLUE)).use { server ->
            ActivityScenario.launch<PhotoActivity>(intent(server.url("/photo?i=0"))).use { scenario ->
                photo(scenario, 1, 1, Color.RED)
                lateinit var initial: Bitmap
                scenario.onActivity { activity ->
                    initial = field(activity, "bitmap")
                    click(activity, R.string.photo_rotate)
                    click(activity, R.string.photo_scale)
                    activity.startActivity(intent(server.url("/photo?i=0")))
                }
                await(scenario, "Repeated URL did not reload/recycle the bitmap") { initial.isRecycled }
                photo(scenario, 1, 1, Color.RED)
                scenario.onActivity { activity ->
                    assertEquals(0f, field<ImageView>(activity, "image").rotation, 0f)
                    assertEquals(ImageView.ScaleType.CENTER_CROP, field<ImageView>(activity, "image").scaleType)
                }
                for (index in 1..21) {
                    scenario.onActivity { it.startActivity(intent(server.url("/photo?i=$index"))) }
                    photo(scenario, minOf(index + 1, 20), minOf(index + 1, 20), if (index % 2 == 0) Color.RED else Color.BLUE)
                }
                scenario.onActivity { activity ->
                    val history = field<List<String>>(activity, "history")
                    assertEquals(20, history.size)
                    assertEquals(server.url("/photo?i=2"), history.first())
                    assertEquals(server.url("/photo?i=21"), history.last())
                    click(activity, R.string.media_previous)
                }
                photo(scenario, 19, 20, Color.RED)
                scenario.onActivity { click(it, R.string.media_next) }
                photo(scenario, 20, 20, Color.BLUE)
                scenario.onActivity { click(it, R.string.media_next) }
                photo(scenario, 1, 20, Color.RED)
                scenario.onActivity { click(it, R.string.photo_slideshow) }
                photo(scenario, 2, 20, Color.BLUE)
                scenario.onActivity { click(it, R.string.btn_stop) }
                lateinit var final: Bitmap
                scenario.onActivity { final = field(it, "bitmap") }
                PhotoActivity.closePhoto()
                awaitClosed()
                assertTrue("Activity destruction did not recycle the displayed bitmap", final.isRecycled)
            }
        }
        assertFalse(PhotoActivity.isVisible())
    }

    @Test fun loadingFailuresShowTheirMessagesAndAValidPhotoRecoversTheViewer() {
        Server(png(Color.RED), png(Color.BLUE)).use { server ->
            ActivityScenario.launch<PhotoActivity>(intent(server.url("/invalid"))).use { scenario ->
                for ((path, message) in listOf("/invalid" to R.string.photo_error_format,
                    "/missing" to R.string.photo_error, "/redirect" to R.string.photo_error_redirect,
                    "/large" to R.string.photo_error_size)) {
                    if (path != "/invalid") scenario.onActivity { it.startActivity(intent(server.url(path))) }
                    await(scenario, "Photo failure did not produce its UI message") {
                        field<TextView>(it, "status").text.toString() == it.getString(message)
                    }
                    scenario.onActivity { assertNull(field<Bitmap?>(it, "bitmap")) }
                }
                scenario.onActivity { it.startActivity(intent(server.url("/photo?i=0"))) }
                photo(scenario, 5, 5, Color.RED)
                lateinit var retained: Bitmap
                scenario.onActivity {
                    retained = field(it, "bitmap")
                    it.startActivity(intent(server.url("/invalid?again")))
                }
                await(scenario, "Invalid replacement did not show the format error") {
                    field<TextView>(it, "status").text.toString() == it.getString(R.string.photo_error_format)
                }
                scenario.onActivity { assertSame(retained, field<Bitmap>(it, "bitmap")); assertFalse(retained.isRecycled) }
            }
        }
    }
}
