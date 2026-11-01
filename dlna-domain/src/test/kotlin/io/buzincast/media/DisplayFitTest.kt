package io.buzincast.media

import org.junit.Assert.*
import org.junit.Test

class DisplayFitTest {
    @Test fun `Mac 16 by 10 fits a 16 by 9 TV without cropping`() {
        assertEquals(DisplayFit.Viewport(96, 0, 1728, 1080), DisplayFit.viewport(1920, 1080, 1920, 1200, 0))
    }
    @Test fun `three percent per edge keeps TV borders inside safe area`() {
        val result = DisplayFit.viewport(1920, 1080, 1920, 1080, 3)
        assertEquals(1804, result.width); assertEquals(1015, result.height)
        assertTrue(result.x >= 57); assertTrue(result.y >= 32)
    }
    @Test fun `portrait and ultrawide sources preserve aspect within one pixel`() {
        listOf(1080 to 1920, 3440 to 1440, 3840 to 2160).forEach { (w,h) ->
            val fit = DisplayFit.viewport(1920, 1080, w, h, 5)
            assertTrue(fit.width <= 1728 && fit.height <= 972)
            assertEquals(w.toDouble() / h, fit.width.toDouble() / fit.height, 0.003)
        }
    }
}
