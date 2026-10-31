package io.buzincast.media

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object NalUnits {
    fun split(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i + 2 < data.size) {
            val length = if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) 3
                else if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) 4 else 0
            } else 0
            if (length > 0) { starts += i to length; i += length } else i++
        }
        return starts.mapIndexedNotNull { index, (start, length) ->
            val end = starts.getOrNull(index + 1)?.first ?: data.size
            if (end > start + length) data.copyOfRange(start + length, end) else null
        }
    }
    fun type(nal: ByteArray, hevc: Boolean) = if (hevc) (nal[0].toInt() ushr 1) and 63 else nal[0].toInt() and 31
    fun isKeyframe(nals: List<ByteArray>, hevc: Boolean) = nals.any {
        val type = type(it, hevc)
        if (hevc) type in 16..21 else type == 5
    }
    fun lengthPrefixed(nals: List<ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out -> nals.forEach { out.writeInt(it.size); out.write(it) } }
    }.toByteArray()

    fun avcConfig(sps: ByteArray, pps: ByteArray): ByteArray {
        require(sps.size >= 4 && pps.isNotEmpty() && sps.size <= 65535 && pps.size <= 65535)
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeByte(1); out.writeByte(sps[1].toInt()); out.writeByte(sps[2].toInt()); out.writeByte(sps[3].toInt())
            out.writeByte(255); out.writeByte(225); out.writeShort(sps.size); out.write(sps)
            out.writeByte(1); out.writeShort(pps.size); out.write(pps)
        } }.toByteArray()
    }

    fun hevcConfig(vps: ByteArray, sps: ByteArray, pps: ByteArray): ByteArray {
        require(vps.isNotEmpty() && sps.size >= 15 && pps.isNotEmpty())
        val rbsp = ByteArrayOutputStream()
        var zeros = 0
        for (b in sps.drop(2)) {
            if (zeros >= 2 && b == 3.toByte()) { zeros = 0; continue }
            rbsp.write(b.toInt()); zeros = if (b == 0.toByte()) zeros + 1 else 0
        }
        val bits = Bits(rbsp.toByteArray())
        bits.read(4); val subLayers = bits.read(3); val nested = bits.read(1)
        val profile = ByteArray(12) { bits.read(8).toByte() }
        val profileFlags = BooleanArray(subLayers); val levelFlags = BooleanArray(subLayers)
        repeat(subLayers) { profileFlags[it] = bits.read(1) == 1; levelFlags[it] = bits.read(1) == 1 }
        if (subLayers > 0) repeat(8 - subLayers) { bits.read(2) }
        repeat(subLayers) { if (profileFlags[it]) repeat(11) { bits.read(8) }; if (levelFlags[it]) bits.read(8) }
        bits.ue(); val chroma = bits.ue(); if (chroma == 3) bits.read(1)
        bits.ue(); bits.ue(); if (bits.read(1) == 1) repeat(4) { bits.ue() }
        val luma = bits.ue(); val chromaDepth = bits.ue()
        require(chroma in 0..3 && luma in 0..7 && chromaDepth in 0..7)
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeByte(1); out.write(profile)
            out.writeShort(0xf000); out.writeByte(0xfc); out.writeByte(0xfc or chroma)
            out.writeByte(0xf8 or luma); out.writeByte(0xf8 or chromaDepth); out.writeShort(0)
            out.writeByte(((subLayers + 1) shl 3) or (nested shl 2) or 3); out.writeByte(3)
            listOf(32 to vps, 33 to sps, 34 to pps).forEach { (type, nal) ->
                require(nal.size <= 65535)
                out.writeByte(0x80 or type); out.writeShort(1); out.writeShort(nal.size); out.write(nal)
            }
        } }.toByteArray()
    }

    private class Bits(private val bytes: ByteArray) {
        private var position = 0
        fun read(n: Int): Int {
            require(n <= 31 && position + n <= bytes.size * 8)
            var result = 0
            repeat(n) { result = (result shl 1) or ((bytes[position / 8].toInt() ushr (7 - position % 8)) and 1); position++ }
            return result
        }
        fun ue(): Int {
            var zeros = 0
            while (read(1) == 0) { zeros++; require(zeros <= 24) }
            return (1 shl zeros) - 1 + read(zeros)
        }
    }
}
