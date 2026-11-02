package io.github.jqssun.airplay.renderer

import io.buzincast.core.SessionToken
import io.buzincast.core.PlaybackRecovery
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

// rate is 0 while buffering; speed is the configured rate regardless of pause state
// native reads the effective rate, overlay reads playWhenReady + speed + skipSilence
data class PlaybackSnapshot(
    val position: Float,
    val duration: Float,
    val rate: Float,
    val ready: Boolean,
    val playWhenReady: Boolean,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val buffering: Boolean = false,
)

// exoplayer calls stay on the main thread; native only reads the onPlaybackInfo snapshot
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AirPlayVideoPlayer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var currentToken: SessionToken? = null
    private var activeListener: Player.Listener? = null
    private var pendingSurface: Surface? = null
    private var clientGain = 1f
    val subtitleText = kotlinx.coroutines.flow.MutableStateFlow("")
    private val recovery = PlaybackRecovery()
    private val retry = Runnable { player?.prepare() }
    var recoveryEnabled: () -> Boolean = { true }
    var onRecovery: ((SessionToken, String) -> Unit)? = null

    var onPlaybackInfo: ((SessionToken, PlaybackSnapshot) -> Unit)? = null
    var onVideoSize: ((SessionToken, Int, Int, Float) -> Unit)? = null
    var onTitle: ((SessionToken, String?) -> Unit)? = null
    var onEnded: ((SessionToken) -> Unit)? = null
    var onError: ((SessionToken, String) -> Unit)? = null

    private val _reportTick = object : Runnable {
        override fun run() {
            _reportPlaybackInfo()
            mainHandler.postDelayed(this, REPORT_INTERVAL_MS)
        }
    }

    private fun listener(token: SessionToken) = object : Player.Listener {
        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            if (currentToken != token) return
            subtitleText.value = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n").take(4000)
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if (currentToken != token) return
            Log.w(TAG, "playback error", error)
            val delay = recovery.nextDelay(error.errorCode, recoveryEnabled())
            if (delay != null) {
                onRecovery?.invoke(token, "Conexão interrompida. Tentando retomar vídeo (${recovery.attempt}/3)…")
                mainHandler.postDelayed(retry, delay)
                return
            }
            onError?.invoke(token, error.errorCodeName)
            onEnded?.invoke(token)
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (currentToken != token) return
            if (state == Player.STATE_READY) onRecovery?.invoke(token, "")
            if (state == Player.STATE_ENDED) onEnded?.invoke(token)
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (currentToken != token) return
            // duration is usually established here (esp. hls): report so the held /play releases
            _reportPlaybackInfo()
        }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            if (currentToken != token) return
            onTitle?.invoke(token, mediaMetadata.title?.toString())
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (currentToken != token) return
            if (videoSize.height == 0) return
            val width = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
            onVideoSize?.invoke(token, width, videoSize.height, width.toFloat() / videoSize.height)
        }
    }

    fun play(location: String, startPositionSeconds: Float, mime: String? = null, headers: Map<String, String> = emptyMap(), token: SessionToken, resetGain: Boolean = true) = mainHandler.post {
        // recycling must not report the stopped sentinel: senders poll right after /play
        _stopInternal(reportStopped = false)
        currentToken = token
        if (resetGain) clientGain = 1f
        val sessionListener = listener(token).also { activeListener = it }
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
        val p = ExoPlayer.Builder(context).setMediaSourceFactory(sources).build().also {
            it.addListener(sessionListener)
            pendingSurface?.let { s -> it.setVideoSurface(s) }
            it.volume = clientGain
        }
        player = p
        p.setMediaItem(MediaItem.Builder().setUri(location).setMimeType(mime).build(), (startPositionSeconds * 1000).toLong())
        p.playWhenReady = true
        p.prepare()
        onPlaybackInfo?.invoke(token, PlaybackSnapshot(startPositionSeconds, 0f, 0f, false, true))
        mainHandler.postDelayed(_reportTick, REPORT_INTERVAL_MS)
    }

    fun scrub(positionSeconds: Float, expected: SessionToken? = null) = mainHandler.post {
        if (expected != null && currentToken != expected) return@post
        player?.seekTo((positionSeconds * 1000).toLong())
    }

    fun setSubtitle(uri: android.net.Uri?, mime: String = "text/vtt") = mainHandler.post {
        val p = player ?: return@post
        val item = p.currentMediaItem ?: return@post
        val subtitles = if (uri == null) emptyList() else listOf(
            MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime)
                .setLanguage("und").setLabel("Legenda externa").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build())
        val time = p.currentPosition
        val playing = p.playWhenReady
        p.setMediaItem(item.buildUpon().setSubtitleConfigurations(subtitles).build(),time)
        p.prepare(); p.playWhenReady = playing
    }

    // coalesces the seek spam from drag-seeking
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun setScrubbing(enabled: Boolean) = mainHandler.post {
        player?.isScrubbingModeEnabled = enabled
    }

    fun setRate(rate: Float) = mainHandler.post {
        val p = player ?: return@post
        if (rate <= 0f) {
            p.playWhenReady = false
        } else {
            p.playbackParameters = PlaybackParameters(rate.coerceAtLeast(0.1f))
            p.playWhenReady = true
        }
    }

    // local-only: the sender self-syncs from its next /playback-info poll
    fun setPlaying(playing: Boolean, expected: SessionToken? = null) = mainHandler.post {
        if (expected != null && currentToken != expected) return@post
        player?.playWhenReady = playing
    }

    // local speed toggle: unlike setRate it must not resume a paused player
    fun setSpeed(speed: Float, expected: SessionToken? = null) = mainHandler.post {
        if (expected != null && currentToken != expected) return@post
        player?.playbackParameters = PlaybackParameters(speed.coerceAtLeast(0.1f))
    }

    // linear, unity unless sender volume isn't synced to receiver
    fun setClientGain(gain: Float, expected: SessionToken? = null) = mainHandler.post {
        if (expected != null && currentToken != expected) return@post
        clientGain = gain
        player?.volume = gain
    }

    fun setSkipSilence(enabled: Boolean) = mainHandler.post {
        player?.skipSilenceEnabled = enabled
    }

    fun seekBy(deltaMs: Long) = mainHandler.post {
        val p = player ?: return@post
        var target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        val duration = p.duration
        if (duration != C.TIME_UNSET) {
            target = target.coerceAtMost(duration)
        }
        p.seekTo(target)
    }

    fun setSurface(surface: Surface) = mainHandler.post {
        pendingSurface = surface
        player?.setVideoSurface(surface)
    }

    // no-op if a newer surface already replaced this one
    fun clearSurface(surface: Surface) = mainHandler.post {
        if (pendingSurface !== surface) return@post
        pendingSurface = null
        player?.setVideoSurface(null)
    }

    fun stop(expected: SessionToken? = null) = mainHandler.post {
        if (expected == null || currentToken == expected) _stopInternal(reportStopped = true)
    }

    private fun _stopInternal(reportStopped: Boolean) {
        val stoppedToken = currentToken
        currentToken = null
        subtitleText.value = ""
        mainHandler.removeCallbacks(retry)
        recovery.reset()
        mainHandler.removeCallbacks(_reportTick)
        player?.let {
            activeListener?.let(it::removeListener)
            it.release()
        }
        player = null
        activeListener = null
        // duration=-1 is the "video finished" sentinel for the playback-info handler
        if (reportStopped && stoppedToken != null) {
            onPlaybackInfo?.invoke(stoppedToken, PlaybackSnapshot(0f, -1f, 0f, false, false))
        }
    }

    private fun _reportPlaybackInfo() {
        val token = currentToken ?: return
        val p = player ?: return
        val durationMs = p.duration
        val position = p.currentPosition / 1000f
        // 0 = live/unknown (TIME_UNSET); a real value only exists for vod
        val duration = if (durationMs == C.TIME_UNSET) 0f else durationMs / 1000f
        val rate = if (p.playWhenReady && p.playbackState == Player.STATE_READY) p.playbackParameters.speed else 0f
        // readyToPlay = the timeline is established, NOT exoplayer's buffering state: for vod that means the duration is known; for live there is no duration so being playable is enough. the sender holds its timeline (and /play) until this, so it must not go true early
        val ready = if (p.isCurrentMediaItemLive) p.playbackState == Player.STATE_READY
                    else durationMs != C.TIME_UNSET
        onPlaybackInfo?.invoke(token,
            PlaybackSnapshot(
                position = position,
                duration = duration,
                rate = rate,
                ready = ready,
                playWhenReady = p.playWhenReady,
                speed = p.playbackParameters.speed,
                skipSilence = p.skipSilenceEnabled,
                buffering = p.playbackState == Player.STATE_BUFFERING,
            )
        )
    }

    companion object {
        private const val TAG = "AirPlayVideoPlayer"
        private const val REPORT_INTERVAL_MS = 250L
    }
}
