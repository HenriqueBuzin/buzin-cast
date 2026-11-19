package io.github.jqssun.airplay

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.ui.gestures.detectHorizontalDragGestures
import io.github.jqssun.airplay.ui.gestures.detectVerticalDragGestures
import io.github.jqssun.airplay.ui.gestures.detectZoomGestures
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Android MotionEvents traverse the activity's real Compose pointer-input tree. */
@RunWith(AndroidJUnit4::class)
class GestureDetectorContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private enum class Mode { HORIZONTAL, VERTICAL, ZOOM }
    private var touchTarget: android.view.View? = null
    private class Events {
        val starts = CopyOnWriteArrayList<Offset>()
        val drags = CopyOnWriteArrayList<Pair<Offset, Float>>()
        val zooms = CopyOnWriteArrayList<Float>()
        val ends = AtomicInteger()
        fun clear() { starts.clear(); drags.clear(); zooms.clear(); ends.set(0) }
    }

    private fun fixture(mode: Mode, consumed: Boolean = false, block: (Events, Offset) -> Unit) {
        val intent = Intent(context, DebugTvPreviewActivity::class.java).putExtra("screen", "contract_host").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<DebugTvPreviewActivity>(intent).use { activity ->
            val events = Events()
            val laidOut = CountDownLatch(1)
            val center = AtomicReference<Offset>()
            activity.onActivity { target ->
                touchTarget = target.window.decorView
                target.setContent {
                    Box(Modifier.fillMaxSize().onGloballyPositioned {
                        center.set(it.boundsInWindow().center)
                        laidOut.countDown()
                    }.pointerInput(consumed) {
                        if (consumed) awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }.pointerInput(mode) {
                        when (mode) {
                            Mode.HORIZONTAL -> detectHorizontalDragGestures(
                                { events.starts += it }, { events.ends.incrementAndGet() },
                                { change, amount -> events.drags += change.position to amount })
                            Mode.VERTICAL -> detectVerticalDragGestures(
                                { events.starts += it }, { events.ends.incrementAndGet() },
                                { change, amount -> events.drags += change.position to amount })
                            Mode.ZOOM -> detectZoomGestures(
                                { events.zooms += it }, { events.ends.incrementAndGet() })
                        }
                    })
                }
            }
            assertTrue("Gesture surface did not lay out", laidOut.await(15, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            block(events, requireNotNull(center.get()))
        }
    }

    private var downTime = 0L
    private fun send(action: Int, first: Offset, second: Offset? = null) {
        val points = listOfNotNull(first, second)
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val properties = points.indices.map { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = points.map { point -> MotionEvent.PointerCoords().apply {
            x = point.x; y = point.y; pressure = 1f; size = 1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, points.size,
            properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            instrumentation.runOnMainSync {
                requireNotNull(touchTarget).dispatchTouchEvent(event)
            }
        } finally { event.recycle() }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(20)
    }

    private fun finishTwo(first: Offset, second: Offset) {
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second)
        send(MotionEvent.ACTION_UP, first)
    }

    private fun drag(center: Offset, horizontal: Boolean, distance: Float, cancel: Boolean = false) {
        val first = center + if (horizontal) Offset(distance / 2, 0f) else Offset(0f, distance / 2)
        val last = center + if (horizontal) Offset(distance, 0f) else Offset(0f, distance)
        send(MotionEvent.ACTION_DOWN, center)
        send(MotionEvent.ACTION_MOVE, first)
        send(MotionEvent.ACTION_MOVE, last)
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, last)
    }

    private fun assertDragModes(mode: Mode) = fixture(mode) { events, center ->
        val horizontal = mode == Mode.HORIZONTAL
        for (distance in listOf(180f, -180f)) {
            events.clear()
            drag(center, horizontal, distance)
            assertEquals("Drag did not start exactly once", 1, events.starts.size)
            assertTrue("Drag omitted movement callbacks", events.drags.size >= 2)
            assertEquals("Drag did not end exactly once", 1, events.ends.get())
            val amount = events.drags.sumOf { it.second.toDouble() }
            assertTrue("Drag direction changed", amount * distance > 0)
            val last = events.drags.last().first
            assertEquals(if (horizontal) center.x + distance else center.x, last.x, 1f)
            assertEquals(if (horizontal) center.y else center.y + distance, last.y, 1f)
        }
        events.clear()
        drag(center, horizontal, 2f)
        assertTrue("Sub-slop movement started a drag", events.starts.isEmpty())
        assertTrue(events.drags.isEmpty())
        assertEquals(0, events.ends.get())
        events.clear()
        drag(center, horizontal, 180f, cancel = true)
        assertEquals(1, events.starts.size)
        assertEquals("Cancelled drag did not release its owner", 1, events.ends.get())
        events.clear()
        val second = center + Offset(0f, 100f)
        send(MotionEvent.ACTION_DOWN, center)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), center, second)
        val moved = center + if (horizontal) Offset(180f, 0f) else Offset(0f, -180f)
        send(MotionEvent.ACTION_MOVE, moved, second)
        finishTwo(moved, second)
        assertTrue("Two pointers acquired a single-pointer drag", events.starts.isEmpty())
        assertTrue(events.drags.isEmpty())
        assertEquals(0, events.ends.get())
    }

    @After fun coverage() {
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Test fun horizontalDragHandlesBothDirectionsSlopCancellationAndMultiplePointers() =
        assertDragModes(Mode.HORIZONTAL)

    @Test fun verticalDragHandlesBothDirectionsSlopCancellationAndMultiplePointers() =
        assertDragModes(Mode.VERTICAL)

    @Test fun pinchWaitsForSlopThenReportsZoomAndReleasesOnPointerUp() = fixture(Mode.ZOOM) { events, center ->
        var left = center + Offset(-40f, 0f)
        var right = center + Offset(40f, 0f)
        send(MotionEvent.ACTION_DOWN, left)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), left, right)
        left = center + Offset(-42f, 0f); right = center + Offset(42f, 0f)
        send(MotionEvent.ACTION_MOVE, left, right)
        assertTrue("Pinch started below touch slop", events.zooms.isEmpty())
        left = center + Offset(-120f, 0f); right = center + Offset(120f, 0f)
        send(MotionEvent.ACTION_MOVE, left, right)
        assertTrue("Pinch did not report expansion", events.zooms.any { it > 1f })
        val count = events.zooms.size
        send(MotionEvent.ACTION_MOVE, left, right)
        assertEquals("Stationary pointers changed zoom", count, events.zooms.size)
        left = center + Offset(-60f, 0f); right = center + Offset(60f, 0f)
        send(MotionEvent.ACTION_MOVE, left, right)
        assertTrue("Pinch did not report contraction", events.zooms.any { it < 1f })
        finishTwo(left, right)
        assertEquals(1, events.ends.get())
    }

    @Test fun zoomRejectsOnePointerAndAllowsTwoPointerPanWithoutChangingScale() = fixture(Mode.ZOOM) { events, center ->
        drag(center, horizontal = true, distance = 180f)
        assertTrue(events.zooms.isEmpty())
        assertEquals("Single-pointer motion acquired zoom", 0, events.ends.get())
        val left = center + Offset(-50f, 0f)
        val right = center + Offset(50f, 0f)
        send(MotionEvent.ACTION_DOWN, left)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), left, right)
        send(MotionEvent.ACTION_MOVE, left + Offset(100f, 0f), right + Offset(100f, 0f))
        finishTwo(left + Offset(100f, 0f), right + Offset(100f, 0f))
        assertTrue("Pan changed zoom", events.zooms.isEmpty())
        assertEquals(1, events.ends.get())
    }

    @Test fun consumedEventsCannotAcquireDragOrZoom() {
        for (mode in Mode.entries) fixture(mode, consumed = true) { events, center ->
            if (mode != Mode.ZOOM) drag(center, mode == Mode.HORIZONTAL, 180f)
            else {
                val left = center + Offset(-40f, 0f)
                val right = center + Offset(40f, 0f)
                send(MotionEvent.ACTION_DOWN, left)
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), left, right)
                val expandedLeft = center + Offset(-150f, 0f)
                val expandedRight = center + Offset(150f, 0f)
                send(MotionEvent.ACTION_MOVE, expandedLeft, expandedRight)
                finishTwo(expandedLeft, expandedRight)
            }
            assertTrue("Consumed input started a drag", events.starts.isEmpty())
            assertTrue(events.drags.isEmpty())
            assertTrue("Consumed input changed zoom", events.zooms.isEmpty())
            assertEquals(0, events.ends.get())
        }
    }
}
