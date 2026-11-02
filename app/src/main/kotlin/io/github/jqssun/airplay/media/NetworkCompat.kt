package io.github.jqssun.airplay.media

import android.net.ConnectivityManager
import android.net.Network
import android.os.Build

/** Android 5.1 exposes connected networks, but has no activeNetwork accessor. */
@Suppress("DEPRECATION")
internal fun ConnectivityManager.currentNetworkCompat(): Network? {
    if (Build.VERSION.SDK_INT >= 23) return activeNetwork
    val active = activeNetworkInfo?.takeIf { it.isConnected } ?: return null
    return allNetworks.firstOrNull { network -> getNetworkInfo(network)?.let { it.isConnected && it.type == active.type } == true }
}
