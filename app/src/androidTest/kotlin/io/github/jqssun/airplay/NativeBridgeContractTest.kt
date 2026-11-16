package io.github.jqssun.airplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.bridge.NativeBridge
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** Calls the shipped JNI entry points, including teardown before initialization. */
@RunWith(AndroidJUnit4::class)
class NativeBridgeContractTest {
    @After fun checkpoint() {
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
            DebugCoverage.dump(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test fun absentServerRejectsStartAndReturnsNoDiscoveryRecords() {
        assertEquals(-1, NativeBridge.nativeStart(0L, 7000))
        assertNull(NativeBridge.nativeGetRaopTxtRecords(0L))
        assertNull(NativeBridge.nativeGetAirplayTxtRecords(0L))
        assertNull(NativeBridge.nativeGetRaopServiceName(0L))
        assertNull(NativeBridge.nativeGetServerName(0L))
    }

    @Test fun absentServerSafelyIgnoresConfigurationAndRepeatedTeardown() {
        repeat(2) {
            NativeBridge.nativeStop(0L)
            NativeBridge.nativeDestroy(0L)
        }
        NativeBridge.nativeSetDisplaySize(0L, 1920, 1080, 60)
        NativeBridge.nativeSetPlist(0L, "width", 1920)
        NativeBridge.nativeSetLang(0L, "en")
        for (enabled in listOf(false, true)) {
            NativeBridge.nativeSetCaptureEnabled(0L, enabled)
            NativeBridge.nativeSetH265Enabled(0L, enabled)
            NativeBridge.nativeSetHlsEnabled(0L, enabled)
            NativeBridge.nativeSetFeature(0L, 42, enabled)
            NativeBridge.nativeSetCodecs(0L, enabled, !enabled)
            NativeBridge.nativeUpdatePlaybackInfo(0L, 1f, 10f, 1f, enabled)
        }
    }

    @Test fun absentAudioEngineRejectsActivationAndDebugWithoutCrashing() {
        assertFalse(NativeBridge.nativeServerAudioConfigure(0L, 50, 95, 256, false, false, false, false, false))
        assertFalse(NativeBridge.nativeServerAudioStart(0L))
        assertFalse(NativeBridge.nativeServerAudioDebug(0L, ByteBuffer.allocateDirect(256)))
        assertFalse(NativeBridge.nativeServerAudioDebug(0L, ByteBuffer.allocate(256)))
        NativeBridge.nativeServerAudioSetClientGain(0L, 0.5f)
        NativeBridge.nativeServerAudioFormat(0L, 2, 352)
        repeat(2) { NativeBridge.nativeServerAudioStop(0L) }
    }
}
