package io.github.jqssun.airplay

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.buzincast.core.AsyncLogStore
import io.buzincast.media.MirrorPacket
import io.github.jqssun.airplay.service.AirPlayService
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real Tools controls, diagnostics, and recording confirmation with isolated fixtures. */
@RunWith(AndroidJUnit4::class)
class ToolsScreenStateContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var root: File
    private lateinit var preferenceName: String
    private lateinit var model: MainViewModel
    private lateinit var store: ViewModelStore
    private lateinit var logs: AsyncLogStore
    private val appLaunches = CopyOnWriteArrayList<Intent>()
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
            override fun startActivity(intent: Intent) { appLaunches += intent }
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
    private fun control(label: String, selected: Boolean = false, action: Int? = null): Boolean {
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
                            if (!selected && parent.isClickable) {
                                if (action == null) return true
                                if (action == AccessibilityNodeInfo.ACTION_FOCUS) {
                                    if (parent.isFocused) return true
                                    parent.performAction(action)
                                    return false
                                }
                                return parent.performAction(action)
                            }
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
    private fun click(label: String) { await("No actionable control: $label") { control(label, action = AccessibilityNodeInfo.ACTION_CLICK) } }

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
    private fun screen(
        fontScale: Float? = null,
        wrapContext: (Context) -> Context = { it },
        block: () -> Unit,
    ) {
        ActivityScenario.launch<DebugTvPreviewActivity>(Intent(context, DebugTvPreviewActivity::class.java).putExtra("screen", "contract_host")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { target ->
                val screenContext = wrapContext(target)
                target.setContent {
                    AirPlayTheme {
                        val density = LocalDensity.current
                        CompositionLocalProvider(
                            LocalContext provides screenContext,
                            LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
                        ) {
                            ToolsScreen(model)
                        }
                    }
                }
            }
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

    private fun readRelayNode(node: AccessibilityNodeInfo): Boolean =
        Build.VERSION.SDK_INT !in 30..33 || node.refresh()

    @Suppress("DEPRECATION")
    private fun withRelayNodes(action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 34) check(automation.clearCache()) {
            "Could not invalidate the relay form accessibility cache"
        }
        val windows = automation.windows
        try {
            for (window in windows.sortedByDescending { it.isFocused }) {
                if (!window.isActive && !window.isFocused) continue
                val root = window.root ?: continue
                try {
                    if (root.refresh() && root.packageName?.toString() == context.packageName && root.childCount > 0)
                        return action(root)
                } finally { root.recycle() }
            }
        } finally { windows.forEach { it.recycle() } }
        return false
    }

    @Suppress("DEPRECATION")
    private fun withRelayFields(action: (List<AccessibilityNodeInfo>) -> Boolean): Boolean = withRelayNodes { root ->
        val fields = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.className?.toString() == "android.widget.EditText") fields += AccessibilityNodeInfo.obtain(node)
            repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
                try { visit(child) } finally { child.recycle() }
            } }
        }
        try {
            visit(root)
            fields.size == 3 && fields.all(::readRelayNode) && action(fields)
        } finally { fields.forEach { it.recycle() } }
    }

    private fun relayKey(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
    }

    private fun relayFieldFocused(index: Int) = withRelayFields { it[index].isFocused }

    private fun editRelayField(index: Int, key: Int, value: String, normalized: String) {
        await("Relay field $index did not receive focus") { relayFieldFocused(index) }
        relayKey(key)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        await("Relay field $index did not accept editing") {
            withRelayFields { it[index].isFocused && it[index].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) }
        }
        await("Relay field $index did not show its normalized value") {
            withRelayFields { it[index].text?.toString() == normalized }
        }
    }

    @Suppress("DEPRECATION")
    private fun relayControlFocused(label: String): Boolean = withRelayNodes { root ->
        fun hasLabel(node: AccessibilityNodeInfo): Boolean {
            if (node.text?.toString() == label) return true
            repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
                try { if (!child.isFocusable && hasLabel(child)) return true } finally { child.recycle() }
            } }
            return false
        }
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (!readRelayNode(node)) return false
            if (node.isFocused && hasLabel(node)) return true
            repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
                try { if (visit(child)) return true } finally { child.recycle() }
            } }
            return false
        }
        visit(root)
    }

    @Test fun relayTvFormKeepsFieldsReadOnlyUntilOkAndRetainsAcceptedValues() = screen {
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            val open = text(R.string.tools_relay_connect)
            val connect = text(R.string.tools_connect)
            click(open)
            await("Relay form did not focus the address") { relayFieldFocused(0) }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Draft before OK")
            }
            assertTrue(withRelayFields { !it[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) })
            relayKey(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Remote Down did not focus the port") { relayFieldFocused(1) }
            relayKey(KeyEvent.KEYCODE_DPAD_UP)
            editRelayField(0, KeyEvent.KEYCODE_ENTER, " 127.0.0.1 ", " 127.0.0.1 ")
            relayKey(KeyEvent.KEYCODE_DPAD_DOWN)
            editRelayField(1, KeyEvent.KEYCODE_DPAD_CENTER, "x7123!", "7123")
            relayKey(KeyEvent.KEYCODE_DPAD_DOWN)
            editRelayField(2, KeyEvent.KEYCODE_DPAD_CENTER, "ABCD-EFGH-IJKL", "ABCD-EFGH-IJKL")

            relayKey(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Remote Down did not focus Connect in the valid relay form") { relayControlFocused(connect) }
            relayKey(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Submitting the form did not restore focus") { relayControlFocused(open) }
            relayKey(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Accepted relay values did not survive reopening") {
                withRelayFields { fields -> fields.map { it.text?.toString() } == listOf("127.0.0.1", "7123", "ABCD-EFGH-IJKL") }
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
        }
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

    @Test fun diagnosticDialogScrollsInBothDirectionsWithTheRemote() = screen(fontScale = 2.5f) {
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val diagnostics = text(R.string.tools_connection_diagnostics)
        val close = text(R.string.buzin_close)
        try {
            click(diagnostics)
            await("The diagnostics dialog did not receive focus") {
                snapshot(requireFocusedWindow = true)?.any { it.text == close } == true
            }
            lateinit var reportBounds: Rect
            await("The diagnostics report did not expose a scrollable region") {
                val report = snapshot(requireFocusedWindow = true)
                    ?.firstOrNull { it.className == "android.widget.ScrollView" && !it.bounds.isEmpty }
                if (report == null) false else {
                    reportBounds = Rect(report.bounds)
                    true
                }
            }
            await("The close button did not accept remote focus") {
                control(close, action = AccessibilityNodeInfo.ACTION_FOCUS)
            }
            fun scrollAction(action: Int) {
                val root = requireNotNull(automation.rootInActiveWindow)
                fun visit(node: AccessibilityNodeInfo): Boolean {
                    if (node.isVisibleToUser && node.className == "android.widget.ScrollView" &&
                        node.performAction(action)) return true
                    repeat(node.childCount) { index ->
                        val child = node.getChild(index) ?: return@repeat
                        try { if (visit(child)) return true } finally { child.recycle() }
                    }
                    return false
                }
                try { assertTrue("The report did not accept accessibility scroll action $action", visit(root)) }
                finally { root.recycle() }
            }
            fun reportFingerprint(): Int {
                val image = requireNotNull(automation.takeScreenshot())
                try {
                    val bounds = Rect(reportBounds)
                    check(bounds.intersect(0, 0, image.width, image.height))
                    val pixels = IntArray(bounds.width() * bounds.height())
                    image.getPixels(pixels, 0, bounds.width(), bounds.left, bounds.top, bounds.width(), bounds.height())
                    return pixels.contentHashCode()
                } finally {
                    image.recycle()
                }
            }
            fun settledReport(message: String, expected: (Int) -> Boolean): Int {
                var fingerprint: Int? = null
                var lastChange = SystemClock.elapsedRealtime()
                await(message) {
                    val current = reportFingerprint()
                    if (current != fingerprint) {
                        fingerprint = current
                        lastChange = SystemClock.elapsedRealtime()
                    }
                    expected(current) && SystemClock.elapsedRealtime() - lastChange >= 300
                }
                return requireNotNull(fingerprint)
            }

            val top = settledReport("Report layout did not settle") { true }
            scrollAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            settledReport("Accessibility scroll did not move the report") { it != top }
            scrollAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            settledReport("Accessibility scroll did not return to the start") { it == top }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            settledReport("Remote Down did not move the report") { it != top }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            settledReport("Remote Up did not return to the start") { it == top }
            tap(close)
            await("The scrolled diagnostics dialog did not close") {
                snapshot()?.let { nodes ->
                    nodes.any { it.text == diagnostics } && nodes.none { it.text == close }
                } == true
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
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

    @Test fun missingSystemMirroringAppShowsRecoveryInstructions() {
        val requests = CopyOnWriteArrayList<ComponentName>()
        val missing = AtomicBoolean(false)
        val sink = ComponentName("com.amazon.cast.sink", "com.amazon.cast.sink.DisplayMirroringSinkActivity")
        val open = text(R.string.tools_native_open)
        val unavailable = text(R.string.tools_native_unavailable)

        screen(wrapContext = { target ->
            object : ContextWrapper(target) {
                override fun startActivity(intent: Intent) {
                    requests += requireNotNull(intent.component)
                    if (missing.get()) throw ActivityNotFoundException("No mirroring app installed in this fixture")
                }
            }
        }) {
            revealToolsItem(open)
            assertTrue(absent(unavailable))
            click(open)
            await("Tools did not open the Fire TV mirroring activity") { requests.size == 1 }
            assertEquals(sink, requests.single())
            assertTrue(absent(unavailable))

            missing.set(true)
            click(open)
            revealToolsItem(unavailable)
            assertEquals(listOf(sink, sink), requests.toList())
        }
    }

    @Suppress("DEPRECATION")
    private fun revealToolsItem(label: String) {
        fun scroll(node: AccessibilityNodeInfo): Boolean {
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            repeat(node.childCount) { index ->
                val child = node.getChild(index) ?: return@repeat
                try { if (scroll(child)) return true } finally { child.recycle() }
            }
            return false
        }
        await("Tools did not show $label") {
            if (has(label)) return@await true
            val tree = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
            try {
                if (tree.packageName?.toString() == context.packageName && scroll(tree)) Thread.sleep(250)
                false
            } finally { tree.recycle() }
        }
    }

    private fun recordedVideo(): ByteArray {
        val isolated = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?) = File(root, "recording-fixture")
        }
        val capture = MirrorCapture(isolated) {}
        try {
            val frame = context.assets.open("mirror/sample.h264").use { it.readBytes() }
            capture.start()
            capture.offer(MirrorPacket.Video(frame, 1_000_000_000L, 64, 48, false))
            await("The recording fixture did not receive its keyframe") { capture.state.value.recording }
        } finally {
            capture.close()
        }
        return capture.directory.listFiles().orEmpty().single().readBytes()
    }

    @Suppress("DEPRECATION")
    @Test fun recordingExportAndDeletionApplyToTheSelectedFile() {
        val connected = CountDownLatch(1)
        var boundService: AirPlayService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = requireNotNull((binder as? AirPlayService.LocalBinder)?.service)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        assertTrue(context.bindService(Intent(context, AirPlayService::class.java), connection, Context.BIND_AUTO_CREATE))
        val files = mutableListOf<File>()
        try {
            assertTrue("The recording service did not bind", connected.await(15, TimeUnit.SECONDS))
            val service = requireNotNull(boundService)
            val directory = service.capture.directory
            assertTrue(directory.isDirectory || directory.mkdirs())
            val remaining = File.createTempFile("recording-retain-", ".mkv", directory).also(files::add)
            val selected = File.createTempFile("recording-delete-", ".mkv", directory).also(files::add)
            val automation = instrumentation.uiAutomation
            val flags = automation.serviceInfo.flags
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            try {
                remaining.writeBytes(byteArrayOf(1, 2, 3))
                val selectedBytes = if (BuildConfig.RECORDING_ENABLED) recordedVideo() else byteArrayOf(4, 5, 6)
                selected.writeBytes(selectedBytes)
                assertTrue(remaining.setLastModified(selected.lastModified() - 2_000))
                main { model.bindService(service) }
                val remove = text(R.string.tools_delete)
                val confirm = text(R.string.tools_delete_confirm)
                val recordings = text(R.string.tools_recordings)
                screen {
                    if (BuildConfig.RECORDING_ENABLED) {
                        fun recordingListVisible(): Boolean {
                            val nodes = snapshot() ?: return false
                            return nodes.none { it.text == confirm } && nodes.any {
                                it.text == recordings || it.text.startsWith("${selected.name} • ") ||
                                    it.text.startsWith("${remaining.name} • ") || it.text == text(R.string.tab_tools)
                            }
                        }
                        fun openConfirmation() {
                            revealToolsItem(remove)
                            click(remove)
                            await("The confirmation did not name the selected recording") {
                                has(confirm) && has(selected.name)
                            }
                        }
                        val play = text(R.string.tools_play)
                        val export = text(R.string.btn_export)
                        val refresh = text(R.string.tools_refresh)
                        revealToolsItem(refresh)
                        val late = File.createTempFile("recording-refresh-", ".mkv", directory).also(files::add)
                        late.writeBytes(byteArrayOf(7, 8, 9))
                        assertTrue(model.recordings.value.none { it.name == late.name })
                        click(refresh)
                        await("Refresh did not find the new recording") {
                            model.recordings.value.any { it.name == late.name && it.bytes == 3L }
                        }
                        assertTrue(late.delete())
                        click(refresh)
                        await("Refresh retained a recording removed from disk") {
                            model.recordings.value.none { it.name == late.name }
                        }
                        revealToolsItem(play)
                        fun expectActions(state: String, canPlay: Boolean, canManage: Boolean) {
                            await("Unexpected recording actions while $state") {
                                buttonEnabled(play) == canPlay && buttonEnabled(export) == canManage &&
                                    buttonEnabled(remove) == canManage
                            }
                        }
                        expectActions("idle", canPlay = true, canManage = true)
                        main { state("_connectionCount", 1) }
                        expectActions("a sender is connected", canPlay = false, canManage = true)
                        main { state("_connectionCount", 0) }
                        expectActions("the sender has disconnected", canPlay = true, canManage = true)
                        main { state("_relayState", MirrorRelay.State(receiving = true)) }
                        expectActions("a relay is streaming", canPlay = false, canManage = true)
                        main { state("_relayState", MirrorRelay.State()) }
                        expectActions("the relay has stopped", canPlay = true, canManage = true)
                        main { state("_captureState", MirrorCapture.State(armed = true, recording = true)) }
                        expectActions("capture is active", canPlay = false, canManage = false)
                        main { state("_captureState", MirrorCapture.State()) }
                        expectActions("capture has stopped", canPlay = true, canManage = true)
                        // Resolve FileProvider's cached roots against the real app directories.
                        val expectedUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", selected)
                        click(export)
                        await("Export did not launch a sharing chooser") { appLaunches.isNotEmpty() }
                        val chooser = appLaunches.single()
                        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                        val share = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
                        assertEquals(Intent.ACTION_SEND, share.action)
                        assertEquals("video/x-matroska", share.type)
                        assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                        val uri = requireNotNull(share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                        assertEquals(expectedUri, uri)
                        assertArrayEquals(selectedBytes,
                            requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() })
                        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                        val launch = Prefs.LAUNCH_ON_CONNECT
                        val originalLaunch = prefs.takeIf { it.contains(launch.key) }?.let(launch::read)
                        try {
                            launch.write(prefs, false)
                            click(play)
                            await("Play did not open the selected recording") {
                                service.videoPlaybackActive.value && service.videoLocation.value == uri.toString()
                            }
                        } finally {
                            service.stopVideoPlayback()
                            if (originalLaunch == null) prefs.edit().remove(launch.key).apply()
                            else launch.write(prefs, originalLaunch)
                        }
                        await("The recording did not stop") { !service.videoPlaybackActive.value }
                        openConfirmation()
                        tap(text(R.string.tv_edit_cancel))
                        await("Cancel did not restore the recording list") { recordingListVisible() && selected.isFile }
                        openConfirmation()
                        relayKey(KeyEvent.KEYCODE_BACK)
                        await("Back did not restore the recording list") { recordingListVisible() && selected.isFile }
                        openConfirmation()
                        tap(remove)
                        await("The confirmed deletion did not update the file and list") {
                            !selected.exists() && model.recordings.value.none { it.name == selected.name } && !has(confirm)
                        }
                        assertTrue(model.recordings.value.any { it.name == remaining.name })
                    } else {
                        revealToolsItem(text(R.string.tools_native_open))
                        assertTrue(model.recordings.value.isEmpty())
                        assertTrue(absent(remove))
                        assertTrue(absent(text(R.string.btn_export)))
                        assertTrue(absent(text(R.string.tools_refresh)))
                        assertTrue(appLaunches.isEmpty())
                        assertTrue(selected.isFile)
                    }
                    assertArrayEquals(byteArrayOf(1, 2, 3), remaining.readBytes())
                }
            } finally {
                automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
                main { model.unbindService() }
            }
        } finally {
            context.unbindService(connection)
            boundService?.let { service ->
                await("The recording service did not stop") {
                    var destroyed = false
                    main { destroyed = service.lifecycle.currentState == Lifecycle.State.DESTROYED }
                    destroyed
                }
            }
            files.forEach { file -> if (file.exists()) assertTrue(file.delete()) }
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
