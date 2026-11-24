package io.github.jqssun.airplay

import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.buzincast.media.MirrorPacket
import io.github.jqssun.airplay.media.RelayPlayback
import io.github.jqssun.airplay.renderer.DecoderSelector
import io.github.jqssun.airplay.renderer.VideoRenderer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList

/** Real silent AudioTrack output and real renderer; no peer TV or media download. */
@RunWith(AndroidJUnit4::class)
class RelayPlaybackContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private data class Session(val relay: RelayPlayback, val renderer: VideoRenderer,
                               val sizes: CopyOnWriteArrayList<Pair<Int, Int>>)
    private val sessions = mutableListOf<Session>()
    private fun session(): Session {
        val renderer = VideoRenderer(context)
        val sizes = CopyOnWriteArrayList<Pair<Int, Int>>()
        return Session(RelayPlayback(renderer) { width, height -> sizes += width to height }, renderer, sizes)
            .also { sessions += it }
    }
    @After fun cleanup() {
        sessions.forEach { it.relay.stop(); it.renderer.release() }
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T
    private fun worker(session: Session): Thread? = field(session.relay, "audioThread")
    private fun track(session: Session): AudioTrack? = field(session.relay, "audioTrack")
    private fun queue(session: Session): ArrayBlockingQueue<MirrorPacket.Audio> = field(session.relay, "audioQueue")
    private fun await(message: String, timeoutMs: Long = 10000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(2)
        }
        fail(message)
    }
    private fun silentAudio(pts: Long = 0, rate: Int = 44100, channels: Int = 2) =
        MirrorPacket.Audio(ByteArray(4410 * 4), pts, rate, channels)
    private fun played(session: Session): AudioTrack {
        repeat(4) { session.relay.accept(silentAudio()) }
        await("Relay did not initialize a playing AudioTrack") {
            track(session)?.let { it.state == AudioTrack.STATE_INITIALIZED && it.playState == AudioTrack.PLAYSTATE_PLAYING } == true
        }
        val output = requireNotNull(track(session))
        assertEquals(44100, output.sampleRate)
        assertEquals(2, output.channelCount)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, output.audioFormat)
        await("Silent PCM was not consumed by the real AudioTrack") { output.playbackHeadPosition >= 4 * 4410 }
        return output
    }
    private fun assertStopped(session: Session, oldWorker: Thread, oldTrack: AudioTrack? = null) {
        assertFalse("Stopped relay retained a live audio worker", oldWorker.isAlive)
        assertFalse(field<Boolean>(session.relay, "active"))
        assertNull(worker(session)); assertNull(track(session))
        assertTrue(queue(session).isEmpty())
        assertEquals(Long.MIN_VALUE, field<Long>(session.relay, "base"))
        assertEquals(0, field<Int>(session.relay, "width")); assertEquals(0, field<Int>(session.relay, "height"))
        if (oldTrack != null) assertEquals("Stopped relay did not release its AudioTrack", AudioTrack.STATE_UNINITIALIZED, oldTrack.state)
    }

    @Test fun silentAudioReleasesItsTrackAndWorkerAndRestartsWithNewOwners() {
        val session = session()
        session.relay.stop(false) // Stopping an idle receiver is safe.
        val firstTrack = played(session)
        val firstWorker = requireNotNull(worker(session))
        session.relay.stop(false)
        assertStopped(session, firstWorker, firstTrack)
        val secondTrack = played(session)
        val secondWorker = requireNotNull(worker(session))
        assertNotSame(firstTrack, secondTrack); assertNotSame(firstWorker, secondWorker)
        session.relay.accept(MirrorPacket.End)
        assertStopped(session, secondWorker, secondTrack)
        session.relay.stop()
    }

    @Test fun invalidAudioFormatsEndTheirWorkerAndDoNotPoisonTheNextSession() {
        val session = session()
        for ((rate, channels) in listOf(48000 to 2, 44100 to 1)) {
            session.relay.accept(silentAudio(rate = rate, channels = channels))
            val rejectedWorker = requireNotNull(worker(session))
            rejectedWorker.join(5000)
            assertFalse("Invalid audio left the worker running", rejectedWorker.isAlive)
            assertFalse(field<Boolean>(session.relay, "active"))
            assertNull(track(session)); assertTrue(queue(session).isEmpty())
            val output = played(session)
            val replacement = requireNotNull(worker(session))
            assertNotSame(rejectedWorker, replacement)
            session.relay.stop(false)
            assertStopped(session, replacement, output)
        }
    }

    private fun video(session: Session, pts: Long, width: Int = 640, height: Int = 360, hevc: Boolean = false) {
        // Reference NAL, not a keyframe: exercises routing without starting a codec.
        session.relay.accept(MirrorPacket.Video(byteArrayOf(0, 0, 0, 1, 1, 0), pts, width, height, hevc))
    }

    @Test fun decoderRestartScansForAvcAndHevcAccessUnits() {
        val renderer = session().renderer
        val classify = VideoRenderer::class.java
            .getDeclaredMethod("_isKeyframe", ByteArray::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        fun canRestart(packet: ByteArray, hevc: Boolean) = classify.invoke(renderer, packet, hevc) as Boolean
        fun nal(header: Int) = byteArrayOf(0, 0, 0, 1, header.toByte())

        assertFalse(canRestart(byteArrayOf(0, 0, 0, 1), hevc = false))
        assertFalse(canRestart(byteArrayOf(0, 3, 0, 1, 0x65), hevc = false))
        assertFalse(canRestart(byteArrayOf(0, 0, 0, 2, 0x65), hevc = false))
        assertFalse(canRestart(nal(0x61), hevc = false))
        assertTrue(canRestart(nal(0x65), hevc = false)) // AVC IDR.
        assertTrue(canRestart(nal(0x61) + nal(0x67), hevc = false)) // AVC SPS after a reference slice.

        assertFalse(canRestart(nal(1 shl 1), hevc = true))
        for (type in listOf(19, 20, 21, 32, 33)) {
            assertTrue("HEVC NAL type $type did not qualify for decoder restart", canRestart(nal(type shl 1), hevc = true))
        }
        assertTrue(canRestart(byteArrayOf(9, 0, 0, 2) + nal(33 shl 1), hevc = true))
    }

    @Test fun decoderFormatRespectsSdrFrameDropAndInputSizeSettings() {
        val renderer = session().renderer
        val mime = DecoderSelector.AVC
        assertFalse(renderer.selectDecoders(64, 48, 24, h265 = false))
        val decoder = requireNotNull(field<MediaCodecInfo?>(renderer, "avcDecoder"))
        val method = VideoRenderer::class.java.getDeclaredMethod("_format", String::class.java, MediaCodecInfo::class.java)
            .apply { isAccessible = true }
        fun format() = method.invoke(renderer, mime, decoder) as MediaFormat

        renderer.setResolution(64, 48)
        renderer.enforceSdr = true
        renderer.keyAllowFrameDrop = true
        val sdr = format()
        assertEquals(24, sdr.getInteger(MediaFormat.KEY_FRAME_RATE))
        assertEquals(1024 * 1024, sdr.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, sdr.getInteger(MediaFormat.KEY_COLOR_STANDARD))
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, sdr.getInteger(MediaFormat.KEY_COLOR_RANGE))
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, sdr.getInteger(MediaFormat.KEY_COLOR_TRANSFER))

        renderer.setResolution(1920, 1080)
        renderer.enforceSdr = false
        renderer.keyAllowFrameDrop = false
        val passthrough = format()
        assertEquals(1920 * 1080 * 3 / 4, passthrough.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
        for (key in listOf(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER)) {
            assertFalse("Disabled SDR override still set $key", passthrough.containsKey(key))
        }

        if (Build.VERSION.SDK_INT >= 29) {
            assertEquals(1, sdr.getInteger(MediaFormat.KEY_ALLOW_FRAME_DROP))
            assertEquals(0, passthrough.getInteger(MediaFormat.KEY_ALLOW_FRAME_DROP))
        } else {
            assertFalse(sdr.containsKey(MediaFormat.KEY_ALLOW_FRAME_DROP))
            assertFalse(passthrough.containsKey(MediaFormat.KEY_ALLOW_FRAME_DROP))
        }
    }

    @Test fun benchmarkPublishesFrameStatisticsOnlyWhenEnabled() {
        val renderer = session().renderer
        val window = VideoRenderer::class.java.getDeclaredField("_lastStatReset").apply { isAccessible = true }
        val reference = ByteArray(1000).apply { this[3] = 1; this[4] = 0x61 }
        val lines = mutableListOf<String>()
        renderer.setResolution(640, 360)
        renderer.startSession()
        renderer.benchmarkLogCallback = { lines += it }

        renderer.benchmarkLog = false
        window.setLong(renderer, 0L)
        renderer.feedFrame(reference, 0L, isH265 = false)
        // Keep both samples in the same reporting window even on a busy emulator.
        window.setLong(renderer, System.currentTimeMillis() + 60_000L)
        renderer.feedFrame(reference, 1L, isH265 = false)
        assertTrue(lines.isEmpty())

        renderer.benchmarkLog = true
        window.setLong(renderer, 0L)
        renderer.feedFrame(reference, 2L, isH265 = false)
        assertEquals(2, renderer.fps)
        assertEquals(16_000L, renderer.bitrateBps)
        assertEquals(3L, renderer.frameCount)
        assertEquals("fps=2 bitrate=16kbps jitter=0us frames=2 dropped=0 codec= res=640x360", lines.single())

        renderer.benchmarkLogCallback = null
        window.setLong(renderer, 0L)
        renderer.feedFrame(reference, 3L, isH265 = false)
        assertEquals(1, lines.size)
        assertEquals(1, renderer.fps)
        assertEquals(8_000L, renderer.bitrateBps)
        assertEquals(4L, renderer.frameCount)

        renderer.startSession()
        assertEquals(0, renderer.fps)
        assertEquals(0L, renderer.bitrateBps)
        assertEquals(0L, renderer.frameCount)
    }

    @Test fun videoChangesAnnounceDimensionsAndOnlyNewSessionsResetTheClockAnchor() {
        val session = session()
        val firstPts = 1_000_000_000L
        val before = System.nanoTime()
        video(session, firstPts)
        val after = System.nanoTime()
        val anchor = field<Long>(session.relay, "clock")
        assertTrue("Initial clock was not anchored with the startup delay", anchor in before + 120_000_000..after + 120_000_000)
        assertEquals(firstPts, field<Long>(session.relay, "base"))
        assertEquals(listOf(640 to 360), session.sizes.toList())
        video(session, firstPts + 33_000_000)
        assertEquals(1, session.sizes.size)
        assertEquals(anchor, field<Long>(session.relay, "clock"))
        video(session, firstPts + 66_000_000, height = 480)
        video(session, firstPts + 99_000_000, width = 854, height = 480)
        video(session, firstPts + 132_000_000, width = 854, height = 480, hevc = true)
        assertEquals(listOf(640 to 360, 640 to 480, 854 to 480, 854 to 480), session.sizes.toList())
        assertTrue(field<Boolean>(session.relay, "hevc"))
        assertNull("Reference NAL incorrectly started a decoder", field<Any?>(session.renderer, "codec"))
        val oldWorker = requireNotNull(worker(session))
        session.relay.stop(false)
        assertStopped(session, oldWorker)
        val nextPts = 9_000_000_000L
        val nextBefore = System.nanoTime()
        video(session, nextPts)
        val nextAfter = System.nanoTime()
        assertEquals(nextPts, field<Long>(session.relay, "base"))
        assertTrue(field<Long>(session.relay, "clock") in nextBefore + 120_000_000..nextAfter + 120_000_000)
        assertEquals(640 to 360, session.sizes.last())
        assertFalse(field<Boolean>(session.relay, "hevc"))
        assertNotSame(oldWorker, worker(session))
    }

    @Test fun pacingHasABoundedWaitAndQueueOverflowCanBeStoppedWithoutLeakingOutput() {
        val session = session()
        val output = played(session)
        val base = 1_000_000_000L
        video(session, base)
        // A timestamp several seconds ahead must not sleep outside the bounded window.
        session.relay.accept(silentAudio(base + 5_000_000_000L))
        await("Far-future PCM exceeded the bounded pacing policy", 2000) { output.playbackHeadPosition >= 5 * 4410 }
        await("Audio queue did not drain before the pacing case") { queue(session).isEmpty() }
        val clock = field<Long>(session.relay, "clock")
        val desiredWrite = System.nanoTime() + 450_000_000L
        val pts = base + desiredWrite - clock + 60_000_000L
        session.relay.accept(MirrorPacket.Audio(ByteArray(4), pts))
        val pacedWorker = requireNotNull(worker(session))
        await("Worker never entered the real pacing sleep", 400) {
            pacedWorker.state == Thread.State.TIMED_WAITING && pacedWorker.stackTrace.any {
                it.className == "java.lang.Thread" && it.methodName == "sleep"
            }
        }
        repeat(64) { session.relay.accept(MirrorPacket.Audio(ByteArray(4), pts)) }
        assertEquals("Paced worker did not retain the bounded queue", 64, queue(session).size)
        try {
            session.relay.accept(MirrorPacket.Audio(ByteArray(4), pts))
            fail("Relay silently accepted an overflowing audio queue")
        } catch (error: IllegalStateException) {
            assertEquals("Buffer de retransmissão cheio", error.message)
        }
        session.relay.stop(false)
        assertStopped(session, pacedWorker, output)
    }
}
