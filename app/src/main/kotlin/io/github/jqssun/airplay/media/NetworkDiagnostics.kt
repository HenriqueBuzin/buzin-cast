package io.github.jqssun.airplay.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.get
import io.leancast.app.Net

object NetworkDiagnostics {
    fun report(context: Context, running: Boolean): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.currentNetworkCompat()
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val vpnActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        return format(context, running, Net.lanIpv4(), vpnActive)
    }

    internal fun format(context: Context, running: Boolean, ip: String?, vpnActive: Boolean): String {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        return buildString {
            appendLine(if (ip == null) context.getString(R.string.diag_no_ipv4) else context.getString(R.string.diag_tv_ip, ip))
            appendLine(context.getString(if (running) R.string.diag_receiver_running else R.string.diag_receiver_stopped))
            if (vpnActive) {
                appendLine(context.getString(R.string.diag_vpn_active))
            }
            if (prefs[Prefs.USB_ONLY]) appendLine(context.getString(R.string.diag_usb_only))
            appendLine(context.getString(R.string.diag_airplay, prefs[Prefs.SERVER_NAME], prefs[Prefs.SERVER_PORT]))
            val dlna = context.getString(if (prefs[Prefs.DLNA_ENABLED]) R.string.chip_on else R.string.chip_off)
            val fcast = context.getString(if (prefs[Prefs.FCAST_ENABLED]) R.string.chip_on else R.string.chip_off)
            appendLine(context.getString(R.string.diag_media_protocols, dlna, fcast))
            appendLine()
            appendLine(context.getString(R.string.diag_help))
        }
    }
}
