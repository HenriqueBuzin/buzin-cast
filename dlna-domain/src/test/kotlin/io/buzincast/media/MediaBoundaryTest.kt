package io.buzincast.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class MediaBoundaryTest {
    @Test fun `viewport rejects every invalid dimension and clamps safe area`() {
        for (index in 0..3) for (invalid in listOf(0,-1)) {
            val dimensions = intArrayOf(1920,1080,1920,1080)
            dimensions[index] = invalid
            assertThrows(IllegalArgumentException::class.java) {
                DisplayFit.viewport(dimensions[0],dimensions[1],dimensions[2],dimensions[3],0)
            }
        }
        assertEquals(DisplayFit.viewport(1920,1080,1920,1080,0),DisplayFit.viewport(1920,1080,1920,1080,-5))
        assertEquals(DisplayFit.viewport(1920,1080,1920,1080,10),DisplayFit.viewport(1920,1080,1920,1080,30))
        assertEquals(DisplayFit.Viewport(0,0,1,1),DisplayFit.viewport(1,1,Int.MAX_VALUE,1,10))
    }

    @Test fun `fcast rejects invalid opcode before writing output`() {
        for (opcode in listOf(-1,256)) {
            val output=ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) { FCastFrame(opcode).writeTo(output) }
            assertEquals(0,output.size())
        }
        for (opcode in listOf(0,255)) {
            val frame=FCastFrame(opcode)
            val wire=ByteArrayOutputStream().also(frame::writeTo).toByteArray()
            val restored = FCastFrame.readFrom(ByteArrayInputStream(wire))!!
            assertEquals(frame,restored)
            assertEquals(opcode,restored.opcode)
            assertEquals("",restored.body)
        }
    }

    private val key = SecretKeySpec(ByteArray(32){it.toByte()},"AES")
    private fun channel(wire:ByteArray)=SecureMirrorChannel(ByteArrayInputStream(wire),ByteArrayOutputStream(),key)
    private fun header(size:Int, sequence:Long=0)=ByteArrayOutputStream().apply {
        DataOutputStream(this).apply { writeInt(size);writeLong(sequence) }
    }.toByteArray()

    @Test fun `relay rejects invalid lengths sequences and oversized outbound data`() {
        for (size in listOf(-1,0,15,MirrorPacketCodec.MAX_BYTES+65))
            assertThrows(IllegalArgumentException::class.java) { channel(header(size)).receive() }
        for (sequence in listOf(-1L,1L))
            assertThrows(IllegalArgumentException::class.java) { channel(header(16,sequence)).receive() }
        val output=ByteArrayOutputStream()
        val sender=SecureMirrorChannel(ByteArrayInputStream(byteArrayOf()),output,key)
        assertThrows(IllegalArgumentException::class.java) { sender.send(ByteArray(MirrorPacketCodec.MAX_BYTES+33)) }
        assertEquals(0,output.size())
        sender.send(byteArrayOf())
        assertArrayEquals(byteArrayOf(),channel(output.toByteArray()).receive())
    }

    @Test fun `relay key requires full code and exact salt length`() {
        assertArrayEquals("BZCAST02".toByteArray(Charsets.US_ASCII),SecureMirrorChannel.MAGIC)
        assertEquals("ABCDEFGHIJKL",SecureMirrorChannel.normalizeCode("ab cd-ef_gh.ij/kl"))
        for (code in listOf("","A".repeat(11),"A".repeat(13)))
            assertThrows(IllegalArgumentException::class.java) { SecureMirrorChannel.deriveKey(code,ByteArray(16)) }
        for (length in listOf(0,15,17))
            assertThrows(IllegalArgumentException::class.java) { SecureMirrorChannel.deriveKey("ABCDEFGHIJKL",ByteArray(length)) }
        assertEquals("AES",SecureMirrorChannel.deriveKey("ABCDEFGHIJKL",ByteArray(16)).algorithm)
    }

    @Test fun `relay sequence exhaustion cannot wrap into an accepted negative sequence`() {
        // Set the lifetime counter at its boundary without transmitting 2^63 frames.
        val output = ByteArrayOutputStream()
        val sender = SecureMirrorChannel(ByteArrayInputStream(byteArrayOf()),output,key)
        sender.javaClass.getDeclaredField("tx").apply { isAccessible=true;setLong(sender,Long.MAX_VALUE) }
        sender.send(byteArrayOf(42))
        val receiver = channel(output.toByteArray())
        receiver.javaClass.getDeclaredField("rx").apply { isAccessible=true;setLong(receiver,Long.MAX_VALUE) }
        assertArrayEquals(byteArrayOf(42),receiver.receive())
        val length = output.size()
        assertThrows(IllegalArgumentException::class.java) { sender.send(byteArrayOf(43)) }
        assertEquals(length,output.size())
        val wrapped = channel(header(16,Long.MIN_VALUE))
        wrapped.javaClass.getDeclaredField("rx").apply { isAccessible=true;setLong(wrapped,Long.MIN_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { wrapped.receive() }
    }
}
