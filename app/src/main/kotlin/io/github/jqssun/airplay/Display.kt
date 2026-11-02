package io.github.jqssun.airplay

import android.content.Context
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
