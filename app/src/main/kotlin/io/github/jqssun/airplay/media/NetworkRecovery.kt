package io.github.jqssun.airplay.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/** Debounces real default-network transitions. No periodic receiver restart. */
class NetworkRecovery(context: Context, private val changed: (Boolean) -> Unit) {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val main = Handler(Looper.getMainLooper())
    private var fingerprint: String? = null
    private var initialized = false
    private var stopped = false
    private val check = Runnable {
        if (!stopped) {
            val network = manager.currentNetworkCompat()
            val props = network?.let(manager::getLinkProperties)
            val addresses = props?.linkAddresses?.mapNotNull { it.address.hostAddress }.orEmpty().sorted()
            val current = if (network != null && addresses.isNotEmpty()) "$network:${addresses.joinToString()}" else null
            if (!initialized) { initialized = true; fingerprint = current }
            else if (current != fingerprint) { fingerprint = current; changed(current != null) }
        }
    }
    private fun schedule() { main.removeCallbacks(check); main.postDelayed(check,1500) }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule()
        override fun onLost(network: Network) = schedule()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = schedule()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = schedule()
    }
    fun start() {
        if (android.os.Build.VERSION.SDK_INT >= 24) manager.registerDefaultNetworkCallback(callback)
        else manager.registerNetworkCallback(android.net.NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
        schedule()
    }
    fun close() { stopped = true; main.removeCallbacks(check); runCatching { manager.unregisterNetworkCallback(callback) } }
}
