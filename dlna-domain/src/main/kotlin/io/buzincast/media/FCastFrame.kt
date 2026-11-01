package io.buzincast.media

import java.io.InputStream
import java.io.OutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** FCast v2 framing. Size includes the opcode, excludes the four size bytes. */
data class FCastFrame(val opcode: Int, val body: String = "") {
    fun writeTo(output: OutputStream) {
        require(opcode in 0..255)
        val bytes = body.toByteArray(Charsets.UTF_8)
        val size = bytes.size + 1
        require(size in 1..MAX_SIZE)
        repeat(4) { output.write(size ushr (it * 8) and 255) }
        output.write(opcode)
        output.write(bytes)
        output.flush()
    }

    companion object {
        const val MAX_SIZE = 32000
        /** Call afterFirstByte to apply a deadline to partial frames, without an idle deadline. */
        fun readFrom(input: InputStream, afterFirstByte: () -> Unit = {}): FCastFrame? {
            val first = input.read()
            if (first < 0) return null
            afterFirstByte()
            var size = first.toLong()
            for (i in 1..3) {
                val byte = input.read()
                if (byte < 0) throw EOFException("Incomplete FCast header")
                size = size or (byte.toLong() shl (8 * i))
            }
            require(size in 1..MAX_SIZE.toLong()) { "Invalid FCast frame length" }
            val opcode = input.read()
            if (opcode < 0) throw EOFException("Missing FCast opcode")
            val bytes = ByteArray(size.toInt() - 1)
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) throw EOFException("Incomplete FCast body")
                offset += count
            }
            val body = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            return FCastFrame(opcode, body)
        }
    }
}
