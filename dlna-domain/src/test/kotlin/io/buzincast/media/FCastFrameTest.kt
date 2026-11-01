package io.buzincast.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import org.junit.Assert.*
import org.junit.Test

class FCastFrameTest {
    @Test fun `wire format uses little endian size including opcode`() {
        val out = ByteArrayOutputStream()
        FCastFrame(12).writeTo(out)
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 12), out.toByteArray())
    }
    @Test fun `unicode and consecutive frames survive fragmented reads`() {
        val frames = listOf(FCastFrame(1, "{\"title\":\"Vídeo 📺\"}"), FCastFrame(2))
        val out = ByteArrayOutputStream(); frames.forEach { it.writeTo(out) }
        val stream = object : ByteArrayInputStream(out.toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 1))
        }
        frames.forEach { assertEquals(it, FCastFrame.readFrom(stream)) }
        assertNull(FCastFrame.readFrom(stream))
    }
    @Test fun `maximum bounded body round trips`() {
        val frame = FCastFrame(1, "a".repeat(31999)); val out = ByteArrayOutputStream()
        frame.writeTo(out)
        assertEquals(frame, FCastFrame.readFrom(ByteArrayInputStream(out.toByteArray())))
    }
    @Test fun `zero oversized and unsigned huge lengths rejected before allocation`() {
        listOf(byteArrayOf(0,0,0,0), byteArrayOf(1,125,0,0), byteArrayOf(-1,-1,-1,-1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { FCastFrame.readFrom(ByteArrayInputStream(it)) }
        }
    }
    @Test fun `partial header opcode and body fail rather than parsing another command`() {
        listOf(byteArrayOf(1), byteArrayOf(1,0,0,0), byteArrayOf(3,0,0,0,1,65)).forEach {
            assertThrows(EOFException::class.java) { FCastFrame.readFrom(ByteArrayInputStream(it)) }
        }
    }
    @Test fun `malformed utf8 rejected`() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            FCastFrame.readFrom(ByteArrayInputStream(byteArrayOf(2,0,0,0,1,-1)))
        }
    }
    @Test fun `outbound bounds count bytes not characters`() {
        assertThrows(IllegalArgumentException::class.java) { FCastFrame(1, "é".repeat(16000)).writeTo(ByteArrayOutputStream()) }
    }
}
