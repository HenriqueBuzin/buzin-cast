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

    @Test fun aacUsesThePreferredDecoderWhenItStartsAndReleasesItAfterward() =
        decoderContract("preferred decoder contracts passed", DebugCoverage::nativePreferredDecoderContracts)

    @Test fun platformDrainStartupFailureReleasesCodecAndFallsBackToFfmpeg() =
        decoderContract("decoder drain startup contracts passed", DebugCoverage::nativeDecoderDrainStartupContracts)

    @Test fun ffmpegForwardsWarningsAndErrorsAtTheCorrectAndroidPriority() =
        decoderContract("FFmpeg log contracts passed", DebugCoverage::nativeFfmpegLogContracts)

    @Test fun platformInputHandlesMissingLimitedAndRejectedBuffers() =
        decoderContract("platform input buffer contracts passed", DebugCoverage::nativePlatformInputBufferContracts)

    @Test fun platformDrainReleasesMissingAndEmptyBuffersAndCopiesValidPcm() =
        decoderContract("platform output buffer contracts passed", DebugCoverage::nativePlatformOutputBufferContracts)

    @Test fun decoderLatencySkipsUnmatchedTimestampsAndReportsWithLoggingDisabled() =
        decoderContract("decoder latency contracts passed", DebugCoverage::nativeDecoderLatencyContracts)

    @Test fun invalidInputsAndPreconfigurationLifecycleAreSafe() =
        decoderContract("audio contracts passed", DebugCoverage::nativeAudioContracts)

    @Test fun readingAudioDiagnosticsDoesNotWaitForEngineRebuild() =
        decoderContract("engine debug cache contract passed", DebugCoverage::nativeEngineDebugCacheContract)

    @Test fun ffmpegAllocationFailuresReleasePartialStateAndTheNextDecoderProducesPcm() =
        decoderContract("ffmpeg failure contracts passed") {
            DebugCoverage.nativeFfmpegFailureContracts(firstAlacPacket())
        }

    @Test fun decoderWaitsForRetryDeadlineThenResumesTheSameAlacStream() =
        decoderContract("decoder retry contract passed") {
            DebugCoverage.nativeDecoderRetryContract(firstAlacPacket(), firstAlacPacket("native_audio"))
        }

    private fun firstAlacPacket(assetPath: String = "native_audio/raop352"): ByteArray = DataInputStream(
        InstrumentationRegistry.getInstrumentation().context.assets.open("$assetPath/alac16.packets")
    ).use {
        val length = it.readInt()
        assertTrue(length in 1..8192)
        ByteArray(length).also(it::readFully)
    }
}
