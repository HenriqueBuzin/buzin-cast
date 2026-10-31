package io.buzincast.media

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class MirrorMediaTest {
    @Test fun `video packet preserves dimensions timestamps codec and bytes`() {
        val original = MirrorPacket.Video(byteArrayOf(0, 0, 0, 1, 0x65, 2), 123456789, 1920, 1080, false)
        val restored = MirrorPacketCodec.decode(MirrorPacketCodec.encode(original)) as MirrorPacket.Video
        assertArrayEquals(original.data, restored.data); assertEquals(original.ptsNs, restored.ptsNs)
        assertEquals(1920, restored.width); assertEquals(1080, restored.height); assertFalse(restored.hevc)
    }
    @Test fun `invalid and truncated packet rejected before allocation`() {
        listOf(byteArrayOf(9), byteArrayOf(1, 0), ByteArray(40)).forEach { bad ->
            try { MirrorPacketCodec.decode(bad); fail("Accepted malformed packet") } catch (_: Exception) { }
        }
    }
    @Test fun `encrypted channel authenticates code payload and sequence`() {
        val salt = ByteArray(16) { it.toByte() }
        val key = SecureMirrorChannel.deriveKey("ABCD-EFGH-JKLM", salt)
        val wire = ByteArrayOutputStream()
        val sender = SecureMirrorChannel(ByteArrayInputStream(byteArrayOf()), wire, key)
        sender.send("hello".toByteArray()); sender.send(byteArrayOf(1, 2, 3))
        val reader = SecureMirrorChannel(ByteArrayInputStream(wire.toByteArray()), ByteArrayOutputStream(), key)
        assertEquals("hello", reader.receive().toString(Charsets.UTF_8)); assertArrayEquals(byteArrayOf(1, 2, 3), reader.receive())
        val tampered = wire.toByteArray(); tampered[24] = (tampered[24].toInt() xor 1).toByte()
        try { SecureMirrorChannel(ByteArrayInputStream(tampered), ByteArrayOutputStream(), key).receive(); fail("Accepted modified ciphertext") } catch (_: javax.crypto.AEADBadTagException) { }
        val wrong = SecureMirrorChannel.deriveKey("ZZZZ-ZZZZ-ZZZZ", salt)
        try { SecureMirrorChannel(ByteArrayInputStream(wire.toByteArray()), ByteArrayOutputStream(), wrong).receive(); fail("Accepted wrong code") } catch (_: javax.crypto.AEADBadTagException) { }
    }
    @Test fun `nal parser handles both start codes and serializes sample`() {
        val nals = NalUnits.split(byteArrayOf(0, 0, 0, 1, 0x67, 66, 0, 31, 0, 0, 1, 0x65, 1, 2))
        assertEquals(2, nals.size); assertTrue(NalUnits.isKeyframe(nals, false))
        val encoded = DataInputStream(ByteArrayInputStream(NalUnits.lengthPrefixed(nals)))
        assertEquals(4, encoded.readInt()); assertEquals(0x67, encoded.readUnsignedByte())
    }
    @Test fun `mkv waits for key frame and records pcm plus video`() {
        val file = File.createTempFile("buzin-", ".mkv")
        try {
            val recorder = MatroskaRecorder(file)
            recorder.write(MirrorPacket.Video(byteArrayOf(0, 0, 0, 1, 0x41, 1), 1_000_000_000L, 16, 16, false))
            assertEquals(0, file.length())
            val key = byteArrayOf(0, 0, 0, 1, 0x67, 66, 0, 31, 1, 0, 0, 0, 1, 0x68, 1, 0, 0, 0, 1, 0x65, 1)
            recorder.write(MirrorPacket.Video(key, 1_000_000_000L, 16, 16, false))
            recorder.write(MirrorPacket.Audio(ByteArray(1764), 1_010_000_000L))
            recorder.write(MirrorPacket.Video(byteArrayOf(0, 0, 1, 0x41, 1), 7_000_000_000L, 16, 16, false))
            recorder.close()
            assertEquals(2, recorder.videoFrames); assertEquals(1, recorder.audioPackets)
            val content = file.readBytes()
            assertArrayEquals(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()), content.take(4).toByteArray())
            assertTrue(content.toString(Charsets.ISO_8859_1).contains("A_PCM/INT/LIT"))
        } finally { file.delete() }
    }
}
