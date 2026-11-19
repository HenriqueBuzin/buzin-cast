package io.github.jqssun.airplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.buzincast.core.*
import io.buzincast.media.CastEnvelope
import io.github.jqssun.airplay.discovery.CastV2Receiver
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException

/** Command-boundary contracts: no listener, discovery, external media or TLS identity. */
@RunWith(AndroidJUnit4::class)
class CastCommandContractTest {
    private class Playback : ReceiverPlaybackPort {
        override var activeConnections = 0
        override var relayReceiving = false
        override var mediaOwner: Protocol? = null
        override var mediaActive = false
        override var playbackInfo = PlaybackInfo()
        val requests = mutableListOf<MediaRequest>()
        val events = mutableListOf<String>()
        override fun authorize(protocol: String, peer: String) = true
        override fun load(request: MediaRequest) {
            requests += request; mediaOwner = request.protocol; mediaActive = true
        }
        override fun stop(protocol: Protocol) { events += "stop:$protocol"; mediaActive = false }
        override fun playing(protocol: Protocol, playing: Boolean) { events += "playing:$playing" }
        override fun seek(protocol: Protocol, seconds: Float) { events += "seek:$seconds" }
        override fun gain(protocol: Protocol, gain: Float) { events += "gain:$gain" }
        override fun speed(protocol: Protocol, speed: Float) { error("Unexpected speed") }
        override fun photo(location: String) { error("Unexpected photo") }
    }

