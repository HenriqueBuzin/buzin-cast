package io.github.jqssun.airplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.discovery.MdnsService
import io.github.jqssun.airplay.discovery.UsbMdnsAdvertiser
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*

/** Actual responder methods; unicast uses emulator loopback, multicast is captured. */
@RunWith(AndroidJUnit4::class)
class UsbMdnsResponderContractTest {
    private val fixtures = mutableListOf<Fixture>()
    private val address = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val service = MdnsService("TV", "_airplay._tcp", 7000,
        linkedMapOf("model" to "Buzin", "unicode" to "é".repeat(200)))
    private val second = MdnsService("Audio", "_raop._tcp", 5000, emptyMap())
    private val instance get() = listOf("TV", "_airplay", "_tcp", "local")

    private class CapturedSocket : MulticastSocket(null as SocketAddress?) {
        val sent = mutableListOf<ByteArray>()
        override fun send(packet: DatagramPacket) {
            if (isClosed) throw SocketException("closed fixture socket")
            sent += packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        }
    }
    private inner class Fixture(val socket: MulticastSocket, onLink: (String?, Inet4Address?) -> Unit) {
        val advertiser = UsbMdnsAdvertiser(listOf(service, second), onLink)
        val link: Any
        init {
            val type = advertiser.javaClass.declaredClasses.single { it.simpleName == "Link" }
            link = type.declaredConstructors.single().apply { isAccessible = true }
                .newInstance("usb-test", address, -0x1000000, socket)
        }
        fun call(name: String, vararg args: Any): Any? = advertiser.javaClass.declaredMethods
            .single { it.name == name }.apply { isAccessible = true }.invoke(advertiser, *args)
        fun state(name: String) {
            val field = advertiser.javaClass.getDeclaredField("state").apply { isAccessible = true }
            field.set(advertiser, requireNotNull(field.type.enumConstants).single { (it as Enum<*>).name == name })
        }
        fun state(): String = (advertiser.javaClass.getDeclaredField("state").apply { isAccessible = true }
            .get(advertiser) as Enum<*>).name
        fun timestamp(name: String): Long = advertiser.javaClass.getDeclaredField(name).apply { isAccessible = true }
            .getLong(advertiser)
        @Suppress("UNCHECKED_CAST")
        fun host() = advertiser.javaClass.getDeclaredField("host").apply { isAccessible = true }
            .get(advertiser) as List<String>
        fun packet(bytes: ByteArray, source: InetAddress = address, port: Int = 5353) =
            call("_onPacket", link, DatagramPacket(bytes, bytes.size, source, port))
    }
    private fun fixture(
        socket: MulticastSocket = CapturedSocket(),
        onLink: (String?, Inet4Address?) -> Unit = { _, _ -> },
    ) = Fixture(socket, onLink).also { fixtures += it }
    private fun bytes(body: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).apply(body).flush()
    }.toByteArray()
    private fun DataOutputStream.name(labels: List<String>) {
        for (label in labels) { val encoded = label.toByteArray(); writeByte(encoded.size); write(encoded) }
        writeByte(0)
    }
    private fun query(vararg questions: Pair<List<String>, Int>, authority: Int = 0) = bytes {
        writeShort(0x6a70); writeShort(0); writeShort(questions.size)
        writeShort(0); writeShort(authority); writeShort(0)
        for ((labels, type) in questions) { name(labels); writeShort(type); writeShort(1) }
    }
    private fun foreign(target: List<String>, ttl: Int = 120, owner: List<String> = instance) = bytes {
        writeShort(0); writeShort(0x8400); writeShort(0); writeShort(1); writeShort(0); writeShort(0)
        name(owner); writeShort(33); writeShort(0x8001); writeInt(ttl)
        val payload = bytes { writeShort(0); writeShort(0); writeShort(7000); name(target) }
        writeShort(payload.size); write(payload)
    }
    private data class Record(val name: List<String>, val type: Int, val clazz: Int, val ttl: Int, val data: ByteArray)
    private data class Reply(val id: Int, val questions: Int, val answers: Int, val extras: Int, val records: List<Record>)
    private fun readName(input: DataInputStream): List<String> {
        val labels = mutableListOf<String>()
        while (true) {
            val length = input.readUnsignedByte()
            if (length == 0) return labels
            assertTrue("Response emitted an invalid label", length <= 63)
            val bytes = ByteArray(length); input.readFully(bytes); labels += bytes.toString(Charsets.UTF_8)
        }
    }
    private fun reply(data: ByteArray): Reply {
        val input = DataInputStream(ByteArrayInputStream(data))
        val id = input.readUnsignedShort(); assertEquals(0x8400, input.readUnsignedShort())
        val questions = input.readUnsignedShort(); val answers = input.readUnsignedShort()
        assertEquals(0, input.readUnsignedShort()); val extras = input.readUnsignedShort()
        repeat(questions) { readName(input); input.readUnsignedShort(); input.readUnsignedShort() }
        val records = (0 until answers + extras).map {
            val name = readName(input); val type = input.readUnsignedShort(); val clazz = input.readUnsignedShort()
            val ttl = input.readInt(); val payload = ByteArray(input.readUnsignedShort()); input.readFully(payload)
            Record(name, type, clazz, ttl, payload)
        }
        assertEquals("Trailing unparsed DNS data", 0, input.available())
        return Reply(id, questions, answers, extras, records)
    }
    @After fun cleanup() {
        fixtures.forEach { it.advertiser.stop(); it.socket.close() }
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
            DebugCoverage.dump(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test fun legacyQueriesProduceRealUdpResponsesWithBothDiscoveryAndHostRecords() {
        MulticastSocket(InetSocketAddress(address, 0)).use { sender ->
            DatagramSocket(InetSocketAddress(address, 0)).use { receiver ->
                receiver.soTimeout = 1000
                val fixture = fixture(sender)
                val cases = listOf(
                    Triple(listOf("_services", "_dns-sd", "_udp", "local") to 12, 2, 0),
                    Triple(listOf("_airplay", "_tcp", "local") to 12, 1, 3),
                    Triple(instance to 255, 2, 1), Triple(instance to 33, 1, 1),
                    Triple(instance.map { it.uppercase() } to 33, 1, 1),
                    Triple(instance to 16, 1, 0), Triple(fixture.host() to 1, 1, 0))
                for ((question, answers, extras) in cases) {
                    fixture.call("_answer", fixture.link, InetSocketAddress(address, receiver.localPort), query(question))
                    val packet = DatagramPacket(ByteArray(9000), 9000); receiver.receive(packet)
                    val response = reply(packet.data.copyOf(packet.length))
                    assertEquals(0x6a70, response.id); assertEquals(1, response.questions)
                    assertEquals(answers, response.answers); assertEquals(extras, response.extras)
                    assertTrue(response.records.all { it.ttl == 10 && it.clazz == 1 })
                }
            }
        }
    }

    @Test fun multicastAnswersDeduplicateExtrasPreserveTxtAndWithdrawOnlyHostRecords() {
        val socket = CapturedSocket(); val fixture = fixture(socket)
        fixture.call("_answer", fixture.link, InetSocketAddress(address, 5353),
            query(listOf("_airplay", "_tcp", "local") to 12, instance to 33))
        val response = reply(socket.sent.single())
        assertEquals(0, response.id); assertEquals(0, response.questions)
        assertEquals(2, response.answers); assertEquals(2, response.extras)
        assertEquals(setOf(12, 33, 16, 1), response.records.map { it.type }.toSet())
        for (record in response.records) {
            assertEquals(if (record.type == 12) 1 else 0x8001, record.clazz)
            assertEquals(if (record.type in setOf(1, 33)) 120 else 4500, record.ttl)
            when (record.type) {
                1 -> assertArrayEquals(address.address, record.data)
                12 -> assertEquals(instance, readName(DataInputStream(ByteArrayInputStream(record.data))))
                33 -> {
                    val data = DataInputStream(ByteArrayInputStream(record.data))
                    assertEquals(0, data.readUnsignedShort()); assertEquals(0, data.readUnsignedShort())
                    assertEquals(7000, data.readUnsignedShort()); assertEquals(fixture.host(), readName(data))
                }
            }
        }
        val txt = DataInputStream(ByteArrayInputStream(response.records.single { it.type == 16 }.data))
        val strings = mutableListOf<String>()
        while (txt.available() > 0) {
            val payload = ByteArray(txt.readUnsignedByte()); txt.readFully(payload)
            strings += Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(payload)).toString()
        }
        assertEquals(listOf("model=Buzin", "unicode=" + "é".repeat(123)), strings)
        socket.sent.clear()
        fixture.call("_answer", fixture.link, InetSocketAddress(address, 5353), query(listOf("Audio", "_raop", "_tcp", "local") to 16))
        assertArrayEquals(byteArrayOf(0), reply(socket.sent.single()).records.single().data)
        socket.sent.clear(); fixture.state("ACTIVE"); fixture.call("_foreignSeen", fixture.link)
        val goodbye = reply(socket.sent.single())
        assertEquals(3, goodbye.answers)
        assertTrue(goodbye.records.all { it.type in setOf(33, 1) && it.ttl == 0 && it.clazz == 1 })
        assertEquals("YIELDED", fixture.state())
        fixture.call("_foreignSeen", fixture.link); assertEquals(1, socket.sent.size)
    }

    @Test fun invalidQueriesAndForeignPacketsCannotStealAResponderOutsideItsSubnet() {
        val socket = CapturedSocket(); val fixture = fixture(socket)
        for (query in listOf(query(listOf("unknown", "local") to 1), query(instance to 28),
            query(instance to 33).dropLast(3).toByteArray(), query(instance to 33).copyOf(13))) {
            fixture.call("_answer", fixture.link, InetSocketAddress(address, 5353), query)
        }
        assertTrue(socket.sent.isEmpty())
        val foreignReply = foreign(listOf("other-host", "local"))
        fixture.packet(byteArrayOf(0)); fixture.packet(foreignReply, InetAddress.getByName("192.0.2.1"))
        fixture.packet(foreignReply, InetAddress.getByName("::1")); fixture.packet(foreignReply, port = 9999)
        fixture.packet(foreign(fixture.host()))
        fixture.packet(foreign(listOf("other-host", "local"), owner = listOf("Other", "_airplay", "_tcp", "local")))
        fixture.packet(foreignReply.copyOf(13))
        fixture.packet(query(listOf("unknown", "local") to 33))
        assertEquals("CHECKING", fixture.state())
        fixture.packet(foreignReply); assertEquals("YIELDED", fixture.state())
        fixture.packet(foreign(listOf("other-host", "local"), 0)); assertEquals("CHECKING", fixture.state())
        val authority = foreign(fixture.host()).drop(12).toByteArray()
        fixture.packet(query(instance to 33, authority = 1) + authority); assertEquals("YIELDED", fixture.state())
        socket.close()
        fixture.call("_answer", fixture.link, InetSocketAddress(address, 5353), query(instance to 33))
    }

    @Test fun probeAnnouncementAndYieldTimersRespectTheirIntervalsAndRetryAfterSilence() {
        val socket = CapturedSocket(); val fixture = fixture(socket)
        fun tick(time: Long) { fixture.call("_tick", fixture.link, time) }
        fun assertProbe(data: ByteArray) {
            val input = DataInputStream(ByteArrayInputStream(data))
            assertEquals(0, input.readUnsignedShort()); assertEquals(0, input.readUnsignedShort())
            assertEquals(2, input.readUnsignedShort())
            repeat(3) { assertEquals(0, input.readUnsignedShort()) }
            for (name in listOf(instance, listOf("Audio", "_raop", "_tcp", "local"))) {
                assertEquals(name, readName(input)); assertEquals(33, input.readUnsignedShort())
                assertEquals(1, input.readUnsignedShort())
            }
            assertEquals(0, input.available())
        }
        fixture.call("_check", 10000L)
        tick(10000); assertProbe(socket.sent.single())
        tick(10500); assertEquals(1, socket.sent.size)
        tick(11000); assertProbe(socket.sent.last()); assertEquals(2, socket.sent.size)
        tick(12500); assertEquals("ACTIVE", fixture.state()); assertEquals(2, socket.sent.size)
        tick(12501); assertEquals(9, reply(socket.sent.last()).answers)
        tick(12502); assertEquals(3, socket.sent.size)
        tick(13501); tick(15501); assertEquals(5, socket.sent.size)
        tick(19501); assertEquals("Announcements must stop after three sends", 5, socket.sent.size)
        fixture.call("_foreignSeen", fixture.link)
        assertEquals("YIELDED", fixture.state())
        val foreignAt = fixture.timestamp("foreignAt")
        val sent = socket.sent.size
        tick(foreignAt + 14999); assertEquals(sent, socket.sent.size)
        tick(foreignAt + 15000); assertProbe(socket.sent.last()); assertEquals(sent + 1, socket.sent.size)
        tick(foreignAt + 17501); assertEquals("CHECKING", fixture.state())
        tick(foreignAt + 17502); assertProbe(socket.sent.last())
        tick(foreignAt + 20001); assertEquals("ACTIVE", fixture.state())
        fixture.call("_close", fixture.link)
        val goodbye = reply(socket.sent.last())
        assertEquals(9, goodbye.answers)
        assertTrue(goodbye.records.all { it.ttl == 0 && it.clazz == 1 })
        assertTrue(socket.isClosed)
    }

    @Test fun idleDiscoveryWorkerStopsPromptlyWithoutALingeringThread() {
        // This suite runs only on isolated emulators, without a USB tether.
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        assertFalse("Disconnect the emulator's USB tether before this lifecycle test",
            interfaces.any { Regex("(usb|rndis|ncm)\\d+").matches(it.name) && it.isUp })
        val fixture = fixture()
        fixture.advertiser.start()
        val worker = fixture.advertiser.javaClass.getDeclaredField("thread").apply { isAccessible = true }
            .get(fixture.advertiser) as Thread
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (worker.state != Thread.State.TIMED_WAITING) {
            assertTrue("Discovery worker exited before reaching its idle wait", worker.isAlive)
            assertTrue("Discovery worker did not reach its idle wait", System.nanoTime() < deadline)
            Thread.sleep(10)
        }
        fixture.advertiser.stop()
        assertFalse("Stopping discovery left a running worker", worker.isAlive)
        fixture.advertiser.stop()
    }

    @Test fun losingAnAdvertisedLinkWithdrawsItAndNotifiesTheServiceOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        assertFalse("The emulator for this case must have no active USB tether",
            interfaces.any { Regex("(usb|rndis|ncm)\\d+").matches(it.name) && it.isUp })
        val reports = mutableListOf<Pair<String?, Inet4Address?>>()
        val socket = CapturedSocket()
        val fixture = fixture(socket) { name, ip -> reports += name to ip }
        val link = fixture.advertiser.javaClass.getDeclaredField("link").apply { isAccessible = true }
        link.set(fixture.advertiser, fixture.link)
        fixture.state("ACTIVE")
        fixture.call("_report", fixture.link)
        instrumentation.waitForIdleSync()
        assertEquals(listOf("usb-test" to address), reports)

        fixture.call("_scan", 1_000L)
        instrumentation.waitForIdleSync()
        assertTrue("Link loss kept the multicast socket open", socket.isClosed)
        assertNull(link.get(fixture.advertiser))
        val goodbye = reply(socket.sent.single())
        assertTrue(goodbye.records.isNotEmpty())
        assertTrue(goodbye.records.all { it.ttl == 0 })
        val expected = listOf("usb-test" to address, null to null)
        assertEquals(expected, reports)

        fixture.call("_scan", 2_000L)
        instrumentation.runOnMainSync {
            fixture.call("_report", fixture.link)
            fixture.advertiser.stop()
        }
        instrumentation.waitForIdleSync()
        assertEquals("An unchanged or stopped advertiser reported a link", expected, reports)
    }

    @Test fun truncatedProbeDoesNotWithdrawAnActiveResponder() {
        val socket = CapturedSocket(); val fixture = fixture(socket)
        fixture.state("ACTIVE")
        val question = query(instance to 33, authority = 1)
        val shortRecord = bytes { name(instance); write(ByteArray(9)) }
        val missingPayload = bytes {
            name(instance); writeShort(33); writeShort(1); writeInt(120); writeShort(100); writeByte(0)
        }
        for (packet in listOf(question, question.dropLast(3).toByteArray(), question + shortRecord,
            question + missingPayload, question + byteArrayOf(192.toByte()))) {
            fixture.packet(packet)
            assertEquals("Malformed DNS probe withdrew the active service", "ACTIVE", fixture.state())
            assertTrue("Malformed probe emitted goodbye records", socket.sent.isEmpty())
        }
    }

    @Test fun malformedResponsesPreserveTheAdvertisementUntilACompleteForeignRecordArrives() {
        val socket = CapturedSocket()
        val fixture = fixture(socket)
        fixture.state("ACTIVE")

        val validResponse = foreign(listOf("other-host", "local"))
        val serviceRecord = validResponse.copyOfRange(12, validResponse.size)
        val responseHeader = validResponse.copyOf(12)
        val question = query(instance to 33).let { it.copyOfRange(12, it.size) }
        val headerWithQuestion = responseHeader.copyOf().apply { this[5] = 1 }
        val recordHeader = bytes { name(instance); writeShort(33); writeShort(0x8001); writeInt(120) }
        val invalidTargetRecord = recordHeader + bytes {
            writeShort(7); writeShort(0); writeShort(0); writeShort(7000); writeByte(0xc0)
        }
        val shortServiceRecord = recordHeader + bytes {
            writeShort(6); writeShort(0); writeShort(0); writeShort(7000)
        }
        val unrelatedRecord = bytes {
            name(instance); writeShort(16); writeShort(1); writeInt(120); writeShort(1); writeByte(0)
        }

        val ignored = listOf(
            headerWithQuestion + byteArrayOf(0xc0.toByte()),
            responseHeader + recordHeader,
            validResponse.copyOf(validResponse.size - 1),
            responseHeader + invalidTargetRecord,
            responseHeader + shortServiceRecord,
            responseHeader + unrelatedRecord,
        )
        for (packet in ignored) {
            fixture.packet(packet)
            assertEquals("An incomplete or unrelated response displaced the receiver", "ACTIVE", fixture.state())
            assertTrue("The receiver sent goodbye records before detecting a peer", socket.sent.isEmpty())
        }

        fixture.packet(headerWithQuestion + question + serviceRecord)
        assertEquals("YIELDED", fixture.state())
        val goodbye = reply(socket.sent.single())
        assertEquals(listOf(33, 33, 1), goodbye.records.map { it.type })
        assertTrue(goodbye.records.all { it.ttl == 0 })
    }
}
