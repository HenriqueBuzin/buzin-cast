package io.buzincast.media

import java.io.*

sealed class MirrorPacket {
    data class Video(val data: ByteArray, val ptsNs: Long, val width: Int, val height: Int, val hevc: Boolean) : MirrorPacket()
    data class Audio(val data: ByteArray, val ptsNs: Long, val sampleRate: Int = 44100, val channels: Int = 2) : MirrorPacket()
    data object End : MirrorPacket()
}

object MirrorPacketCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    fun encode(packet: MirrorPacket): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            when (packet) {
                is MirrorPacket.Video -> {
                    require(packet.width in 1..8192 && packet.height in 1..8192)
                    require(packet.data.size in 5..MAX_BYTES)
                    out.writeByte(1); out.writeLong(packet.ptsNs)
                    out.writeInt(packet.width); out.writeInt(packet.height); out.writeBoolean(packet.hevc)
                    out.writeInt(packet.data.size); out.write(packet.data)
                }
                is MirrorPacket.Audio -> {
                    require(packet.sampleRate in 8000..192000 && packet.channels in 1..8)
                    require(packet.data.isNotEmpty() && packet.data.size <= 128 * 1024 && packet.data.size % (2 * packet.channels) == 0)
                    out.writeByte(2); out.writeLong(packet.ptsNs)
                    out.writeInt(packet.sampleRate); out.writeInt(packet.channels)
                    out.writeInt(packet.data.size); out.write(packet.data)
                }
                MirrorPacket.End -> out.writeByte(3)
            }
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): MirrorPacket = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(bytes.size <= MAX_BYTES + 32)
        val result = when (input.readUnsignedByte()) {
            1 -> {
                val pts = input.readLong(); val w = input.readInt(); val h = input.readInt(); val hevc = input.readBoolean()
                require(w in 1..8192 && h in 1..8192)
                val size = input.readInt(); require(size in 5..MAX_BYTES && size == input.available())
                MirrorPacket.Video(ByteArray(size).also(input::readFully), pts, w, h, hevc)
            }
            2 -> {
                val pts = input.readLong(); val rate = input.readInt(); val channels = input.readInt(); val size = input.readInt()
                require(rate in 8000..192000 && channels in 1..8)
                require(size in 1..128 * 1024 && size % (2 * channels) == 0 && size == input.available())
                MirrorPacket.Audio(ByteArray(size).also(input::readFully), pts, rate, channels)
            }
            3 -> MirrorPacket.End
            else -> error("Unknown Buzin Cast packet")
        }
        require(input.available() == 0)
        result
    }
}
