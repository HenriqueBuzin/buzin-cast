package io.github.jqssun.airplay

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.media.PhotoActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real HTTP framing and Android image decoding; no external hosts or disk downloads. */
@RunWith(AndroidJUnit4::class)
class PhotoTransferContractTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @After fun checkpoint() {
        if(runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }
    private fun png(width:Int,height:Int):ByteArray {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().apply { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,this)) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private class Server(private val pictures:Map<String,ByteArray>):Closeable {
        private val server=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
        private val running=AtomicBoolean(true)
        val requests=ConcurrentHashMap<String,AtomicInteger>()
        val port get()=server.localPort
        fun url(path:String)="http://127.0.0.1:$port$path"
        private val thread=Thread({
            while(running.get()) {
                try { server.accept().use(::respond) }
                catch(error:IOException) { if(!running.get()) break }
            }
        },"photo-contract-http").apply { isDaemon=true;start() }

        private fun respond(socket:Socket) {
            socket.soTimeout=5000
            val input=socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val request=input.readLine()?:return
            val path=request.split(' ')[1]
            while(input.readLine()?.isNotEmpty()==true) { }
            requests.getOrPut(path){AtomicInteger()}.incrementAndGet()
            val output=socket.getOutputStream()
            fun headers(status:Int,extra:String="") {
                output.write("HTTP/1.0 $status Test\r\nConnection: close\r\n$extra\r\n".toByteArray(Charsets.US_ASCII))
            }
            when(path) {
                "/redirect" -> headers(302,"Location: /ok\r\n")
                "/loop" -> headers(302,"Location: /loop\r\n")
                "/no-location" -> headers(302)
                "/redirect-file" -> headers(302,"Location: file:///nonexistent-photo\r\n")
                "/redirect-credentials" -> headers(302,"Location: http://sender:example@127.0.0.1:$port/ok\r\n")
                "/declared-oversize" -> headers(200,"Content-Length: ${16*1024*1024+1}\r\n")
                "/stream-oversize" -> {
                    headers(200)
                    val block=ByteArray(16384)
                    repeat(1025) { output.write(block) }
                }
                "/invalid" -> { headers(200,"Content-Length: 3\r\n");output.write(byteArrayOf(1,2,3)) }
                else -> {
                    val bytes=pictures[path]
                    if(bytes==null) headers(404)
                    else { headers(200,"Content-Length: ${bytes.size}\r\nContent-Type: image/png\r\n");output.write(bytes) }
                }
            }
            output.flush()
        }

        override fun close() { running.set(false);server.close();thread.join(6000);assertFalse("Photo fixture did not stop",thread.isAlive) }
    }

    @Test fun validPhotoAndRelativeRedirectDecodeWithTheirOriginalSize() {
        Server(mapOf("/ok" to png(64,48))).use { server ->
            for(path in listOf("/ok","/redirect")) {
                val bitmap=PhotoActivity.loadPhoto(server.url(path))
                try { assertEquals(64,bitmap.width);assertEquals(48,bitmap.height) }
                finally { bitmap.recycle() }
            }
            assertEquals(2,server.requests["/ok"]?.get())
        }
    }

    @Test fun oversizedDimensionsAreSampledToTheTvViewport() {
        Server(mapOf("/wide" to png(4000,8),"/tall" to png(8,2200))).use { server ->
            for(path in listOf("/wide","/tall")) {
                val bitmap=PhotoActivity.loadPhoto(server.url(path))
                try { assertTrue(bitmap.width in 1..3840);assertTrue(bitmap.height in 1..2160) }
                finally { bitmap.recycle() }
            }
        }
    }

    @Test fun rejectsRedirectLoopsMissingLocationsAndUnsupportedOrAuthenticatedTargets() {
        Server(emptyMap()).use { server ->
            for(path in listOf("/loop","/no-location","/redirect-file","/redirect-credentials"))
                assertThrows(IllegalArgumentException::class.java) { PhotoActivity.loadPhoto(server.url(path)) }
            assertEquals(5,server.requests["/loop"]?.get())
            assertNull(server.requests["/ok"])
        }
        assertThrows(IllegalArgumentException::class.java) { PhotoActivity.loadPhoto("file:///nonexistent-photo") }
        assertThrows(IllegalArgumentException::class.java) { PhotoActivity.loadPhoto("http://sender:example@127.0.0.1/photo") }
        assertThrows(java.net.MalformedURLException::class.java) { PhotoActivity.loadPhoto("not a URL") }
    }

    @Test fun rejectsHttpErrorsInvalidImagesAndBothAdvertisedAndStreamedSizeOverflow() {
        Server(emptyMap()).use { server ->
            for(path in listOf("/missing","/invalid","/declared-oversize","/stream-oversize"))
                assertThrows(IllegalArgumentException::class.java) { PhotoActivity.loadPhoto(server.url(path)) }
        }
    }
}