    private class Fixture : AutoCloseable {
        val playback = Playback()
        val receiver = CastV2Receiver(InstrumentationRegistry.getInstrumentation().targetContext,
            playback, "Command contract") { error("Unexpected listener log: $it") }
        var transport = ""
        var session = ""
        private val method = CastV2Receiver::class.java.getDeclaredMethod("command",
            CastEnvelope::class.java, JSONObject::class.java).apply { isAccessible = true }
        fun command(namespace: String, body: JSONObject, destination: String = "receiver-0"): List<CastEnvelope> {
            var result: List<CastEnvelope>? = null
            var failure: Throwable? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                try {
                    @Suppress("UNCHECKED_CAST")
                    result = method.invoke(receiver, CastEnvelope("sender-7", destination, namespace, body.toString()), body) as List<CastEnvelope>
                } catch (e: InvocationTargetException) { failure = e.targetException }
            }
            failure?.let { throw it }
            return requireNotNull(result)
        }
        fun receiver(body: JSONObject) = JSONObject(requireNotNull(command(RECEIVER, body).single().text))
        fun launch() {
            val app = receiver(body("LAUNCH").put("appId", APP)).getJSONObject("status")
                .getJSONArray("applications").getJSONObject(0)
            transport = app.getString("transportId"); session = app.getString("sessionId")
        }
        fun media(body: JSONObject) = JSONObject(requireNotNull(command(MEDIA, body, transport).single().text))
        fun load(mime: String = "video/mp4") = body("LOAD").put("media", JSONObject()
            .put("contentId", "https://example.invalid/movie").put("contentType", mime))
        override fun close() { receiver.close() }
    }

    @Test fun receiverLaunchAvailabilityAndConnectionDestinations() = Fixture().use { f ->
        val empty = f.receiver(body("GET_STATUS"))
        assertFalse(empty.has("requestId"))
        assertEquals(0, empty.getJSONObject("status").getJSONArray("applications").length())
        val apps = JSONArray().put(APP).put("unknown")
        repeat(30) { apps.put("app-$it") }
        val availability = f.receiver(body("GET_APP_AVAILABILITY").put("appId", apps).put("requestId", 0))
        assertEquals(0, availability.getInt("requestId"))
        assertEquals("APP_AVAILABLE", availability.getJSONObject("availability").getString(APP))
        assertEquals("APP_UNAVAILABLE", availability.getJSONObject("availability").getString("unknown"))
        rejects { f.receiver(body("GET_APP_AVAILABILITY").put("appId", apps.put("overflow"))) }
        assertEquals("NOT_FOUND", f.receiver(body("LAUNCH").put("appId", "unknown")).getString("reason"))
        assertTrue(f.command(CONNECTION, body("CONNECT")).isEmpty())
        rejects { f.command(CONNECTION, body("CONNECT"), "missing") }
        rejects { f.command(CONNECTION, body("OTHER")) }
        rejects { f.receiver(body("GET_STATUS").put("requestId", -1)) }
        rejects { f.command(RECEIVER, body("GET_STATUS"), "wrong") }
        rejects { f.receiver(body("OTHER")) }
        f.launch(); val original = f.session
        assertTrue(original.isNotBlank()); assertEquals("buzin-$original", f.transport)
        f.launch(); assertEquals(original, f.session)
        assertTrue(f.command(CONNECTION, body("CLOSE"), f.transport).isEmpty())
        rejects { f.receiver(body("STOP").put("sessionId", "wrong")) }
        assertEquals(0, f.receiver(body("STOP").put("sessionId", original))
            .getJSONObject("status").getJSONArray("applications").length())
        assertTrue(f.playback.events.isEmpty())
        rejects { f.media(body("GET_STATUS")) }
    }

    @Test fun cachedVolumeIsAtomicAndAppliedWhenMediaLoads() = Fixture().use { f ->
        f.receiver(body("SET_VOLUME").put("volume", JSONObject().put("level", 0.25).put("muted", true)))
        assertTrue(f.playback.events.isEmpty())
        for (invalid in listOf(-0.01, 1.01, "NaN", "Infinity")) {
            rejects { f.receiver(body("SET_VOLUME").put("volume", JSONObject().put("level", invalid))) }
        }
        rejects { f.receiver(body("SET_VOLUME").put("volume", JSONObject().put("level", 0.75).put("muted", "invalid"))) }
        val volume = f.receiver(body("GET_STATUS")).getJSONObject("status").getJSONObject("volume")
        assertEquals(0.25, volume.getDouble("level"), 0.0); assertTrue(volume.getBoolean("muted"))
        f.launch(); f.media(f.load())
        assertEquals(listOf("gain:0.0"), f.playback.events)
        f.receiver(body("SET_VOLUME").put("volume", JSONObject().put("muted", false)))
        assertEquals("gain:0.25", f.playback.events.last())
        f.media(body("SET_VOLUME").put("mediaSessionId", 1).put("volume", JSONObject().put("level", 1)))
        assertEquals("gain:1.0", f.playback.events.last())
        f.receiver(body("SET_VOLUME").put("volume", JSONObject().put("level", 0)))
        assertEquals("gain:0.0", f.playback.events.last())
    }

    @Test fun supportedLoadTypesAndContentUrlPrecedence() {
        for (mime in listOf("video/mp4", "audio/aac", "application/dash+xml", "application/vnd.apple.mpegurl", "application/x-mpegURL")) {
            Fixture().use { f ->
                f.launch()
                val load = f.load(mime).put("currentTime", 31536000).put("autoplay", false)
                load.getJSONObject("media").put("contentUrl", "http://example.invalid/chosen").put("streamType", "LIVE")
                val status = f.media(load).getJSONArray("status").getJSONObject(0)
                assertEquals(MediaRequest("http://example.invalid/chosen", Protocol.CAST_V2, 31536000f, mime), f.playback.requests.single())
                assertEquals(listOf("gain:1.0", "playing:false"), f.playback.events)
                assertEquals("LIVE", status.getJSONObject("media").getString("streamType"))
                assertEquals("sender-7", f.command(MEDIA, body("GET_STATUS"), f.transport).single().destination)
            }
        }
    }

    @Test fun invalidLoadsAndBusyReceiversHaveNoPlaybackSideEffects() = Fixture().use { f ->
        f.launch()
        val invalid = listOf("file:///movie", "https:///movie", "https://user@example.invalid/movie", "ftp://example.invalid/movie", "https://example.invalid/" + "x".repeat(8192))
        for (url in invalid) {
            val load = f.load(); load.getJSONObject("media").put("contentId", url)
            rejects { f.media(load) }
        }
        for (mime in listOf("text/plain", "video/" + "x".repeat(120))) rejects { f.media(f.load(mime)) }
        for (key in listOf("drm", "contentProtection")) {
            val load = f.load(); load.getJSONObject("media").put(key, JSONObject())
            rejects { f.media(load) }
        }
        for (time in listOf(-0.1, 31536001, "NaN", "Infinity")) rejects { f.media(f.load().put("currentTime", time)) }
        f.playback.activeConnections = 1; rejects { f.media(f.load()) }
        f.playback.activeConnections = 0; f.playback.relayReceiving = true; rejects { f.media(f.load()) }
        assertEquals(0, f.media(body("GET_STATUS")).getJSONArray("status").length())
        assertTrue(f.playback.requests.isEmpty()); assertTrue(f.playback.events.isEmpty())
        f.playback.relayReceiving = false
        val load = f.load(); load.getJSONObject("media").put("contentUrl", "")
        f.media(load)
        assertEquals("https://example.invalid/movie", f.playback.requests.single().location)
        rejects { f.media(f.load()) } // Real elapsed-time rate limit, without altering its clock.
        assertEquals(1, f.playback.requests.size)
    }

    @Test fun sessionControlsStatusAndTerminalIdle() = Fixture().use { f ->
        f.launch(); f.media(f.load())
        fun control(type: String) = body(type).put("mediaSessionId", 1)
        rejects { f.media(body("PLAY").put("mediaSessionId", 2)) }
        rejects { f.media(control("OTHER")) }
        f.media(control("PLAY")); f.media(control("PAUSE"))
        for ((state, event) in listOf("PLAYBACK_START" to "playing:true", "PLAYBACK_PAUSE" to "playing:false")) {
            f.media(control("SEEK").put("currentTime", 12.5).put("resumeState", state))
            assertEquals(listOf("seek:12.5", event), f.playback.events.takeLast(2))
        }
        f.media(control("SEEK").put("currentTime", 0))
        assertEquals("seek:0.0", f.playback.events.last())
        val before = f.playback.events.toList()
        rejects { f.media(control("SEEK").put("currentTime", -1)) }
        assertEquals(before, f.playback.events)
        for ((playing, buffering, expected) in listOf(Triple(true, false, "PLAYING"), Triple(false, false, "PAUSED"), Triple(true, true, "BUFFERING"))) {
            f.playback.playbackInfo = PlaybackInfo(1250, -1, playing, 1.5f, buffering = buffering)
            val status = f.media(body("GET_STATUS")).getJSONArray("status").getJSONObject(0)
            assertEquals(expected, status.getString("playerState"))
            assertEquals(1.25, status.getDouble("currentTime"), 0.0)
            assertEquals(1.5, status.getDouble("playbackRate"), 0.0)
            assertEquals(0.0, status.getJSONObject("media").getDouble("duration"), 0.0)
        }
        f.media(control("STOP"))
        assertEquals("stop:CAST_V2", f.playback.events.last())
        val idle = f.media(body("GET_STATUS")).getJSONArray("status").getJSONObject(0)
        assertEquals("IDLE", idle.getString("playerState")); assertEquals(0, idle.getInt("supportedMediaCommands"))
        rejects { f.media(control("PLAY")) }
    }

    @Test fun sessionIdWrapAndReceiverStopPreserveOwnership() = Fixture().use { f ->
        f.launch()
        CastV2Receiver::class.java.getDeclaredField("mediaId").apply { isAccessible = true; setInt(f.receiver, Int.MAX_VALUE) }
        assertEquals(1, f.media(f.load()).getJSONArray("status").getJSONObject(0).getInt("mediaSessionId"))
        f.receiver(body("STOP"))
        assertEquals("stop:CAST_V2", f.playback.events.last())
        f.launch(); f.playback.mediaOwner = null; f.playback.mediaActive = true
        val before = f.playback.events.toList()
        rejects { f.media(body("PLAY").put("mediaSessionId", 1)) }
        f.receiver(body("STOP")); assertEquals(before, f.playback.events)
    }

    companion object {
        private const val APP = "CC1AD845"
        private const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val MEDIA = "urn:x-cast:com.google.cast.media"
        private const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private fun body(type: String) = JSONObject().put("type", type)
        private fun rejects(action: () -> Unit) {
            try { action() } catch (_: Exception) { return }
            fail("Command should be rejected")
        }
    }
}
