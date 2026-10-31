package io.buzincast.media

import java.io.*
import java.nio.ByteBuffer

/** Streaming Matroska muxer: AVC/HEVC plus stereo PCM, without re-encoding. */
class MatroskaRecorder(private val file: File) : Closeable {
    private var output: RandomAccessFile? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var vps: ByteArray? = null
    private var firstPts = 0L
    private var clusterMs = Long.MIN_VALUE
    private var durationOffset = 0L
    private var lastMs = 0L
    private var codec: Boolean? = null
    private var width = 0
    private var height = 0
    var videoFrames = 0L; private set
    var audioPackets = 0L; private set

    fun write(packet: MirrorPacket) {
        when (packet) {
            is MirrorPacket.Video -> {
                val nals = NalUnits.split(packet.data)
                nals.forEach { nal -> when (NalUnits.type(nal, packet.hevc)) {
                    if (packet.hevc) 32 else -1 -> vps = nal
                    if (packet.hevc) 33 else 7 -> sps = nal
                    if (packet.hevc) 34 else 8 -> pps = nal
                } }
                val key = NalUnits.isKeyframe(nals, packet.hevc)
                if (output == null) {
                    if (!key || sps == null || pps == null || (packet.hevc && vps == null)) return
                    codec = packet.hevc; width = packet.width; height = packet.height; firstPts = packet.ptsNs
                    begin(if (packet.hevc) NalUnits.hevcConfig(vps!!, sps!!, pps!!) else NalUnits.avcConfig(sps!!, pps!!))
                }
                check(codec == packet.hevc && width == packet.width && height == packet.height) { "Video format changed; recording stopped" }
                val content = nals.filter { if (packet.hevc) NalUnits.type(it, true) !in 32..34 else NalUnits.type(it, false) !in setOf(7, 8) }
                if (content.isNotEmpty()) { block(1, packet.ptsNs, NalUnits.lengthPrefixed(content), key); videoFrames++ }
            }
            is MirrorPacket.Audio -> {
                if (output == null || packet.ptsNs < firstPts) return
                require(packet.sampleRate == 44100 && packet.channels == 2) { "Unsupported recording audio format" }
                block(2, packet.ptsNs, packet.data, true); audioPackets++
                lastMs = maxOf(lastMs, (packet.ptsNs - firstPts) / 1_000_000 + packet.data.size.toLong() * 1000 / (packet.sampleRate * packet.channels * 2))
            }
            MirrorPacket.End -> close()
        }
    }

    private fun begin(privateData: ByteArray) {
        file.parentFile?.mkdirs()
        val out = RandomAccessFile(file, "rw"); output = out
        out.setLength(0)
        out.write(element(0x1a45dfa3, uint(0x4286, 1), uint(0x42f7, 1), uint(0x42f2, 4), uint(0x42f3, 8), text(0x4282, "matroska"), uint(0x4287, 4), uint(0x4285, 2)))
        out.write(id(0x18538067)); out.write(UNKNOWN_SIZE)
        val infoPrefix = uint(0x2ad7b1, 1_000_000) + text(0x4d80, "Buzin Cast") + text(0x5741, "Buzin Cast")
        val duration = element(0x4489, ByteArray(8))
        val info = element(0x1549a966, infoPrefix, duration)
        durationOffset = out.filePointer + info.size - 8
        out.write(info)
        out.write(element(0x1654ae6b,
            element(0xae, uint(0xd7, 1), uint(0x73c5, 1), uint(0x83, 1), text(0x86, if (codec == true) "V_MPEGH/ISO/HEVC" else "V_MPEG4/ISO/AVC"), element(0x63a2, privateData),
                element(0xe0, uint(0xb0, width.toLong()), uint(0xba, height.toLong()))),
            element(0xae, uint(0xd7, 2), uint(0x73c5, 2), uint(0x83, 2), text(0x86, "A_PCM/INT/LIT"),
                element(0xe1, element(0xb5, ByteBuffer.allocate(8).putDouble(44100.0).array()), uint(0x9f, 2), uint(0x6264, 16)))
        ))
    }

    private fun block(track: Int, ptsNs: Long, payload: ByteArray, key: Boolean) {
        val out = output ?: return
        val ms = ((ptsNs - firstPts) / 1_000_000).coerceAtLeast(0)
        if (clusterMs == Long.MIN_VALUE || ms - clusterMs > 5000 || ms - clusterMs < -32000) {
            clusterMs = ms
            out.write(id(0x1f43b675)); out.write(UNKNOWN_SIZE); out.write(uint(0xe7, ms))
        }
        val relative = ms - clusterMs; require(relative in -32768..32767)
        val header = byteArrayOf((0x80 or track).toByte(), (relative.toInt() shr 8).toByte(), relative.toByte(), if (key) 0x80.toByte() else 0)
        out.write(element(0xa3, header, payload)); lastMs = maxOf(lastMs, ms)
    }

    override fun close() {
        val out = output ?: return
        output = null
        try { out.seek(durationOffset); out.writeDouble(lastMs.toDouble()); out.fd.sync() }
        finally { out.close() }
    }

    companion object {
        private val UNKNOWN_SIZE = byteArrayOf(1, -1, -1, -1, -1, -1, -1, -1)
        private fun id(value: Long): ByteArray {
            val size = (1..8).first { value ushr (it * 8).coerceAtMost(63) == 0L || it == 8 }
            return ByteArray(size) { (value ushr (8 * (size - it - 1))).toByte() }
        }
        private fun size(value: Long): ByteArray {
            val count = (1..8).first { value < (1L shl (7 * it)) - 1 }
            val bytes = ByteArray(count) { (value ushr (8 * (count - it - 1))).toByte() }
            bytes[0] = (bytes[0].toInt() or (1 shl (8 - count))).toByte()
            return bytes
        }
        private fun element(tag: Long, vararg content: ByteArray): ByteArray {
            val length = content.sumOf { it.size }
            return ByteArrayOutputStream().also { out -> out.write(id(tag)); out.write(size(length.toLong())); content.forEach(out::write) }.toByteArray()
        }
        private fun text(tag: Long, value: String) = element(tag, value.toByteArray(Charsets.UTF_8))
        private fun uint(tag: Long, value: Long) = element(tag, id(value))
    }
}
