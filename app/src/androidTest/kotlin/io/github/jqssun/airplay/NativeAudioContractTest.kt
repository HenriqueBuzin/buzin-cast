package io.github.jqssun.airplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real native C API boundaries; fixture exists only with -PbuzinCoverage=true. */
@RunWith(AndroidJUnit4::class)
class NativeAudioContractTest {
    @Test fun invalidInputsAndPreconfigurationLifecycleAreSafe() {
        assumeTrue("Requires the instrumented coverage APK", runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        System.loadLibrary("airplay_native")
        try {
            assertEquals("audio contracts passed", DebugCoverage.nativeAudioContracts())
        } finally {
            DebugCoverage.dump(InstrumentationRegistry.getInstrumentation().targetContext)
        }
    }
}
