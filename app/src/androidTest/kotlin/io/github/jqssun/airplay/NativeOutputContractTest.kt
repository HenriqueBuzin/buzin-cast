package io.github.jqssun.airplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Oboe streams on the emulator, with explicit post-close callback delivery. */
@RunWith(AndroidJUnit4::class)
class NativeOutputContractTest {
    private fun scenario(number: Int) {
        assumeTrue("Requires coverage JNI fixture", runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        System.loadLibrary("airplay_native")
        try {
            assertEquals("output contracts passed", DebugCoverage.nativeOutputContracts(number))
        } finally { DebugCoverage.dump(InstrumentationRegistry.getInstrumentation().targetContext) }
    }
    @Test fun lateCallbacksDoNotReopenStoppedOrDestroyedOwners() = scenario(0)
    @Test fun postCloseRecoveryOpensAndStartsADifferentStream() = scenario(1)
    @Test fun invalidOutputFormatFailsStartupAndRecoveryWithoutLeavingAStream() = scenario(2)
    @Test fun debugPollingReturnsCachedUnderrunsWhileOutputIsLocked() = scenario(3)
    @Test fun losingTheStreamBeforeStartAllowsTheNextAutomaticBufferStartup() = scenario(4)
}
