package io.github.jqssun.airplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.bridge.LogListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Real Oboe streams on the emulator, with explicit post-close callback delivery. */
@RunWith(AndroidJUnit4::class)
class NativeOutputContractTest {
    private fun scenario(number: Int, log: LogListener? = null) {
        assumeTrue("Requires coverage JNI fixture", runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        System.loadLibrary("airplay_native")
        try {
            assertEquals("output contracts passed", DebugCoverage.nativeOutputContracts(number, log))
        } finally { DebugCoverage.dump(InstrumentationRegistry.getInstrumentation().targetContext) }
    }
    @Test fun lateCallbacksDoNotReopenStoppedOrDestroyedOwners() = scenario(0)
    @Test fun postCloseRecoveryOpensAndStartsADifferentStream() = scenario(1)
    @Test fun invalidOutputFormatFailsStartupAndRecoveryWithoutLeavingAStream() = scenario(2)
    @Test fun debugPollingReturnsCachedUnderrunsWhileOutputIsLocked() = scenario(3)
    @Test fun losingTheStreamBeforeStartAllowsTheNextAutomaticBufferStartup() = scenario(4)
    @Test fun diagnosticLogDescribesUnknownAndMmapBackendsAndKeepsCachedUnderruns() {
        val messages = CopyOnWriteArrayList<String>()
        scenario(5, object : LogListener {
            override fun onLog(msg: String) { messages.add(msg) }
        })
        assertTrue(messages.toString(), messages.any { it.startsWith("Oboe out: ?, mmap=no,") })
        assertTrue(messages.toString(), messages.any { it.startsWith("Oboe out: AAudio, mmap=yes,") })
    }
}
