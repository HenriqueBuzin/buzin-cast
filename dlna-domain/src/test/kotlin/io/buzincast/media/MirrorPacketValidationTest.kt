package io.buzincast.media

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class MirrorPacketValidationTest {
    private fun video(w:Int=16,h:Int=16,size:Int=5,payload:ByteArray=ByteArray(5),hevc:Boolean=false):ByteArray =
        ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).apply {
            writeByte(1);writeLong(77);writeInt(w);writeInt(h);writeBoolean(hevc);writeInt(size);write(payload)
        } }.toByteArray()
    private fun audio(rate:Int=44100,channels:Int=2,size:Int=4,payload:ByteArray=ByteArray(4)):ByteArray =
        ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).apply {
            writeByte(2);writeLong(88);writeInt(rate);writeInt(channels);writeInt(size);write(payload)
        } }.toByteArray()

    @Test fun `video dimensions and payload bounds apply to senders and receivers`() {
        for ((w,h) in listOf(0 to 16,-1 to 16,8193 to 16,16 to 0,16 to -1,16 to 8193)) {
            assertThrows(IllegalArgumentException::class.java) { MirrorPacketCodec.encode(MirrorPacket.Video(ByteArray(5),0,w,h,false)) }
            assertThrows(IllegalArgumentException::class.java) { MirrorPacketCodec.decode(video(w=w,h=h)) }
        }
        for (size in listOf(0,4,MirrorPacketCodec.MAX_BYTES+1)) {
            assertThrows(IllegalArgumentException::class.java) { MirrorPacketCodec.encode(MirrorPacket.Video(ByteArray(size),0,16,16,false)) }
            assertThrows(IllegalArgumentException::class.java) { MirrorPacketCodec.decode(video(size=size)) }
        }
        for (size in listOf(-1,6,MirrorPacketCodec.MAX_BYTES))
            assertThrows(IllegalArgumentException::class.java) { MirrorPacketCodec.decode(video(size=size)) }
        for ((w,h) in listOf(1 to 8192,8192 to 1)) {
            val restored=MirrorPacketCodec.decode(video(w=w,h=h,hevc=true)) as MirrorPacket.Video
            assertEquals(w,restored.width);assertEquals(h,restored.height);assertTrue(restored.hevc);assertEquals(77L,restored.ptsNs)
        }
    }
    @Test fun `PCM validation enforces supported rates channels alignment and exact framing`() {
        for ((rate,channels) in listOf(7999 to 2,192001 to 2,44100 to 0,44100 to 9)) {
            assertThrows(IllegalArgumentException::class.java) { MirrorPacketCodec.encode(MirrorPacket.Audio(ByteArray(4),0,rate,channels)) }
            assertThrows(IllegalArgumentException::class.java) { MirrorPacketCodec.decode(audio(rate=rate,channels=channels)) }
        }
        for (size in listOf(0,1,6,128*1024+4)) {
            assertThrows(IllegalArgumentException::class.java) { MirrorPacketCodec.encode(MirrorPacket.Audio(ByteArray(size),0)) }
            assertThrows(IllegalArgumentException::class.java) { MirrorPacketCodec.decode(audio(size=size,payload=ByteArray(size))) }
        }
        for (size in listOf(-1,8))
            assertThrows(IllegalArgumentException::class.java) { MirrorPacketCodec.decode(audio(size=size)) }
        for ((rate,channels) in listOf(8000 to 1,192000 to 8,44100 to 2)) {
            val data=ByteArray(channels*2){it.toByte()}
            val restored=MirrorPacketCodec.decode(MirrorPacketCodec.encode(MirrorPacket.Audio(data,Long.MAX_VALUE,rate,channels))) as MirrorPacket.Audio
            assertEquals(rate,restored.sampleRate);assertEquals(channels,restored.channels)
            assertEquals(Long.MAX_VALUE,restored.ptsNs);assertArrayEquals(data,restored.data)
        }
    }
    @Test fun `end framing and truncated headers cannot hide trailing messages`() {
        assertSame(MirrorPacket.End,MirrorPacketCodec.decode(MirrorPacketCodec.encode(MirrorPacket.End)))
        assertThrows(IllegalArgumentException::class.java) { MirrorPacketCodec.decode(byteArrayOf(3,1)) }
        assertThrows(IllegalArgumentException::class.java) { MirrorPacketCodec.decode(ByteArray(MirrorPacketCodec.MAX_BYTES+33)) }
        for (wire in listOf(video(),audio())) for (length in 0..20)
            assertThrows(Exception::class.java) { MirrorPacketCodec.decode(wire.copyOf(length)) }
    }
    @Test fun `maximum permitted payloads remain accepted without truncation`() {
        val video=ByteArray(MirrorPacketCodec.MAX_BYTES){(it%251).toByte()}
        val picture=MirrorPacketCodec.decode(MirrorPacketCodec.encode(MirrorPacket.Video(video,123,8192,8192,true))) as MirrorPacket.Video
        assertArrayEquals(video,picture.data)
        val pcm=ByteArray(128*1024){(it%127).toByte()}
        val sound=MirrorPacketCodec.decode(MirrorPacketCodec.encode(MirrorPacket.Audio(pcm,456,192000,8))) as MirrorPacket.Audio
        assertArrayEquals(pcm,sound.data)
    }
}
