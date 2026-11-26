package io.github.jqssun.airplay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.media.CalibrationActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class CalibrationActivityContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    @After fun checkpointCoverage() {
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Test fun draftSurvivesRecreationAndOnlyEnterPersistsIt() {
        val key = Prefs.TV_MARGIN.key
        val original = preferences.all[key] as Int?
        assertTrue(preferences.edit().putInt(key, 4).commit())
        try {
            launch().use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue("4%" in drawTextWidths(activity, 960))
                    assertFalse(press(activity, KeyEvent.KEYCODE_F1))
                    repeat(3) { assertTrue(press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)) }
                    assertTrue(press(activity, KeyEvent.KEYCODE_DPAD_LEFT))
                }
                assertEquals(4, Prefs.TV_MARGIN.read(preferences))
                scenario.recreate()
                val destroyed = CountDownLatch(1)
                scenario.onActivity { activity ->
                    assertTrue("6%" in drawTextWidths(activity, 960))
                    assertEquals(4, Prefs.TV_MARGIN.read(preferences))
                    activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                        override fun onDestroy(owner: LifecycleOwner) = destroyed.countDown()
                    })
                    assertTrue(press(activity, KeyEvent.KEYCODE_ENTER))
                    assertTrue(activity.isFinishing)
                }
                assertTrue("Saving did not close calibration", destroyed.await(25, TimeUnit.SECONDS))
                assertEquals(6, Prefs.TV_MARGIN.read(preferences))
            }
        } finally {
            val restore = preferences.edit()
            if (original == null) restore.remove(key) else restore.putInt(key, original)
            assertTrue(restore.commit())
        }
    }

    @Test fun calibrationHintsShrinkToFitACompactPreview() {
        launch().use { scenario ->
            scenario.onActivity { activity ->
                val spacious = drawTextWidths(activity, 960)
                val compact = drawTextWidths(activity, 80)
                val density = activity.resources.displayMetrics.density
                val available = (80 * density).roundToInt() - 32 * density
                for (hint in listOf(R.string.calibration_adjust_hint, R.string.calibration_save_hint)) {
                    val label = activity.getString(hint)
                    val originalWidth = spacious.getValue(label)
                    val scaledWidth = compact.getValue(label)
                    assertTrue("Expected a smaller hint in the narrower preview", scaledWidth < originalWidth)
                    assertTrue("Hint exceeds the available preview width", scaledWidth <= available + 1f)
                }
            }
        }
    }

    private fun launch(): ActivityScenario<CalibrationActivity> =
        ActivityScenario.launch(Intent(context, CalibrationActivity::class.java))

    private fun press(activity: CalibrationActivity, key: Int): Boolean {
        val handled = activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return handled
    }

    private fun drawTextWidths(activity: CalibrationActivity, widthDp: Int): Map<String, Float> {
        val preview = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val density = activity.resources.displayMetrics.density
        val width = (widthDp * density).roundToInt()
        val height = (240 * density).roundToInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val widths = mutableMapOf<String, Float>()
        try {
            val canvas = object : Canvas(bitmap) {
                override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
                    widths[text] = paint.measureText(text)
                    super.drawText(text, x, y, paint)
                }
            }
            preview.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            preview.layout(0, 0, width, height)
            preview.draw(canvas)
        } finally {
            bitmap.recycle()
        }
        return widths
    }
}
