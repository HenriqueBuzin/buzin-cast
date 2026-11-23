package io.github.jqssun.airplay

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.buzincast.media.MirrorPacket
import io.buzincast.media.MirrorPacketCodec
import io.buzincast.media.SecureMirrorChannel
import io.github.jqssun.airplay.media.MirrorRelay
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private fun withRelayHandshake(state: MirrorRelay.State, block: (Socket, SecureMirrorChannel) -> Unit) {
    Socket("127.0.0.1", state.port).use { socket ->
        socket.soTimeout = 15_000
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        output.write(SecureMirrorChannel.MAGIC)
        output.flush()
        val salt = ByteArray(16).also(input::readFully)
        val channel = SecureMirrorChannel(input, output, SecureMirrorChannel.deriveKey(state.code, salt))
        block(socket, channel)
    }
}

internal fun withAuthenticatedRelayPeer(state: MirrorRelay.State, block: (SecureMirrorChannel) -> Unit) {
    withRelayHandshake(state) { _, channel ->
        channel.send("hello".toByteArray())
        assertArrayEquals("ready".toByteArray(), channel.receive())
        block(channel)
    }
}

/** Real local sockets and authenticated frames, without another TV or sender. */
@RunWith(AndroidJUnit4::class)
class MirrorRelayContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val relays = mutableListOf<MirrorRelay>()
    private val packets = LinkedBlockingQueue<MirrorPacket>()
    private val endings = AtomicInteger()
    private val available = AtomicBoolean(true)

    private fun relay() = MirrorRelay(context, {}, { packets.add(it) }, { endings.incrementAndGet() }, available::get)
        .also { relays += it }

    private fun await(message: String, timeoutMs: Long = 15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate()) {
            assertTrue(message + ": " + relays.joinToString { it.state.value.message },
                SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(10)
        }
    }

    private fun peer(receiver: MirrorRelay, block: (SecureMirrorChannel) -> Unit) {
        withAuthenticatedRelayPeer(receiver.state.value) { channel ->
            await("Receiver did not authenticate") { receiver.state.value.receiving }
            block(channel)
        }
    }

    @After fun cleanup() {
        relays.forEach(MirrorRelay::stop)
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Test fun authenticatedReceiverDeliversFramesIgnoresKeepaliveAndEndsOnce() {
        val receiver = relay()
        receiver.listen()
        assertTrue(receiver.state.value.listening)
        assertEquals(12, receiver.state.value.code.length)
        peer(receiver) { channel ->
            channel.send(ByteArray(0))
            val audio = MirrorPacket.Audio(byteArrayOf(1, 2, 3, 4), 12_345, 48000, 2)
            channel.send(MirrorPacketCodec.encode(audio))
            val received = packets.poll(15, TimeUnit.SECONDS) as MirrorPacket.Audio
            assertArrayEquals(audio.data, received.data)
            assertEquals(audio.ptsNs, received.ptsNs)
            assertEquals(audio.sampleRate, received.sampleRate)
            channel.send(MirrorPacketCodec.encode(MirrorPacket.End))
            await("Receiver did not end") { endings.get() == 1 }
        }
        assertEquals(context.getString(R.string.relay_ended), receiver.state.value.message)
        receiver.stop()
        assertEquals(1, endings.get())
        assertTrue(packets.isEmpty())
    }

    @Test fun rejectedMagicDoesNotPreventTheNextAuthenticatedPeer() {
        val receiver = relay()
        receiver.listen()
        Socket("127.0.0.1", receiver.state.value.port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write("INVALID!".toByteArray())
            assertEquals(-1, socket.getInputStream().read())
        }
        peer(receiver) { it.send(MirrorPacketCodec.encode(MirrorPacket.End)) }
        await("Recovered receiver did not finish") { endings.get() == 1 }
    }

    @Test fun busyReceiverRejectsBeforeSendingPairingSalt() {
        available.set(false)
        val receiver = relay()
        receiver.listen()
        Socket("127.0.0.1", receiver.state.value.port).use { socket ->
            socket.soTimeout = 5000
            assertEquals(-1, socket.getInputStream().read())
        }
        assertTrue(receiver.state.value.listening)
        assertEquals(0, endings.get())
    }

    @Test fun receiverRechecksDisplayAvailabilityAfterThePairingProof() {
        val receiver = relay()
        receiver.listen()
        val listening = receiver.state.value
        withRelayHandshake(listening) { socket, channel ->
            available.set(false)
            channel.send("hello".toByteArray())
            assertEquals(-1, socket.getInputStream().read())
        }
        assertEquals(listening, receiver.state.value)
        assertEquals(0, endings.get())
        assertTrue(packets.isEmpty())

        available.set(true)
        peer(receiver) { it.send(MirrorPacketCodec.encode(MirrorPacket.End)) }
        await("An available receiver did not accept the next pairing") { endings.get() == 1 }
    }

    @Test fun rejectedPairingsExpireTheListenerAndItCanStartAgain() {
        available.set(false)
        val receiver = relay()
        receiver.listen()
        val originalPort = receiver.state.value.port
        repeat(8) {
            Socket("127.0.0.1", originalPort).use { socket ->
                socket.soTimeout = 5000
                assertEquals(-1, socket.getInputStream().read())
            }
        }
        await("Listener did not expire after eight failed pairings") {
            !receiver.state.value.listening && receiver.state.value.message == context.getString(R.string.relay_new_code)
        }
        assertEquals(0, endings.get())
        assertTrue(packets.isEmpty())

        available.set(true)
        receiver.listen()
        peer(receiver) { it.send(MirrorPacketCodec.encode(MirrorPacket.End)) }
        await("Restarted listener did not finish") { endings.get() == 1 }
    }

    @Test fun newLocalStreamTerminatesAuthenticatedRelayWithoutDeliveringItsPacket() {
        val receiver = relay()
        receiver.listen()
        peer(receiver) { channel ->
            available.set(false)
            channel.send(MirrorPacketCodec.encode(MirrorPacket.Audio(byteArrayOf(0, 0, 0, 0), 1)))
            await("Takeover did not end relay") { endings.get() == 1 }
        }
        assertFalse(receiver.state.value.receiving)
        assertTrue(receiver.state.value.message.contains(context.getString(R.string.relay_mirroring_took_over)))
        assertTrue(packets.isEmpty())
    }

    @Test fun invalidAuthenticatedPacketReportsFailureAndEnds() {
        val receiver = relay()
        receiver.listen()
        peer(receiver) { channel ->
            channel.send(byteArrayOf(99))
            await("Malformed packet did not terminate receiver") { endings.get() == 1 }
        }
        assertFalse(receiver.state.value.receiving)
        assertNotEquals(context.getString(R.string.relay_ended), receiver.state.value.message)
        assertTrue(packets.isEmpty())
    }

    @Test fun senderValidatesPortAndRejectsLoopbackAndPublicDestinations() {
        val sender = relay()
        for ((host, port) in listOf("" to 1, " " to 1, "127.0.0.1" to 0, "127.0.0.1" to 65536)) {
            assertThrows(IllegalArgumentException::class.java) { sender.connect(host, port, "ABCDEFGHIJKL") }
        }
        for (host in listOf("127.0.0.1", "192.0.2.1")) {
            sender.connect(host, 12345, "ABCDEFGHIJKL")
            await("Unsafe destination did not fail") {
                sender.state.value.message.contains(context.getString(R.string.relay_use_local_ip))
            }
            assertFalse(sender.state.value.sending)
        }
        sender.offer(MirrorPacket.End)
        sender.stop()
        assertEquals(MirrorRelay.State(), sender.state.value)
    }

    @Test fun pairedSenderAndReceiverTransferVideoOnTheEmulatorLocalInterface() {
        val address = localAddress()
        val receiver = relay()
        receiver.listen()
        val sender = relay()
        sender.connect(address.hostAddress!!, receiver.state.value.port, receiver.state.value.code)
        // Two real PBKDF2 derivations run concurrently on a software-emulated CPU.
        await("Sender did not authenticate on local interface", 45_000) { sender.state.value.sending }
        val video = MirrorPacket.Video(byteArrayOf(0, 0, 0, 1, 5), 9876, 640, 480, false)
        sender.offer(video)
        val received = packets.poll(15, TimeUnit.SECONDS) as MirrorPacket.Video
        assertArrayEquals(video.data, received.data)
        assertEquals(video.ptsNs, received.ptsNs)
        assertEquals(video.width, received.width)
        assertEquals(video.height, received.height)
        assertEquals(video.hevc, received.hevc)
        sender.offer(MirrorPacket.End)
        await("Paired session did not end") { !sender.state.value.sending && endings.get() == 1 }
    }

    @Test fun switchingToReceiveClosesPendingPairingWithoutReplacingTheNewListenerState() {
        val address = localAddress()
        val relay = relay()
        val previousThreads = Thread.getAllStackTraces().keys
        ServerSocket(0, 1, address).use { peer ->
            peer.soTimeout = 10_000
            relay.connect(requireNotNull(address.hostAddress), peer.localPort, "ABCDEFGHIJKL")
            peer.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = DataInputStream(socket.getInputStream())
                val magic = ByteArray(SecureMirrorChannel.MAGIC.size).also(input::readFully)
                assertArrayEquals(SecureMirrorChannel.MAGIC, magic)
                val sender = Thread.getAllStackTraces().keys.single {
                    it.name == "buzin-relay-send" && it !in previousThreads
                }

                relay.listen()
                val receiving = relay.state.value
                assertTrue(receiving.listening)
                assertTrue(receiving.port > 0)
                assertEquals(-1, input.read())
                sender.join(5_000)
                assertFalse("The previous pairing still has a sender thread", sender.isAlive)
                assertEquals(receiving, relay.state.value)
            }
        }
    }

    @Test fun senderRecoversFromRejectedPairingAndSendsKeepaliveBeforeVideo() {
        val address = localAddress()
        val code = "ABCDEFGHIJKL"
        val sender = relay()
        ServerSocket(0, 1, address).use { server ->
            server.soTimeout = 10_000
            fun connect(respond: (SecureMirrorChannel) -> Unit) {
                sender.connect(requireNotNull(address.hostAddress), server.localPort, code)
                server.accept().use { socket ->
                    socket.soTimeout = 15_000
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    val magic = ByteArray(SecureMirrorChannel.MAGIC.size).also(input::readFully)
                    assertArrayEquals(SecureMirrorChannel.MAGIC, magic)
                    val salt = ByteArray(16) { it.toByte() }
                    output.write(salt)
                    output.flush()
                    val channel = SecureMirrorChannel(input, output, SecureMirrorChannel.deriveKey(code, salt))
                    assertArrayEquals("hello".toByteArray(), channel.receive())
                    respond(channel)
                }
            }

            connect { it.send("rejected".toByteArray()) }
            await("Rejected pairing did not report an error") {
                !sender.state.value.sending && sender.state.value.message.contains(context.getString(R.string.relay_bad_code))
            }
            connect { channel ->
                channel.send("ready".toByteArray())
                await("Retry did not become active") { sender.state.value.sending }
                assertArrayEquals(ByteArray(0), channel.receive())
                val video = MirrorPacket.Video(byteArrayOf(0, 0, 0, 1, 5), 9876, 640, 480, false)
                sender.offer(video)
                fun receivePacket() = generateSequence(channel::receive).take(5).first { it.isNotEmpty() }
                val frame = receivePacket()
                assertArrayEquals(MirrorPacketCodec.encode(video), frame)
                sender.offer(MirrorPacket.End)
                val end = receivePacket()
                assertEquals(MirrorPacket.End, MirrorPacketCodec.decode(end))
                await("Retried sender did not finish cleanly") {
                    !sender.state.value.sending && sender.state.value.message == context.getString(R.string.relay_ended)
                }
            }
        }
    }

    private fun localAddress() = NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .first { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }
}
