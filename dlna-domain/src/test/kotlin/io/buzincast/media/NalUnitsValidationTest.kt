package io.buzincast.media

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class NalUnitsValidationTest {
    private val avcSps=byteArrayOf(0x67,0x42,0,0x1e)
    private val avcPps=byteArrayOf(0x68,1)

    @Test fun `annex b handles adjacent empty prefixes and incomplete trailing prefixes`() {
        assertTrue(NalUnits.split(byteArrayOf()).isEmpty())
        assertTrue(NalUnits.split(byteArrayOf(0,0,0)).isEmpty())
        assertTrue(NalUnits.split(byteArrayOf(0,0,2,1)).isEmpty())
        assertTrue(NalUnits.split(byteArrayOf(0,0,0,2)).isEmpty())
        assertTrue(NalUnits.split(byteArrayOf(0,0,1)).isEmpty())
        val mixed=byteArrayOf(9,0,0,1,0,0,0,1,0x65,7,0,0,2,0,0,0)
        val nals=NalUnits.split(mixed)
        assertEquals(1,nals.size)
        assertArrayEquals(byteArrayOf(0x65,7,0,0,2,0,0,0),nals.single())
        assertArrayEquals(byteArrayOf(),NalUnits.lengthPrefixed(emptyList()))
        assertFalse(NalUnits.isKeyframe(emptyList(),false))
        for (type in listOf(15,16,21,22))
            assertEquals(type in 16..21,NalUnits.isKeyframe(listOf(byteArrayOf((type shl 1).toByte())),true))
    }

    @Test fun `avc configuration validates parameter set sizes and preserves headers`() {
        for ((sps,pps) in listOf(ByteArray(3) to avcPps,avcSps to byteArrayOf(),ByteArray(65536) to avcPps,avcSps to ByteArray(65536)))
            assertThrows(IllegalArgumentException::class.java) { NalUnits.avcConfig(sps,pps) }
        val config=NalUnits.avcConfig(avcSps,avcPps)
        assertArrayEquals(byteArrayOf(1,0x42,0,0x1e,-1,-31,0,4),config.copyOfRange(0,8))
        assertArrayEquals(avcSps,config.copyOfRange(8,12))
        assertArrayEquals(byteArrayOf(1,0,2,0x68,1),config.copyOfRange(12,17))
    }

    private fun sps(subLayers:Int=0,chroma:Int=1,luma:Int=0,depth:Int=0,crop:Boolean=false):ByteArray {
        val bits=StringBuilder()
        fun fixed(value:Int,count:Int) { for (shift in count-1 downTo 0) bits.append(if (value ushr shift and 1 == 1) '1' else '0') }
        fun ue(value:Int) { val encoded=(value+1).toString(2);bits.append("0".repeat(encoded.length-1));bits.append(encoded) }
        fixed(0,4);fixed(subLayers,3);fixed(1,1)
        repeat(12) { fixed(0x55,8) }
        repeat(subLayers) { fixed(if(it%2==0)1 else 0,1);fixed(if(it%2==0)0 else 1,1) }
        if(subLayers>0) repeat(8-subLayers) { fixed(0,2) }
        repeat(subLayers) { if(it%2==0)repeat(11){fixed(0x55,8)} else fixed(0x55,8) }
        ue(0);ue(chroma);if(chroma==3)fixed(1,1)
        ue(64);ue(48);fixed(if(crop)1 else 0,1)
        if(crop)repeat(4){ue(1)}
        ue(luma);ue(depth)
        while(bits.length%8!=0)bits.append('0')
        val rbsp=bits.toString().chunked(8).map{it.toInt(2).toByte()}
        val escaped=ByteArrayOutputStream()
        var zeros=0
        for(b in rbsp) {
            if(zeros>=2 && (b.toInt() and 255)<=3) { escaped.write(3);zeros=0 }
            escaped.write(b.toInt());zeros=if(b==0.toByte())zeros+1 else 0
        }
        return byteArrayOf(0x42,1)+escaped.toByteArray()
    }

    @Test fun `hevc configuration reads sublayer profile level crop and bit depth fields`() {
        for (layers in listOf(0,2)) {
            val parameter=sps(layers,chroma=3,luma=7,depth=7,crop=true)
            val config=NalUnits.hevcConfig(byteArrayOf(0x40,1),parameter,byteArrayOf(0x44,1))
            assertEquals(1,config[0].toInt())
            assertArrayEquals(ByteArray(12){0x55},config.copyOfRange(1,13))
            assertEquals(255,config[16].toInt() and 255)
            assertEquals(255,config[17].toInt() and 255)
            assertEquals(255,config[18].toInt() and 255)
            assertEquals(((layers+1) shl 3) or 7,config[21].toInt() and 255)
            assertEquals(3,config[22].toInt())
        }
    }

    @Test fun `hevc rejects missing oversized truncated and invalid parameter sets`() {
        val valid=sps()
        for ((vps,sps,pps) in listOf(
            Triple(byteArrayOf(),valid,byteArrayOf(1)),Triple(byteArrayOf(1),ByteArray(14),byteArrayOf(1)),
            Triple(byteArrayOf(1),valid,byteArrayOf()),Triple(ByteArray(65536),valid,byteArrayOf(1)),
            Triple(byteArrayOf(1),valid,ByteArray(65536)),Triple(byteArrayOf(1),valid.copyOf(65536),byteArrayOf(1)),
            Triple(byteArrayOf(1),sps(chroma=4),byteArrayOf(1)),Triple(byteArrayOf(1),sps(luma=8),byteArrayOf(1)),
            Triple(byteArrayOf(1),sps(depth=8),byteArrayOf(1)),Triple(byteArrayOf(1),ByteArray(15),byteArrayOf(1))))
            assertThrows(IllegalArgumentException::class.java) { NalUnits.hevcConfig(vps,sps,pps) }
        // No terminating Exp-Golomb one-bit: bounded decoding must reject it.
        assertThrows(IllegalArgumentException::class.java) { NalUnits.hevcConfig(byteArrayOf(1),byteArrayOf(0x42,1,1)+ByteArray(18),byteArrayOf(1)) }
    }
}
