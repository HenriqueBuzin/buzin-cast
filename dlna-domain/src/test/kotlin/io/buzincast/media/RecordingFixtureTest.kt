package io.buzincast.media

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecordingFixtureTest {
    @Test fun `real AVC access units produce an mkv with video and PCM`() = create(false)
    @Test fun `real HEVC access units produce an mkv with video and PCM`() = create(true)

    private fun create(hevc: Boolean) {
        val extension = if (hevc) "h265" else "h264"
        val data = javaClass.getResourceAsStream("/mirror/sample.$extension")!!.use { it.readBytes() }
        val frames = mutableListOf<MutableList<ByteArray>>()
        NalUnits.split(data).forEach { nal ->
            if (NalUnits.type(nal, hevc) == if (hevc) 35 else 9) frames += mutableListOf<ByteArray>()
            if (frames.isEmpty()) frames += mutableListOf<ByteArray>()
            frames.last().add(nal)
        }
        val directory = File("build/mux-fixtures").apply { mkdirs() }
        val file = File(directory, "recorded-$extension.mkv")
        val writer = MatroskaRecorder(file)
        frames.forEachIndexed { index, nals ->
            val start = byteArrayOf(0, 0, 0, 1)
            val encoded = nals.fold(byteArrayOf()) { bytes, nal -> bytes + start + nal }
            val pts = 1_000_000_000L + index * 200_000_000L
            writer.write(MirrorPacket.Video(encoded, pts, 64, 48, hevc))
            writer.write(MirrorPacket.Audio(ByteArray(35280), pts))
        }
        writer.close()
        assertEquals(10L, writer.videoFrames)
        assertEquals(10L, writer.audioPackets)
        assertTrue(file.length() > 350000)
    }
}
