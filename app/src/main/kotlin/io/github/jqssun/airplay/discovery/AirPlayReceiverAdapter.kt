package io.github.jqssun.airplay.discovery

import io.buzincast.core.ReceiverAdapter
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler

/** Owns the JNI engine lifetime; renderer/configuration policy is supplied by the host. */
class AirPlayReceiverAdapter(
    private val callbacks: RaopCallbackHandler,
    private val address: ByteArray, private val name: String, private val keyFile: String,
    private val allowNewConnection: Boolean, private val requirePin: Boolean,
    private val requestedPort: Int, private val configure: (Long) -> Unit,
) : ReceiverAdapter {
    @Volatile var handle: Long = 0L
        private set
    var port: Int = -1
        private set
    private var closed = false
    @Synchronized override fun start() {
        if (closed || handle != 0L) return
        handle = NativeBridge.nativeInit(callbacks,address,name,keyFile,allowNewConnection,requirePin)
        check(handle != 0L) { "Native AirPlay init failed" }
        configure(handle)
        port = NativeBridge.nativeStart(handle,requestedPort)
        // The host must detach the audio renderer before closing a failed engine.
        check(port >= 0) { "Cannot bind AirPlay port $requestedPort" }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val engine = handle
        handle = 0L; port = -1
        if (engine != 0L) {
            try { NativeBridge.nativeStop(engine) }
            finally { NativeBridge.nativeDestroy(engine) }
        }
    }
}
