package io.github.jqssun.airplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess

// Permission is introduced in API 37; a literal keeps this build compatible with SDK 36.
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
const val LOCAL_NETWORK_API = 37

fun Context.hasLocalNetwork() = Build.VERSION.SDK_INT < LOCAL_NETWORK_API ||
    ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

internal fun resolveLocalNetworkAccess(
    granted: Boolean,
    requested: Boolean,
    previous: LocalNetworkAccess,
    showRationale: () -> Boolean,
): LocalNetworkAccess = when {
    granted -> LocalNetworkAccess.GRANTED
    showRationale() -> LocalNetworkAccess.ASK
    requested || previous == LocalNetworkAccess.SETTINGS -> LocalNetworkAccess.SETTINGS
    else -> LocalNetworkAccess.ASK
}

fun Context.appSettingsIntent() =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
