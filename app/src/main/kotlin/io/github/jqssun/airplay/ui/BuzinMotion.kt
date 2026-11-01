package io.github.jqssun.airplay.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Draw-only animation: does not recompose layout or participate in TV focus. */
@Composable
fun BuzinSignal(modifier: Modifier = Modifier, active: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "buzin-signal")
    val phase = transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(12000, easing = LinearEasing)), label = "orbit")
    Canvas(modifier) {
        val progress = if (active) phase.value else 0f
        val center = Offset(size.width * 0.76f, size.height * 0.46f)
        val unit = minOf(size.width, size.height)
        val mint = Color(0xFF70E3C5)
        repeat(3) { index ->
            val rx = unit * (0.23f + index * 0.12f)
            val ry = rx * 0.61f
            drawOval(mint.copy(alpha = 0.08f), center - Offset(rx, ry), Size(rx * 2, ry * 2), style = Stroke(1.dp.toPx()))
            val angle = (progress * 2 * PI + index * 2.1) * if (index == 1) -1 else 1
            val point = center + Offset(cos(angle).toFloat() * rx, sin(angle).toFloat() * ry)
            if (active) {
                drawCircle(mint.copy(alpha = 0.06f), 9.dp.toPx(), point)
                drawCircle(mint.copy(alpha = 0.24f), 2.dp.toPx(), point)
            }
        }
    }
}
