package io.github.jqssun.airplay.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale

/** Draws the existing brand geometry; the light trace becomes the finished mark. */
@Composable
fun BuzinLogoReveal(progress: () -> Float, modifier: Modifier = Modifier) {
    val contours = remember {
        listOf(
            Path().apply {
                moveTo(29f, 84f); lineTo(29f, 18f); lineTo(54f, 18f)
                cubicTo(79f, 18f, 83f, 43f, 67f, 50f)
                cubicTo(88f, 59f, 77f, 84f, 54f, 84f)
                close()
            },
            Path().apply {
                moveTo(43f, 31f); lineTo(53f, 31f)
                cubicTo(66f, 31f, 66f, 44f, 53f, 44f); lineTo(43f, 44f); close()
            },
            Path().apply {
                moveTo(43f, 57f); lineTo(55f, 57f)
                cubicTo(69f, 57f, 69f, 71f, 55f, 71f); lineTo(43f, 71f); close()
            },
        )
    }
    val glyph = remember { Path().apply { fillType = PathFillType.EvenOdd; contours.forEach { addPath(it) } } }
    val tile = remember { Path().apply {
        moveTo(25f, 0f); lineTo(75f, 0f); quadraticTo(100f, 0f, 100f, 25f)
        lineTo(100f, 75f); quadraticTo(100f, 100f, 75f, 100f)
        lineTo(25f, 100f); quadraticTo(0f, 100f, 0f, 75f)
        lineTo(0f, 25f); quadraticTo(0f, 0f, 25f, 0f); close()
    } }
    val lengths = remember { contours.map { path -> PathMeasure().apply { setPath(path, false) }.length } }
    val measure = remember { PathMeasure() }
    val segment = remember { Path() }
    Canvas(modifier) {
        val p = progress()
        val trace = ((p - 0.025f) / 0.49f).coerceIn(0f, 1f)
        val fill = FastOutSlowInEasing.transform(((p - 0.46f) / 0.22f).coerceIn(0f, 1f))
        val mint = Color(0xFF7CF4CD)
        scale(size.width / 100f, size.height / 100f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            if (fill > 0f) {
                drawPath(tile, mint, alpha = fill)
                drawPath(glyph, Color(0xFF092A24), alpha = fill)
            }
            if (trace > 0f && fill < 1f) {
                var remaining = lengths.sum() * trace
                contours.forEachIndexed { index, path ->
                    val length = lengths[index]
                    val end = remaining.coerceIn(0f, length)
                    if (end > 0f) {
                        measure.setPath(path, false)
                        segment.reset()
                        measure.getSegment(0f, end, segment, startWithMoveTo = true)
                        drawPath(segment, mint, alpha = 1f - fill,
                            style = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (remaining < length) {
                            val tip = measure.getPosition(end)
                            drawCircle(mint, radius = 5f, center = tip, alpha = (1f - fill) * 0.12f)
                            drawCircle(Color.White, radius = 1.5f, center = tip, alpha = 1f - fill)
                        }
                    }
                    remaining -= length
                }
            }
        }
    }
}
