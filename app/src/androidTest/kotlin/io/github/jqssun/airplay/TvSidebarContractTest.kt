package io.github.jqssun.airplay

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.ui.AppDestination
import io.github.jqssun.airplay.ui.TvSidebar
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class TvSidebarContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @After fun checkpointCoverage() {
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Test fun dpadMovesFocusBeforeSelectingAndActivatesTheCurrentCallback() {
        val overview = AppDestination.OVERVIEW
        val tools = AppDestination.TOOLS
        val settings = AppDestination.SETTINGS
        val selected = mutableStateOf(overview)
        val generation = mutableStateOf(0)
        val selections = CopyOnWriteArrayList<Pair<Int, AppDestination>>()
        val intent = Intent(context, DebugTvPreviewActivity::class.java).putExtra("screen", "contract_host")
        ActivityScenario.launch<DebugTvPreviewActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    AirPlayTheme {
                        val callbackVersion = generation.value
                        TvSidebar(listOf(overview, tools, settings), selected.value) {
                            selections.add(callbackVersion to it)
                            selected.value = it
                        }
                    }
                }
            }
            await("Overview did not expose initial selection") { hasNode(overview) { it.isSelected } }
            await("Overview could not receive input focus") {
                hasNode(overview) { node -> node.isFocused || node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            }
            await("Overview did not receive input focus") { hasNode(overview) { it.isFocused } }
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down did not move focus to Tools") { hasNode(tools) { it.isFocused } }
            assertTrue("Moving focus changed the selected destination", hasNode(overview) { it.isSelected })
            assertTrue(selections.isEmpty())
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK did not select Tools") { hasNode(tools) { it.isSelected } }
            assertEquals(listOf(0 to tools), selections.toList())

            scenario.onActivity { generation.value = 1 }
            instrumentation.waitForIdleSync()
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down did not move focus to Settings") { hasNode(settings) { it.isFocused } }
            assertTrue(hasNode(tools) { it.isSelected })
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK did not select Settings") { hasNode(settings) { it.isSelected } }
            assertEquals(listOf(0 to tools, 1 to settings), selections.toList())
        }
    }

    private fun press(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        instrumentation.waitForIdleSync()
    }

    @Suppress("DEPRECATION")
    private fun hasNode(destination: AppDestination, condition: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        try {
            if (!root.refresh() || root.packageName?.toString() != context.packageName) return false
            val label = context.getString(destination.labelRes)
            fun visit(match: AccessibilityNodeInfo): Boolean {
                if (match.isVisibleToUser && match.text?.toString() == label) {
                    var node: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(match)
                    try {
                        for (depth in 0 until 16) {
                            val current = node ?: break
                            if (current.packageName?.toString() != context.packageName) break
                            if (condition(current)) return true
                            node = current.parent
                            current.recycle()
                        }
                    } finally {
                        node?.recycle()
                    }
                }
                repeat(match.childCount) { index ->
                    val child = match.getChild(index) ?: return@repeat
                    try {
                        if (visit(child)) return true
                    } finally {
                        child.recycle()
                    }
                }
                return false
            }
            return visit(root)
        } finally {
            root.recycle()
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        assertTrue(message, condition())
    }
}
