package io.github.jqssun.airplay

import android.content.Context
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.bridge.LogListener
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.Buffer
import java.nio.ByteBuffer

/** Real initialized JNI contexts; the callbacks never open UI or publish NSD services. */
@RunWith(AndroidJUnit4::class)
class NativeServerContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val handles = mutableListOf<Long>()
    private val keys = mutableListOf<File>()

    private class Callbacks : RaopCallbackHandler, LogListener {
        fun onConnectionRequest(peer: String) = true
        override fun onLog(msg: String) {}
        override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {}
        override fun onAudioPcm(data: ByteArray, ntpTimeNs: Long, sampleRate: Int, channels: Int) {}
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
        val handle = NativeBridge.nativeInit(Callbacks(), mac, "JNI Contract", key.absolutePath, pin, pin)
        assertNotEquals("Native initialization failed", 0L, handle)
        handles += handle
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
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        NativeBridge.nativeSetDefaultStreamValues(audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000,
            audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 192)
        NativeBridge.nativeServerAudioSetClientGain(handle, 0.5f)
        for ((ct, spf) in listOf(2 to 352, 4 to 1024, 8 to 480, 99 to 100, 2 to 0)) NativeBridge.nativeServerAudioFormat(handle, ct, spf)
        assertTrue(NativeBridge.nativeServerAudioConfigure(handle, 50, 95, 0, true, true, false, false, false))
        NativeBridge.nativeServerAudioSetClientGain(handle, 0.25f)
        assertTrue("Configured emulator output did not start", NativeBridge.nativeServerAudioStart(handle))
        assertTrue(NativeBridge.nativeServerAudioDebug(handle, buffer))
        repeat(2) { NativeBridge.nativeServerAudioStop(handle) }
        assertTrue(NativeBridge.nativeServerAudioConfigure(handle, 0, 90, 192, false, false, true, true, true))
    }
}
