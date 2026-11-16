package io.github.jqssun.airplay

import android.content.Context
import android.content.ContextWrapper
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.*
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.buzincast.media.MirrorPacket
import io.github.jqssun.airplay.media.MirrorCapture
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real recorder workers, bounded queues and independently parsed output files. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MirrorCaptureContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val roots = mutableListOf<File>()
    private val captures = mutableListOf<MirrorCapture>()
    private fun isolatedContext(): Context {
        val root = File(context.cacheDir, "capture-contract-${System.nanoTime()}").also { it.mkdirs(); roots += it }
        return object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File = root
            override fun getFilesDir(): File = root
        }
    }
    private fun capture(changed: () -> Unit = {}): MirrorCapture =
        MirrorCapture(isolatedContext(), changed).also { captures += it }
    private fun video(hevc: Boolean = false, width: Int = 64, pts: Long = 1_000_000_000L) =
        MirrorPacket.Video(context.assets.open("mirror/sample.${if (hevc) "h265" else "h264"}").use { it.readBytes() }, pts, width, 48, hevc)
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate()) { assertTrue(message, System.nanoTime() < deadline); Thread.sleep(10) }
    }
    private class ParsedTrack : TrackOutput {
        lateinit var format: Format
        private val pending = ByteArrayOutputStream()
        val samples = mutableListOf<Pair<Long, ByteArray>>()
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val bytes = ByteArray(length)
            val count = input.read(bytes, 0, length)
            if (count < 0) { if (!allowEndOfInput) throw EOFException(); return -1 }
            pending.write(bytes, 0, count)
            return count
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val bytes = ByteArray(length); data.readBytes(bytes, 0, length); pending.write(bytes)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val bytes = pending.toByteArray()
            samples += timeUs to bytes.copyOfRange(bytes.size - offset - size, bytes.size - offset)
            pending.reset(); pending.write(bytes, bytes.size - offset, offset)
        }
    }
    private fun readTracks(file: File): List<ParsedTrack> {
        val tracks = mutableMapOf<Int, ParsedTrack>()
        // The player's Media3 parser supports Matroska PCM on old Fire OS too;
        // Android's platform extractor can omit that track on API22.
        val extractor = MatroskaExtractor(DefaultSubtitleParserFactory(), MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { ParsedTrack() }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        try {
            file.inputStream().use { stream ->
                val input = DefaultExtractorInput(DataReader { bytes, offset, length -> stream.read(bytes, offset, length) }, 0, file.length())
                val position = PositionHolder()
                var calls = 0
                while (true) {
                    assertTrue("Extractor did not finish", ++calls < 10000)
                    val result = extractor.read(input, position)
                    if (result == Extractor.RESULT_END_OF_INPUT) break
                    assertEquals("Unexpected seek in a streaming recording", Extractor.RESULT_CONTINUE, result)
                }
            }
        } finally { extractor.release() }
        return tracks.values.toList()
    }
    @After fun cleanup() {
        captures.forEach { it.close() }
        roots.forEach { it.deleteRecursively() }
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Test fun startIsIdempotentAndStoppingBeforeKeyframeLeavesNoRecording() {
        val changes = AtomicInteger()
        val capture = capture { changes.incrementAndGet() }
        assertFalse(capture.active())
        capture.offer(MirrorPacket.End)
        capture.start()
        assertTrue(capture.active()); assertTrue(capture.state.value.armed)
        assertFalse(capture.state.value.recording)
        assertEquals(context.getString(R.string.capture_waiting_keyframe), capture.state.value.message)
        capture.start(); assertEquals(1, changes.get())
        capture.stop(); capture.close()
        assertFalse(capture.active()); assertFalse(capture.state.value.armed)
        assertEquals(context.getString(R.string.capture_no_keyframe), capture.state.value.message)
        assertTrue(capture.directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun avcAndHevcRecordingsContainReadableVideoAndPcmTracks() {
        for (hevc in listOf(false, true)) {
            val capture = capture()
            capture.start(); capture.offer(video(hevc))
            await("Keyframe did not start recording") { capture.state.value.recording }
            capture.offer(video(hevc, pts = 1_040_000_000L))
            capture.offer(MirrorPacket.Audio(ByteArray(4096), 1_060_000_000L, 44100, 2))
            capture.offer(MirrorPacket.End)
            capture.stop(); capture.close()
            assertFalse(capture.active()); assertFalse(capture.state.value.recording)
            val file = capture.directory.listFiles().orEmpty().single()
            assertEquals(context.getString(R.string.capture_saved_file, file.name), capture.state.value.message)
            val tracks = readTracks(file)
            assertEquals(2, tracks.size)
            val video = tracks.single { it.format.sampleMimeType == if (hevc) "video/hevc" else "video/avc" }
            assertEquals(64, video.format.width); assertEquals(48, video.format.height)
            assertEquals(2, video.samples.size)
            val audio = tracks.single { it.format.sampleMimeType == "audio/raw" }
            assertEquals(44100, audio.format.sampleRate); assertEquals(2, audio.format.channelCount)
            val (timeUs, pcm) = audio.samples.single()
            assertEquals(4096, pcm.size); assertEquals(60000L, timeUs)
            assertTrue(pcm.all { it == 0.toByte() })
        }
    }

    @Test fun formatChangeStopsWorkerAndPreservesTheCompletedVideo() {
        val capture = capture()
        capture.start(); capture.offer(video())
        await("Recording did not start") { capture.state.value.recording }
        capture.offer(video(width = 128, pts = 1_040_000_000L))
        await("Format change did not finish worker") { !capture.state.value.armed }
        capture.close()
        assertFalse(capture.active())
        assertEquals("Video format changed; recording stopped", capture.state.value.message)
        assertTrue(capture.directory.listFiles().orEmpty().single().length() > 100)
    }

    @Test fun overflowingQueueStopsCaptureWithAnExplicitStorageFailure() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var recorder: MirrorCapture
        recorder = capture {
            if (Thread.currentThread().name == "buzin-recorder" && recorder.state.value.recording) {
                blocked.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Test did not release recorder" }
            }
        }
        try {
            recorder.start(); recorder.offer(video())
            assertTrue("Worker did not reach first keyframe", blocked.await(10, TimeUnit.SECONDS))
            repeat(121) { recorder.offer(MirrorPacket.Audio(ByteArray(4096), 1_040_000_000L + it * 23_000_000L, 44100, 2)) }
            assertFalse("Full queue must stop accepting packets", recorder.active())
        } finally { release.countDown() }
        await("Recorder did not drain after queue overflow") { !recorder.state.value.armed }
        recorder.close()
        assertEquals(context.getString(R.string.capture_slow_storage), recorder.state.value.message)
        assertTrue(recorder.directory.listFiles().orEmpty().single().length() > 100)
    }
}
