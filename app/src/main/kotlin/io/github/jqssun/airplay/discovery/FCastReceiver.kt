package io.github.jqssun.airplay.discovery

import android.os.Handler
import android.os.Looper
import io.buzincast.media.FCastFrame
import android.content.Context
import io.buzincast.core.*
import io.leancast.app.Net
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in LAN receiver implementing the URL playback subset of FCast v2 (not Google Cast). */
class FCastReceiver(private val context: Context, private val playback: ReceiverPlaybackPort, private val name: String, private val log: (String) -> Unit,
    private val requestedPort: Int = 46899,
) : ReceiverAdapter {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var listenerThread: Thread? = null
    @Volatile private var clientThread: Thread? = null
    internal val listeningPort: Int get() = listener?.localPort?.takeIf { it > 0 } ?: 0
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
            val owns = playback.mediaOwner == Protocol.FCAST && playback.mediaActive
            val info = playback.playbackInfo
            state = FCastFrame(6, JSONObject().put("generationTime", System.currentTimeMillis())
                .put("time", if (owns) info.positionMs / 1000.0 else 0.0)
                .put("duration", if (owns) info.durationMs.coerceAtLeast(0) / 1000.0 else 0.0)
                .put("state", if (!owns) 0 else if (info.playing) 1 else 2)
                .put("speed", if (owns) info.speed.toDouble() else 1.0).toString())
            main.postDelayed(this, 500)
        }
    }

    @Synchronized override fun start() {
        if (closed || listener != null) return
        try {
            val ip = Net.lanIpv4() ?: error("No LAN IPv4")
            val server = ServerSocket().apply { reuseAddress = true }
            listener = server
            server.bind(InetSocketAddress(ip, requestedPort), 1)
            val port = server.localPort
            discovery = NsdServiceManager(context).also {
                it.acquireMulticastLock()
                it.register(listOf(MdnsService(name, "_fcast._tcp", port, mapOf("v" to "2", "appName" to "Buzin Cast"))))
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
                    Thread({ session(socket) }, "buzin-fcast-client").also {
                        it.isDaemon = true; clientThread = it; it.start()
                    }
                }
            }, "buzin-fcast-listener").also {
                it.isDaemon = true; listenerThread = it; it.start()
            }
            log("FCast v2 experimental listening on $ip:$port")
        } catch (e: Exception) { log("FCast start failed: ${e.message}"); close() }
    }

    private fun send(socket: Socket, frame: FCastFrame) = synchronized(writes) {
        writeStartedAt = android.os.SystemClock.elapsedRealtime()
        try { frame.writeTo(socket.getOutputStream()) } finally { writeStartedAt = 0L }
    }
    private fun session(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            if (!playback.authorize("FCast", socket.inetAddress.hostAddress.orEmpty())) return
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
        check(playback.activeConnections == 0 && !playback.relayReceiving) { "Another screen session is active" }
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
            playback.load(MediaRequest(url, Protocol.FCAST, time.toFloat(), mime, headers))
            volume = 1.0
            playback.speed(Protocol.FCAST, speed.toFloat())
        } else {
            check(playback.mediaOwner == Protocol.FCAST && playback.mediaActive) { "No FCast media is playing" }
            when (opcode) {
                2 -> playback.playing(Protocol.FCAST, false)
                3 -> playback.playing(Protocol.FCAST, true)
                4 -> playback.stop(Protocol.FCAST)
                5 -> playback.seek(Protocol.FCAST, number(body, "time", 0.0, 31536000.0).toFloat())
                8 -> { volume = number(body, "volume", 0.0, 1.0); playback.gain(Protocol.FCAST, volume.toFloat()) }
                10 -> playback.speed(Protocol.FCAST, number(body, "speed", 0.25, 4.0).toFloat())
            }
        }
    }

    @Synchronized override fun close() {
        closed = true
        runCatching { listener?.close() }; listener = null
        runCatching { client?.close() }
        ticker.shutdownNow()
        // Android retains a socket's native descriptor while accept/read is
        // unwinding. Wait for those owners before a replacement binds the port.
        awaitShutdown(listenerThread); listenerThread = null
        // accept() can return between the closed check and assigning client.
        runCatching { client?.close() }
        awaitShutdown(clientThread); clientThread = null
        client = null
        main.removeCallbacks(update)
        discovery?.release(); discovery = null
    }
    private fun awaitShutdown(thread: Thread?) {
        if (thread == null || thread === Thread.currentThread()) return
        try {
            thread.join(1000)
            if (thread.isAlive) log("FCast worker shutdown timed out: ${thread.name}")
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
}
