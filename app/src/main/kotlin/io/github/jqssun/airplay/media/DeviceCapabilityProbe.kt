package io.github.jqssun.airplay.media

import android.media.MediaCodecList
import android.os.Build
import io.buzincast.core.DeviceCapabilities
import io.buzincast.core.VideoMode

/** Query the actual AVC decoder; no inference from TV panel marketing resolution. */
object DeviceCapabilityProbe {
    private val candidates = listOf(VideoMode(1280,720,30), VideoMode(1280,720,60), VideoMode(1920,1080,30), VideoMode(1920,1080,60))
    fun read(): DeviceCapabilities {
        val modes = mutableSetOf<VideoMode>()
        var lowLatency = false
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals("video/avc",true) } }
                .forEach { codec -> runCatching {
                    val caps = codec.getCapabilitiesForType("video/avc")
                    val video = caps.videoCapabilities ?: return@runCatching
                    candidates.filterTo(modes) { video.areSizeAndRateSupported(it.width,it.height,it.fps.toDouble()) }
                    if (Build.VERSION.SDK_INT >= 30 && caps.isFeatureSupported("low-latency")) lowLatency = true
                } }
        }
        return DeviceCapabilities(modes, lowLatency)
    }
}
