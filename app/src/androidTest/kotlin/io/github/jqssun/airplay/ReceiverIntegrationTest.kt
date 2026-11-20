package io.github.jqssun.airplay

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
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
import java.net.ConnectException
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
        @Volatile var failLoad = false
        val requests = CopyOnWriteArrayList<MediaRequest>()
        val events = CopyOnWriteArrayList<String>()
        override fun authorize(protocol: String, peer: String) = allowed.also { events += "authorize:$protocol" }
        override fun load(request: MediaRequest) {
            check(!failLoad) { "Fixture player refused the request" }
            requests += request
            mediaOwner = request.protocol
            mediaActive = true
        }
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
    private inner class DlnaControl(private val port: Int, val receiver: DlnaReceiver) {
        fun request(method: String, path: String, headers: String = "", body: String = ""): String =
            Socket(ip, port).use { socket ->
                socket.soTimeout = 5000
                val request = "$method $path HTTP/1.1\r\nHost: $ip\r\nConnection: close\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\n$headers\r\n$body"
                socket.getOutputStream().write(request.toByteArray())
                socket.getInputStream().bufferedReader().readText()
            }

        private fun soap(path: String, name: String, action: String, args: Array<out Pair<String, String>>): String {
            val service = "urn:schemas-upnp-org:service:$name:1"
            val arguments = args.joinToString("") { (name, value) -> "<$name>${TextUtils.htmlEncode(value)}</$name>" }
            val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">""" +
                """<s:Body><u:$action xmlns:u="$service"><InstanceID>0</InstanceID>$arguments</u:$action></s:Body></s:Envelope>"""
            return request("POST", path, "SOAPAction: \"$service#$action\"\r\n", body)
        }

        fun transport(action: String, vararg args: Pair<String, String>) = soap("/ctl/avt", "AVTransport", action, args)

        fun rendering(action: String, vararg args: Pair<String, String>) =
            soap("/ctl/rcs", "RenderingControl", action, arrayOf("Channel" to "Master", *args))

        fun accepted(action: String, vararg args: Pair<String, String>): String = transport(action, *args).also {
            assertTrue(it, it.startsWith("HTTP/1.1 200"))
        }

        fun media(location: String, metadata: String = "") =
            transport("SetAVTransportURI", "CurrentURI" to location, "CurrentURIMetaData" to metadata)

        fun state() = accepted("GetTransportInfo").substringAfter("<CurrentTransportState>")
            .substringBefore("</CurrentTransportState>")
    }

    private fun withDlna(test: (Playback, DlnaControl) -> Unit) {
        val log = CopyOnWriteArrayList<String>()
        val playback = Playback()
        DlnaReceiver(context, playback, "Buzin integration", { log += it }).use { receiver ->
            receiver.start()
            receiver.start()
            val line = log.filter { it.contains("receiver started on") }.singleOrNull() ?: error(log.toString())
            test(playback, DlnaControl(line.substringAfterLast(':').toInt(), receiver))
        }
    }

    @Test fun dlnaHttpDescriptionAndScpdsUseRealListener() = withDlna { _, control ->
        val description = control.request("GET", "/desc.xml")
        assertTrue(description.startsWith("HTTP/1.1 200")); assertTrue(description.contains("Buzin integration"))
        for (path in listOf("/scpd/avt.xml", "/scpd/rcs.xml", "/scpd/cmn.xml")) assertTrue(control.request("GET", path).startsWith("HTTP/1.1 200"))
        assertTrue(control.request("GET", "/missing").startsWith("HTTP/1.1 404"))
        assertTrue(control.request("HEAD", "/desc.xml").substringAfter("\r\n\r\n").isEmpty())
        val invalid = control.request("POST", "/ctl/avt", "SOAPAction: urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\r\n", "<broken>")
        assertTrue(invalid, invalid.startsWith("HTTP/1.1 400"))
    }

    @Test fun dlnaTransportUsesPlayerStateAndDistinguishesLiveStreams() = withDlna { playback, control ->
        val movie = "http://example.test/movie.mp4"
        playback.playbackInfo = PlaybackInfo(positionMs = 12_000, durationMs = 90_000, buffering = true)
        assertTrue(control.media(movie).startsWith("HTTP/1.1 200"))
        eventually { playback.requests == listOf(MediaRequest(movie, Protocol.DLNA)) }
        assertEquals("TRANSITIONING", control.state())

        playback.playbackInfo = playback.playbackInfo.copy(buffering = false)
        eventually { control.state() == "PLAYING" }
        val position = control.accepted("GetPositionInfo")
        assertTrue(position, "<RelTime>0:00:12</RelTime>" in position)
        assertTrue(position, "<TrackDuration>0:01:30</TrackDuration>" in position)
        control.accepted("Seek", "Unit" to "REL_TIME", "Target" to "00:00:34")
        eventually { "seek:34.0" in playback.events }

        playback.playbackInfo = playback.playbackInfo.copy(playing = false)
        eventually { control.state() == "PAUSED_PLAYBACK" && "playing:false" in playback.events }
        val pauseCount = playback.events.count { it == "playing:false" }
        control.accepted("Pause")
        eventually { playback.events.count { it == "playing:false" } > pauseCount }
        playback.playbackInfo = playback.playbackInfo.copy(playing = true)
        eventually { control.state() == "PLAYING" && "playing:true" in playback.events }

        playback.playbackInfo = playback.playbackInfo.copy(durationMs = 0)
        val livePosition = control.accepted("GetPositionInfo")
        assertTrue(livePosition, "<TrackDuration>0:00:00</TrackDuration>" in livePosition)
        control.accepted("Seek", "Unit" to "REL_TIME", "Target" to "00:00:56")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(listOf("seek:34.0"), playback.events.filter { it.startsWith("seek:") })

        control.accepted("Stop")
        eventually { control.state() == "STOPPED" && "stop:DLNA" in playback.events }
        control.accepted("Play", "Speed" to "1")
        eventually { control.state() == "PLAYING" && playback.requests.size == 2 }
        playback.mediaActive = false
        eventually { control.state() == "STOPPED" }
    }

    @Test fun dlnaRejectsUnplayableItemsAndReleasesOwnershipOnHandoff() = withDlna { playback, control ->
        val movie = "https://example.test/movie.mp4"
        for (location in listOf("ftp://example.test/movie.mp4", "http:/movie.mp4", "http://user@example.test/movie.mp4")) {
            val rejected = control.media(location)
            assertTrue(rejected, rejected.startsWith("HTTP/1.1 500") && "<errorCode>402</errorCode>" in rejected)
        }
        playback.activeConnections = 1
        val busy = control.media(movie)
        assertTrue(busy, busy.startsWith("HTTP/1.1 500") && "<errorCode>701</errorCode>" in busy)
        assertTrue(playback.requests.isEmpty())

        playback.activeConnections = 0
        playback.failLoad = true
        assertTrue(control.media(movie).startsWith("HTTP/1.1 200"))
        eventually { control.state() == "STOPPED" }
        assertTrue(playback.requests.isEmpty())

        playback.failLoad = false
        assertTrue(control.media(movie).startsWith("HTTP/1.1 200"))
        eventually { control.state() == "PLAYING" }
        playback.mediaOwner = Protocol.FCAST
        eventually { control.state() == "STOPPED" }
        control.accepted("Stop")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(Protocol.FCAST, playback.mediaOwner)
        assertTrue(playback.mediaActive)
        assertFalse("stop:DLNA" in playback.events)

        assertTrue(control.media(movie).startsWith("HTTP/1.1 200"))
        eventually { control.state() == "PLAYING" }
        playback.activeConnections = 1
        eventually { control.state() == "STOPPED" }
        assertFalse("stop:DLNA" in playback.events)
    }

    @Test fun dlnaRoutesPhotosBySuffixOrMetadataAndExpiresUnopenedViewer() = withDlna { playback, control ->
        val cases = listOf("photo.jpg" to "", "photo.JPEG" to "", "photo.png" to "", "jpeg" to "image/jpeg", "png" to "image/png")
        for ((path, metadata) in cases) {
            val location = "http://example.test/$path"
            assertTrue(control.media(location, metadata).startsWith("HTTP/1.1 200"))
            eventually { control.state() == "PLAYING" && "photo:$location" in playback.events }
            if (path != "png") control.accepted("Stop")
            eventually { control.state() == "STOPPED" }
        }
        assertTrue(playback.requests.isEmpty())
    }
    @Test fun dlnaRenderingControlSetsMediaVolumeAndNotifiesSubscribers() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val original = audio.getStreamVolume(stream)
        val max = audio.getStreamMaxVolume(stream)
        assertTrue("Expected adjustable emulator media volume", max > 2 && !audio.isVolumeFixed)
        try {
            withDlna { playback, control ->
                ServerSocket(0).use { callback ->
                    callback.soTimeout = 5000
                    val response = control.request("SUBSCRIBE", "/evt/rcs",
                        "CALLBACK: <http://$ip:${callback.localPort}/volume>\r\nNT: upnp:event\r\n")
                    assertTrue(response, response.startsWith("HTTP/1.1 200"))
                    val sid = response.lineSequence().first { it.startsWith("SID:", ignoreCase = true) }.substringAfter(':').trim()
                    fun expectNotification(seq: Int, percent: Int) {
                        val body = receiveDlnaEvent(callback, "/volume", sid, seq)
                        assertTrue(body, "<LastChange>" in body)
                        assertTrue(body, TextUtils.htmlEncode("""<Volume channel="Master" val="$percent"/>""") in body)
                    }
                    expectNotification(0, original * 100 / max)
                    playback.allowed = false
                    val denied = control.rendering("SetVolume", "DesiredVolume" to "0")
                    assertTrue(denied, denied.startsWith("HTTP/1.1 403"))
                    assertEquals(original, audio.getStreamVolume(stream))
                    playback.allowed = true

                    val intermediate = if (original < max / 2) 63 else 37
                    for ((index, requested) in listOf(intermediate, 130).withIndex()) {
                        val percent = requested.coerceAtMost(100)
                        val set = control.rendering("SetVolume", "DesiredVolume" to requested.toString())
                        assertTrue(set, set.startsWith("HTTP/1.1 200"))
                        expectNotification(index + 1, percent)
                        val get = control.rendering("GetVolume")
                        assertTrue(get, get.startsWith("HTTP/1.1 200") && "<CurrentVolume>$percent</CurrentVolume>" in get)
                        eventually { audio.getStreamVolume(stream) == (percent * max / 100f).toInt() }
                    }
                    val removed = control.request("UNSUBSCRIBE", "/evt/rcs", "SID: $sid\r\n")
                    assertTrue(removed, removed.startsWith("HTTP/1.1 200"))
                }
            }
        } finally {
            audio.setStreamVolume(stream, original, 0)
            eventually { audio.getStreamVolume(stream) == original }
        }
    }

    private fun whileMainIsGated(block: () -> Unit) {
        val blocked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val resumedInTime = AtomicBoolean(false)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(Handler(Looper.getMainLooper()).post {
            blocked.countDown()
            resumedInTime.set(resume.await(4, TimeUnit.SECONDS))
        })
        try {
            assertTrue("Main looper did not reach the fixture gate", blocked.await(4, TimeUnit.SECONDS))
            block()
        } finally {
            resume.countDown()
            instrumentation.waitForIdleSync()
        }
        assertTrue("The SOAP request did not complete while the main looper was gated", resumedInTime.get())
    }

    @Test fun dlnaPendingLoadChecksAirPlayOwnershipAgainOnMainThread() = withDlna { playback, control ->
        whileMainIsGated {
            val response = control.media("http://example.test/queued.mp4")
            assertTrue(response, response.startsWith("HTTP/1.1 200"))
            playback.activeConnections = 1
            playback.mediaOwner = Protocol.AIRPLAY
            playback.mediaActive = true
        }
        eventually { control.state() == "STOPPED" }
        assertTrue(playback.requests.isEmpty())
        assertEquals(Protocol.AIRPLAY, playback.mediaOwner)
        assertTrue(playback.mediaActive)
    }

    @Test fun dlnaQueuedTransportCommandsCannotControlAReplacementPlayer() = withDlna { playback, control ->
        playback.playbackInfo = PlaybackInfo(durationMs = 60_000)
        val movie = "http://example.test/owned.mp4"
        assertTrue(control.media(movie).startsWith("HTTP/1.1 200"))
        eventually { control.state() == "PLAYING" }
        whileMainIsGated {
            control.accepted("Seek", "Unit" to "REL_TIME", "Target" to "0:00:20")
            control.accepted("Pause")
            assertEquals("PAUSED_PLAYBACK", control.state())
            control.accepted("Play", "Speed" to "1")
            assertEquals("PLAYING", control.state())
            control.accepted("Stop")
            assertEquals("STOPPED", control.state())
            playback.mediaOwner = Protocol.FCAST
        }
        assertEquals(listOf(MediaRequest(movie, Protocol.DLNA)), playback.requests)
        assertTrue(playback.events.toString(), playback.events.all { it == "authorize:DLNA" })
        assertEquals(Protocol.FCAST, playback.mediaOwner)
        assertTrue(playback.mediaActive)
    }

    @Test fun dlnaCloseDiscardsAcceptedButUndeliveredLoadAndVolume() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val original = audio.getStreamVolume(stream)
        assertTrue("Expected adjustable emulator media volume", audio.getStreamMaxVolume(stream) > 0 && !audio.isVolumeFixed)
        val target = if (original == 0) 100 else 0
        try {
            withDlna { playback, control ->
                whileMainIsGated {
                    assertTrue(control.media("http://example.test/after-close.mp4").startsWith("HTTP/1.1 200"))
                    assertTrue(control.rendering("SetVolume", "DesiredVolume" to target.toString()).startsWith("HTTP/1.1 200"))
                    control.receiver.close()
                    control.receiver.start()
                }
                assertTrue(playback.requests.isEmpty())
                assertEquals(original, audio.getStreamVolume(stream))
                val failed = runCatching { control.request("GET", "/desc.xml") }.exceptionOrNull()
                assertTrue("Closed receiver unexpectedly accepted a request: $failed", failed is ConnectException)
            }
        } finally {
            audio.setStreamVolume(stream, original, 0)
            eventually { audio.getStreamVolume(stream) == original }
        }
    }

    private fun receiveDlnaEvent(server: ServerSocket, path: String, sid: String, seq: Int, status: String = "200 OK"): String =
        server.accept().use { socket ->
            socket.soTimeout = 5000
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            assertEquals("NOTIFY $path HTTP/1.1", input.readLine())
            val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
            assertTrue("SID: $sid" in headers)
            assertTrue("SEQ: $seq" in headers)
            val size = headers.first { it.startsWith("CONTENT-LENGTH:") }.substringAfter(':').trim().toInt()
            val body = buildString { repeat(size) { append(input.read().also { assertTrue(it >= 0) }.toChar()) } }
            socket.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Length: 0\r\n\r\n").toByteArray())
            body
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

                assertTrue("LastChange" in receiveDlnaEvent(callback, "/fail", sid, 0, "500 Diagnostic 200"))
                assertTrue("LastChange" in receiveDlnaEvent(callback, "/notify?source=test", sid, 0))
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
