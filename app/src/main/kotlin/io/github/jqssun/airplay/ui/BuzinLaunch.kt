package io.github.jqssun.airplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Short, skippable brand reveal. The receiver starts normally behind the overlay. */
@Composable
fun BuzinLaunch(enabled: Boolean, content: @Composable () -> Unit) {
    var completed by rememberSaveable { mutableStateOf(!enabled) }
    var showContent by rememberSaveable { mutableStateOf(!enabled) }
    val introFocus = remember { FocusRequester() }
    val reveal = remember { Animatable(0f) }
    val skip = { showContent = true; completed = true }
    LaunchedEffect(enabled) {
        if (!enabled) { showContent = true; completed = true }
        if (!completed) {
            if (!showContent) introFocus.requestFocus()
            // Let the first layout/draw settle before starting the animation clock.
            // Cold service startup on older Fire TVs can otherwise skip its first frames.
            withFrameNanos { }
            withFrameNanos { }
            // Keep the large home composition and its JIT work out of the drawing
            // sequence. Prepare it under the completed, opaque brand before fading.
            reveal.animateTo(0.82f, tween(1968, easing = androidx.compose.animation.core.LinearEasing))
            showContent = true
            withFrameNanos { }
            withFrameNanos { }
            reveal.animateTo(1f, tween(432, easing = androidx.compose.animation.core.LinearEasing))
            completed = true
        }
    }
    BackHandler(enabled = !completed, onBack = skip)
    Box(Modifier.fillMaxSize().background(Color(0xFF080F19)).onPreviewKeyEvent {
        if (!completed && it.type == KeyEventType.KeyDown) { skip(); true } else false
    }.focusRequester(introFocus).focusable(enabled = !showContent && !completed)) {
        if (showContent) content()
        if (!completed) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = ((1f - reveal.value) / 0.1f).coerceIn(0f, 1f)
            }.background(Color(0xFF080F19)).pointerInput(Unit) { detectTapGestures { skip() } },
                contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2 - 24.dp.toPx())
                    val radius = minOf(size.width, size.height) * 0.6f
                    repeat(3) { i ->
                        val wave = ((reveal.value - 0.5f - i * 0.08f) / 0.45f).coerceIn(0f, 1f)
                        if (wave > 0f && wave < 1f) {
                            drawCircle(Color(0xFF70E3C5).copy(alpha = (1f - wave) * 0.34f),
                                52.dp.toPx() + wave * radius, center, style = Stroke(1.5.dp.toPx()))
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    BuzinLogoReveal(progress = { reveal.value }, modifier = Modifier.size(84.dp))
                    Text("Buzin Cast", color = Color(0xFFF1F5F1), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                        modifier = Modifier.graphicsLayer {
                            alpha = ((reveal.value - 0.58f) / 0.16f).coerceIn(0f, 1f)
                        }.drawWithContent {
                            val title = FastOutSlowInEasing.transform(((reveal.value - 0.58f) / 0.2f).coerceIn(0f, 1f))
                            clipRect(right = size.width * title) { this@drawWithContent.drawContent() }
                        })
                }
            }
        }
    }
}
