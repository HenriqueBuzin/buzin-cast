package io.github.jqssun.airplay

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.buzincast.core.AsyncLogStore
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.ui.MainScreen
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import io.github.jqssun.airplay.viewmodel.AudioDebug
import io.github.jqssun.airplay.viewmodel.DebugInfo
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Real main-screen dialogs/navigation and rendering, with an unbound ViewModel. */
@RunWith(AndroidJUnit4::class)
class MainScreenStateContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var root: File
    private lateinit var preferenceName: String
    private lateinit var model: MainViewModel
    private lateinit var store: ViewModelStore
    private lateinit var logs: AsyncLogStore
    private val networkRequests = AtomicInteger()
    private val surfaces = AtomicInteger()
    private val bitmaps = mutableListOf<Bitmap>()
    private var lastNodes = emptyList<Node>()
    private var originalLocale: Locale = Locale.ROOT
    private class TestApplication(base: Context) : Application() { init { attachBaseContext(base) } }
    private data class Node(val text: String, val description: String, val bounds: Rect, val selected: Boolean, val clickable: Boolean)

    @Before fun setup() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        preferenceName = "main-screen-${System.nanoTime()}"
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
        bitmaps.forEach { it.recycle() }
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
    private fun snapshot(): List<Node>? {
        val tree = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        try {
            if (tree.packageName?.toString() != context.packageName || tree.childCount == 0) return null
            val result = mutableListOf<Node>()
            fun visit(node: AccessibilityNodeInfo) {
                if (node.isVisibleToUser) {
                    val bounds = Rect(); node.getBoundsInScreen(bounds)
                    result += Node(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), bounds, node.isSelected, node.isClickable)
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
    private fun screen(block: () -> Unit) {
        ActivityScenario.launch<DebugTvPreviewActivity>(Intent(context, DebugTvPreviewActivity::class.java).putExtra("screen", "contract_host")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { target -> target.setContent { AirPlayTheme {
                MainScreen(model, onSurfaceAvailable = { surfaces.incrementAndGet() }, onSurfaceDestroyed = {},
                    onLocalNetwork = { networkRequests.incrementAndGet() })
            } } }
            await("Main screen did not expose content") { snapshot() != null }
            block()
        }
        assertEquals("Idle/audio/dialog states unexpectedly created a video surface", 0, surfaces.get())
    }

    @Test fun networkActionsFollowAskAndSettingsThenShowGrantedServerErrors() {
        main { model.setLocalNetwork(LocalNetworkAccess.ASK) }
        screen {
            await("Network permission explanation missing") { has(text(R.string.local_network_desc)) && has(text(R.string.local_network_label)) }
            click(text(R.string.btn_allow))
            assertEquals(1, networkRequests.get())
            main { model.setLocalNetwork(LocalNetworkAccess.SETTINGS) }
            click(text(R.string.btn_open_settings))
            assertEquals(2, networkRequests.get())
            main { model.setLocalNetwork(LocalNetworkAccess.GRANTED); state("_serverState", ServerState.ERROR) }
            await("Granted access did not reveal the server error") {
                val nodes = snapshot() ?: return@await false
                nodes.any { it.text == text(R.string.error_starting_server) } &&
                    nodes.any { it.text == text(R.string.error_label) } &&
                    nodes.none { it.text == text(R.string.local_network_label) || it.text == text(R.string.btn_open_settings) }
            }
        }
    }

    @Test fun pinConfirmationAndBackClearBothStateAndDialog() = screen {
        for ((pin, back) in listOf("1234" to false, "9876" to true)) {
            main { model.showPin(pin) }
            await("PIN dialog missing") { has(pin) && has(text(R.string.dialog_pin_title)) }
            if (back) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) else click(text(R.string.btn_ok))
            await("Dismissed PIN remained in state or accessibility") {
                val nodes = snapshot() ?: return@await false
                model.pinCode.value == null && nodes.none { it.text == pin || it.text == text(R.string.dialog_pin_title) } &&
                    control(text(R.string.tab_overview), selected = true)
            }
        }
    }

    @Test fun diagnosticsNavigationReturnsToOverviewWhenDeveloperOptionsAreDisabled() = screen {
        main { model.set(Prefs.DEVELOPER_OPTIONS, true) }
        await("Developer logs tab missing") { control(text(R.string.tab_logs)) }
        model.addLog("main-screen-contract-row"); assertTrue(logs.flush())
        click(text(R.string.tab_tools))
        await("Tools did not become selected") { control(text(R.string.tab_tools), selected = true) }
        click(text(R.string.tab_settings))
        await("Settings did not become selected") { control(text(R.string.tab_settings), selected = true) }
        click(text(R.string.tab_logs))
        await("Logs tab did not display its real log entry") {
            control(text(R.string.tab_logs), selected = true) && snapshot()?.any { "main-screen-contract-row" in it.text } == true
        }
        main { model.set(Prefs.DEVELOPER_OPTIONS, false) }
        await("Disabling developer options did not reset active diagnostics") {
            val nodes = snapshot() ?: return@await false
            control(text(R.string.tab_overview), selected = true) &&
                nodes.none { it.text == text(R.string.tab_logs) || "main-screen-contract-row" in it.text }
        }
    }

    @Test fun debugOverlayUpdatesMeasurementsAndRemovesEmptySections() {
        val audio = AudioDebug(12, 34, 1, 2, 3, 4, 5, 1400, 2500, 2, 3)
        main {
            state("_serverState", ServerState.RUNNING); state("_connectionCount", 1)
            model.set(Prefs.DEBUG_ENABLED, true)
            state("_debugInfo", DebugInfo(videoCodec = "H264 contract", videoRes = "640x360", videoFps = 30,
                videoBitrate = 1_500_000, videoFrames = 9, droppedFrames = 1, framePacingJitterUs = 1500,
                audioCodec = "PCM contract", serverVolume = 42, clientVolume = 63, audio = audio, connections = 1))
        }
        screen {
            for (value in listOf(text(R.string.debug_video, "H264 contract", "640x360"),
                text(R.string.debug_fps_bitrate, 30, "1.5 Mbps"),
                text(R.string.debug_audio, "PCM contract", 42, 63),
                text(R.string.debug_audio_decode, "1.4/2.5 ms held=2 errs=3"),
                text(R.string.debug_clients, 1))) await("Debug measurement missing: $value") { has(value) }
            main { state("_debugInfo", DebugInfo(audio = audio.copy(decodeMeanUs = 0, decodeHeld = 0, decodeErrors = 0), connections = 1)) }
            await("Empty video section or initial decode window was not updated") {
                val nodes = snapshot() ?: return@await false
                nodes.any { it.text == text(R.string.debug_audio_decode, "held=0 errs=0") } &&
                    nodes.none { it.text == text(R.string.debug_section_video).uppercase() || "H264 contract" in it.text }
            }
            main { state("_debugInfo", DebugInfo(connections = 1)) }
            await("Empty audio section remained") {
                val nodes = snapshot() ?: return@await false
                nodes.any { it.text == text(R.string.debug_clients, 1) } &&
                    nodes.none { it.text == text(R.string.debug_section_audio).uppercase() }
            }
            main { model.set(Prefs.DEBUG_ENABLED, false) }
            await("Disabled debug overlay remained visible") {
                val nodes = snapshot() ?: return@await false
                nodes.any { it.text == text(R.string.waiting_for_playback) } && nodes.none { it.text == text(R.string.debug_clients, 1) }
            }
        }
    }

    @Test fun audioMetadataAndCoverArtUpdateThenReturnToUnknownTrack() {
        main { state("_serverState", ServerState.RUNNING); state("_connectionCount", 1); state("_audioOnly", true)
            state("_trackInfo", TrackInfo(title = "Contract song", artist = "Contract artist", album = "Contract album")) }
        screen {
            await("Audio metadata missing") { has("Contract song") && has("Contract artist") && has("Contract album") }
            val art = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            bitmaps += art
            main { state("_trackInfo", TrackInfo(title = "Contract song", coverArt = art)) }
            await("Cover art was not painted") {
                val nodes = snapshot() ?: return@await false
                val bounds = nodes.firstOrNull { it.description == text(R.string.cd_cover_art) }?.bounds ?: return@await false
                if (bounds.isEmpty) return@await false
                val image = instrumentation.uiAutomation.takeScreenshot() ?: return@await false
                try { image.getPixel(bounds.centerX(), bounds.centerY()) == android.graphics.Color.BLUE }
                finally { image.recycle() }
            }
            main { state("_trackInfo", TrackInfo()) }
            await("Cleared metadata remained visible") {
                val nodes = snapshot() ?: return@await false
                nodes.any { it.text == text(R.string.unknown_track) } &&
                    nodes.none { it.text in listOf("Contract song", "Contract artist", "Contract album") || it.description == text(R.string.cd_cover_art) }
            }
        }
    }
}
