package io.github.jqssun.airplay.discovery

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import io.github.jqssun.airplay.service.AirPlayService
import io.leancast.app.ControlServer
import io.leancast.app.Net
import io.leancast.app.SsdpAgent
import io.leancast.app.SubscriptionRegistry
import io.leancast.domain.cast.CastSession
import io.leancast.domain.cast.PlayerPort
import io.leancast.domain.cast.TransportState
import io.leancast.domain.ssdp.SsdpIdentity
import io.leancast.domain.upnp.LastChangeEvent
import io.leancast.domain.upnp.RendererDescription
import io.leancast.domain.upnp.SoapRouter
import io.leancast.domain.upnp.UpnpTime
import java.net.URI
import java.util.UUID

/** Buzin Cast integration of LeanCast's Apache-2.0 DLNA implementation.
 * Playback shares the AirPlay media player. DLNA is opt-in and has no PIN.
 */
class DlnaReceiver(
    private val service: AirPlayService,
    private val name: String,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var ownsPlayback = false
    @Volatile private var ownsPhoto = false
    private var photoStartedAt = 0L
    private var server: ControlServer? = null
    private var ssdp: SsdpAgent? = null
    private var lock: WifiManager.MulticastLock? = null
    private val events = SubscriptionRegistry()
    private val audio = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = service.getSharedPreferences("buzin_dlna", Context.MODE_PRIVATE)
    private val uuid = prefs.getString("uuid", null) ?: "uuid:${UUID.randomUUID()}".also {
        prefs.edit().putString("uuid", it).apply()
    }
    private val session: CastSession = CastSession(object : PlayerPort {
        override fun prepareAndPlay(uri: String) {
            require(URI(uri).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null }) {
                "DLNA only supports HTTP(S) media URLs"
            }
            check(service.connectionCount.value == 0) { "An AirPlay session is active" }
            main.post {
                if (!closed && service.connectionCount.value == 0) {
                    ownsPlayback = true
                    val imageMetadata = session.currentMetaData.orEmpty().let { it.contains("image/jpeg", true) || it.contains("image/png", true) }
                    if (imageMetadata || URI(uri).path.orEmpty().lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") }) {
                        ownsPlayback = false
                        ownsPhoto = true
                        photoStartedAt = System.currentTimeMillis()
                        service.showDlnaPhoto(uri)
                        session.onPlayerPrepared()
                    } else service.playDlnaVideo(uri)
                } else session.onPlayerError()
            }
        }
        override fun play() { main.post { if (ownsPlayback && !closed && service.networkPlaybackOwner == "dlna") service.airPlayVideoPlayer.setPlaying(true) } }
        override fun pause() { main.post { if (ownsPlayback && !closed && service.networkPlaybackOwner == "dlna") service.airPlayVideoPlayer.setPlaying(false) } }
        override fun seekTo(ms: Long) { main.post { if (ownsPlayback && !closed && service.networkPlaybackOwner == "dlna") service.onVideoScrub(ms / 1000f) } }
        override fun stop() {
            main.post {
                if (ownsPhoto) { ownsPhoto = false; io.github.jqssun.airplay.media.PhotoActivity.closePhoto() }
                if (ownsPlayback) {
                    ownsPlayback = false
                    if (service.networkPlaybackOwner == "dlna") service.stopVideoPlayback()
                }
            }
        }
        override fun currentPositionMs() = if (ownsPlayback) service.videoPlaybackInfo.value.positionMs else 0L
        override fun currentDurationMs() = if (ownsPlayback) service.videoPlaybackInfo.value.durationMs else 0L
        override fun isLive() = ownsPlayback && service.videoPlaybackInfo.value.durationMs <= 0
    })

    private fun body(kind: String): String = when (kind) {
        "rcs" -> LastChangeEvent.renderingControl(session.volume, mute = false)
        else -> LastChangeEvent.avTransport(
            transportState = session.state.name,
            currentUri = session.currentUri,
            currentTrackDuration = UpnpTime.renderMsToHms(session.durationMs()),
            isLive = session.isLive(),
        )
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            if (ownsPhoto && System.currentTimeMillis() - photoStartedAt > 1500 && !io.github.jqssun.airplay.media.PhotoActivity.isVisible()) {
                ownsPhoto = false; session.stop()
            }
            if (ownsPlayback) {
                if (service.connectionCount.value > 0 || service.networkPlaybackOwner != "dlna") {
                    // The native AirPlay callbacks now own the shared player.
                    ownsPlayback = false
                    session.onPlayerError()
                } else if (!service.videoPlaybackActive.value) {
                    ownsPlayback = false
                    session.stop()
                } else {
                    val info = service.videoPlaybackInfo.value
                    if (!info.buffering && info.playing) session.onPlayerPrepared()
                    if (!info.playing && session.state == TransportState.PLAYING) session.pause()
                    if (info.playing && session.state == TransportState.PAUSED_PLAYBACK) session.resume()
                }
            }
            main.postDelayed(this, 500)
        }
    }

    @Synchronized
    fun start() {
        if (closed || server != null) return
        try {
            val ip = Net.lanIpv4() ?: error("No local IPv4 network for DLNA; restart the receiver after connecting")
            val wifi = service.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("buzin-dlna").apply { setReferenceCounted(false); acquire() }
            session.addListener { events.publish("avt", body("avt")) }
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            session.setVolume(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
            session.setVolumeSink { percent ->
                main.post {
                    if (!closed) {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * max / 100f).toInt(), 0)
                        events.publish("rcs", body("rcs"))
                    }
                }
            }
            val token = "Android UPnP/1.0 BuzinCast/0.1"
            val control = ControlServer(
                port = 0,
                descriptionProvider = {
                    RendererDescription(friendlyName = "$name (DLNA)", uuid = uuid,
                        manufacturer = "Buzin Cast", modelName = "Buzin Cast",
                        baseUrl = "http://$ip:${server?.boundPort ?: 0}")
                },
                router = SoapRouter(session), events = events,
                eventBody = ::body, serverToken = token,
                authorize = { peer -> service.authorizeConnection("DLNA", peer) },
            )
            server = control
            control.start()
            ssdp = SsdpAgent(SsdpIdentity(uuid, "http://$ip:${control.boundPort}/desc.xml", token)).also { it.start() }
            main.post(tick)
            log("DLNA experimental receiver started on $ip:${control.boundPort}")
        } catch (e: Exception) {
            log("DLNA start failed: ${e.message}")
            close()
        }
    }

    @Synchronized
    fun close() {
        closed = true
        if (ownsPhoto) { ownsPhoto = false; main.post { io.github.jqssun.airplay.media.PhotoActivity.closePhoto() } }
        main.removeCallbacks(tick)
        ssdp?.stop()
        ssdp = null
        server?.stop()
        server = null
        events.shutdown()
        lock?.takeIf { it.isHeld }?.release()
        lock = null
        ownsPlayback = false
    }
}
