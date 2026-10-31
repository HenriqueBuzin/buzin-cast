package io.github.jqssun.airplay.media

import android.media.*
import io.buzincast.media.*
import io.github.jqssun.airplay.renderer.VideoRenderer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class RelayPlayback(private val renderer: VideoRenderer, private val videoSize: (Int, Int) -> Unit) {
    private val audioQueue = ArrayBlockingQueue<MirrorPacket.Audio>(64)
    @Volatile private var active = false
    private var audioThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var base = Long.MIN_VALUE
    @Volatile private var clock = 0L
    private var width = 0
    private var height = 0
    private var hevc = false

    @Synchronized fun accept(packet: MirrorPacket) {
        if (!active) {
            active = true
            audioThread = Thread({
                try {
                    while (active) {
                        val audio = audioQueue.poll(300, TimeUnit.MILLISECONDS) ?: continue
                        require(audio.sampleRate == 44100 && audio.channels == 2)
                        if (audioTrack == null) {
                            val minimum = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                            audioTrack = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                                .setBufferSizeInBytes(maxOf(minimum * 4, 32768)).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
                        }
                        val waitNs = if (base != Long.MIN_VALUE) clock + audio.ptsNs - base - System.nanoTime() - 60_000_000 else 0
                        if (waitNs in 1..500_000_000) Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
                        audioTrack?.write(audio.data, 0, audio.data.size)
                    }
                } catch (_: Exception) { active = false }
                finally { runCatching { audioTrack?.stop() }; audioTrack?.release(); audioTrack = null }
            }, "buzin-relay-audio").apply { start() }
        }
        when (packet) {
            is MirrorPacket.Video -> {
                if (width != packet.width || height != packet.height || hevc != packet.hevc) {
                    width = packet.width; height = packet.height; hevc = packet.hevc
                    renderer.selectDecoders(width, height, 30, if (hevc) true else false)
                    renderer.setResolution(width, height); renderer.startSession(); videoSize(width, height)
                }
                if (base == Long.MIN_VALUE) { base = packet.ptsNs; clock = System.nanoTime() + 120_000_000 }
                renderer.feedFrame(packet.data, clock + packet.ptsNs - base, packet.hevc)
            }
            is MirrorPacket.Audio -> check(audioQueue.offer(packet)) { "Buffer de retransmissão cheio" }
            MirrorPacket.End -> stop()
        }
    }
    @Synchronized fun stop(stopVideo: Boolean = true) {
        active = false; audioQueue.clear(); audioThread?.interrupt(); audioThread?.join(1000); audioThread = null
        if (stopVideo) renderer.stopSession()
        base = Long.MIN_VALUE; width = 0; height = 0
    }
}
