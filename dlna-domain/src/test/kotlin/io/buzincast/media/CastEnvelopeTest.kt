package io.buzincast.media

import java.io.*
import org.junit.Assert.*
import org.junit.Test

class CastEnvelopeTest {
    private fun wire(frame: CastEnvelope) = ByteArrayOutputStream().apply { frame.writeTo(this) }.toByteArray()
    @Test fun `big endian envelope and utf8 roundtrip`() {
        val message = CastEnvelope("sender-0", "receiver-0", "urn:x-cast:test", "{\"name\":\"Vídeo\"}")
        val bytes = wire(message)
        assertEquals(bytes.size - 4, DataInputStream(ByteArrayInputStream(bytes)).readInt())
        assertEquals(message, CastEnvelope.readFrom(ByteArrayInputStream(bytes)))
    }
    @Test fun `binary auth error survives transport`() {
        val frame = CastEnvelope("receiver-0", "sender-0", "urn:x-cast:com.google.cast.tp.deviceauth", binary = byteArrayOf(26,2,8,0))
        assertArrayEquals(frame.binary, CastEnvelope.readFrom(ByteArrayInputStream(wire(frame)))!!.binary)
    }
    @Test fun `multiple messages with fragmented input`() {
        val frame = CastEnvelope("a", "b", "ns", "{}")
        val data = wire(frame) + wire(frame)
        val stream = object : ByteArrayInputStream(data) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 1))
        }
        repeat(2) { assertEquals(frame, CastEnvelope.readFrom(stream)) }; assertNull(CastEnvelope.readFrom(stream))
    }
    @Test fun `malicious lengths rejected`() {
        listOf(byteArrayOf(0,0,0,0), byteArrayOf(0,1,0,1), byteArrayOf(-1,-1,-1,-1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { CastEnvelope.readFrom(ByteArrayInputStream(it)) }
        }
    }
    @Test fun `truncation fails`() {
        val data = wire(CastEnvelope("a", "b", "ns", "{}"))
        assertThrows(EOFException::class.java) { CastEnvelope.readFrom(ByteArrayInputStream(data.dropLast(1).toByteArray())) }
    }
    @Test fun `missing required fields rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CastEnvelope.readFrom(ByteArrayInputStream(byteArrayOf(0,0,0,2,8,0))) }
    }
    @Test fun `missing or ambiguous payload rejected`() {
        assertThrows(IllegalArgumentException::class.java) { wire(CastEnvelope("a", "b", "ns")) }
        assertThrows(IllegalArgumentException::class.java) { wire(CastEnvelope("a", "b", "ns", "{}", byteArrayOf(1))) }
    }
    @Test fun `outbound body bounded`() {
        assertThrows(IllegalArgumentException::class.java) { wire(CastEnvelope("a", "b", "ns", "x".repeat(65536))) }
    }
}
