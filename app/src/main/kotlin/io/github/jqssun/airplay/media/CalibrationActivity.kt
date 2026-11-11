package io.github.jqssun.airplay.media

import android.graphics.*
import android.os.Bundle
import android.view.*
import androidx.activity.ComponentActivity
import io.buzincast.media.DisplayFit
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.get

/** Calibration is explicit: preview never changes playback preferences until OK. */
class CalibrationActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences(Prefs.NAME, MODE_PRIVATE) }
    private var margin = 2
    private lateinit var preview: View
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        margin = savedInstanceState?.getInt("margin") ?: prefs[Prefs.TV_MARGIN]
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        preview = object : View(this) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.rgb(8, 15, 25))
                val box = DisplayFit.viewport(width, height, width, height, margin)
                val unit = resources.displayMetrics.density
                val left = box.x.toFloat() + 3 * unit
                val top = box.y.toFloat() + 3 * unit
                val right = (box.x + box.width).toFloat() - 3 * unit
                val bottom = (box.y + box.height).toFloat() - 3 * unit
                paint.color = Color.rgb(124,244,205); paint.strokeWidth = 3 * unit; paint.style = Paint.Style.STROKE
                val corner = 35 * unit
                listOf(left to top, right to top, left to bottom, right to bottom).forEach { (x,y) ->
                    canvas.drawLine(x,y,x + if (x == left) corner else -corner,y,paint)
                    canvas.drawLine(x,y,x,y + if (y == top) corner else -corner,paint)
                }
                paint.strokeWidth = unit; paint.alpha = 70
                canvas.drawRect(left,top,right,bottom,paint)
                paint.alpha = 255; paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
                paint.textSize = 25 * unit
                canvas.drawText(context.getString(R.string.setting_tv_margin), width / 2f, height / 2f - 55 * unit, paint)
                paint.textSize = 36 * unit
                canvas.drawText("$margin%", width / 2f, height / 2f, paint)
                paint.color = Color.WHITE
                fun drawHint(text: String, offset: Int) {
                    paint.textSize = 15 * unit
                    val availableWidth = (width - 32 * unit).coerceAtLeast(1f)
                    val textWidth = paint.measureText(text)
                    if (textWidth > availableWidth) paint.textSize *= availableWidth / textWidth
                    canvas.drawText(text, width / 2f, height / 2f + offset * unit, paint)
                }
                drawHint(context.getString(R.string.calibration_adjust_hint), 40)
                drawHint(context.getString(R.string.calibration_save_hint), 70)
            }
        }
        setContentView(preview)
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("margin",margin); super.onSaveInstanceState(outState) }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when(keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { margin = (margin-1).coerceAtLeast(0); preview.invalidate(); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { margin = (margin+1).coerceAtMost(10); preview.invalidate(); true }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { Prefs.TV_MARGIN.write(prefs,margin); finish(); true }
        else -> super.onKeyDown(keyCode,event)
    }
}
