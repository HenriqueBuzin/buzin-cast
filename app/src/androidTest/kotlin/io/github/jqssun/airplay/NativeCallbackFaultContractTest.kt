package io.github.jqssun.airplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Failure injection models the JNI dependency, never a real ART out-of-memory. */
@RunWith(AndroidJUnit4::class)
class NativeCallbackFaultContractTest {
    private fun checkFailures(scenarios: IntRange, failures: IntRange) {
        assumeTrue("Requires coverage JNI fixture", runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        System.loadLibrary("airplay_native")
        try {
            for (scenario in scenarios) for (failure in failures) {
                assertEquals("scenario=$scenario failure=$failure", "callback fault contracts passed",
                    DebugCoverage.nativeCallbackFaultContracts(scenario, failure))
            }
        } finally { DebugCoverage.dump(InstrumentationRegistry.getInstrumentation().targetContext) }
    }
    @Test fun allocationFailuresReleaseReferencesAndRecover() = checkFailures(0..7, 0..0)
    @Test fun byteCopyFailuresDoNotInvokeJavaAndRecover() {
        checkFailures(0..2, 1..1)
        checkFailures(7..7, 1..1)
    }
    @Test fun unavailableVmDoesNotUseItsOutputPointer() = checkFailures(0..7, 2..3)
    @Test fun throwingCallbacksReleaseReferencesAndRecover() = checkFailures(0..7, 4..4)
}
