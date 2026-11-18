package io.github.jqssun.airplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Calls production DNS-SD C APIs; the Android shim never publishes DNS itself. */
@RunWith(AndroidJUnit4::class)
class NativeDnsContractTest {
    @Test fun recordsFeaturesCodecsAndInvalidIndicesPreserveTheirContracts() {
        assumeTrue("Requires the instrumented coverage APK", runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        System.loadLibrary("airplay_native")
        try {
            assertEquals("DNS contracts passed", DebugCoverage.nativeDnsContracts())
        } finally {
            DebugCoverage.dump(InstrumentationRegistry.getInstrumentation().targetContext)
        }
    }
}
