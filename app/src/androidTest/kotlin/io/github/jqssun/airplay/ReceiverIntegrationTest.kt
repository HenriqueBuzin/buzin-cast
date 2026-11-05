package io.github.jqssun.airplay

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.buzincast.core.*
import io.buzincast.media.FCastFrame
import io.buzincast.media.CastEnvelope
import org.json.JSONObject
import javax.net.ssl.*
import java.security.cert.X509Certificate
import io.github.jqssun.airplay.discovery.*
import io.leancast.app.Net
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** Real Android sockets, NSD, handlers and parsers; only the playback device is a recording port. */
@RunWith(AndroidJUnit4::class)
class ReceiverIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun coverageCheckpoint() {
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }
    private val ip get() = requireNotNull(Net.lanIpv4())
    private class Playback : ReceiverPlaybackPort {
        @Volatile override var activeConnections = 0
        @Volatile override var relayReceiving = false
        @Volatile override var mediaOwner: Protocol? = null
        @Volatile override var mediaActive = false
        @Volatile override var playbackInfo = PlaybackInfo()
        @Volatile var allowed = true
        val requests = CopyOnWriteArrayList<MediaRequest>()
        val events = CopyOnWriteArrayList<String>()
        override fun authorize(protocol: String, peer: String) = allowed.also { events += "authorize:$protocol" }
        override fun load(request: MediaRequest) { requests += request; mediaOwner = request.protocol; mediaActive = true }
        override fun stop(protocol: Protocol) { events += "stop:$protocol"; mediaActive = false }
        override fun playing(protocol: Protocol, playing: Boolean) { events += "playing:$playing" }
        override fun seek(protocol: Protocol, seconds: Float) { events += "seek:$seconds" }
        override fun gain(protocol: Protocol, gain: Float) { events += "gain:$gain" }
        override fun speed(protocol: Protocol, speed: Float) { events += "speed:$speed" }
        override fun photo(location: String) { events += "photo:$location" }
    }
    private fun eventually(check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5000
        while (!check()) { assertTrue("Timed out waiting for Android callback", SystemClock.elapsedRealtime() < end); Thread.sleep(20) }
    }
    private fun frame(socket: Socket, opcode: Int): FCastFrame {
        repeat(20) { val frame = requireNotNull(FCastFrame.readFrom(socket.getInputStream())); if (frame.opcode == opcode) return frame }
        error("Expected opcode $opcode")
    }
    private fun withFcast(test: (Playback, Socket) -> Unit) {
        val playback = Playback()
        val diagnostics = CopyOnWriteArrayList<String>()
        FCastReceiver(context, playback, "Buzin integration", { diagnostics += it }, requestedPort = 0).use { receiver ->
            try {
                receiver.start(); receiver.start() // idempotent startup
                check(receiver.listeningPort > 0) { "FCast listener did not start" }
                Socket(ip, receiver.listeningPort).use { socket -> socket.soTimeout = 5000; assertTrue(frame(socket, 11).body.contains("2")); test(playback, socket) }
            } catch (failure: Exception) {
                throw AssertionError("FCast socket integration failed: ${diagnostics.joinToString("; ")}", failure)
            }
        }
    }
    @Test fun fcastHandshakePingAndUnknownOpcode() = withFcast { _, socket ->
        FCastFrame(12).writeTo(socket.getOutputStream()); assertEquals(13, frame(socket, 13).opcode)
        FCastFrame(99).writeTo(socket.getOutputStream()); assertTrue(frame(socket, 9).body.contains("Unsupported"))
    }
    @Test fun fcastPlaybackControlsReachAndroidMainThread() = withFcast { playback, socket ->
        FCastFrame(1, """{"url":"http://example.test/movie.mp4","container":"video/mp4","time":3,"speed":1.5,"headers":{"X-Test":"yes"}}""").writeTo(socket.getOutputStream())
        eventually { playback.requests.size == 1 }
        val request = playback.requests.single()
        assertEquals(Protocol.FCAST, request.protocol); assertEquals(3f, request.time)
        assertEquals("video/mp4", request.mime); assertEquals("yes", request.headers["X-Test"])
        for ((opcode, body) in listOf(2 to "", 3 to "", 5 to """{"time":12}""", 8 to """{"volume":0.5}""", 10 to """{"speed":2}""", 4 to "")) {
            FCastFrame(opcode, body).writeTo(socket.getOutputStream())
        }
        eventually { playback.events.contains("stop:FCAST") }
        assertTrue(playback.events.containsAll(listOf("playing:false", "playing:true", "seek:12.0", "gain:0.5", "speed:2.0")))
    }
    @Test fun fcastRejectsInvalidUrlsAndHeadersWithoutClaimingPlayback() = withFcast { playback, socket ->
        for (body in listOf(
            """{"url":"file:///etc/passwd","container":"video/mp4"}""",
            """{"url":"http://example.test/a","container":"text/html"}""",
            """{"url":"http://example.test/a","container":"video/mp4","headers":{"Host":"evil"}}""",
            """{"url":"http://user:password@example.test/a","container":"video/mp4"}""",
            """{"url":"http://example.test/a","container":"video/mp4","speed":0}"""
        )) { FCastFrame(1, body).writeTo(socket.getOutputStream()); frame(socket, 9) }
        assertTrue(playback.requests.isEmpty())
    }
    @Test fun fcastCannotTakeOverActiveMirroring() = withFcast { playback, socket ->
        playback.activeConnections = 1
        FCastFrame(1, """{"url":"http://example.test/a","container":"video/mp4"}""").writeTo(socket.getOutputStream())
        assertTrue(frame(socket, 9).body.contains("Another screen")); assertTrue(playback.requests.isEmpty())
    }
    @Test fun fcastRejectsControlsForAnotherProtocol() = withFcast { playback, socket ->
        playback.mediaOwner = Protocol.DLNA; playback.mediaActive = true
        FCastFrame(4).writeTo(socket.getOutputStream()); frame(socket, 9)
        assertFalse(playback.events.contains("stop:FCAST"))
    }
    @Test fun fcastReconnectsAfterMalformedFrame() {
        val playback = Playback()
        FCastReceiver(context, playback, "Buzin integration", {}, requestedPort = 0).use { receiver ->
            receiver.start()
            Socket(ip, receiver.listeningPort).use { socket ->
                socket.soTimeout = 5000; frame(socket, 11)
                socket.getOutputStream().write(byteArrayOf(-1,-1,-1,127))
                eventually { socket.getInputStream().read() == -1 }
            }
            Thread.sleep(100)
            Socket(ip, receiver.listeningPort).use { socket -> socket.soTimeout = 5000; frame(socket, 11); FCastFrame(12).writeTo(socket.getOutputStream()); frame(socket, 13) }
        }
    }
    @Test fun fcastReleasesItsPortBeforeAnotherReceiverStarts() {
        repeat(5) {
            val diagnostics = CopyOnWriteArrayList<String>()
            val first = FCastReceiver(context, Playback(), "Buzin close test", { diagnostics += it }, requestedPort = 0)
            first.start()
            val port = first.listeningPort
            assertTrue(diagnostics.toString(), port > 0)
            try {
                Socket(ip, port).use { socket ->
                    socket.soTimeout = 5000; frame(socket, 11)
                    first.close()
                    FCastReceiver(context, Playback(), "Buzin restart test", { diagnostics += it }, requestedPort = port).use { next ->
                        next.start()
                        assertEquals(diagnostics.toString(), port, next.listeningPort)
                        Socket(ip, port).use { fresh -> fresh.soTimeout = 5000; frame(fresh, 11) }
                    }
                }
            } finally { first.close() }
        }
    }
    @Test fun dlnaHttpDescriptionAndScpdsUseRealListener() {
        val log = CopyOnWriteArrayList<String>()
        DlnaReceiver(context, Playback(), "Buzin integration", { log += it }).use { receiver ->
            receiver.start()
            val line = log.firstOrNull { it.contains("receiver started on") } ?: error(log.toString())
            val port = line.substringAfterLast(":").toInt()
            fun request(method: String, path: String, headers: String = "", body: String = ""): String = Socket(ip, port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(("$method $path HTTP/1.1\r\nHost: $ip\r\nConnection: close\r\nContent-Length: ${body.toByteArray().size}\r\n$headers\r\n$body").toByteArray())
                socket.getInputStream().bufferedReader().readText()
            }
            val description = request("GET", "/desc.xml")
            assertTrue(description.startsWith("HTTP/1.1 200")); assertTrue(description.contains("Buzin integration"))
            for (path in listOf("/scpd/avt.xml", "/scpd/rcs.xml", "/scpd/cmn.xml")) assertTrue(request("GET", path).startsWith("HTTP/1.1 200"))
            assertTrue(request("GET", "/missing").startsWith("HTTP/1.1 404"))
            assertTrue(request("HEAD", "/desc.xml").substringAfter("\r\n\r\n").isEmpty())
            val invalid = request("POST", "/ctl/avt", "SOAPAction: urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\r\n", "<broken>")
            assertTrue(invalid, invalid.startsWith("HTTP/1.1 400"))
        }
    }
    @Test fun dlnaSubscriptionNotifiesFallbackCallbackAndSupportsRenewalAndRemoval() {
        val log = CopyOnWriteArrayList<String>()
        DlnaReceiver(context, Playback(), "Buzin integration", { log += it }).use { receiver ->
            receiver.start()
            val port = log.first { it.contains("receiver started on") }.substringAfterLast(':').toInt()
            fun eventRequest(method: String, vararg headers: String): String = Socket(ip, port).use { socket ->
                socket.soTimeout = 5000
                val head = listOf("$method /evt/avt HTTP/1.1", "Host: $ip", "Connection: close") + headers
                socket.getOutputStream().write((head.joinToString("\r\n") + "\r\n\r\n").toByteArray())
                socket.getInputStream().bufferedReader().readText()
            }
            assertTrue(eventRequest("SUBSCRIBE").startsWith("HTTP/1.1 412"))
            assertTrue(eventRequest("SUBSCRIBE", "CALLBACK: <http://127.0.0.1/foreign>").startsWith("HTTP/1.1 412"))
            ServerSocket(0).use { callback ->
                callback.soTimeout = 5000
                val base = "http://$ip:${callback.localPort}"
                val response = eventRequest("SUBSCRIBE", "CALLBACK: <$base/fail> <$base/notify?source=test>",
                    "NT: upnp:event", "TIMEOUT: SECOND-120")
                assertTrue(response, response.startsWith("HTTP/1.1 200"))
                assertTrue(response, response.contains("TIMEOUT: Second-120", ignoreCase = true))
                val sid = response.lineSequence().first { it.startsWith("SID:", ignoreCase = true) }.substringAfter(':').trim()

                fun receive(path: String, status: String) = callback.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    assertEquals("NOTIFY $path HTTP/1.1", input.readLine())
                    val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
                    assertTrue("SID: $sid" in headers)
                    assertTrue("SEQ: 0" in headers)
                    val size = headers.first { it.startsWith("CONTENT-LENGTH:") }.substringAfter(':').trim().toInt()
                    val body = buildString { repeat(size) { append(input.read().also { assertTrue(it >= 0) }.toChar()) } }
                    assertTrue(body.contains("LastChange"))
                    socket.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Length: 0\r\n\r\n").toByteArray())
                }
                receive("/fail", "500 Diagnostic 200")
                receive("/notify?source=test", "200 OK")
                val renewed = eventRequest("SUBSCRIBE", "SID: $sid", "TIMEOUT: Second-10")
                assertTrue(renewed.startsWith("HTTP/1.1 200"))
                assertTrue(renewed.contains("TIMEOUT: Second-60", ignoreCase = true))
                assertTrue(eventRequest("UNSUBSCRIBE", "SID: $sid").startsWith("HTTP/1.1 200"))
                assertTrue(eventRequest("SUBSCRIBE", "SID: $sid").startsWith("HTTP/1.1 412"))
                assertTrue(eventRequest("UNSUBSCRIBE", "SID: $sid").startsWith("HTTP/1.1 412"))
            }
        }
    }
    @Test fun castV2TlsHeartbeatStatusAndUnsupportedApp() {
        val playback = Playback()
        val log = CopyOnWriteArrayList<String>()
        CastV2Receiver(context, playback, "Buzin integration", { log += it }).use { receiver ->
            receiver.start()
            val port = log.first { it.contains("listening on") }.substringBefore(";").substringAfterLast(":").toInt()
            // Test-only trust manager for this receiver's self-signed test identity.
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            }
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            (tls.socketFactory.createSocket(ip, port) as SSLSocket).use { socket ->
                socket.soTimeout = 5000; socket.startHandshake()
                fun exchange(namespace: String, body: String): JSONObject {
                    CastEnvelope("sender-test", "receiver-0", "urn:x-cast:com.google.cast.$namespace", body).writeTo(socket.getOutputStream())
                    return JSONObject(requireNotNull(CastEnvelope.readFrom(socket.getInputStream())).text!!)
                }
                assertEquals("PONG", exchange("tp.heartbeat", """{"type":"PING"}""").getString("type"))
                assertEquals("RECEIVER_STATUS", exchange("receiver", """{"type":"GET_STATUS","requestId":1}""").getString("type"))
                val unsupported = exchange("receiver", """{"type":"LAUNCH","requestId":2,"appId":"UNKNOWN"}""")
                assertEquals("LAUNCH_ERROR", unsupported.getString("type")); assertEquals("NOT_FOUND", unsupported.getString("reason"))
                assertTrue(playback.requests.isEmpty())
            }
        }
    }
}
