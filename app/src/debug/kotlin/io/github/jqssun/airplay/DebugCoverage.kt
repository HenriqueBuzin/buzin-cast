package io.github.jqssun.airplay

import android.content.Context
import java.io.File

/** Explicit checkpoint: force-stop does not run Java or native shutdown hooks. */
object DebugCoverage {
    external fun nativeDump(path: String): Int
    /** Contract fixture is linked only into the instrumented native library. */
    external fun nativeAudioContracts(): String
    external fun nativeDnsContracts(): String
    external fun nativeCallbackContracts(callback: io.github.jqssun.airplay.bridge.RaopCallbackHandler): String
    external fun nativeDecodeAlac(packets: Array<ByteArray>, timestamps: LongArray, samplesPerFrame: Int): Array<ByteArray>
    fun dump(context: Context) {
        val dir = File(context.filesDir, "coverage").apply { mkdirs() }
        val runtime = Class.forName("org.jacoco.agent.rt.RT").getMethod("getAgent").invoke(null)
        val bytes = runtime.javaClass.getMethod("getExecutionData", Boolean::class.javaPrimitiveType)
            .invoke(runtime, false) as ByteArray
        File(dir, "android.ec").writeBytes(bytes)
        System.loadLibrary("airplay_native")
        val native = File(dir, "native.profraw")
        check(!native.exists() || native.delete()) { "Cannot replace native profile" }
        check(nativeDump(native.absolutePath) == 0) { "Native coverage write failed" }
    }
}
