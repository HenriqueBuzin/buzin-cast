package io.buzincast.media

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MatroskaBoundaryTest {
    private fun units(hevc:Boolean):List<ByteArray> = javaClass.getResourceAsStream("/mirror/sample."+if(hevc)"h265" else "h264")!!.use { NalUnits.split(it.readBytes()) }
    private fun packet(nals:List<ByteArray>,pts:Long=1_000_000_000L,hevc:Boolean=false,width:Int=64,height:Int=48)=MirrorPacket.Video(
        nals.fold(byteArrayOf()){bytes,nal->bytes+byteArrayOf(0,0,0,1)+nal},pts,width,height,hevc)
    private fun parameters(hevc:Boolean)=units(hevc).filter { NalUnits.type(it,hevc) in if(hevc)32..34 else 7..8 }.distinctBy { NalUnits.type(it,hevc) }
    private fun key(hevc:Boolean)=units(hevc).first { NalUnits.isKeyframe(listOf(it),hevc) }

    @Test fun `recording waits for complete keyframe and ignores audio before its timeline`() {
        val dir=Files.createTempDirectory("buzin-matroska").toFile()
        try {
            for(hevc in listOf(false,true)) {
                val file=File(dir,"pending-$hevc.mkv")
                MatroskaRecorder(file).use { recorder ->
                    recorder.write(MirrorPacket.Audio(ByteArray(8),0))
                    recorder.write(packet(emptyList(),hevc=hevc))
                    recorder.write(packet(listOf(key(hevc)),hevc=hevc))
                    assertFalse(file.exists())
                    // Exercise every missing parameter independently before the keyframe.
                    val parameterSets=parameters(hevc)
                    for(parameter in parameterSets) recorder.write(packet(listOf(parameter),hevc=hevc))
                    assertFalse(file.exists())
                    recorder.write(packet(listOf(key(hevc)),hevc=hevc))
                    assertEquals(1L,recorder.videoFrames)
                    recorder.write(MirrorPacket.Audio(ByteArray(8),999_999_999L))
                    assertEquals(0L,recorder.audioPackets)
                    recorder.write(MirrorPacket.Audio(ByteArray(8),1_000_000_000L))
                    assertEquals(1L,recorder.audioPackets)
                    recorder.write(packet(parameterSets,hevc=hevc))
                    assertEquals(1L,recorder.videoFrames)
                    recorder.write(MirrorPacket.End)
                    recorder.close()
                    assertTrue(file.length()>100)
                }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `recording rejects format changes and unsupported audio without counting packets`() {
        val file=Files.createTempFile("buzin-matroska-formats",".mkv").toFile()
        try {
            MatroskaRecorder(file).use { recorder ->
                val nals=parameters(false)+listOf(key(false))
                recorder.write(packet(nals))
                for(changed in listOf(packet(nals,hevc=true),packet(nals,width=128),packet(nals,height=96)))
                    assertThrows(IllegalStateException::class.java) { recorder.write(changed) }
                for(audio in listOf(MirrorPacket.Audio(ByteArray(8),1_000_000_000L,sampleRate=48000),MirrorPacket.Audio(ByteArray(8),1_000_000_000L,channels=1)))
                    assertThrows(IllegalArgumentException::class.java) { recorder.write(audio) }
                assertEquals(1L,recorder.videoFrames)
                assertEquals(0L,recorder.audioPackets)
            }
        } finally { file.delete() }
    }

    @Test fun `keyframe missing any required parameter set cannot create a file`() {
        val dir=Files.createTempDirectory("buzin-matroska-incomplete").toFile()
        try {
            for(hevc in listOf(false,true)) {
                val parameters=parameters(hevc)
                for(omitted in parameters.indices) {
                    val file=File(dir,"missing-$hevc-$omitted.mkv")
                    MatroskaRecorder(file).use { recorder ->
                        recorder.write(packet(parameters.filterIndexed { index,_->index!=omitted }+listOf(key(hevc)),hevc=hevc))
                        assertEquals(0L,recorder.videoFrames)
                        assertFalse(file.exists())
                    }
                }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `large forward and backward timestamps start fresh bounded clusters`() {
        val file=Files.createTempFile("buzin-matroska-clusters",".mkv").toFile()
        try {
            MatroskaRecorder(file).use { recorder ->
                val nals=parameters(false)+listOf(key(false))
                for(time in listOf(1_000_000_000L,41_000_000_000L,1_000_000_000L)) recorder.write(packet(nals,time))
                assertEquals(3L,recorder.videoFrames)
            }
            val bytes=file.readBytes()
            val cluster=byteArrayOf(0x1f,0x43,0xb6.toByte(),0x75)
            assertEquals(3,(0..bytes.size-cluster.size).count { bytes.copyOfRange(it,it+cluster.size).contentEquals(cluster) })
        } finally { file.delete() }
    }

    @Test fun `relative file paths and exact signed cluster boundaries remain valid`() {
        val file=File("buzin-recording-${java.util.UUID.randomUUID()}.mkv")
        assertFalse(file.exists())
        try {
            MatroskaRecorder(file).use { recorder ->
                val nals=parameters(false)+listOf(key(false))
                for(ms in listOf(0L,5000L,5001L,40000L,8000L,7999L))
                    recorder.write(packet(nals,1_000_000_000L+ms*1_000_000L))
                assertEquals(6L,recorder.videoFrames)
            }
            val bytes=file.readBytes()
            val cluster=byteArrayOf(0x1f,0x43,0xb6.toByte(),0x75)
            assertEquals(4,(0..bytes.size-cluster.size).count { bytes.copyOfRange(it,it+cluster.size).contentEquals(cluster) })
        } finally { file.delete() }
    }
}
