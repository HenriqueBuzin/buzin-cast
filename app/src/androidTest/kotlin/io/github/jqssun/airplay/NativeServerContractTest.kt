package io.github.jqssun.airplay

import android.content.Context
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.bridge.LogListener
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import io.github.jqssun.airplay.discovery.AirPlayReceiverAdapter
import io.github.jqssun.airplay.renderer.AudioConfig
import io.github.jqssun.airplay.renderer.AudioRenderer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Real initialized JNI contexts; the callbacks never open UI or publish NSD services. */
@RunWith(AndroidJUnit4::class)
class NativeServerContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val handles = mutableListOf<Long>()
    private val keys = mutableListOf<File>()
    private val callbacks = mutableMapOf<Long, Callbacks>()

    private data class Pcm(val bytes: ByteArray, val pts: Long, val rate: Int, val channels: Int)

    private class Callbacks : RaopCallbackHandler, LogListener {
        val pcm = CopyOnWriteArrayList<Pcm>()
        val logs = CopyOnWriteArrayList<String>()
        fun onConnectionRequest(peer: String) = true
        override fun onLog(msg: String) { logs += msg }
        override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {}
        override fun onAudioPcm(data: ByteArray, ntpTimeNs: Long, sampleRate: Int, channels: Int) {
            pcm += Pcm(data.copyOf(), ntpTimeNs, sampleRate, channels)
        }
        override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {}
        override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {}
        override fun onClientVolume(db: Float) {}
        override fun onClientVolumeQuery() = 0f
        override fun onAudioTeardown() {}
        override fun onConnectionInit() {}
        override fun onConnectionDestroy() {}
        override fun onConnectionReset(reason: Int) {}
        override fun onDisplayPin(pin: String) {}
        override fun onMetadata(data: ByteArray) {}
        override fun onCoverArt(data: ByteArray) {}
        override fun onProgress(start: Long, curr: Long, end: Long) {}
        override fun onDacpId(dacpId: String, activeRemote: String) {}
        override fun onMirrorRunning(running: Boolean) {}
        override fun onVideoPlay(location: String, startPositionSeconds: Float) {}
        override fun onVideoScrub(positionSeconds: Float) {}
        override fun onVideoRate(rate: Float) {}
        override fun onVideoStop() {}
        override fun onVideoSessionPoll() {}
    }

    private fun create(pin: Boolean = false, mac: ByteArray = byteArrayOf(16, 32, 48, 64, 80, 96, 112, 120)): Long {
        val key = File.createTempFile("native-contract-", ".pem", context.cacheDir).also { keys += it }
        val callback = Callbacks()
        val handle = NativeBridge.nativeInit(callback, mac, "JNI Contract", key.absolutePath, pin, pin)
        assertNotEquals("Native initialization failed", 0L, handle)
        handles += handle
        callbacks[handle] = callback
        return handle
    }

    private fun port() = ServerSocket(0).use { it.localPort }
    private fun refresh(handle: Long, port: Int) {
        NativeBridge.nativeStop(handle)
        assertEquals(port, NativeBridge.nativeStart(handle, port))
    }
    private fun feature(records: Map<String, String>, bit: Int): Boolean {
        val words = records.getValue("features").split(',').map { it.removePrefix("0x").toLong(16) }
        val features = words[0] or (words[1] shl 32)
        return (features and (1L shl bit)) != 0L
    }

    @After fun cleanup() {
        for (handle in handles) NativeBridge.nativeDestroy(handle)
        keys.forEach { it.delete() }
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Test fun receiverAdapterStartAndCloseAreIdempotent() {
        val key = File.createTempFile("receiver-adapter-", ".pem", context.cacheDir).also { keys += it }
        val requestedPort = port()
        val configured = mutableListOf<Long>()
        val adapter = AirPlayReceiverAdapter(Callbacks(), byteArrayOf(16, 32, 48, 64, 80, 96),
            "Adapter Contract", key.absolutePath, false, false, requestedPort) { configured += it }
        assertEquals(0L, adapter.handle)
        assertEquals(-1, adapter.port)
        try {
            adapter.start()
            val handle = adapter.handle
            assertNotEquals(0L, handle)
            assertEquals(requestedPort, adapter.port)
            adapter.start()
            assertEquals(handle, adapter.handle)
            assertEquals(requestedPort, adapter.port)
            assertEquals(listOf(handle), configured)
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", requestedPort), 2000) }
        } finally {
            adapter.close()
        }
        adapter.close()
        adapter.start()
        assertEquals(0L, adapter.handle)
        assertEquals(-1, adapter.port)
        assertEquals(1, configured.size)
        assertThrows(ConnectException::class.java) {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", requestedPort), 2000) }
        }
    }

    @Test fun receiverAdapterClosedBeforeStartNeverConfiguresTheEngine() {
        val key = File.createTempFile("receiver-adapter-", ".pem", context.cacheDir).also { keys += it }
        val adapter = AirPlayReceiverAdapter(Callbacks(), byteArrayOf(16, 32, 48, 64, 80, 96),
            "Adapter Contract", key.absolutePath, false, false, port()) {
            fail("A closed receiver must not configure an engine")
        }
        adapter.close()
        adapter.start()
        assertEquals(0L, adapter.handle)
        assertEquals(-1, adapter.port)
        assertEquals(0L, key.length())
    }

    @Test fun bridgeCanStartStopAndChangeHlsWithoutPublishingDiscovery() {
        org.junit.Assume.assumeTrue("Requires coverage-only bridge discovery fixture",
            runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        val handle = create()
        val requestedPort = port()
        val address = InetSocketAddress("127.0.0.1", requestedPort)
        NativeBridge.nativeSetHlsEnabled(handle, false)
        assertTrue(DebugCoverage.withBridgeDiscoveryDetached(handle) {
            assertNull(NativeBridge.nativeGetRaopTxtRecords(handle))
            assertNull(NativeBridge.nativeGetAirplayTxtRecords(handle))
            NativeBridge.nativeSetHlsEnabled(handle, true)
            try {
                assertEquals(requestedPort, NativeBridge.nativeStart(handle, requestedPort))
                Socket().use { it.connect(address, 2000) }
                assertNull(NativeBridge.nativeGetRaopTxtRecords(handle))
            } finally {
                NativeBridge.nativeStop(handle)
            }
            assertThrows(ConnectException::class.java) { Socket().use { it.connect(address, 2000) } }
        })

        assertTrue(requireNotNull(NativeBridge.nativeGetAirplayTxtRecords(handle)).isEmpty())
        assertEquals(requestedPort, NativeBridge.nativeStart(handle, requestedPort))
        try {
            val records = requireNotNull(NativeBridge.nativeGetAirplayTxtRecords(handle))
            assertFalse(feature(records, 0))
            assertFalse(feature(records, 4))
        } finally {
            NativeBridge.nativeStop(handle)
        }
        NativeBridge.nativeSetHlsEnabled(handle, true)
        assertEquals(requestedPort, NativeBridge.nativeStart(handle, requestedPort))
        val restored = requireNotNull(NativeBridge.nativeGetAirplayTxtRecords(handle))
        assertTrue(feature(restored, 0))
        assertTrue(feature(restored, 4))
    }

    @Test fun partialServerRejectsUnavailableChildrenAndDestroysItsCallbackBinding() {
        org.junit.Assume.assumeTrue("Requires coverage-only partial-server fixture",
            runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        val callback = Callbacks()
        val handle = DebugCoverage.nativeCreatePartialServer(callback)
        assertNotEquals("Partial context callback initialization failed", 0L, handle)
        try {
            assertEquals(-1, NativeBridge.nativeStart(handle, 7000))
            assertNull(NativeBridge.nativeGetRaopTxtRecords(handle))
            assertNull(NativeBridge.nativeGetAirplayTxtRecords(handle))
            assertNull(NativeBridge.nativeGetRaopServiceName(handle))
            assertNull(NativeBridge.nativeGetServerName(handle))
            NativeBridge.nativeSetDisplaySize(handle, 1920, 1080, 60)
            NativeBridge.nativeSetPlist(handle, "width", 1920)
            NativeBridge.nativeSetLang(handle, "en")
            for (enabled in listOf(false, true)) {
                NativeBridge.nativeSetCaptureEnabled(handle, enabled)
                NativeBridge.nativeSetH265Enabled(handle, enabled)
                NativeBridge.nativeSetHlsEnabled(handle, enabled)
                NativeBridge.nativeSetFeature(handle, 42, enabled)
                NativeBridge.nativeSetCodecs(handle, enabled, !enabled)
                NativeBridge.nativeUpdatePlaybackInfo(handle, 1f, 10f, 1f, enabled)
            }
            assertFalse(NativeBridge.nativeServerAudioConfigure(handle, 50, 95, 256, false, false, false, false, false))
            assertFalse(NativeBridge.nativeServerAudioStart(handle))
            assertFalse(NativeBridge.nativeServerAudioDebug(handle, ByteBuffer.allocateDirect(256)))
            NativeBridge.nativeServerAudioSetClientGain(handle, 0.5f)
            NativeBridge.nativeServerAudioFormat(handle, 2, 352)
            repeat(2) {
                NativeBridge.nativeServerAudioStop(handle)
                NativeBridge.nativeStop(handle)
            }
            assertTrue(callback.pcm.isEmpty())
            assertTrue(callback.logs.isEmpty())
        } finally { NativeBridge.nativeDestroy(handle) }
        assertTrue(callback.pcm.isEmpty())
        assertTrue(callback.logs.isEmpty())
    }

    @Test fun raopLoggerKeepsMessageTextAndUsesTheMatchingAndroidPriority() {
        org.junit.Assume.assumeTrue("Requires coverage-only log fixture",
            runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        val key = File.createTempFile("native-log-contract-", ".pem", context.cacheDir).also { keys += it }
        assertEquals("RAOP log contracts passed", DebugCoverage.nativeRaopLogContracts(NativeBridge, Callbacks(),
            byteArrayOf(16, 32, 48, 64, 80, 96), "Log Contract", key.absolutePath))
    }

    @Test fun diagnosticAudioDoesNotReleaseAnArrayThatCouldNotBeAcquired() {
        org.junit.Assume.assumeTrue("Requires coverage-only JNI array fixture",
            runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        val handle = create()
        assertEquals("diagnostic audio array contracts passed",
            DebugCoverage.nativeDiagnosticAudioArrayContracts(DebugNativeBridge, handle, byteArrayOf(1)))
        assertTrue(callbacks.getValue(handle).pcm.isEmpty())
    }

    @Test fun invalidPinResultsAbortInitializationAndValidBoundariesRecover() {
        org.junit.Assume.assumeTrue("Requires coverage-only PIN fault fixture",
            runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        for (pin in listOf(-1, 10000, 0, 9999)) {
            val key = File.createTempFile("native-pin-contract-", ".pem", context.cacheDir).also { keys += it }
            val callback = Callbacks()
            val handle = DebugCoverage.nativeInitWithPinResult(NativeBridge, callback,
                byteArrayOf(16, 32, 48, 64, 80, 96), "PIN Contract", key.absolutePath, pin)
            if (pin < 0 || pin > 9999) {
                if (handle != 0L) handles += handle
                assertEquals("Invalid generated PIN was accepted: $pin", 0L, handle)
            } else {
                assertNotEquals("Valid PIN boundary was rejected: $pin", 0L, handle)
                handles += handle
                assertEquals("PIN Contract", NativeBridge.nativeGetServerName(handle))
                assertTrue("Initialization unexpectedly registered discovery records",
                    requireNotNull(NativeBridge.nativeGetAirplayTxtRecords(handle)).isEmpty())
            }
            assertTrue("Initialization unexpectedly emitted audio", callback.pcm.isEmpty())
        }
        // The scoped override must not contaminate ordinary PIN generation.
        val ordinary = create(pin = true)
        assertEquals("JNI Contract", NativeBridge.nativeGetServerName(ordinary))
        assertTrue(requireNotNull(NativeBridge.nativeGetAirplayTxtRecords(ordinary)).isEmpty())
    }

    @Test fun discoveryMapsSkipOnlyTheIncompleteEntryAndRestoreTheirRealGetters() {
        org.junit.Assume.assumeTrue("Requires coverage-only discovery fault fixture",
            runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        val handle = create()
        for (raop in listOf(false, true)) {
            val complete = requireNotNull(DebugCoverage.nativeDiscoveryMapWithMissingPart(NativeBridge, handle, raop, 0))
            assertTrue("Discovery fixture did not contain multiple records", complete.size > 1)
            var missingKey: Map<String, String>? = null
            for (part in listOf(1, 2)) {
                val incomplete = requireNotNull(DebugCoverage.nativeDiscoveryMapWithMissingPart(NativeBridge, handle, raop, part))
                assertEquals("More than one discovery entry disappeared", complete.size - 1, incomplete.size)
                assertEquals(complete.filterKeys { it in incomplete }, incomplete)
                assertEquals(1, (complete.keys - incomplete.keys).size)
                if (part == 1) missingKey = incomplete else assertEquals(missingKey, incomplete)
                val restored = if (raop) NativeBridge.nativeGetRaopTxtRecords(handle)
                               else NativeBridge.nativeGetAirplayTxtRecords(handle)
                assertEquals("Scoped getter failure leaked into a later request", complete, restored)
            }
        }
    }

    @Test fun capabilitiesAndCodecsArePublishedForBothEnabledAndDisabledValues() {
        val handle = create()
        assertEquals("JNI Contract", NativeBridge.nativeGetServerName(handle))
        assertTrue(requireNotNull(NativeBridge.nativeGetRaopTxtRecords(handle)).isEmpty())
        val port = port()
        NativeBridge.nativeSetDisplaySize(handle, 1280, 720, 30)
        NativeBridge.nativeSetPlist(handle, "width", 1280)
        NativeBridge.nativeSetLang(handle, "en")
        NativeBridge.nativeSetLang(handle, "pt-BR")
        for (enabled in listOf(false, true)) {
            NativeBridge.nativeSetCaptureEnabled(handle, enabled)
            NativeBridge.nativeSetH265Enabled(handle, enabled)
            NativeBridge.nativeSetHlsEnabled(handle, enabled)
            NativeBridge.nativeSetFeature(handle, NativeBridge.FT_MIRROR, enabled)
            NativeBridge.nativeSetFeature(handle, NativeBridge.FT_AUDIO, enabled)
            NativeBridge.nativeSetFeature(handle, 63, enabled)
            NativeBridge.nativeUpdatePlaybackInfo(handle, 3f, 10f, if (enabled) 1f else 0f, enabled)
            refresh(handle, port)
            val airplay = requireNotNull(NativeBridge.nativeGetAirplayTxtRecords(handle))
            assertEquals("10:20:30:40:50:60", airplay["deviceid"])
            assertEquals("false", airplay["pw"])
            for (bit in listOf(0, 4, 7, 9, 42, 63)) assertEquals("Feature $bit", enabled, feature(airplay, bit))
        }
        val before = NativeBridge.nativeGetAirplayTxtRecords(handle)
        NativeBridge.nativeSetFeature(handle, -1, true)
        NativeBridge.nativeSetFeature(handle, 64, true)
        refresh(handle, port)
        assertEquals(before, NativeBridge.nativeGetAirplayTxtRecords(handle))
        for ((alac, aac, expected) in listOf(Triple(false, false, "0"), Triple(true, false, "0,1"),
            Triple(false, true, "0,2,3"), Triple(true, true, "0,1,2,3"))) {
            NativeBridge.nativeSetCodecs(handle, alac, aac)
            refresh(handle, port)
            assertEquals(expected, NativeBridge.nativeGetRaopTxtRecords(handle)?.get("cn"))
        }
        assertEquals("102030405060@JNI Contract", NativeBridge.nativeGetRaopServiceName(handle))
    }

    @Test fun occupiedPortFailsAndDefaultPortCanServeRtspAfterRecovery() {
        val handle = create()
        ServerSocket(0).use { occupied -> assertEquals(-1, NativeBridge.nativeStart(handle, occupied.localPort)) }
        ServerSocket(7000).close()
        assertEquals(7000, NativeBridge.nativeStart(handle, 0))
        Socket("127.0.0.1", 7000).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
            assertTrue(socket.getInputStream().bufferedReader().readLine().startsWith("RTSP/1.0 200"))
        }
        repeat(2) { NativeBridge.nativeStop(handle) }
        ServerSocket(7000).close()
    }

    @Test fun pinProtectedServerPadsShortHardwareAddressAndAdvertisesAuthentication() {
        val handle = create(pin = true, mac = byteArrayOf(16, 32, 48))
        val port = port()
        assertEquals(port, NativeBridge.nativeStart(handle, port))
        assertEquals("true", NativeBridge.nativeGetRaopTxtRecords(handle)?.get("pw"))
        assertEquals("true", NativeBridge.nativeGetAirplayTxtRecords(handle)?.get("pw"))
        assertEquals("10:20:30:00:00:00", NativeBridge.nativeGetAirplayTxtRecords(handle)?.get("deviceid"))
        assertEquals("102030000000@JNI Contract", NativeBridge.nativeGetRaopServiceName(handle))
    }

    @Test fun initializedAudioEngineValidatesDebugBuffersAndConfigurationLifecycle() {
        val handle = create()
        assertFalse(NativeBridge.nativeServerAudioStart(handle))
        assertFalse(NativeBridge.nativeServerAudioDebug(handle, ByteBuffer.allocate(1024)))
        assertFalse(NativeBridge.nativeServerAudioDebug(handle, ByteBuffer.allocateDirect(1)))
        val backing = ByteBuffer.allocateDirect(1)
        (backing as Buffer).limit(0)
        assertFalse(NativeBridge.nativeServerAudioDebug(handle, backing.slice()))
        val method = NativeBridge::class.java.getMethod("nativeServerAudioDebug", java.lang.Long.TYPE, ByteBuffer::class.java)
        assertEquals(false, method.invoke(NativeBridge, handle, null))
        val buffer = ByteBuffer.allocateDirect(1024)
        repeat(buffer.capacity()) { buffer.put(it, 90.toByte()) }
        assertTrue(NativeBridge.nativeServerAudioDebug(handle, buffer))
        assertTrue((0 until buffer.capacity()).any { buffer.get(it) != 90.toByte() })
        assertEquals(90.toByte(), buffer.get(buffer.capacity() - 1))
        setDefaultOutputParameters()
        NativeBridge.nativeServerAudioSetClientGain(handle, 0.5f)
        for ((ct, spf) in listOf(2 to 352, 4 to 1024, 8 to 480, 99 to 100, 2 to 0)) NativeBridge.nativeServerAudioFormat(handle, ct, spf)
        assertTrue(NativeBridge.nativeServerAudioConfigure(handle, 50, 95, 0, true, true, false, false, false))
        NativeBridge.nativeServerAudioSetClientGain(handle, 0.25f)
        assertTrue("Configured emulator output did not start", NativeBridge.nativeServerAudioStart(handle))
        assertTrue(NativeBridge.nativeServerAudioDebug(handle, buffer))
        repeat(2) { NativeBridge.nativeServerAudioStop(handle) }
        assertTrue(NativeBridge.nativeServerAudioConfigure(handle, 0, 90, 192, false, false, true, true, true))
    }

    private fun setDefaultOutputParameters() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        NativeBridge.nativeSetDefaultStreamValues(
            audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000,
            audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 192,
        )
    }

    private fun aacFrames(): List<ByteArray> {
        val encoded = context.assets.open("mirror/sample.aac").use { it.readBytes() }
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < encoded.size) {
            assertTrue(offset + 7 <= encoded.size)
            assertEquals(255, encoded[offset].toInt() and 255)
            assertEquals(240, encoded[offset + 1].toInt() and 240)
            val header = if (encoded[offset + 1].toInt() and 1 == 1) 7 else 9
            val length = ((encoded[offset + 3].toInt() and 3) shl 11) or
                ((encoded[offset + 4].toInt() and 255) shl 3) or ((encoded[offset + 5].toInt() and 224) shr 5)
            assertTrue(length > header && offset + length <= encoded.size)
            frames += encoded.copyOfRange(offset + header, offset + length)
            offset += length
        }
        // Independent ffmpeg decoding of this checked-in silence fixture yields
        // 88 x 1024 stereo PCM16 frames (360448 bytes), all zero.
        assertEquals(88, frames.size)
        return frames
    }

    @Test fun rejectedAudioConfigurationPreservesThePreviouslyQueuedChange() {
        val handle = create()
        val frames = aacFrames()
        val debug = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder())
        val base = System.nanoTime()
        fun configure(cushionMs: Int) = NativeBridge.nativeServerAudioConfigure(
            handle, cushionMs, 95, 0, true, true, false, false, false
        )
        fun tunedCushionMs(): Int {
            assertTrue(NativeBridge.nativeServerAudioDebug(handle, debug))
            return debug.getShort(2).toInt() and 0xffff
        }

        NativeBridge.nativeServerAudioFormat(handle, 4, 1024)
        assertTrue(configure(20))
        assertEquals(20, tunedCushionMs())
        assertTrue(configure(40))
        assertEquals(1, DebugCoverage.withPendingAudioConfigAllocationFailure {
            assertFalse(configure(80))
        })
        assertEquals(20, tunedCushionMs())

        NativeBridge.nativeSetCaptureEnabled(handle, true)
        DebugNativeBridge.nativeFeedAudio(handle, frames[0], base)
        assertEquals(40, tunedCushionMs())
        assertTrue(configure(60))
        DebugNativeBridge.nativeFeedAudio(handle, frames[1], base + 1024L * 1_000_000_000L / 44100)
        assertEquals(60, tunedCushionMs())
        assertEquals(2, callbacks.getValue(handle).pcm.size)
    }

    @Test fun concurrentAudioConfigurationsKeepDecodingAndApplyTheFinalCushion() {
        val handle = create()
        val frames = aacFrames()
        fun configure(cushionMs: Int) = NativeBridge.nativeServerAudioConfigure(
            handle, cushionMs, 95, 0, forceSwAlac = true, forceSwAac = true,
            realtimePriority = false, lowLatency = false, benchmarkLog = false,
        )
        NativeBridge.nativeServerAudioFormat(handle, 4, 1024)
        assertTrue(configure(20))
        NativeBridge.nativeSetCaptureEnabled(handle, true)

        val firstPts = System.nanoTime()
        fun pts(index: Int) = firstPts + index * 1024L * 1_000_000_000L / 44100
        fun feed(index: Int) = DebugNativeBridge.nativeFeedAudio(handle, frames[index % frames.size], pts(index))

        val begin = CountDownLatch(1)
        val updates = FutureTask {
            assertTrue(begin.await(5, TimeUnit.SECONDS))
            repeat(80) { assertTrue(configure(30 + it % 20)) }
        }
        val settingsWriter = Thread(updates)
        settingsWriter.start()
        try {
            begin.countDown()
            for (index in frames.indices) feed(index)
            updates.get(5, TimeUnit.SECONDS)
        } finally {
            settingsWriter.join(5_000)
            if (settingsWriter.isAlive) {
                handles.remove(handle)
                fail("A live settings writer still owns the native engine")
            }
        }

        assertTrue(configure(80))
        feed(frames.size)
        val debug = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
        assertTrue(NativeBridge.nativeServerAudioDebug(handle, debug))
        assertEquals(80, debug.getShort(2).toInt() and 0xffff)
        assertEquals(0, debug.getInt(34))
        assertEquals((0..frames.size).map(::pts), callbacks.getValue(handle).pcm.map { it.pts })
    }

    @Test fun adaptiveAudioCushionStaysBoundedWhenAnAacPacketArrivesLate() {
        val handle = create()
        val frames = aacFrames()
        val debug = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
        val frameDurationNs = 1024L * 1_000_000_000L / 44100
        fun tunedCushionMs(): Int {
            assertTrue(NativeBridge.nativeServerAudioDebug(handle, debug))
            return debug.getShort(2).toInt() and 0xffff
        }

        NativeBridge.nativeServerAudioFormat(handle, 4, 1024)
        assertTrue(NativeBridge.nativeServerAudioConfigure(handle, 0, 95, 0, true, true, false, false, false))
        NativeBridge.nativeSetCaptureEnabled(handle, true)

        val firstPts = System.nanoTime()
        DebugNativeBridge.nativeFeedAudio(handle, frames[0], firstPts)
        assertTrue("A single packet should not saturate the adaptive cushion", tunedCushionMs() in 1 until 1000)

        // Keep consecutive sender timestamps while introducing a receiver-side stall.
        android.os.SystemClock.sleep(1200)
        DebugNativeBridge.nativeFeedAudio(handle, frames[1], firstPts + frameDurationNs)
        assertEquals("The adaptive cushion must stop growing at one second", 1000, tunedCushionMs())
        assertEquals(0, debug.getInt(34))
        assertEquals(listOf(firstPts, firstPts + frameDurationNs), callbacks.getValue(handle).pcm.map { it.pts })
    }

    @Test fun softwareAacDecodesExactPcmAcrossReconfigurationAndCaptureToggle() {
        val frames = aacFrames()
        val handle = create()
        val callback = callbacks.getValue(handle)
        NativeBridge.nativeServerAudioFormat(handle, 4, 1024)
        assertTrue(NativeBridge.nativeServerAudioConfigure(handle, 50, 95, 0, true, true, false, false, false))
        NativeBridge.nativeSetCaptureEnabled(handle, true)
        val base = System.nanoTime()
        // Rejected JNI input must not emit PCM or poison the next real decode.
        DebugNativeBridge.nativeFeedAudio(0L, frames.first(), base)
        val feed = DebugNativeBridge::class.java.getMethod("nativeFeedAudio",
            java.lang.Long.TYPE, ByteArray::class.java, java.lang.Long.TYPE)
        feed.invoke(DebugNativeBridge, handle, null, base)
        DebugNativeBridge.nativeFeedAudio(handle, ByteArray(0), base)
        DebugNativeBridge.nativeFeedAudio(handle, ByteArray(8193), base)
        assertTrue("Rejected audio input emitted PCM", callback.pcm.isEmpty())
        val count = frames.size * 3 // enough real decodes to exercise latency reporting
        for (index in 0 until count) {
            if (index == 20)
                assertTrue(NativeBridge.nativeServerAudioConfigure(handle, 80, 90, 0, true, true, false, false, true))
            NativeBridge.nativeSetCaptureEnabled(handle, index != 40)
            DebugNativeBridge.nativeFeedAudio(handle, frames[index % frames.size], base + index * 1024L * 1_000_000_000L / 44100)
        }
        assertEquals(count - 1, callback.pcm.size)
        assertEquals((count - 1) * 4096, callback.pcm.sumOf { it.bytes.size })
        for ((record, index) in callback.pcm.zip((0 until count).filter { it != 40 })) {
            assertEquals(44100, record.rate); assertEquals(2, record.channels)
            assertEquals(4096, record.bytes.size)
            assertTrue(record.bytes.all { it == 0.toByte() })
            assertEquals(base + index * 1024L * 1_000_000_000L / 44100, record.pts)
        }
        assertTrue("Decoder latency callback was not delivered", callback.logs.any { it.contains("decode latency:") })
    }

    @Test fun platformAacDecodesPcmOnItsDrainThreadAndStopsCaptureCleanly() {
        val frames = aacFrames()
        val instrumented = runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess
        for (fast in listOf(false, true)) {
            val handle = create()
            val callback = callbacks.getValue(handle)
            NativeBridge.nativeServerAudioFormat(handle, 4, 1024)
            assertTrue(NativeBridge.nativeServerAudioConfigure(handle, 50, 95, 0, true, false, fast, fast, false))
            NativeBridge.nativeSetCaptureEnabled(handle, true)
            val base = System.nanoTime() / 1000 * 1000
            fun pts(index: Int) = (base + index * 1024L * 1_000_000_000L / 44100) / 1000 * 1000
            fun feed(index: Int) {
                val packet = frames[index % frames.size]
                if (instrumented) DebugCoverage.nativeFeedAwaitingCodecInput(DebugNativeBridge, handle, packet, pts(index))
                else DebugNativeBridge.nativeFeedAudio(handle, packet, pts(index))
            }
            val frameDelayMs = (1024L * 1000 + 44100 - 1) / 44100
            val packetDebug = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
            fun assertAccepted(index: Int) {
                assertTrue(NativeBridge.nativeServerAudioDebug(handle, packetDebug))
                assertEquals("Platform AAC rejected packet $index; PCM=${callback.pcm.size}; logs=${callback.logs}",
                    0, packetDebug.getInt(34))
            }
            // A live MediaCodec stream has no EOS here. Supply a silent tail so
            // decoder lookahead can release every frame of the original fixture.
            val supplied = frames.size + 8
            for (index in 0 until supplied) {
                if (index == 0 && instrumented) {
                    val attempts = DebugCoverage.nativeFeedWithInputNotReady(DebugNativeBridge,
                        handle, frames[index % frames.size], pts(index), 1)
                    assertEquals("Startup input did not recover from transient unavailability", 2, attempts)
                } else feed(index)
                assertAccepted(index)
                // Feed at the fixture's 1024/44100-second packet duration. A
                // 10ms interval is over twice real time and can fill codec input
                // buffers before the emulator schedules the output thread.
                Thread.sleep(frameDelayMs)
            }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            while (callback.pcm.size < frames.size && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("Platform AAC output count=${callback.pcm.size}; logs=${callback.logs}",
                callback.pcm.size in frames.size..supplied)
            var observed = callback.pcm.size
            var stableSince = System.nanoTime()
            while (System.nanoTime() - stableSince < 100_000_000L) {
                assertTrue("Platform output did not settle", System.nanoTime() < deadline)
                Thread.sleep(10)
                if (callback.pcm.size != observed) { observed = callback.pcm.size; stableSince = System.nanoTime() }
            }
            assertTrue("Platform decoder was not selected: ${callback.logs}",
                callback.logs.any { it.contains("audio/mp4a-latm decoder started (") && !it.contains("ffmpeg") })
            if (android.os.Build.VERSION.SDK_INT < 23)
                assertFalse("Lollipop attempted the optional named decoder", callback.logs.any { it.contains("c2.android.inproc.aac.decoder") })
            NativeBridge.nativeSetCaptureEnabled(handle, false)
            repeat(8) { index ->
                feed(supplied + index)
                assertAccepted(supplied + index)
                Thread.sleep(frameDelayMs)
            }
            NativeBridge.nativeServerAudioStop(handle) // joins the drain thread before comparing callbacks
            assertEquals("Disabled capture still delivered PCM", observed, callback.pcm.size)
            for ((index, record) in callback.pcm.withIndex()) {
                assertEquals(44100, record.rate); assertEquals(2, record.channels)
                assertEquals(4096, record.bytes.size)
                assertTrue(record.bytes.all { it == 0.toByte() })
                assertEquals("AAC timestamp $index", pts(index), record.pts)
            }
            val debug = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
            assertTrue(NativeBridge.nativeServerAudioDebug(handle, debug))
            assertEquals("Native decoder reported rejected packets", 0, debug.getInt(34))
        }
    }

    @Test fun platformInputWarmupIsBoundedAndDoesNotExtendTheLiveInputBudget() {
        org.junit.Assume.assumeTrue("Requires coverage-only input availability fixture",
            runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        val frames = aacFrames()
        val handle = create()
        val callback = callbacks.getValue(handle)
        NativeBridge.nativeServerAudioFormat(handle, 4, 1024)
        assertTrue(NativeBridge.nativeServerAudioConfigure(handle, 50, 95, 0, true, false, false, false, false))
        NativeBridge.nativeSetCaptureEnabled(handle, true)
        val base = System.nanoTime() / 1000 * 1000
        fun pts(index: Int) = (base + index * 1024L * 1_000_000_000L / 44100) / 1000 * 1000
        fun forced(index: Int, failures: Int) = DebugCoverage.nativeFeedWithInputNotReady(
            DebugNativeBridge, handle, frames[index % frames.size], pts(index), failures)
        val debug = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
        fun rejected(expected: Int) {
            assertTrue(NativeBridge.nativeServerAudioDebug(handle, debug))
            assertEquals("Unexpected platform input rejection; logs=${callback.logs}", expected, debug.getInt(34))
        }
        assertEquals("Persistent startup starvation was not bounded", 2, forced(0, 2))
        rejected(1)
        assertTrue(callback.pcm.isEmpty())
        assertEquals("Startup did not recover on its second attempt", 2, forced(0, 1))
        rejected(1)
        assertEquals("Live input incorrectly used the startup retry", 1, forced(1, 1))
        rejected(2)
        val supplied = frames.size + 8
        val frameDelayMs = (1024L * 1000 + 44100 - 1) / 44100
        for (index in 1 until supplied) {
            DebugCoverage.nativeFeedAwaitingCodecInput(DebugNativeBridge, handle, frames[index % frames.size], pts(index))
            rejected(2)
            Thread.sleep(frameDelayMs)
        }
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (callback.pcm.size < frames.size && System.nanoTime() < deadline) Thread.sleep(10)
        NativeBridge.nativeSetCaptureEnabled(handle, false)
        NativeBridge.nativeServerAudioStop(handle)
        assertTrue("Platform recovery lost PCM: ${callback.pcm.size}; logs=${callback.logs}",
            callback.pcm.size in frames.size..supplied)
        for ((index, record) in callback.pcm.withIndex()) {
            assertEquals(44100, record.rate); assertEquals(2, record.channels)
            assertEquals(4096, record.bytes.size)
            assertTrue(record.bytes.all { it == 0.toByte() })
            assertEquals("Recovery timestamp $index", pts(index), record.pts)
        }
        rejected(2)
    }

    @Test fun audioRendererPreservesDetachedConfigurationAndClearsEngineLabels() {
        val renderer = AudioRenderer()
        assertEquals(AudioConfig(), renderer.config)
        assertEquals(1f, renderer.clientGain, 0f)
        assertEquals("", renderer.codecLabel)
        assertNull(renderer.audioDebug())
        renderer.start(); renderer.stop()
        renderer.setClientGain(0.25f)
        val config = AudioConfig(cushionMs = 50, forceSwAlac = true, forceSwAac = true,
            realtimePriority = false, lowLatency = false)
        renderer.updateConfig(config)
        assertEquals(config, renderer.config)
        assertEquals(0.25f, renderer.clientGain, 0f)
        for ((ct, label) in listOf(2 to "ALAC", 4 to "AAC-LC", 8 to "AAC-ELD", 99 to "?")) {
            renderer.setFormat(ct, 1024)
            assertEquals(label, renderer.codecLabel)
        }
        renderer.detachEngine()
        assertEquals("", renderer.codecLabel)
        assertNull(renderer.audioDebug())
        val handle = create()
        renderer.attachEngine(handle)
        try {
            val debug = requireNotNull(renderer.audioDebug())
            assertEquals(0, debug.backlogMs)
            assertEquals(50, debug.tunedCushionMs)
            assertEquals(0, debug.decodeErrors)
            renderer.setClientGain(0.75f)
            assertEquals(0.75f, renderer.clientGain, 0f)
            renderer.setFormat(4, 1024)
            assertEquals("AAC-LC", renderer.codecLabel)
            renderer.start(); renderer.stop(); renderer.stop()
            assertEquals("", renderer.codecLabel)
            assertNotNull(renderer.audioDebug())
        } finally { renderer.detachEngine() }
        assertNull(renderer.audioDebug())
        renderer.start(); renderer.stop()
        assertEquals(config, renderer.config)
    }

    @Test fun audioRendererAppliesQueuedConfigurationAtTheNextDecodedPacket() {
        val handle = create()
        setDefaultOutputParameters()
        val renderer = AudioRenderer()
        val receiver = callbacks.getValue(handle)
        fun outputsOpened() = receiver.logs.count { it.startsWith("Oboe out:") }
        val initial = AudioConfig(cushionMs = 50, forceSwAlac = true, forceSwAac = true,
            realtimePriority = false, lowLatency = false)
        renderer.updateConfig(initial)
        renderer.attachEngine(handle)
        try {
            renderer.setFormat(4, 1024)
            renderer.start()
            assertTrue("Initial playback output did not start", NativeBridge.nativeServerAudioStart(handle))
            assertEquals(1, outputsOpened())
            NativeBridge.nativeSetCaptureEnabled(handle, true)
            val packet = aacFrames().first()
            val base = System.nanoTime()
            DebugNativeBridge.nativeFeedAudio(handle, packet, base)
            assertEquals(50, requireNotNull(renderer.audioDebug()).tunedCushionMs)
            val changed = initial.copy(cushionMs = 80, percentilePct = 90, benchmarkLog = true)
            renderer.updateConfig(initial.copy(cushionMs = 60))
            renderer.updateConfig(changed)
            assertEquals(changed, renderer.config)
            // The control thread queues updates; only the decode thread rebuilds.
            assertEquals(50, requireNotNull(renderer.audioDebug()).tunedCushionMs)
            assertEquals(1, outputsOpened())
            DebugNativeBridge.nativeFeedAudio(handle, packet, base + 30_000_000)
            val debug = requireNotNull(renderer.audioDebug())
            assertEquals(80, debug.tunedCushionMs)
            assertEquals(0, debug.decodeErrors)
            assertEquals("Reconfiguration did not open replacement output", 2, outputsOpened())
            assertTrue("Replacement playback output did not stay active", NativeBridge.nativeServerAudioStart(handle))
            assertEquals("Requesting Start again opened another output", 2, outputsOpened())
            val pcm = receiver.pcm
            assertEquals(2, pcm.size)
            assertEquals(listOf(base, base + 30_000_000), pcm.map { it.pts })
            assertTrue(pcm.all { record -> record.bytes.size == 4096 && record.bytes.all { it == 0.toByte() } })
        } finally { renderer.stop(); renderer.detachEngine() }
    }
}
