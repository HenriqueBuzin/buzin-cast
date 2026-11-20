package io.github.jqssun.airplay

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.media.NetworkDiagnostics
import io.github.jqssun.airplay.media.currentNetworkCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkDiagnosticsContractTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = target.getSharedPreferences("network-diagnostics-contract", Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(target) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            assertEquals(Prefs.NAME, name)
            return preferences
        }
    }

    @After fun cleanup() {
        assertTrue(preferences.edit().clear().commit())
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(target)
    }

    @Test fun stoppedReceiverReportsMissingIpv4VpnAndUsbOnlyMode() {
        configure(usbOnly = true, dlna = false, fcast = true)
        val report = NetworkDiagnostics.format(context, running = false, ip = null, vpnActive = true)
        assertEquals(listOf(
            context.getString(R.string.diag_no_ipv4),
            context.getString(R.string.diag_receiver_stopped),
            context.getString(R.string.diag_vpn_active),
            context.getString(R.string.diag_usb_only),
            context.getString(R.string.diag_airplay, "Media room", 7123),
            context.getString(R.string.diag_media_protocols,
                context.getString(R.string.chip_off), context.getString(R.string.chip_on)),
            "",
            context.getString(R.string.diag_help),
        ).joinToString(separator = "\n", postfix = "\n"), report)
    }

    @Test fun runningReceiverReportsItsLanAddressAndConfiguredProtocols() {
        configure(usbOnly = false, dlna = true, fcast = false)
        val report = NetworkDiagnostics.format(context, running = true, ip = "192.0.2.10", vpnActive = false)
        assertEquals(listOf(
            context.getString(R.string.diag_tv_ip, "192.0.2.10"),
            context.getString(R.string.diag_receiver_running),
            context.getString(R.string.diag_airplay, "Media room", 7123),
            context.getString(R.string.diag_media_protocols,
                context.getString(R.string.chip_on), context.getString(R.string.chip_off)),
            "",
            context.getString(R.string.diag_help),
        ).joinToString(separator = "\n", postfix = "\n"), report)
    }

    @Test fun publicReportMatchesTheDefaultAndroidNetwork() {
        val manager = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = manager.currentNetworkCompat()
        val expectation = InstrumentationRegistry.getArguments().getString("networkDiagnosticState")
        if (expectation == "offline") assertNull("The test emulator still has a default network", activeNetwork)
        val hasVpn = activeNetwork?.let(manager::getNetworkCapabilities)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val report = NetworkDiagnostics.report(context, running = false)
        assertEquals(hasVpn, context.getString(R.string.diag_vpn_active) in report.lineSequence())
        assertTrue(context.getString(R.string.diag_receiver_stopped) in report.lineSequence())
    }

    private fun configure(usbOnly: Boolean, dlna: Boolean, fcast: Boolean) {
        assertTrue(preferences.edit()
            .putString(Prefs.SERVER_NAME.key, "Media room")
            .putInt(Prefs.SERVER_PORT.key, 7123)
            .putBoolean(Prefs.USB_ONLY.key, usbOnly)
            .putBoolean(Prefs.DLNA_ENABLED.key, dlna)
            .putBoolean(Prefs.FCAST_ENABLED.key, fcast)
            .commit())
    }
}
