package io.github.jqssun.airplay.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween

fun Context.isTvDevice(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION ||
        (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
        Configuration.UI_MODE_TYPE_TELEVISION

@Composable
fun isTv(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) { ctx.isTvDevice() }
}

fun Modifier.dpadFocus(shape: Shape = CircleShape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val outline by animateColorAsState(if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
        tween(160), label = "tv-focus-outline")
    heightIn(min = 48.dp)
        .onFocusChanged { focused = it.isFocused }
        .smoothFocusOutline(shape, outline, 2.dp)
}

/** Vector stroke inside the actual bounds, without scaling a cached focus bitmap. */
internal fun Modifier.smoothFocusOutline(shape: Shape, color: Color, width: Dp): Modifier = drawWithCache {
    val stroke = width.toPx().coerceAtLeast(1f)
    val inset = stroke / 2f
    val path = android.graphics.Path()
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rectangle -> path.addRect(inset, inset, size.width-inset, size.height-inset, android.graphics.Path.Direction.CW)
        is Outline.Rounded -> {
            val r = outline.roundRect
            val radii = floatArrayOf(r.topLeftCornerRadius.x, r.topLeftCornerRadius.y,
                r.topRightCornerRadius.x, r.topRightCornerRadius.y, r.bottomRightCornerRadius.x,
                r.bottomRightCornerRadius.y, r.bottomLeftCornerRadius.x, r.bottomLeftCornerRadius.y)
            for (i in radii.indices) radii[i] = (radii[i]-inset).coerceAtLeast(0f)
            path.addRoundRect(android.graphics.RectF(r.left+inset,r.top+inset,r.right-inset,r.bottom-inset),radii,android.graphics.Path.Direction.CW)
        }
        is Outline.Generic -> {
            path.set(outline.path.asAndroidPath())
            if (size.width > stroke && size.height > stroke) {
                path.transform(android.graphics.Matrix().apply { setScale((size.width-stroke)/size.width,(size.height-stroke)/size.height) })
                path.offset(inset,inset)
            }
        }
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = stroke
        this.color = color.toArgb()
    }
    onDrawWithContent {
        drawContent()
        if (color.alpha > 0f) drawContext.canvas.nativeCanvas.drawPath(path,paint)
    }
}

fun Modifier.dpadAdjust(onLeft: () -> Unit, onRight: () -> Unit): Modifier = composed {
    val focus = LocalFocusManager.current
    onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionLeft -> { onLeft(); true }
            Key.DirectionRight -> { onRight(); true }
            // slider
            Key.DirectionUp -> { focus.moveFocus(FocusDirection.Up); true }
            Key.DirectionDown -> { focus.moveFocus(FocusDirection.Down); true }
            else -> false
        }
    }
}
