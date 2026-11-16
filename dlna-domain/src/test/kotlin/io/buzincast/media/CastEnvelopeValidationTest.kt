package io.buzincast.media

import java.io.*
import java.nio.charset.CharacterCodingException
import org.junit.Assert.*
import org.junit.Test

class CastEnvelopeValidationTest {
    private fun integer(value:Long):ByteArray = ByteArrayOutputStream().apply {
        var remaining=value
        do { write(((remaining and 127) or if (remaining ushr 7 != 0L) 128 else 0).toInt());remaining=remaining ushr 7 } while(remaining!=0L)
    }.toByteArray()
    private fun scalar(field:Int,value:Long)=integer((field*8).toLong())+integer(value)
    private fun block(field:Int,value:ByteArray)=integer((field*8+2).toLong())+integer(value.size.toLong())+value
    private fun body(version:Int=0,type:Int=0,values:Map<Int,ByteArray> = mapOf(2 to "sender".toByteArray(),3 to "receiver".toByteArray(),4 to "ns".toByteArray(),6 to "{}".toByteArray())) =
        scalar(1,version.toLong())+scalar(5,type.toLong())+values.entries.fold(byteArrayOf()) { bytes,(key,value)->bytes+block(key,value) }
    private fun read(bytes:ByteArray):CastEnvelope? {
        val wire=ByteArrayOutputStream().apply { DataOutputStream(this).writeInt(bytes.size);write(bytes) }
        return CastEnvelope.readFrom(ByteArrayInputStream(wire.toByteArray()))
    }
    @Test fun `supported extensions and unknown protobuf fields preserve the message`() {
        val extensions=scalar(8,0)+scalar(9,0)+scalar(10,Int.MAX_VALUE.toLong())+
            block(11,byteArrayOf(1,2))+integer((12*8+1).toLong())+ByteArray(8)+integer((13*8+5).toLong())+ByteArray(4)
        val message=read(body()+extensions)!!
        assertEquals("sender",message.source);assertEquals("receiver",message.destination)
        assertEquals("ns",message.namespace);assertEquals("{}",message.text);assertNull(message.binary)
        for (field in listOf(8,9)) assertThrows(IllegalArgumentException::class.java) { read(body()+scalar(field,1)) }
    }
    @Test fun `malformed tags varints blocks and fixed width fields fail before allocation`() {
        for (bad in listOf(byteArrayOf(0),integer(2*8L),block(2,byteArrayOf(1)),
            integer(10*8L)+integer(Int.MAX_VALUE.toLong()+1),
            integer(11*8L+2)+integer(1000),integer(12*8L+1)+ByteArray(7),integer(13*8L+5)+ByteArray(3)))
            assertThrows(IllegalArgumentException::class.java) { read(body()+bad) }
        assertThrows(IllegalStateException::class.java) { read(body()+integer(10*8L)+ByteArray(5){128.toByte()}) }
        assertThrows(IllegalStateException::class.java) { read(body()+integer(14*8L+3)) }
        assertThrows(IllegalArgumentException::class.java) { read(body()+integer(1*8L+2)) }
        assertThrows(EOFException::class.java) { read(body()+byteArrayOf(128.toByte())) }
    }
    @Test fun `version type and payload presence must agree`() {
        assertThrows(IllegalArgumentException::class.java) { read(body(version=1)) }
        assertThrows(IllegalArgumentException::class.java) { read(body(type=2)) }
        assertThrows(IllegalArgumentException::class.java) { read(body().drop(scalar(1,0).size).toByteArray()) }
        val missingType = scalar(1,0)+body().drop(scalar(1,0).size+scalar(5,0).size).toByteArray()
        assertThrows(IllegalArgumentException::class.java) { read(missingType) }
        val ids=mapOf(2 to "s".toByteArray(),3 to "r".toByteArray(),4 to "n".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { read(body(values=ids)) }
        assertThrows(IllegalArgumentException::class.java) { read(body(values=ids+(7 to byteArrayOf(1)))) }
        assertThrows(IllegalArgumentException::class.java) { read(body(type=1)) }
        assertThrows(IllegalArgumentException::class.java) { read(body(values=ids+(6 to byteArrayOf(1))+(7 to byteArrayOf(2)))) }
        val binary=read(body(type=1,values=ids+(7 to byteArrayOf())))!!
        assertArrayEquals(byteArrayOf(),binary.binary);assertNull(binary.text)
    }
    @Test fun `all routing identifiers require bounded nonblank valid utf8 without controls`() {
        val good=mapOf(2 to "s".toByteArray(),3 to "r".toByteArray(),4 to "n".toByteArray(),6 to "{}".toByteArray())
        for (field in 2..4) {
            assertThrows(IllegalArgumentException::class.java) { read(body(values=good-field)) }
            for (bad in listOf("", " ","x".repeat(257),"x\n","x\u0000"))
                assertThrows(IllegalArgumentException::class.java) { read(body(values=good+(field to bad.toByteArray()))) }
            assertThrows(CharacterCodingException::class.java) { read(body(values=good+(field to byteArrayOf(0xc3.toByte(),0x28)))) }
            assertNotNull(read(body(values=good+(field to "x".repeat(256).toByteArray()))))
        }
    }
    @Test fun `first byte callback runs once only after a frame begins`() {
        var began=0
        assertNull(CastEnvelope.readFrom(ByteArrayInputStream(byteArrayOf())) { began++ })
        assertEquals(0,began)
        val payload=body();val wire=ByteArrayOutputStream().apply { DataOutputStream(this).writeInt(payload.size);write(payload) }
        assertNotNull(CastEnvelope.readFrom(ByteArrayInputStream(wire.toByteArray())) { began++ })
        assertEquals(1,began)
    }
}
