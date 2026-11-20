package io.github.jqssun.airplay

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.view.Display

// physical panel resolution
fun Context.realDisplaySize(): Pair<Int, Int> {
    val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
    if (android.os.Build.VERSION.SDK_INT >= 23) display?.mode?.let { return it.physicalWidth to it.physicalHeight }
    if (display != null) {
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        return size.x to size.y
    }
    return resources.displayMetrics.let { it.widthPixels to it.heightPixels }
}

internal fun receiverDisplaySize(
    resolution: String,
    orientation: Int,
    panel: Pair<Int, Int>,
    clamp: Boolean,
    decoderLimit: () -> Pair<Int, Int>,
): Pair<Int, Int> {
    val portrait = when (resolution) {
        Prefs.PORTRAIT -> true
        Prefs.LANDSCAPE -> false
        else -> orientation == Configuration.ORIENTATION_PORTRAIT
    }
    val (panelWidth, panelHeight) = panel
    val oriented = if (portrait != (panelHeight >= panelWidth)) panelHeight to panelWidth else panel
    if (resolution == Prefs.PORTRAIT || resolution == Prefs.LANDSCAPE) return oriented

    val (width, height) = if (resolution.contains('x')) {
        val (explicitWidth, explicitHeight) = resolution.split('x')
        explicitWidth.toInt() to explicitHeight.toInt()
    } else oriented
    if (!clamp) return width to height

    // Strict decoders can black-screen beyond their limits.
    val (maxWidth, maxHeight) = decoderLimit()
    return width.coerceAtMost(maxWidth) to height.coerceAtMost(maxHeight)
}
