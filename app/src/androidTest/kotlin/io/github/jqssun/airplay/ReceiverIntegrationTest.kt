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
import io.leancast.app.ControlServer
import io.leancast.app.Net
import io.leancast.app.SubscriptionRegistry
import io.leancast.domain.cast.CastSession
import io.leancast.domain.cast.PlayerPort
import io.leancast.domain.upnp.RendererDescription
import io.leancast.domain.upnp.SoapRouter
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
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
        FCastFrame(11, """{"version":1}""").writeTo(socket.getOutputStream())
        // Periodic status updates already in flight can precede EOF.
        repeat(6) { if (FCastFrame.readFrom(socket.getInputStream()) == null) return@withFcast }
        fail("FCast kept the connection open after an unsupported version")
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

    @Test fun fcastReportsPlayingPausedAndReleasedPlayback() = withFcast { playback, socket ->
        fun update(opcode: Int, matches: (JSONObject) -> Boolean): JSONObject {
            repeat(5) {
                val fields = JSONObject(frame(socket, opcode).body)
                if (matches(fields)) return fields
            }
            error("FCast did not report the expected update for opcode $opcode")
        }
        fun status(expectedState: Int) = update(6) { it.getInt("state") == expectedState }
        val output = socket.getOutputStream()
        FCastFrame(11, """{"version":2}""").writeTo(output)
        FCastFrame(13).writeTo(output)
        FCastFrame(12).writeTo(output)
        frame(socket, 13)

        playback.playbackInfo = PlaybackInfo(positionMs = 1250, durationMs = 12000, speed = 1.25f)
        FCastFrame(1, """{"url":"https://example.test/track.aac","container":"audio/aac","content":null,"headers":null,"time":null,"speed":null}""").writeTo(output)
        eventually { playback.events.contains("speed:1.0") }
        val request = playback.requests.single()
        assertEquals("audio/aac", request.mime)
        assertEquals(0f, request.time, 0f)
        assertTrue(request.headers.isEmpty())

        val playing = status(1)
        assertEquals(1.25, playing.getDouble("time"), 0.0)
        assertEquals(12.0, playing.getDouble("duration"), 0.0)
        assertEquals(1.25, playing.getDouble("speed"), 0.0)

        playback.playbackInfo = playback.playbackInfo.copy(playing = false, durationMs = -1)
        val paused = status(2)
        assertEquals(1.25, paused.getDouble("time"), 0.0)
        assertEquals(0.0, paused.getDouble("duration"), 0.0)

        FCastFrame(8, """{"volume":0.25}""").writeTo(output)
        eventually { playback.events.contains("gain:0.25") }
        update(7) { it.getDouble("volume") == 0.25 }

        playback.mediaActive = false
        val released = status(0)
        assertEquals(0.0, released.getDouble("time"), 0.0)
        assertEquals(0.0, released.getDouble("duration"), 0.0)
        assertEquals(1.0, released.getDouble("speed"), 0.0)
        FCastFrame(4).writeTo(output)
        assertTrue(frame(socket, 9).body.contains("No FCast media"))
        assertFalse(playback.events.contains("stop:FCAST"))
    }

    @Test fun fcastDeniesConnectionBeforeSendingTheHandshake() {
        val playback = Playback().apply { allowed = false }
        FCastReceiver(context, playback, "Buzin integration", {}, requestedPort = 0).use { receiver ->
            receiver.start()
            Socket(ip, receiver.listeningPort).use { socket ->
                socket.soTimeout = 5000
                assertEquals(-1, socket.getInputStream().read())
            }
            assertEquals(listOf("authorize:FCast"), playback.events.toList())
            assertTrue(playback.requests.isEmpty())
        }
    }

    @Test fun fcastKeepsTheActiveClientWhenAnotherClientConnects() {
        val playback = Playback()
        FCastReceiver(context, playback, "Buzin integration", {}, requestedPort = 0).use { receiver ->
            receiver.start()
            Socket(ip, receiver.listeningPort).use { active ->
                active.soTimeout = 5000
                frame(active, 11)
                Socket(ip, receiver.listeningPort).use { second ->
                    second.soTimeout = 5000
                    assertEquals(-1, second.getInputStream().read())
                }
                FCastFrame(12).writeTo(active.getOutputStream())
                frame(active, 13)
            }
            assertEquals(listOf("authorize:FCast"), playback.events.toList())
        }
    }

    @Test fun fcastReportsAnOccupiedPortAndRemainsClosedAfterStartupFails() {
        val diagnostics = CopyOnWriteArrayList<String>()
        val blocker = ServerSocket(0, 1, InetAddress.getByName(ip))
        FCastReceiver(context, Playback(), "Buzin integration", { diagnostics += it }, blocker.localPort).use { receiver ->
            blocker.use { receiver.start() }
            assertEquals(0, receiver.listeningPort)
            assertTrue(diagnostics.single().startsWith("FCast start failed:"))
            receiver.start()
            assertEquals(0, receiver.listeningPort)
            assertEquals(1, diagnostics.size)
        }
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

    @Test fun fcastRejectsInvalidLoadFieldsAndAcceptsExplicitNullOptions() = withFcast { playback, socket ->
        val url = "https://example.test/audio.mp3"
        val mime = "audio/mpeg"
        fun request(field: String, value: Any) = JSONObject().put("url", url).put("container", mime).put(field, value)

        val invalidLoads = listOf(
            request("content", "inline content") to "Inline manifests are not supported; send an HTTP(S) URL",
            request("url", "https://example.test/" + "a".repeat(8192)) to "Expected HTTP(S) media URL",
            request("container", "audio/" + "a".repeat(120)) to "Unsupported media type",
            request("time", -1) to "Invalid time",
            request("headers", JSONObject().apply { repeat(25) { put("X-Field-$it", "value") } }) to null,
            request("headers", JSONObject().put("Invalid key", "value")) to null,
            request("headers", JSONObject().put("X-Long", "a".repeat(4097))) to null,
            request("headers", JSONObject().put("X-Control", "value\ncontinued")) to null,
        )
        val output = socket.getOutputStream()
        for ((request, message) in invalidLoads) {
            FCastFrame(1, request.toString()).writeTo(output)
            val error = JSONObject(frame(socket, 9).body).getString("message")
            if (message != null) assertEquals(message, error) else assertTrue(error.isNotBlank())
            assertTrue(playback.requests.isEmpty())
        }

        val valid = request("content", JSONObject.NULL).put("headers", JSONObject.NULL)
            .put("time", JSONObject.NULL).put("speed", JSONObject.NULL)
        FCastFrame(1, valid.toString()).writeTo(output)
        eventually { playback.events.contains("speed:1.0") }
        val loaded = playback.requests.single()
        assertEquals(url, loaded.location)
        assertEquals(mime, loaded.mime)
        assertEquals(0f, loaded.time)
        assertTrue(loaded.headers.isEmpty())
        assertEquals(Protocol.FCAST, playback.mediaOwner)
        assertTrue(playback.mediaActive)
    }

    @Test fun fcastCannotTakeOverActiveMirroring() = withFcast { playback, socket ->
        playback.activeConnections = 1
        FCastFrame(1, """{"url":"http://example.test/a","container":"video/mp4"}""").writeTo(socket.getOutputStream())
        assertTrue(frame(socket, 9).body.contains("Another screen")); assertTrue(playback.requests.isEmpty())
    }
    @Test fun fcastDefersPlaybackUntilRelayReceptionEnds() = withFcast { playback, socket ->
        val output = socket.getOutputStream()
        val load = FCastFrame(1, JSONObject()
            .put("url", "https://example.test/live.m3u8")
            .put("container", "application/vnd.apple.mpegurl")
            .toString())
        playback.relayReceiving = true
        load.writeTo(output)
        assertTrue(JSONObject(frame(socket, 9).body).getString("message").contains("Another screen"))
        assertTrue(playback.requests.isEmpty())
        assertNull(playback.mediaOwner)

        playback.relayReceiving = false
        load.writeTo(output)
        eventually { playback.events.contains("speed:1.0") }
        val request = playback.requests.single()
        assertEquals("application/vnd.apple.mpegurl", request.mime)
        assertEquals(0f, request.time, 0f)
        assertTrue(request.headers.isEmpty())
        assertEquals(Protocol.FCAST, playback.mediaOwner)
    }

    @Test fun fcastReportsPlaybackFailureAndKeepsTheClientConnected() = withFcast { playback, socket ->
        val output = socket.getOutputStream()
        playback.failLoad = true
        FCastFrame(1, JSONObject()
            .put("url", "https://example.test/movie.mp4")
            .put("container", "video/mp4")
            .toString()).writeTo(output)
        assertEquals("Fixture player refused the request", JSONObject(frame(socket, 9).body).getString("message"))
        assertTrue(playback.requests.isEmpty())
        assertNull(playback.mediaOwner)
        assertFalse(playback.mediaActive)

        FCastFrame(12).writeTo(output)
        frame(socket, 13)
    }

    @Test fun fcastKeepsCurrentPlaybackDuringTheLoadCooldownAndAcceptsTheRetry() = withFcast { playback, socket ->
        val output = socket.getOutputStream()
        val dash = FCastFrame(1, JSONObject()
            .put("url", "https://example.test/live.mpd")
            .put("container", "application/dash+xml")
            .toString())
        val hls = FCastFrame(1, JSONObject()
            .put("url", "https://example.test/replacement.m3u8")
            .put("container", "application/x-mpegURL")
            .toString())
        val mainWaiting = CountDownLatch(1)
        val releaseMain = CountDownLatch(1)
        val holdingMain = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post {
            holdingMain.set(true)
            mainWaiting.countDown()
            try {
                releaseMain.await(5, TimeUnit.SECONDS)
            } finally {
                holdingMain.set(false)
            }
        }
        try {
            assertTrue(mainWaiting.await(5, TimeUnit.SECONDS))
            dash.writeTo(output)
            hls.writeTo(output)
            FCastFrame(12).writeTo(output)
            // The socket worker posts both loads before it answers this ping.
            // The main thread can then process them together.
            frame(socket, 13)
            assertTrue("The load commands must be queued before main is released", holdingMain.get())
        } finally {
            releaseMain.countDown()
        }
        assertEquals("Wait before starting another item", JSONObject(frame(socket, 9).body).getString("message"))
        assertEquals("application/dash+xml", playback.requests.single().mime)
        assertTrue(playback.mediaActive)

        SystemClock.sleep(550)
        hls.writeTo(output)
        eventually { playback.requests.size == 2 }
        assertEquals("application/x-mpegURL", playback.requests.last().mime)
        assertEquals(Protocol.FCAST, playback.mediaOwner)
        assertTrue(playback.mediaActive)
    }

    @Test fun fcastRejectsControlsForAnotherProtocol() = withFcast { playback, socket ->
        playback.mediaOwner = Protocol.DLNA; playback.mediaActive = true
        FCastFrame(4).writeTo(socket.getOutputStream()); frame(socket, 9)
        assertFalse(playback.events.contains("stop:FCAST"))
    }

    @Test fun fcastClosingTheReceiverDropsAnAlreadyQueuedLoad() {
        val playback = Playback()
        FCastReceiver(context, playback, "Buzin close test", {}, requestedPort = 0).use { receiver ->
            receiver.start()
            Socket(ip, receiver.listeningPort).use { socket ->
                socket.soTimeout = 4000
                frame(socket, 11)
                whileMainIsGated {
                    val output = socket.getOutputStream()
                    FCastFrame(1, """{"url":"https://example.test/movie.mp4","container":"video/mp4"}""").writeTo(output)
                    FCastFrame(12).writeTo(output)
                    frame(socket, 13)
                    receiver.close()
                }
                assertTrue(playback.requests.isEmpty())
                assertNull(playback.mediaOwner)
            }
        }
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

    @Test fun dlnaControlFallsBackWhenItsPreferredPortIsOccupied() {
        val player = object : PlayerPort {
            override fun prepareAndPlay(uri: String) = Unit
            override fun play() = Unit
            override fun pause() = Unit
            override fun seekTo(ms: Long) = Unit
            override fun stop() = Unit
            override fun currentPositionMs() = 0L
            override fun currentDurationMs() = 0L
        }
        val events = SubscriptionRegistry()
        ServerSocket(0).use { occupied ->
            val server = ControlServer.startWithFallback(
                preferredPort = occupied.localPort,
                descriptionProvider = {
                    RendererDescription(
                        friendlyName = "Buzin fallback fixture",
                        uuid = "uuid:bdcd8700-e92c-42c2-a41e-2998b6c2112d",
                        manufacturer = "Buzin Solutions",
                        modelName = "BuzinCast",
                        baseUrl = "http://127.0.0.1",
                    )
                },
                router = SoapRouter(CastSession(player)),
                events = events,
                eventBody = { "" },
                serverToken = "BuzinCast-Test",
            )
            try {
                assertTrue(server.boundPort > 0)
                assertNotEquals(occupied.localPort, server.boundPort)
                val response = Socket("127.0.0.1", server.boundPort).use { client ->
                    client.soTimeout = 5_000
                    client.getOutputStream().write(
                        "GET /desc.xml HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray()
                    )
                    client.getInputStream().bufferedReader().readText()
                }
                assertTrue(response, response.startsWith("HTTP/1.1 200"))
                assertTrue(response, response.contains("<friendlyName>Buzin fallback fixture</friendlyName>"))
            } finally {
                server.stop()
                events.shutdown()
            }
        }
    }

    @Test fun dlnaHttpDescriptionAndScpdsUseRealListener() = withDlna { _, control ->
        val description = control.request("GET", "/desc.xml")
        assertTrue(description.startsWith("HTTP/1.1 200")); assertTrue(description.contains("Buzin integration"))
        val root = control.request("GET", "/?client=integration")
        assertTrue(root, root.startsWith("HTTP/1.1 200"))
        assertEquals(description.substringAfter("\r\n\r\n"), root.substringAfter("\r\n\r\n"))
        for (path in listOf("/scpd/avt.xml", "/scpd/rcs.xml", "/scpd/cmn.xml")) assertTrue(control.request("GET", path).startsWith("HTTP/1.1 200"))
        assertTrue(control.request("GET", "/missing").startsWith("HTTP/1.1 404"))
        assertTrue(control.request("HEAD", "/desc.xml").substringAfter("\r\n\r\n").isEmpty())
        val unsupported = control.request("OPTIONS", "/ctl/avt")
        assertTrue(unsupported, unsupported.startsWith("HTTP/1.1 405"))
        val invalid = control.request("POST", "/ctl/avt", "SOAPAction: urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\r\n", "<broken>")
        assertTrue(invalid, invalid.startsWith("HTTP/1.1 400"))
    }

    @Test fun dlnaRoutesBodyOnlySoapAndRejectsControlMethodsOnDescriptionUrls() = withDlna { playback, control ->
        val service = "urn:schemas-upnp-org:service:AVTransport:1"
        val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">""" +
            """<s:Body><u:GetTransportInfo xmlns:u="$service"><InstanceID>0</InstanceID>""" +
            """</u:GetTransportInfo></s:Body></s:Envelope>"""
        val accepted = control.request("POST", "/ctl/avt", body = body)
        assertTrue(accepted, accepted.startsWith("HTTP/1.1 200") && "GetTransportInfoResponse" in accepted)

        val mismatched = control.request("POST", "/ctl/avt", "SOAPAction: $service#Stop\r\n", body)
        assertTrue(mismatched, mismatched.startsWith("HTTP/1.1 400") && "<errorCode>402</errorCode>" in mismatched)
        for (method in listOf("POST", "SUBSCRIBE", "UNSUBSCRIBE")) {
            val response = control.request(method, "/desc.xml", body = if (method == "POST") body else "")
            assertTrue(response, response.startsWith("HTTP/1.1 405"))
        }
        assertTrue(playback.requests.isEmpty())
        assertFalse("A rejected request stopped playback", "stop:DLNA" in playback.events)
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
                    val renewalHeaders = "SID: $sid\r\nTIMEOUT: Second-120\r\n"
                    val renewed = control.request("POST", "/evt/rcs", renewalHeaders)
                    assertTrue(renewed, renewed.startsWith("HTTP/1.1 200"))
                    assertTrue(renewed, renewed.lineSequence().any { it.equals("SID: $sid", ignoreCase = true) })
                    assertTrue(renewed, renewed.lineSequence().any { it.equals("TIMEOUT: Second-120", ignoreCase = true) })

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
                    val expired = control.request("SUBSCRIBE", "/evt/rcs", renewalHeaders)
                    assertTrue(expired, expired.startsWith("HTTP/1.1 412"))
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
        assertTrue("The action did not complete while the main looper was gated", resumedInTime.get())
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

    private fun receiveDlnaEvent(
        server: ServerSocket, path: String, sid: String, seq: Int, status: String? = "200 OK",
        resetConnection: Boolean = false,
        beforeReply: () -> Unit = {},
    ): String =
        server.accept().use { socket ->
            socket.soTimeout = 5000
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            assertEquals("NOTIFY $path HTTP/1.1", input.readLine())
            val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
            assertTrue("SID: $sid" in headers)
            assertTrue("SEQ: $seq" in headers)
            val size = headers.first { it.startsWith("CONTENT-LENGTH:") }.substringAfter(':').trim().toInt()
            val body = buildString { repeat(size) { append(input.read().also { assertTrue(it >= 0) }.toChar()) } }
            beforeReply()
            if (resetConnection) {
                socket.setSoLinger(true, 0)
            } else if (status != null) {
                socket.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Length: 0\r\n\r\n").toByteArray())
            }
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
                val response = eventRequest("SUBSCRIBE",
                    "CALLBACK: <$base/reset> <$base/eof> <$base/fail> <$base/notify?source=test>",
                    "NT: upnp:event", "TIMEOUT: SECOND-120")
                assertTrue(response, response.startsWith("HTTP/1.1 200"))
                assertTrue(response, response.contains("TIMEOUT: Second-120", ignoreCase = true))
                val sid = response.lineSequence().first { it.startsWith("SID:", ignoreCase = true) }.substringAfter(':').trim()

                assertTrue("LastChange" in receiveDlnaEvent(callback, "/reset", sid, 0, resetConnection = true))
                assertTrue("LastChange" in receiveDlnaEvent(callback, "/eof", sid, 0, status = null))
                assertTrue("LastChange" in receiveDlnaEvent(callback, "/fail", sid, 0, "500 Diagnostic 200"))
                assertTrue("LastChange" in receiveDlnaEvent(callback, "/notify?source=test", sid, 0))
                val renewed = eventRequest("SUBSCRIBE", "SID: $sid", "TIMEOUT: Second-10")
                assertTrue(renewed.startsWith("HTTP/1.1 200"))
                assertTrue(renewed.contains("TIMEOUT: Second-60", ignoreCase = true))
                val legacyRenewal = eventRequest("POST", "SID: $sid", "TIMEOUT: Second-120")
                assertTrue(legacyRenewal, legacyRenewal.startsWith("HTTP/1.1 200"))
                assertTrue(legacyRenewal, legacyRenewal.contains("SID: $sid", ignoreCase = true))
                assertTrue(legacyRenewal, legacyRenewal.contains("TIMEOUT: Second-120", ignoreCase = true))
                val timeoutCases = listOf(
                    null to 1_800,
                    "Minutes-5" to 1_800,
                    "Second-Infinite" to 1_800,
                    "Second-2147483648" to 1_800,
                    "Second-86400" to 86_400,
                    "Second-86401" to 86_400,
                )
                for ((header, expected) in timeoutCases) {
                    val headers = listOfNotNull("SID: $sid", header?.let { "TIMEOUT: $it" })
                    val renewal = eventRequest("SUBSCRIBE", *headers.toTypedArray())
                    assertTrue("Timeout $header: $renewal", renewal.startsWith("HTTP/1.1 200"))
                    assertTrue("Timeout $header: $renewal", renewal.contains("TIMEOUT: Second-$expected", ignoreCase = true))
                }
                assertTrue(eventRequest("UNSUBSCRIBE", "SID: $sid").startsWith("HTTP/1.1 200"))
                assertTrue(eventRequest("SUBSCRIBE", "SID: $sid").startsWith("HTTP/1.1 412"))
                assertTrue(eventRequest("UNSUBSCRIBE", "SID: $sid").startsWith("HTTP/1.1 412"))
            }
        }
    }

    @Test fun dlnaCallbackSuccessResetsFailuresBeforeTheSubscriberIsRemoved() {
        val registry = SubscriptionRegistry()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { callback ->
                callback.soTimeout = 5000
                val sid = registry.subscribe("avt", listOf("http://127.0.0.1:${callback.localPort}"), 120)
                fun deliver(index: Int, sequence: Int, status: String) {
                    val body = "<event>$index</event>"
                    registry.publish("avt", body)
                    assertEquals(body, receiveDlnaEvent(callback, "/", sid, sequence, status))
                }

                val failed = "503 Service Unavailable"
                deliver(1, 0, failed)
                deliver(2, 0, failed)
                deliver(3, 0, "200 OK")

                deliver(4, 1, failed)
                deliver(5, 1, failed)
                assertTrue("A successful delivery should reset the consecutive failures", registry.renew(sid, 120))
                deliver(6, 1, failed)
                eventually { !registry.renew(sid, 120) }
            }
        } finally {
            registry.shutdown()
        }
    }

    @Test fun dlnaSkipsExpiredAndRemovedSubscriptions() {
        val registry = SubscriptionRegistry()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { callback ->
                callback.soTimeout = 5000
                val base = "http://127.0.0.1:${callback.localPort}"
                val active = registry.subscribe("avt", listOf("$base/active"), 120)
                val removed = registry.subscribe("avt", listOf("$base/removed"), 120)
                val expired = registry.subscribe("avt", listOf("$base/expired"), 0)
                assertFalse("An expired subscription cannot be renewed", registry.renew(expired, 120))

                registry.publishInitial(active, "<event>first</event>")
                val first = receiveDlnaEvent(callback, "/active", active, 0) {
                    // The worker queues both callbacks while it waits for this response.
                    registry.publishInitial(removed, "<event>discard</event>")
                    assertTrue(registry.unsubscribe(removed))
                    registry.publishInitial(active, "<event>next</event>")
                }
                assertEquals("<event>first</event>", first)
                assertEquals("<event>next</event>", receiveDlnaEvent(callback, "/active", active, 1))
            }
        } finally {
            registry.shutdown()
        }
    }

    @Test fun dlnaIgnoresNotificationsRacingRegistryShutdown() {
        val registry = SubscriptionRegistry()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { callback ->
                callback.soTimeout = 250
                val url = listOf("http://127.0.0.1:${callback.localPort}/notify")
                val existing = registry.subscribe("avt", url, 120)

                registry.shutdown()
                assertFalse(registry.renew(existing, 120))
                registry.publishInitial(existing, "<event>removed</event>")

                val late = registry.subscribe("avt", url, 120)
                registry.publishInitial(late, "<event>initial</event>")
                registry.publish("rcs", "<event>unsubscribed-service</event>")
                registry.publish("avt", "<event>update</event>")
                assertThrows(SocketTimeoutException::class.java) { callback.accept().close() }
            }
        } finally {
            registry.shutdown()
        }
    }

    private fun withCastV2(test: (Playback, SSLSocket) -> Unit) {
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
                socket.soTimeout = 5000
                socket.startHandshake()
                test(playback, socket)
            }
        }
    }

    private fun castExchange(socket: SSLSocket, namespace: String, body: String, destination: String = "receiver-0"): JSONObject {
        val wireNamespace = "urn:x-cast:com.google.cast.$namespace"
        val request = JSONObject(body).optInt("requestId", -1)
        CastEnvelope("sender-test", destination, wireNamespace, body).writeTo(socket.outputStream)
        repeat(8) {
            val frame = requireNotNull(CastEnvelope.readFrom(socket.inputStream))
            val reply = JSONObject(requireNotNull(frame.text))
            if (frame.namespace == wireNamespace && reply.optInt("requestId", -1) == request) return reply
        }
        error("Cast V2 reply not received for $namespace request $request")
    }

    @Test fun castV2TlsHeartbeatStatusAndUnsupportedApp() = withCastV2 { playback, socket ->
        assertEquals("PONG", castExchange(socket, "tp.heartbeat", """{"type":"PING"}""").getString("type"))
        assertEquals("RECEIVER_STATUS", castExchange(socket, "receiver", """{"type":"GET_STATUS","requestId":1}""").getString("type"))
        val unsupported = castExchange(socket, "receiver", """{"type":"LAUNCH","requestId":2,"appId":"UNKNOWN"}""")
        assertEquals("LAUNCH_ERROR", unsupported.getString("type"))
        assertEquals("NOT_FOUND", unsupported.getString("reason"))
        assertTrue(playback.requests.isEmpty())
    }

    @Test fun castV2ReportsPlaybackWhileIdleAndKeepsTheSessionAfterAnInvalidCommand() = withCastV2 { playback, socket ->
        val launch = castExchange(socket, "receiver", """{"type":"LAUNCH","requestId":1,"appId":"CC1AD845"}""")
        val transport = launch.getJSONObject("status").getJSONArray("applications").getJSONObject(0).getString("transportId")
        val url = "https://example.test/clip.mp4"
        playback.playbackInfo = PlaybackInfo(positionMs = 4200, durationMs = 12000, speed = 0.75f, playing = true)
        val load = castExchange(socket, "media",
            """{"type":"LOAD","requestId":2,"media":{"contentId":"$url","contentType":"video/mp4"}}""", transport)
        assertEquals(url, playback.requests.single().location)
        assertEquals(Protocol.CAST_V2, playback.mediaOwner)
        val session = load.getJSONArray("status").getJSONObject(0).getInt("mediaSessionId")

        val unsolicited = requireNotNull(CastEnvelope.readFrom(socket.inputStream))
        assertEquals(transport, unsolicited.source)
        assertEquals("sender-test", unsolicited.destination)
        val update = JSONObject(requireNotNull(unsolicited.text))
        assertEquals("MEDIA_STATUS", update.getString("type"))
        assertFalse(update.has("requestId"))
        val item = update.getJSONArray("status").getJSONObject(0)
        assertEquals(session, item.getInt("mediaSessionId"))
        assertEquals("PLAYING", item.getString("playerState"))
        assertEquals(4.2, item.getDouble("currentTime"), 0.001)
        assertEquals(0.75, item.getDouble("playbackRate"), 0.001)

        val invalid = castExchange(socket, "media", """{"type":"SEEK","requestId":3,"mediaSessionId":$session,"currentTime":-1}""", transport)
        assertEquals("INVALID_REQUEST", invalid.getString("type"))
        assertTrue(playback.events.none { it.startsWith("seek:") })
        val current = castExchange(socket, "media", """{"type":"GET_STATUS","requestId":4}""", transport)
        assertEquals(session, current.getJSONArray("status").getJSONObject(0).getInt("mediaSessionId"))
        assertTrue(playback.mediaActive)
    }
}
