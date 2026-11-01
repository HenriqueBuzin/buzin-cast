package io.buzincast.media

/** Fit the full source into the available rectangle, then add a symmetric TV safe area. */
object DisplayFit {
    data class Viewport(val x: Int, val y: Int, val width: Int, val height: Int)
    fun viewport(surfaceWidth: Int, surfaceHeight: Int, videoWidth: Int, videoHeight: Int, marginPercent: Int): Viewport {
        require(surfaceWidth > 0 && surfaceHeight > 0 && videoWidth > 0 && videoHeight > 0)
        val margin = marginPercent.coerceIn(0, 10) / 100.0
        val availableW = surfaceWidth * (1 - 2 * margin)
        val availableH = surfaceHeight * (1 - 2 * margin)
        val scale = minOf(availableW / videoWidth, availableH / videoHeight)
        val w = (videoWidth * scale).toInt().coerceIn(1, surfaceWidth)
        val h = (videoHeight * scale).toInt().coerceIn(1, surfaceHeight)
        return Viewport((surfaceWidth - w) / 2, (surfaceHeight - h) / 2, w, h)
    }
}
