package io.github.jqssun.airplay

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.buzincast.core.AsyncLogStore
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.ui.ToolsScreen
import io.github.jqssun.airplay.media.MirrorRelay
import io.github.jqssun.airplay.media.MirrorCapture
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Real Tools controls and diagnostics, with isolated unbound receiver state. */
@RunWith(AndroidJUnit4::class)
class ToolsScreenStateContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var root: File
    private lateinit var preferenceName: String
    private lateinit var model: MainViewModel
    private lateinit var store: ViewModelStore
    private lateinit var logs: AsyncLogStore
    private var lastNodes = emptyList<Node>()
    private var originalLocale: Locale = Locale.ROOT
    private class TestApplication(base: Context) : Application() { init { attachBaseContext(base) } }
    private data class Node(val text: String, val description: String, val bounds: Rect, val selected: Boolean,
        val clickable: Boolean, val enabled: Boolean, val className: String)

    @Before fun setup() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        preferenceName = "tools-screen-${System.nanoTime()}"
        root = File(context.cacheDir, preferenceName).apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getCacheDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(preferenceName, mode)
        }
        instrumentation.runOnMainSync {
            model = MainViewModel(TestApplication(isolated))
            store = ViewModelStore().also { it.put("main-screen", model) }
            logs = field("logStore")
        }
    }
    @After fun cleanup() {
        Locale.setDefault(originalLocale)
        assertTrue(logs.flush())
        instrumentation.runOnMainSync { store.clear() }
        assertTrue("ViewModel log worker survived clearing", logs.awaitClosed())
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = MainViewModel::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(model) as T
    private fun <T> state(name: String, value: T) { field<MutableStateFlow<T>>(name).value = value }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun text(id: Int, vararg values: Any) = context.getString(id, *values)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(30)
        }
        snapshot()
        fail("$message; visible nodes=$lastNodes")
    }
    @Suppress("DEPRECATION")
    private fun snapshot(requireFocusedWindow: Boolean = false): List<Node>? {
        val automation = instrumentation.uiAutomation
        val tree = automation.rootInActiveWindow ?: return null
        try {
            if (!tree.refresh() || tree.packageName?.toString() != context.packageName || tree.childCount == 0) return null
            if (requireFocusedWindow) {
                val windows = automation.windows
                val focused = try {
                    windows.any { it.id == tree.windowId && it.isActive && it.isFocused }
                } finally { windows.forEach { it.recycle() } }
                if (!focused) return null
            }
            val result = mutableListOf<Node>()
            fun visit(node: AccessibilityNodeInfo) {
                if (node.isVisibleToUser) {
                    val bounds = Rect(); node.getBoundsInScreen(bounds)
                    result += Node(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(),
                        bounds, node.isSelected, node.isClickable, node.isEnabled, node.className?.toString().orEmpty())
                }
                repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
                    try { visit(child) } finally { child.recycle() }
                } }
            }
            visit(tree)
            lastNodes = result
            return result.takeIf { nodes -> nodes.any { it.text.isNotEmpty() || it.description.isNotEmpty() } }
        } finally { tree.recycle() }
    }
    private fun has(value: String) = snapshot()?.any { it.text == value || it.description == value } == true
    @Suppress("DEPRECATION")
    private fun control(label: String, selected: Boolean = false, click: Boolean = false): Boolean {
        val tree = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        try {
            if (tree.packageName?.toString() != context.packageName || tree.childCount == 0) return false
            fun visit(node: AccessibilityNodeInfo): Boolean {
                if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
                    var parent: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
                    try {
                        var depth = 0
                        while (parent != null && parent.packageName?.toString() == context.packageName) {
                            check(depth++ < 64) { "Accessibility parent chain exceeded its bound for $label" }
                            // Selected tabs expose selection on a non-clickable ancestor.
                            if (selected && parent.isSelected) return true
                            if (!selected && parent.isClickable) return if (click)
                                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK) else true
                            val next = parent.parent; parent.recycle(); parent = next
                        }
                    } finally { parent?.recycle() }
                }
                repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
                    try { if (visit(child)) return true } finally { child.recycle() }
                } }
                return false
            }
            return visit(tree)
        } finally { tree.recycle() }
    }
    private fun click(label: String) { await("No actionable control: $label") { control(label, click = true) } }

    private fun tap(label: String) {
        lateinit var bounds: Rect
        await("No visible touch target: $label") {
            val target = snapshot(requireFocusedWindow = true)
                ?.firstOrNull { it.text == label && it.enabled && !it.bounds.isEmpty }
                ?: return@await false
            bounds = target.bounds
            true
        }
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue("Touch injection failed for $label", instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
    }
    private fun screen(block: () -> Unit) {
        ActivityScenario.launch<DebugTvPreviewActivity>(Intent(context, DebugTvPreviewActivity::class.java).putExtra("screen", "contract_host")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { target -> target.setContent { AirPlayTheme {
                ToolsScreen(model)
            } } }
            await("Tools screen did not expose content") { snapshot() != null }
            block()
        }
    }


    @Suppress("DEPRECATION")
    private fun buttonEnabled(label: String): Boolean? {
        val tree = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        try {
            if (tree.packageName?.toString() != context.packageName || tree.childCount == 0) return null
            fun visit(node: AccessibilityNodeInfo): Boolean? {
                if (node.isVisibleToUser && node.text?.toString() == label) {
                    var parent: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
                    try {
                        var depth = 0
                        while (parent != null && parent.packageName?.toString() == context.packageName) {
                            check(depth++ < 64)
                            // Compose can expose a clickable View rather than a
                            // widget Button. A disabled ancestor also makes the
                            // enclosed control unavailable, without a click action.
                            val hasClick = parent.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                            if (parent.isClickable || hasClick || parent.className?.toString() == "android.widget.Button" ||
                                (!parent.isEnabled && parent.childCount > 0)) return parent.isEnabled
                            val next = parent.parent; parent.recycle(); parent = next
                        }
                    } finally { parent?.recycle() }
                }
                repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
                    try { visit(child)?.let { return it } } finally { child.recycle() }
                } }
                return null
            }
            return visit(tree)
        } finally { tree.recycle() }
    }

    private fun absent(label: String): Boolean {
        val nodes = snapshot() ?: return false
        return nodes.none { it.text == label || it.description == label }
    }

    @Test fun receiverCannotStartDuringVideoExistingConnectionsCaptureOrSending() = screen {
        val receive = text(R.string.tools_relay_receive)
        fun idle() {
            main {
                state("_connectionCount", 0)
                state("_videoPlaybackActive", false)
                state("_captureState", MirrorCapture.State())
                state("_relayState", MirrorRelay.State())
            }
            await("Idle receive action did not recover") { buttonEnabled(receive) == true }
        }
        val blockers: List<Pair<String, () -> Unit>> = listOf(
            "Connection" to { state("_connectionCount", 1) },
            "Video" to { state("_videoPlaybackActive", true) },
            "Capture" to { state("_captureState", MirrorCapture.State(armed = true)) },
            "Sending" to { state("_relayState", MirrorRelay.State(sending = true)) },
        )
        for ((label, block) in blockers) {
            // A distinct observable idle frame prevents StateFlow conflation
            // from skipping an intermediate blocker with the same disabled UI.
            idle()
            main(block)
            await("$label did not disable relay receive") { buttonEnabled(receive) == false }
        }
        idle()
    }

    @Test fun relayListeningShowsGroupedCodeAndStopThenClearsTransientDetails() = screen {
        val details = text(R.string.tools_relay_details, 54321, "1234-5678-9012")
        main { state("_relayState", MirrorRelay.State(listening = true, code = "123456789012", port = 54321, message = "Waiting for peer")) }
        await("Listening details/stop missing") {
            has(details) && has("Waiting for peer") && has(text(R.string.tools_relay_stop))
        }
        main { state("_relayState", MirrorRelay.State()) }
        await("Idle relay retained active-session details") {
            absent(details) && absent("Waiting for peer") && absent(text(R.string.tools_relay_stop))
        }
        for (session in listOf(MirrorRelay.State(sending = true), MirrorRelay.State(receiving = true))) {
            main { state("_relayState", MirrorRelay.State()) }
            await("Relay did not return to an actionable idle state") {
                absent(text(R.string.tools_relay_stop)) && buttonEnabled(text(R.string.tools_relay_connect)) == true
            }
            main { state("_relayState", session) }
            await("Active relay has no stop action or permits another connection") {
                has(text(R.string.tools_relay_stop)) && buttonEnabled(text(R.string.tools_relay_connect)) == false
            }
        }
    }

    @Test fun diagnosticDialogReportsReceiverStateAndDismissesWithCloseAndBack() = screen {
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            for ((running, back) in listOf(false to false, true to true)) {
                main { state("_serverState", if (running) ServerState.RUNNING else ServerState.STOPPED) }
                click(text(R.string.tools_connection_diagnostics))
                val expected = text(if (running) R.string.diag_receiver_running else R.string.diag_receiver_stopped)
                await("Diagnostic report has wrong receiver status") {
                    snapshot(requireFocusedWindow = true)?.any { it.text.contains(expected) } == true
                }
                if (back) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                } else tap(text(R.string.buzin_close))
                val action = if (back) "Back" else "Close"
                await("Diagnostic dialog did not dismiss with $action") {
                    val nodes = snapshot() ?: return@await false
                    nodes.none { it.text == text(R.string.buzin_close) } &&
                        nodes.any { it.text == text(R.string.tools_connection_diagnostics) }
                }
            }
        } catch (failure: AssertionError) {
            automation.takeScreenshot()?.let { image ->
                try {
                    File(context.filesDir, "main-contract-failure.png").outputStream().use {
                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { image.recycle() }
            }
            throw failure
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
        }
    }

    @Test fun recordingPolicyMatchesEditionAndLiveCaptureStatus() = screen {
        val prepare = text(R.string.tools_record_prepare)
        val stop = text(R.string.tools_record_stop)
        if (BuildConfig.RECORDING_ENABLED) {
            await("Record-capable edition has no prepare action") { has(prepare) }
            main { state("_captureState", MirrorCapture.State(armed = true, recording = true, message = "Saving test session")) }
            await("Active capture did not expose stop and status") {
                has(stop) && has("Saving test session") && absent(prepare)
            }
            main { state("_captureState", MirrorCapture.State()) }
            await("Stopped capture retained active status") {
                has(prepare) && absent(stop) && absent("Saving test session")
            }
        } else {
            await("Restricted edition exposed recording actions") {
                buttonEnabled(text(R.string.tools_relay_receive)) == true &&
                    absent(prepare) && absent(stop) && absent(text(R.string.tools_record_title))
            }
            main { state("_captureState", MirrorCapture.State(armed = true, message = "Saving test session")) }
            await("Restricted edition exposed injected capture state") {
                buttonEnabled(text(R.string.tools_relay_receive)) == false &&
                    absent(prepare) && absent(stop) && absent("Saving test session")
            }
        }
    }
}
