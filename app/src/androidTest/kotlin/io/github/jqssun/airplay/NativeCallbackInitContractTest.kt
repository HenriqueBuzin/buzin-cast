package io.github.jqssun.airplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Model only JNI's dependency failures; no real VM allocation is exhausted. */
@RunWith(AndroidJUnit4::class)
class NativeCallbackInitContractTest {
    private fun scenarios(modes: Iterable<Int>) {
        assumeTrue("Requires coverage JNI fixture", runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        System.loadLibrary("airplay_native")
        try {
            for (mode in modes) assertEquals("mode=$mode", "callback init contracts passed",
                DebugCoverage.nativeCallbackInitContracts(mode))
        } finally { DebugCoverage.dump(InstrumentationRegistry.getInstrumentation().targetContext) }
    }
    @Test fun missingJavaCallbackRaisesAJavaErrorWithoutStartingAReceiver() {
        scenarios(listOf(4)) // Verify native cleanup before invoking an invalid real Java sink.
        val callback = java.lang.reflect.Proxy.newProxyInstance(
            io.github.jqssun.airplay.bridge.RaopCallbackHandler::class.java.classLoader,
            arrayOf(io.github.jqssun.airplay.bridge.RaopCallbackHandler::class.java),
        ) { _, _, _ -> null } as io.github.jqssun.airplay.bridge.RaopCallbackHandler
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val failure = runCatching {
            io.github.jqssun.airplay.bridge.NativeBridge.nativeInit(callback, ByteArray(6),
                "JNI contract", java.io.File(context.cacheDir, "unused-init-key").absolutePath, false, false)
        }.exceptionOrNull()
        org.junit.Assert.assertTrue("Missing callback did not propagate its Java lookup error: $failure",
            failure is NoSuchMethodError)
        scenarios(listOf(0)) // The following initialization still succeeds.
    }
    @Test fun completeBindingResolvesEveryMethodAndOwnsItsReferences() = scenarios(listOf(0))
    @Test fun unavailableOrNullVmStopsBeforeAllocatingReferences() = scenarios(listOf(1, 27))
    @Test fun allocationAndClassFailuresCleanUpAndPreserveTheException() = scenarios(listOf(2, 3, 26))
    @Test fun everyMissingCallbackStopsLookupAndReleasesPartialBinding() = scenarios(4..25)
}
