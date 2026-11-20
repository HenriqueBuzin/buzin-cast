package io.github.jqssun.airplay

import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor

/** Exposes an Android VPN transport to the diagnostic test on a disposable emulator. */
class DebugDiagnosticVpnService : VpnService() {
    inner class Control : Binder() {
        fun establish(): ParcelFileDescriptor? = Builder()
            .setSession("BuzinCast diagnostics test")
            .addAddress("192.0.2.1", 32)
            .addRoute("198.51.100.1", 32)
            .addAllowedApplication(packageName)
            .establish()
    }

    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == SERVICE_INTERFACE) super.onBind(intent) else Control()
}
