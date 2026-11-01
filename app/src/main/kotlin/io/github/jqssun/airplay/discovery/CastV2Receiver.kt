package io.github.jqssun.airplay.discovery

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.buzincast.media.CastEnvelope
import io.github.jqssun.airplay.service.AirPlayService
import io.leancast.app.Net
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

/** URL-media Cast V2 experiment. Does NOT impersonate a Google-certified identity.
 * Works only with senders that do not require Google's device-auth chain.
 * App namespaces, protected services and Cast Streaming mirroring are unsupported.
 */
class CastV2Receiver(private val service: AirPlayService, private val name: String, private val log: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var listener: SSLServerSocket? = null
    @Volatile private var client: SSLSocket? = null
    @Volatile private var writingSince = 0L
    private var discovery: NsdServiceManager? = null
    private var launched = false
    private var transport = ""
    private var session = ""
    private var mediaId = 0
    private var media = JSONObject()
    private var level = 1.0
    private var muted = false
    private var lastLoadAt = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (closed) return
            if (writingSince != 0L && SystemClock.elapsedRealtime() - writingSince > 5000) runCatching { client?.close() }
            main.postDelayed(this, 1000)
        }
    }

    private fun tls(): SSLContext {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "buzin-cast-v2-tls-2"
        if (!store.containsAlias(alias)) {
            val now = System.currentTimeMillis()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    // API 25 Conscrypt asks AndroidKeyStore to sign a precomputed TLS digest.
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=Buzin Cast Experimental"))
                    .setCertificateSerialNumber(BigInteger(63, SecureRandom()).add(BigInteger.ONE))
                    .setCertificateNotBefore(Date(now - 86400000L)).setCertificateNotAfter(Date(now + 315360000000L)).build())
                generateKeyPair()
            }
        }
        val key = store.getKey(alias, null) as PrivateKey
        val chain = arrayOf(store.getCertificate(alias) as X509Certificate)
        val manager = object : X509KeyManager {
            override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: java.net.Socket?) = null
            override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = if (type == "RSA") arrayOf(alias) else null
            override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: java.net.Socket?) = if (type == "RSA") alias else null
            override fun getCertificateChain(alias: String?) = chain
            override fun getPrivateKey(alias: String?) = key
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, SecureRandom()) }
    }

    @Synchronized fun start() {
        if (closed || listener != null) return
        try {
            val ip = Net.lanIpv4() ?: error("No LAN IPv4")
            val server = tls().serverSocketFactory.createServerSocket() as SSLServerSocket
            listener = server
            server.enabledProtocols = server.supportedProtocols.filter { it == "TLSv1.2" }.toTypedArray()
            // Fire TV's system receiver can hold 8009. DNS-SD advertises our actual port.
            server.bind(InetSocketAddress(ip, 0), 1)
            val prefs = service.getSharedPreferences("buzin_castv2", 0)
            val id = prefs.getString("id", null) ?: UUID.randomUUID().toString().replace("-", "").also { prefs.edit().putString("id", it).apply() }
            discovery = NsdServiceManager(service).also {
                it.acquireMulticastLock()
                it.register(listOf(MdnsService("Buzin-$id", "_googlecast._tcp", server.localPort,
                    mapOf("id" to id, "fn" to name, "md" to "Buzin Cast Experimental", "ve" to "05", "ca" to "5", "st" to "0"))))
            }
            main.post(watchdog)
            Thread({
                var acceptedAt = 0L
                while (!closed) {
                    val socket = try { server.accept() as SSLSocket } catch (_: Exception) { break }
                    val now = SystemClock.elapsedRealtime()
                    if (closed || !socket.inetAddress.isSiteLocalAddress || client != null || now - acceptedAt < 500) { socket.close(); continue }
                    acceptedAt = now
                    client = socket
                    Thread({ serve(socket) }, "buzin-castv2-client").apply { isDaemon = true; start() }
                }
            }, "buzin-castv2-listener").apply { isDaemon = true; start() }
            log("Cast V2 experimental listening on $ip:${server.localPort}; Google device authentication unavailable")
        } catch (e: Exception) { log("Cast V2 start failed: ${e.message}"); close() }
    }

    private fun send(socket: SSLSocket, frame: CastEnvelope) {
        writingSince = SystemClock.elapsedRealtime()
        try { frame.writeTo(socket.outputStream) } finally { writingSince = 0L }
    }
    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask<T> { check(!closed); action() }
        main.post(task)
        return try { task.get(5, TimeUnit.SECONDS) } catch (e: Exception) { task.cancel(false); throw e }
    }
    private fun serve(socket: SSLSocket) {
        var sender: String? = null
        try {
            if (!service.authorizeConnection("Cast V2", socket.inetAddress.hostAddress.orEmpty())) return
            socket.soTimeout = 5000; socket.startHandshake(); socket.tcpNoDelay = true
            var window = SystemClock.elapsedRealtime(); var count = 0
            while (!closed) {
                socket.soTimeout = 1000
                var partial = false
                val frame = try { CastEnvelope.readFrom(socket.inputStream) { partial = true; socket.soTimeout = 5000 } }
                catch (e: SocketTimeoutException) {
                    if (partial) throw e
                    if (sender != null) onMain { if (launched) mediaReply(sender!!, null) else null }?.let { send(socket, it) }
                    continue
                } ?: break
                if (SystemClock.elapsedRealtime() - window > 1000) { window = SystemClock.elapsedRealtime(); count = 0 }
                check(++count <= 40)
                if (frame.namespace == AUTH) {
                    send(socket, CastEnvelope("receiver-0", frame.source, AUTH, binary = byteArrayOf(26, 2, 8, 0)))
                    continue
                }
                require(frame.text != null)
                val body = JSONObject(frame.text)
                if (frame.namespace == HEARTBEAT) {
                    if (body.optString("type") == "PING") send(socket, CastEnvelope(frame.destination, frame.source, HEARTBEAT, "{\"type\":\"PONG\"}"))
                    continue
                }
                sender = frame.source
                val responses = onMain {
                    try { command(frame, body) }
                    catch (_: Exception) {
                        listOf(reply(frame, frame.destination, JSONObject().put("type", "INVALID_REQUEST")
                            .put("reason", "Unsupported command or invalid media").apply { request(body)?.let { put("requestId", it) } }))
                    }
                }
                responses.forEach { send(socket, it) }
            }
        } catch (e: Exception) { if (!closed) log("Cast V2 connection ended: ${e.javaClass.simpleName}") }
        finally { runCatching { socket.close() }; if (client === socket) client = null }
    }

    private fun request(body: JSONObject): Int? = if (body.has("requestId")) body.getInt("requestId").also { require(it >= 0) } else null
    private fun reply(frame: CastEnvelope, source: String, body: JSONObject) = CastEnvelope(source, frame.source, frame.namespace, body.toString())
    private fun receiverReply(destination: String, request: Int?): CastEnvelope {
        val apps = JSONArray()
        if (launched) apps.put(JSONObject().put("appId", DEFAULT_APP).put("displayName", "Buzin Cast")
            .put("sessionId", session).put("transportId", transport).put("isIdleScreen", false)
            .put("statusText", "Buzin Cast").put("namespaces", JSONArray().put(JSONObject().put("name", MEDIA))))
        val status = JSONObject().put("applications", apps).put("volume", JSONObject().put("level", level).put("muted", muted))
        val body = JSONObject().put("type", "RECEIVER_STATUS").put("status", status)
        request?.let { body.put("requestId", it) }
        return CastEnvelope("receiver-0", destination, RECEIVER, body.toString())
    }
    private fun mediaReply(destination: String, request: Int?): CastEnvelope {
        val statuses = JSONArray()
        if (service.networkPlaybackOwner == "castv2" && service.videoPlaybackActive.value) {
            val info = service.videoPlaybackInfo.value
            val description = JSONObject(media.toString()).put("duration", info.durationMs.coerceAtLeast(0) / 1000.0)
            statuses.put(JSONObject().put("mediaSessionId", mediaId).put("media", description)
                .put("currentTime", info.positionMs / 1000.0).put("playbackRate", info.speed.toDouble())
                .put("playerState", if (info.buffering) "BUFFERING" else if (info.playing) "PLAYING" else "PAUSED")
                .put("supportedMediaCommands", 15).put("volume", JSONObject().put("level", level).put("muted", muted)))
        } else if (mediaId > 0) {
            // Keep the terminal session visible: an empty array leaves some senders
            // displaying the previous PLAYING state after a stop or a failed load.
            statuses.put(JSONObject().put("mediaSessionId", mediaId).put("playerState", "IDLE")
                .put("currentTime", 0).put("playbackRate", 1).put("supportedMediaCommands", 0))
        }
        val body = JSONObject().put("type", "MEDIA_STATUS").put("status", statuses)
        request?.let { body.put("requestId", it) }
        return CastEnvelope(transport, destination, MEDIA, body.toString())
    }
    private fun command(frame: CastEnvelope, body: JSONObject): List<CastEnvelope> {
        val type = body.getString("type")
        if (frame.namespace == CONNECTION) {
            require(type == "CONNECT" || type == "CLOSE")
            require(frame.destination == "receiver-0" || launched && frame.destination == transport)
            return emptyList()
        }
        val id = request(body)
        if (frame.namespace == RECEIVER) {
            require(frame.destination == "receiver-0")
            when (type) {
                "GET_STATUS" -> Unit
                "GET_APP_AVAILABILITY" -> {
                    val values = body.getJSONArray("appId"); require(values.length() <= 32)
                    val availability = JSONObject()
                    repeat(values.length()) { val app = values.getString(it); availability.put(app, if (app == DEFAULT_APP) "APP_AVAILABLE" else "APP_UNAVAILABLE") }
                    return listOf(reply(frame, "receiver-0", JSONObject().put("type", "GET_APP_AVAILABILITY").put("requestId", id).put("availability", availability)))
                }
                "LAUNCH" -> {
                    if (body.getString("appId") != DEFAULT_APP) return listOf(reply(frame, "receiver-0", JSONObject().put("type", "LAUNCH_ERROR").put("reason", "NOT_FOUND").put("requestId", id)))
                    if (!launched) { session = UUID.randomUUID().toString(); transport = "buzin-$session"; launched = true }
                }
                "STOP" -> {
                    require(!body.has("sessionId") || body.getString("sessionId") == session)
                    if (service.networkPlaybackOwner == "castv2") service.stopVideoPlayback()
                    launched = false
                }
                "SET_VOLUME" -> setVolume(body.getJSONObject("volume"))
                else -> error("Unsupported receiver command")
            }
            return listOf(receiverReply(frame.source, id))
        }
        require(frame.namespace == MEDIA && launched && frame.destination == transport)
        if (type == "GET_STATUS") return listOf(mediaReply(frame.source, id))
        check(service.connectionCount.value == 0 && !service.relay.state.value.receiving)
        if (type == "LOAD") {
            check(SystemClock.elapsedRealtime() - lastLoadAt >= 500)
            val item = body.getJSONObject("media")
            val url = item.optString("contentUrl").ifBlank { item.getString("contentId") }
            require(url.length <= 8192 && URI(url).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null })
            require(!item.has("contentProtection") && !item.has("drm"))
            val mime = item.getString("contentType")
            require(mime.length <= 120 && (mime.startsWith("video/") || mime.startsWith("audio/") || mime in setOf("application/dash+xml", "application/vnd.apple.mpegurl", "application/x-mpegURL")))
            val time = if (body.has("currentTime")) finite(body, "currentTime", 0.0, 31536000.0) else 0.0
            val autoplay = body.optBoolean("autoplay", true)
            media = JSONObject().put("contentId", url).put("contentType", mime).put("streamType", item.optString("streamType", "BUFFERED"))
            lastLoadAt = SystemClock.elapsedRealtime(); mediaId = if (mediaId == Int.MAX_VALUE) 1 else mediaId + 1
            service.playNetworkVideo(url, "castv2", time.toFloat(), mime)
            service.airPlayVideoPlayer.setClientGain(if (muted) 0f else level.toFloat())
            if (!autoplay) service.airPlayVideoPlayer.setPlaying(false)
        } else {
            check(service.networkPlaybackOwner == "castv2" && service.videoPlaybackActive.value)
            require(body.getInt("mediaSessionId") == mediaId)
            when (type) {
                "PLAY" -> service.airPlayVideoPlayer.setPlaying(true)
                "PAUSE" -> service.airPlayVideoPlayer.setPlaying(false)
                "STOP" -> service.stopVideoPlayback()
                "SEEK" -> {
                    service.onVideoScrub(finite(body, "currentTime", 0.0, 31536000.0).toFloat())
                    when (body.optString("resumeState")) {
                        "PLAYBACK_START" -> service.airPlayVideoPlayer.setPlaying(true)
                        "PLAYBACK_PAUSE" -> service.airPlayVideoPlayer.setPlaying(false)
                    }
                }
                "SET_VOLUME" -> setVolume(body.getJSONObject("volume"))
                else -> error("Unsupported media command")
            }
        }
        return listOf(mediaReply(frame.source, id))
    }
    private fun finite(body: JSONObject, key: String, min: Double, max: Double) = body.getDouble(key).also { require(it.isFinite() && it in min..max) }
    private fun setVolume(value: JSONObject) {
        val nextLevel = if (value.has("level")) finite(value, "level", 0.0, 1.0) else level
        val nextMuted = if (value.has("muted")) value.getBoolean("muted") else muted
        level = nextLevel; muted = nextMuted
        if (service.networkPlaybackOwner == "castv2") service.airPlayVideoPlayer.setClientGain(if (muted) 0f else level.toFloat())
    }
    @Synchronized fun close() {
        closed = true
        runCatching { listener?.close() }; listener = null
        runCatching { client?.close() }; client = null
        main.removeCallbacks(watchdog)
        discovery?.release(); discovery = null
    }
    companion object {
        private const val DEFAULT_APP = "CC1AD845"
        private const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val MEDIA = "urn:x-cast:com.google.cast.media"
        private const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val AUTH = "urn:x-cast:com.google.cast.tp.deviceauth"
    }
}
