package io.github.jqssun.airplay.discovery

import android.os.Handler
import android.os.Looper
import io.buzincast.media.FCastFrame
import io.github.jqssun.airplay.service.AirPlayService
import io.leancast.app.Net
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in LAN receiver implementing the URL playback subset of FCast v2 (not Google Cast). */
class FCastReceiver(private val service: AirPlayService, private val name: String, private val log: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private val writes = Any()
    private var discovery: NsdServiceManager? = null
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var state = FCastFrame(6, "{\"generationTime\":0,\"time\":0,\"duration\":0,\"state\":0,\"speed\":1}")
    @Volatile private var volume = 1.0
    private var lastRequestAt = 0L
    @Volatile private var writeStartedAt = 0L
    private val pendingErrors = java.util.concurrent.atomic.AtomicInteger()

    private val update = object : Runnable {
        override fun run() {
            if (closed) return
            if (writeStartedAt != 0L && android.os.SystemClock.elapsedRealtime() - writeStartedAt > 5000) {
                runCatching { client?.close() }
            }
            val owns = service.networkPlaybackOwner == "fcast" && service.videoPlaybackActive.value
            val info = service.videoPlaybackInfo.value
            state = FCastFrame(6, JSONObject().put("generationTime", System.currentTimeMillis())
                .put("time", if (owns) info.positionMs / 1000.0 else 0.0)
                .put("duration", if (owns) info.durationMs.coerceAtLeast(0) / 1000.0 else 0.0)
                .put("state", if (!owns) 0 else if (info.playing) 1 else 2)
                .put("speed", if (owns) info.speed.toDouble() else 1.0).toString())
            main.postDelayed(this, 500)
        }
    }

    @Synchronized fun start() {
        if (closed || listener != null) return
        try {
            val ip = Net.lanIpv4() ?: error("No LAN IPv4")
            val server = ServerSocket().apply { reuseAddress = true }
            listener = server
            server.bind(InetSocketAddress(ip, 46899), 1)
            discovery = NsdServiceManager(service).also {
                it.acquireMulticastLock()
                it.register(listOf(MdnsService(name, "_fcast._tcp", 46899, mapOf("v" to "2", "appName" to "Buzin Cast"))))
            }
            main.post(update)
            ticker.scheduleAtFixedRate({
                client?.let { socket ->
                    runCatching {
                        send(socket, state)
                        send(socket, FCastFrame(7, JSONObject().put("generationTime", System.currentTimeMillis()).put("volume", volume).toString()))
                    }.onFailure { runCatching { socket.close() } }
                }
            }, 1, 1, TimeUnit.SECONDS)
            Thread({
                while (!closed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    if (closed || !socket.inetAddress.isSiteLocalAddress || client != null) { socket.close(); continue }
                    client = socket
                    Thread({ session(socket) }, "buzin-fcast-client").apply { isDaemon = true; start() }
                }
            }, "buzin-fcast-listener").apply { isDaemon = true; start() }
            log("FCast v2 experimental listening on $ip:46899")
        } catch (e: Exception) { log("FCast start failed: ${e.message}"); close() }
    }

    private fun send(socket: Socket, frame: FCastFrame) = synchronized(writes) {
        writeStartedAt = android.os.SystemClock.elapsedRealtime()
        try { frame.writeTo(socket.getOutputStream()) } finally { writeStartedAt = 0L }
    }
    private fun session(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            if (!service.authorizeConnection("FCast", socket.inetAddress.hostAddress.orEmpty())) return
            send(socket, FCastFrame(11, "{\"version\":2}"))
            var window = System.currentTimeMillis(); var count = 0
            while (!closed && !socket.isClosed) {
                socket.soTimeout = 0 // Idle connections remain valid; partial frames have a deadline.
                val frame = FCastFrame.readFrom(socket.getInputStream()) { socket.soTimeout = 5000 } ?: break
                if (System.currentTimeMillis() - window > 1000) { window = System.currentTimeMillis(); count = 0 }
                check(++count <= 40) { "Command rate exceeded" }
                when (frame.opcode) {
                    11 -> { require(JSONObject(frame.body).getInt("version") >= 2) }
                    12 -> send(socket, FCastFrame(13))
                    13 -> Unit
                    1, 2, 3, 4, 5, 8, 10 -> {
                        val body = if (frame.body.isBlank()) JSONObject() else JSONObject(frame.body)
                        // UI/player work is serialized on main; rate limiting bounds the queue.
                        main.post {
                            if (!closed && client === socket) runCatching { command(frame.opcode, body) }.onFailure {
                                error(socket, it.message ?: "Playback command failed")
                            }
                        }
                    }
                    else -> error(socket, "Unsupported FCast v2 opcode")
                }
            }
        } catch (e: Exception) { if (!closed) log("FCast connection closed: ${e.javaClass.simpleName}") }
        finally { runCatching { socket.close() }; if (client === socket) client = null }
    }

    private fun error(socket: Socket, message: String) {
        // Never block Android's main thread on socket I/O.
        if (pendingErrors.incrementAndGet() > 16) {
            pendingErrors.decrementAndGet(); runCatching { socket.close() }; return
        }
        runCatching { ticker.execute {
            try { runCatching { send(socket, FCastFrame(9, JSONObject().put("message", message).toString())) } }
            finally { pendingErrors.decrementAndGet() }
        } }.onFailure { pendingErrors.decrementAndGet() }
    }
    fun playbackError(message: String) { client?.let { error(it, message) } }
    private fun number(body: JSONObject, key: String, min: Double, max: Double): Double =
        body.getDouble(key).also { require(it.isFinite() && it in min..max) { "Invalid $key" } }

    private fun command(opcode: Int, body: JSONObject) {
        check(service.connectionCount.value == 0 && !service.relay.state.value.receiving) { "Another screen session is active" }
        if (opcode == 1) {
            check(System.currentTimeMillis() - lastRequestAt >= 500) { "Wait before starting another item" }
            require(!body.has("content") || body.isNull("content")) { "Inline manifests are not supported; send an HTTP(S) URL" }
            val url = body.getString("url")
            require(url.length <= 8192 && URI(url).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null }) { "Expected HTTP(S) media URL" }
            val mime = body.getString("container")
            require(mime.length <= 120 && (mime.startsWith("video/") || mime.startsWith("audio/") || mime in setOf("application/dash+xml", "application/vnd.apple.mpegurl", "application/x-mpegURL"))) { "Unsupported media type" }
            val headers = mutableMapOf<String, String>()
            if (body.has("headers") && !body.isNull("headers")) {
                val values = body.getJSONObject("headers")
                require(values.length() <= 24)
                values.keys().forEach { key ->
                    val value = values.getString(key)
                    require(key.matches(Regex("[A-Za-z0-9-]{1,80}")) && value.length <= 4096 && value.none(Char::isISOControl))
                    require(key.lowercase() !in setOf("host", "connection", "content-length", "transfer-encoding"))
                    headers[key] = value
                }
            }
            val time = if (body.has("time") && !body.isNull("time")) number(body, "time", 0.0, 31536000.0) else 0.0
            val speed = if (body.has("speed") && !body.isNull("speed")) number(body, "speed", 0.25, 4.0) else 1.0
            lastRequestAt = System.currentTimeMillis()
            service.playNetworkVideo(url, "fcast", time.toFloat(), mime, headers)
            volume = 1.0
            service.airPlayVideoPlayer.setSpeed(speed.toFloat())
        } else {
            check(service.networkPlaybackOwner == "fcast" && service.videoPlaybackActive.value) { "No FCast media is playing" }
            when (opcode) {
                2 -> service.airPlayVideoPlayer.setPlaying(false)
                3 -> service.airPlayVideoPlayer.setPlaying(true)
                4 -> service.stopVideoPlayback()
                5 -> service.onVideoScrub(number(body, "time", 0.0, 31536000.0).toFloat())
                8 -> { volume = number(body, "volume", 0.0, 1.0); service.airPlayVideoPlayer.setClientGain(volume.toFloat()) }
                10 -> service.airPlayVideoPlayer.setSpeed(number(body, "speed", 0.25, 4.0).toFloat())
            }
        }
    }

    @Synchronized fun close() {
        closed = true
        runCatching { listener?.close() }; listener = null
        runCatching { client?.close() }; client = null
        ticker.shutdownNow()
        main.removeCallbacks(update)
        discovery?.release(); discovery = null
    }
}
