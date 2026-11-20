package io.github.jqssun.airplay

import android.content.Context
import io.github.jqssun.airplay.bridge.LogListener
import java.io.File

/** Explicit checkpoint: force-stop does not run Java or native shutdown hooks. */
object DebugCoverage {
    external fun nativeDump(path: String): Int
    /** Contract fixture is linked only into the instrumented native library. */
    external fun nativeAudioContracts(): String
    external fun nativeFfmpegFailureContracts(packet: ByteArray): String
    external fun nativeAacConfigurationContracts(): String
    external fun nativePlatformDecoderFallbackContracts(): String
    external fun nativeDnsContracts(): String
    external fun nativeCallbackContracts(callback: io.github.jqssun.airplay.bridge.RaopCallbackHandler): String
    external fun nativeDecodeAlac(packets: Array<ByteArray>, timestamps: LongArray, samplesPerFrame: Int,
        attachSinkAfterFirstDecode: Boolean = false, forceSoftware: Boolean = true): Array<ByteArray>
    external fun nativeOutputContracts(scenario: Int, log: LogListener? = null): String
    external fun nativeCallbackFaultContracts(scenario: Int, failure: Int): String
    external fun nativeCallbackInitContracts(mode: Int): String
    external fun nativeCreatePartialServer(callback: io.github.jqssun.airplay.bridge.RaopCallbackHandler): Long
    // Order matches InitStage in coverage_bridge.cpp.
    enum class InitFailure { RAOP_ALLOCATION, RAOP_CONFIGURATION, DNS_ALLOCATION }
    fun withInitFailure(stage: InitFailure, action: Runnable) = nativeWithInitFailure(stage.ordinal, action)
    private external fun nativeWithInitFailure(stage: Int, action: Runnable): Int
    external fun nativeInitWithPinResult(bridge: io.github.jqssun.airplay.bridge.NativeBridge,
        callback: io.github.jqssun.airplay.bridge.RaopCallbackHandler, hardware: ByteArray,
        name: String, keyFile: String, result: Int): Long
    external fun nativeFeedWithInputNotReady(bridge: DebugNativeBridge, handle: Long,
        data: ByteArray, ptsNs: Long, failures: Int): Int
    external fun nativeDiscoveryMapWithMissingPart(bridge: io.github.jqssun.airplay.bridge.NativeBridge,
        handle: Long, raop: Boolean, part: Int): Map<String, String>?
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
