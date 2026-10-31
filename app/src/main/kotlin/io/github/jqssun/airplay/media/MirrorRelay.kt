package io.github.jqssun.airplay.media

import io.buzincast.media.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** One authenticated peer per session, opt-in. Packet transport is Buzin-specific. */
class MirrorRelay(
    private val changed: () -> Unit,
    private val incoming: (MirrorPacket) -> Unit,
    private val ended: () -> Unit,
    private val canReceive: () -> Boolean,
) {
    data class State(val sending: Boolean = false, val receiving: Boolean = false, val listening: Boolean = false,
                     val code: String = "", val port: Int = 0, val message: String = "")
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val queue = ArrayBlockingQueue<MirrorPacket>(120)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private val alive = AtomicBoolean(false)
    private val random = SecureRandom()
    @Volatile private var generation = 0
    private fun code() = (1..12).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random.nextInt(32)] }.joinToString("")

    @Synchronized fun listen() {
        stop()
        val current = ++generation
        alive.set(true)
        val pairing = code()
        try {
            server = ServerSocket(0).apply { soTimeout = 1000 }
            mutable.value = State(listening = true, code = pairing, port = server!!.localPort,
                message = "Aguardando outro Buzin Cast. O código expira em 5 minutos.")
        } catch (e: Exception) { mutable.value = State(message = "Falha ao abrir receptor: ${e.message}"); alive.set(false); return }
        val listener = server!!
        Thread({
            var authenticated = false
            val deadline = System.currentTimeMillis() + 300_000
            var attempts = 0
            try {
                while (alive.get() && current == generation && System.currentTimeMillis() < deadline && attempts < 8) {
                    val peer = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                    attempts++
                    socket = peer
                    try {
                        peer.soTimeout = 8000
                        check(canReceive()) { "Outra transmissão está ativa" }
                        val input = DataInputStream(peer.getInputStream()); val output = DataOutputStream(peer.getOutputStream())
                        val magic = ByteArray(8).also(input::readFully); require(magic.contentEquals(SecureMirrorChannel.MAGIC))
                        val salt = ByteArray(16).also(random::nextBytes); output.write(salt); output.flush()
                        val channel = SecureMirrorChannel(input, output, SecureMirrorChannel.deriveKey(pairing, salt))
                        require(channel.receive().contentEquals("hello".toByteArray()))
                        check(canReceive())
                        channel.send("ready".toByteArray()); authenticated = true
                        mutable.value = State(receiving = true, message = "Recebendo de ${peer.inetAddress.hostAddress}")
                        changed()
                        peer.soTimeout = 15000
                        while (alive.get() && current == generation) {
                            val bytes = channel.receive()
                            if (bytes.isEmpty()) continue // authenticated keepalive while waiting for AirPlay
                            val packet = MirrorPacketCodec.decode(bytes)
                            if (packet == MirrorPacket.End) break
                            check(canReceive()) { "Uma sessão AirPlay assumiu a reprodução" }
                            incoming(packet)
                        }
                        break
                    } catch (e: Exception) {
                        if (authenticated) throw e
                    } finally { runCatching { peer.close() } }
                }
                if (current == generation) mutable.value = State(message = if (authenticated) "Retransmissão encerrada." else "Recepção encerrada. Gere um novo código.")
            } catch (e: Exception) { if (current == generation) mutable.value = State(message = "Recepção encerrada: ${e.message}") }
            finally {
                runCatching { listener.close() }
                if (current == generation) { alive.set(false); changed(); if (authenticated) ended() }
            }
        }, "buzin-relay-listen").apply { start() }
    }

    @Synchronized fun connect(host: String, port: Int, code: String) {
        stop()
        require(port in 1..65535 && host.isNotBlank())
        val current = ++generation
        alive.set(true); queue.clear()
        mutable.value = State(message = "Conectando…")
        Thread({
            var peerToClose: Socket? = null
            try {
                val address = InetAddress.getByName(host)
                require(address.isSiteLocalAddress && !address.isLoopbackAddress) { "Use o endereço IP local da outra TV" }
                val peer = Socket(); peerToClose = peer; socket = peer
                peer.connect(InetSocketAddress(address, port), 5000); peer.soTimeout = 8000
                peer.tcpNoDelay = true
                val output = DataOutputStream(peer.getOutputStream()); val input = DataInputStream(peer.getInputStream())
                output.write(SecureMirrorChannel.MAGIC); output.flush()
                val salt = ByteArray(16).also(input::readFully)
                val channel = SecureMirrorChannel(input, output, SecureMirrorChannel.deriveKey(code, salt))
                channel.send("hello".toByteArray()); require(channel.receive().contentEquals("ready".toByteArray())) { "Código incorreto" }
                if (!alive.get() || current != generation) return@Thread
                mutable.value = State(sending = true, message = "Conectado. Inicie ou reconecte o espelhamento AirPlay.")
                changed()
                while (alive.get() && current == generation) {
                    val packet = queue.poll(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (packet == null) { channel.send(ByteArray(0)); continue }
                    channel.send(MirrorPacketCodec.encode(packet))
                    if (packet == MirrorPacket.End) break
                }
            } catch (e: Exception) { if (current == generation) mutable.value = State(message = "Retransmissão encerrada: ${e.message}") }
            finally {
                runCatching { peerToClose?.close() }
                if (current == generation) {
                    alive.set(false); runCatching { socket?.close() }; mutable.value = State(message = mutable.value.message.takeIf { !mutable.value.sending } ?: "Retransmissão encerrada.")
                    queue.clear(); changed()
                }
            }
        }, "buzin-relay-send").apply { start() }
    }

    fun offer(packet: MirrorPacket) {
        if (mutable.value.sending && !queue.offer(packet)) {
            stop(); mutable.value = State(message = "Rede lenta: retransmissão encerrada. Reconecte os receptores.")
        }
    }
    @Synchronized fun stop() {
        generation++; alive.set(false)
        val receiving = mutable.value.receiving
        runCatching { socket?.close() }; runCatching { server?.close() }
        socket = null; server = null; queue.clear(); mutable.value = State()
        changed(); if (receiving) ended()
    }
}
