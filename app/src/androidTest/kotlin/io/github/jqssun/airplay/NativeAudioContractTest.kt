package io.github.jqssun.airplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream

/** Real native C API boundaries; fixture exists only with -PbuzinCoverage=true. */
@RunWith(AndroidJUnit4::class)
class NativeAudioContractTest {
    private fun decoderContract(expected: String, check: () -> String) {
        assumeTrue("Requires the instrumented coverage APK", runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        System.loadLibrary("airplay_native")
        try {
            assertEquals(expected, check())
        } finally {
            DebugCoverage.dump(InstrumentationRegistry.getInstrumentation().targetContext)
        }
    }

    @Test fun aacFrameSizesUseTheExpectedAudioSpecificConfig() =
        decoderContract("AAC configuration contracts passed", DebugCoverage::nativeAacConfigurationContracts)

    @Test fun aacFallingBackAfterPlatformFailureReleasesBothDecoders() =
        decoderContract("platform decoder fallback contracts passed", DebugCoverage::nativePlatformDecoderFallbackContracts)

    @Test fun invalidInputsAndPreconfigurationLifecycleAreSafe() =
        decoderContract("audio contracts passed", DebugCoverage::nativeAudioContracts)

    @Test fun ffmpegAllocationFailuresReleasePartialStateAndTheNextDecoderProducesPcm() {
        assumeTrue("Requires the instrumented coverage APK", runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packet = DataInputStream(instrumentation.context.assets.open("native_audio/raop352/alac16.packets")).use {
            val length = it.readInt()
            assertTrue(length in 1..8192)
            ByteArray(length).also(it::readFully)
        }
        System.loadLibrary("airplay_native")
        try {
            assertEquals("ffmpeg failure contracts passed", DebugCoverage.nativeFfmpegFailureContracts(packet))
        } finally {
            DebugCoverage.dump(instrumentation.targetContext)
        }
    }
}
