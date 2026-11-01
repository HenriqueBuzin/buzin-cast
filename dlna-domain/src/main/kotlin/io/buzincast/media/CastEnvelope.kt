package io.buzincast.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Independent bounded implementation of the public CastMessage protobuf wire schema.
 * https://chromium.googlesource.com/openscreen/+/refs/heads/main/cast/common/channel/proto/cast_channel.proto
 * Only unchunked CASTV2_1_0 messages are supported. No device-auth identity is provided.
 */
data class CastEnvelope(val source: String, val destination: String, val namespace: String,
    val text: String? = null, val binary: ByteArray? = null) {
    fun writeTo(output: OutputStream) {
        require((text != null) != (binary != null))
        val bytes = ByteArrayOutputStream()
        fun integer(value: Int) {
            var v = value
            do { bytes.write((v and 127) or if (v ushr 7 != 0) 128 else 0); v = v ushr 7 } while (v != 0)
        }
        fun field(key: Int, value: ByteArray) { integer(key * 8 + 2); integer(value.size); bytes.write(value) }
        integer(8); integer(0)
        field(2, source.toByteArray(Charsets.UTF_8)); field(3, destination.toByteArray(Charsets.UTF_8))
        field(4, namespace.toByteArray(Charsets.UTF_8))
        integer(40); integer(if (binary == null) 0 else 1)
        if (text != null) field(6, text.toByteArray(Charsets.UTF_8)) else field(7, binary!!)
        require(bytes.size() in 1..MAX_SIZE)
        DataOutputStream(output).apply { writeInt(bytes.size()); bytes.writeTo(this); flush() }
    }

    companion object {
        const val MAX_SIZE = 65536
        fun readFrom(input: InputStream, afterFirstByte: () -> Unit = {}): CastEnvelope? {
            val first = input.read()
            if (first < 0) return null
            afterFirstByte()
            val tail = ByteArray(3); DataInputStream(input).readFully(tail)
            val size = (first.toLong() shl 24) or ((tail[0].toLong() and 255) shl 16) or
                ((tail[1].toLong() and 255) shl 8) or (tail[2].toLong() and 255)
            require(size in 1..MAX_SIZE.toLong()) { "Invalid Cast envelope length" }
            val data = ByteArray(size.toInt()); DataInputStream(input).readFully(data)
            val bytes = ByteArrayInputStream(data)
            fun integer(): Int {
                var value = 0L
                for (i in 0..4) {
                    val b = bytes.read(); if (b < 0) throw EOFException("Truncated protobuf")
                    value = value or ((b.toLong() and 127) shl (i * 7))
                    if (b and 128 == 0) {
                        require(value <= Int.MAX_VALUE); return value.toInt()
                    }
                }
                error("Overlong protobuf varint")
            }
            fun block(): ByteArray {
                val length = integer(); require(length <= bytes.available())
                return ByteArray(length).also { DataInputStream(bytes).readFully(it) }
            }
            fun utf8(value: ByteArray) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString()
            var version: Int? = null; var type: Int? = null
            val fields = mutableMapOf<Int, ByteArray>()
            while (bytes.available() > 0) {
                val tag = integer(); val field = tag ushr 3; val wire = tag and 7
                require(field > 0)
                when (field) {
                    1, 5, 8, 9 -> {
                        require(wire == 0)
                        val value = integer()
                        when (field) {
                            1 -> version = value
                            5 -> type = value
                            8, 9 -> require(value == 0) { "Chunked Cast messages not supported" }
                        }
                    }
                    in 2..4, 6, 7 -> { require(wire == 2 && !fields.containsKey(field)); fields[field] = block() }
                    else -> when (wire) {
                        0 -> integer()
                        2 -> block()
                        1 -> require(bytes.skip(8) == 8L)
                        5 -> require(bytes.skip(4) == 4L)
                        else -> error("Unsupported protobuf wire type")
                    }
                }
            }
            require(version == 0 && (type == 0 || type == 1))
            fun id(field: Int) = utf8(requireNotNull(fields[field])).also {
                require(it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl))
            }
            require((fields[6] != null) != (fields[7] != null))
            require((type == 0) == (fields[6] != null))
            return CastEnvelope(id(2), id(3), id(4), fields[6]?.let(::utf8), fields[7])
        }
    }
}
