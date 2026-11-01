package io.github.jqssun.airplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.R

/** Short, skippable brand reveal. The receiver starts normally behind the overlay. */
@Composable
fun BuzinLaunch(enabled: Boolean, content: @Composable () -> Unit) {
    var completed by rememberSaveable { mutableStateOf(!enabled) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(enabled) {
        if (!enabled) completed = true
        if (!completed) {
            reveal.animateTo(1f, tween(1500, easing = androidx.compose.animation.core.LinearEasing))
            completed = true
        }
    }
    BackHandler(enabled = !completed) { completed = true }
    Box(Modifier.fillMaxSize().onPreviewKeyEvent {
        if (!completed && it.type == KeyEventType.KeyDown) { completed = true; true } else false
    }) {
        content()
        if (!completed) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = ((1f - reveal.value) / 0.18f).coerceIn(0f, 1f)
            }.background(Color(0xFF080F19)).pointerInput(Unit) { detectTapGestures { completed = true } },
                contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2 - 24.dp.toPx())
                    val radius = minOf(size.width, size.height) * 0.6f
                    repeat(3) { i ->
                        val wave = ((reveal.value - i * 0.13f) / 0.75f).coerceIn(0f, 1f)
                        if (wave > 0f && wave < 1f) {
                            drawCircle(Color(0xFF70E3C5).copy(alpha = (1f - wave) * 0.34f),
                                52.dp.toPx() + wave * radius, center, style = Stroke(1.5.dp.toPx()))
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.graphicsLayer {
                        val entrance = FastOutSlowInEasing.transform((reveal.value / 0.4f).coerceIn(0f, 1f))
                        alpha = entrance
                        scaleX = 0.78f + entrance * 0.22f
                        scaleY = scaleX
                        translationY = (1f - entrance) * 18.dp.toPx()
                    }) {
                    Image(painterResource(R.drawable.buzin_mark), null, Modifier.size(96.dp))
                    Text("Buzin Cast", color = Color(0xFFF1F5F1), fontSize = 31.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                }
            }
        }
    }
}
