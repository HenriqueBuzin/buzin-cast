package io.buzincast.core

data class PlaybackInfo(
    val positionMs: Long = 0, val durationMs: Long = 0,
    val playing: Boolean = true, val speed: Float = 1f,
    val skipSilence: Boolean = false, val buffering: Boolean = false,
)
data class MediaRequest(val location: String, val protocol: Protocol,
    val time: Float = 0f, val mime: String? = null, val headers: Map<String, String> = emptyMap())

/** Protocol adapters know playback commands, not Android services, ExoPlayer or UI. */
interface ReceiverPlaybackPort {
    val activeConnections: Int
    val relayReceiving: Boolean
    val mediaOwner: Protocol?
    val mediaActive: Boolean
    val playbackInfo: PlaybackInfo
    fun authorize(protocol: String, peer: String): Boolean
    fun load(request: MediaRequest)
    fun stop(protocol: Protocol)
    fun playing(protocol: Protocol, playing: Boolean)
    fun seek(protocol: Protocol, seconds: Float)
    fun gain(protocol: Protocol, gain: Float)
    fun speed(protocol: Protocol, speed: Float)
    fun photo(location: String)
}

interface ReceiverAdapter : AutoCloseable { fun start() }
