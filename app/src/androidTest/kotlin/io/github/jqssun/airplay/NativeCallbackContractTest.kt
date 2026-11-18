package io.github.jqssun.airplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/** Real JNI table and callback methods, without starting a RAOP listener/player. */
@RunWith(AndroidJUnit4::class)
class NativeCallbackContractTest {
    private data class Pcm(val bytes: ByteArray, val pts: Long, val rate: Int, val channels: Int)
    private class Sink : RaopCallbackHandler {
        val events = CopyOnWriteArrayList<String>()
        val videos = CopyOnWriteArrayList<Pair<ByteArray, Boolean>>()
        val pcm = CopyOnWriteArrayList<Pcm>()
        val metadata = CopyOnWriteArrayList<ByteArray>()
        val artwork = CopyOnWriteArrayList<ByteArray>()
        fun onConnectionRequest(peer: String): Boolean {
            events += "admit:$peer"
            if (peer == "throw") throw IllegalStateException("Intentional callback rejection")
            return peer == "allow"
        }
        override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
            assertEquals(1234567L, ntpTimeNs); videos += data.copyOf() to isH265
        }
        override fun onAudioPcm(data: ByteArray, ntpTimeNs: Long, sampleRate: Int, channels: Int) {
            pcm += Pcm(data.copyOf(), ntpTimeNs, sampleRate, channels)
            if (ntpTimeNs == 44L) throw IllegalStateException("Intentional PCM callback failure")
        }
        override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) { events += "format:$ct:$spf:$usingScreen" }
        override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) { events += "size:$srcW:$srcH:$w:$h" }
        override fun onClientVolume(db: Float) { events += "volume:$db" }
        override fun onClientVolumeQuery() = -6.25f
        override fun onAudioTeardown() { events += "teardown" }
        override fun onConnectionInit() { events += "init" }
        override fun onConnectionDestroy() { events += "destroy" }
        override fun onConnectionReset(reason: Int) {
            events += "reset:$reason"
            if (reason == -1) throw IllegalStateException("Intentional reset callback failure")
        }
        override fun onDisplayPin(pin: String) { events += "pin:$pin" }
        override fun onMetadata(data: ByteArray) { metadata += data.copyOf() }
        override fun onCoverArt(data: ByteArray) { artwork += data.copyOf() }
        override fun onProgress(start: Long, curr: Long, end: Long) { events += "progress:$start:$curr:$end" }
        override fun onDacpId(dacpId: String, activeRemote: String) { events += "dacp:$dacpId:$activeRemote" }
        override fun onMirrorRunning(running: Boolean) { events += "mirror:$running" }
        override fun onVideoPlay(location: String, startPositionSeconds: Float) { events += "play:$location:$startPositionSeconds" }
        override fun onVideoScrub(positionSeconds: Float) { events += "scrub:$positionSeconds" }
        override fun onVideoRate(rate: Float) { events += "rate:$rate" }
        override fun onVideoStop() { events += "stop" }
        override fun onVideoSessionPoll() { events += "poll" }
    }

    @Test fun payloadsPlaybackStatePairingAndNativeThreadDelivery() {
        assumeTrue("Requires coverage JNI fixture", runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        System.loadLibrary("airplay_native")
        val sink = Sink()
        try {
            assertEquals("callback contracts passed", DebugCoverage.nativeCallbackContracts(sink))
            assertEquals(2, sink.videos.size)
            for ((bytes, _) in sink.videos) assertArrayEquals(byteArrayOf(0, 0, 1, 7), bytes)
            assertEquals(listOf(false, true), sink.videos.map { it.second })
            assertArrayEquals(byteArrayOf(0, 0, 1, 7), sink.metadata.single())
            assertArrayEquals(byteArrayOf(0, 0, 1, 7), sink.artwork.single())
            assertEquals(4, sink.pcm.size)
            sink.pcm.forEach {
                val samples = ByteBuffer.wrap(it.bytes).order(ByteOrder.nativeOrder()).asShortBuffer()
                val actual = ShortArray(samples.remaining()); samples.get(actual)
                assertArrayEquals(shortArrayOf(1, -2, 32767, -32768), actual)
            }
            assertEquals(listOf(42L, 43L, 44L, 45L), sink.pcm.map { it.pts })
            assertEquals(listOf(44100, 48000, 22050, 16000), sink.pcm.map { it.rate })
            assertEquals(listOf(2, 1, 2, 1), sink.pcm.map { it.channels })
            assertEquals(listOf("admit:allow", "admit:deny", "admit:throw", "admit:allow"), sink.events.take(4))
            assertEquals(listOf("pin:1234"), sink.events.filter { it.startsWith("pin:") })
            for (event in listOf("volume:-12.5", "format:4:1024:true", "size:640.0:480.0:1920.0:1080.0",
                "progress:1:2147483648:4294967295", "dacp::", "dacp:device:remote", "mirror:false", "mirror:true",
                "play:https://example.invalid/movie:3.5", "scrub:8.5", "rate:0.5", "reset:7", "reset:-1", "teardown")) {
                assertEquals("Expected exactly one $event", 1, sink.events.count { it == event })
            }
            assertEquals(4, sink.events.count { it == "stop" })
            assertEquals(2, sink.events.count { it == "poll" })
            assertEquals(4, sink.events.count { it == "init" })
            assertEquals(2, sink.events.count { it == "destroy" })
        } finally { DebugCoverage.dump(InstrumentationRegistry.getInstrumentation().targetContext) }
    }
}
