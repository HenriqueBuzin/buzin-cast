package io.github.jqssun.airplay

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.buzincast.core.AsyncLogStore
import io.github.jqssun.airplay.ui.SettingsScreen
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Real touch settings, persisted through an isolated unbound MainViewModel. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenStateContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var isolated: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var root: File
    private lateinit var preferenceName: String
    private lateinit var model: MainViewModel
    private lateinit var store: ViewModelStore
    private lateinit var logs: AsyncLogStore
    private lateinit var scrollState: ScrollState
    private var activity: DebugTvPreviewActivity? = null
    private class TestApplication(base: Context) : Application() { init { attachBaseContext(base) } }

    @Before fun setup() {
        preferenceName = "settings-contract-${System.nanoTime()}"
        root = File(context.cacheDir, preferenceName).apply { check(mkdirs()) }
        val config = Configuration(context.resources.configuration).apply {
            setLocale(Locale.US)
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_NORMAL
        }
        isolated = object : ContextWrapper(context.createConfigurationContext(config)) {
            override fun getFilesDir() = root
            override fun getCacheDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferenceName, mode)
        }
        prefs = isolated.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        instrumentation.runOnMainSync {
            model = MainViewModel(TestApplication(isolated))
            store = ViewModelStore().also { it.put("settings", model) }
            logs = MainViewModel::class.java.getDeclaredField("logStore").apply { isAccessible = true }.get(model) as AsyncLogStore
        }
    }

    @After fun cleanup() {
        if (::logs.isInitialized) assertTrue(logs.flush())
        if (::store.isInitialized) instrumentation.runOnMainSync { store.clear() }
        if (::logs.isInitialized) assertTrue("Settings log worker survived clearing", logs.awaitClosed())
        if (::prefs.isInitialized) assertTrue(prefs.edit().clear().commit())
        if (::root.isInitialized) {
            check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            assertTrue(root.deleteRecursively())
        }
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    private fun label(id: Int) = isolated.getString(id)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(30)
        }
        fail("$message; nodes=${texts()}")
    }

    @Suppress("DEPRECATION")
    private fun <T> tree(block: (AccessibilityNodeInfo) -> T): T? {
        if (Build.VERSION.SDK_INT >= 34) check(instrumentation.uiAutomation.clearCache()) {
            "Could not invalidate the accessibility cache"
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        try {
            if (!root.refresh() || root.packageName?.toString() != context.packageName || root.childCount == 0) return null
            return block(root)
        } finally { root.recycle() }
    }
    private fun matches(node: AccessibilityNodeInfo, value: String): Boolean =
        node.text?.toString()?.lines()?.contains(value) == true || node.contentDescription?.toString() == value ||
            (Build.VERSION.SDK_INT >= 26 && node.hintText?.toString() == value)

    @Suppress("DEPRECATION")
    private fun find(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) return AccessibilityNodeInfo.obtain(root)
        repeat(root.childCount) { index -> root.getChild(index)?.let { child ->
            try { find(child, predicate)?.let { return it } } finally { child.recycle() }
        } }
        return null
    }

    @Suppress("DEPRECATION")
    private fun texts(): List<String>? = tree { root ->
        val result = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) result += "${node.className}:${node.text}:${node.contentDescription}"
            repeat(node.childCount) { i -> node.getChild(i)?.let { try { visit(it) } finally { it.recycle() } } }
        }
        visit(root)
        result.take(100)
    }

    @Suppress("DEPRECATION")
    private fun reveal(value: String) {
        fun visible() = tree { root ->
            find(root) { it.isVisibleToUser && matches(it, value) }?.let { it.recycle(); true } ?: false
        } == true
        if (visible()) return
        // Scroll in overlapping steps and inspect the settled layout. An animated
        // accessibility page scroll can advance after the title first appears.
        val step = (scrollState.viewportSize / 2).coerceAtLeast(1)
        for (direction in listOf(1, -1)) {
            while (true) {
                val position = scrollState.value
                val next = (position + direction * step).coerceIn(0, scrollState.maxValue)
                if (next == position) break
                runBlocking(Dispatchers.Main) { scrollState.scrollTo(next) }
                instrumentation.waitForIdleSync()
                if (visible()) return
            }
        }
        fail("Setting not reachable: $value; nodes=${texts()}")
    }

    @Suppress("DEPRECATION")
    private fun checked(value: String): Boolean? = tree { root ->
        var node = find(root) { it.isVisibleToUser && matches(it, value) } ?: return@tree null
        try {
            repeat(8) {
                if (node.isCheckable) return@tree node.isChecked
                val parent = node.parent ?: return@tree null
                node.recycle(); node = parent
            }
            null
        } finally { node.recycle() }
    }

    private fun switch(id: Int, pref: BoolPref, wanted: Boolean) {
        val value = label(id)
        reveal(value)
        await("Switch state was not rendered: $value") { checked(value) == pref.read(prefs) }
        if (pref.read(prefs) != wanted) click(value)
        await("Switch did not persist and render: $value=$wanted") { pref.read(prefs) == wanted && checked(value) == wanted }
    }

    @Suppress("DEPRECATION")
    private fun sliderValue(maximum: Float): Float? = tree { root ->
        find(root) { it.isVisibleToUser && it.rangeInfo?.max == maximum }?.let { node ->
            try { node.rangeInfo.current } finally { node.recycle() }
        }
    }

    @Suppress("DEPRECATION")
    private fun adjustSlider(maximum: Float, key: Int) {
        await("Slider was not focusable: max=$maximum") {
            tree { root -> find(root) { it.isVisibleToUser && it.rangeInfo?.max == maximum }?.let { node ->
                try { node.isFocused || node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } finally { node.recycle() }
            } ?: false } == true
        }
        await("Slider did not receive keyboard focus: max=$maximum") {
            tree { root -> find(root) { it.isVisibleToUser && it.rangeInfo?.max == maximum }?.let { node ->
                try { node.isFocused } finally { node.recycle() }
            } ?: false } == true
        }
        Log.d("SettingsContractTrace", "before key=$key max=$maximum range=${sliderValue(maximum)} adaptive=${Prefs.AUDIO_ADAPTIVE_STEP.read(prefs)} delay=${Prefs.AUDIO_LATENCY_MS.read(prefs)}")
        instrumentation.sendKeyDownUpSync(key)
        Log.d("SettingsContractTrace", "after key=$key max=$maximum range=${sliderValue(maximum)} adaptive=${Prefs.AUDIO_ADAPTIVE_STEP.read(prefs)} delay=${Prefs.AUDIO_LATENCY_MS.read(prefs)}")
    }

    @Suppress("DEPRECATION")
    private fun withEditor(value: String, action: (AccessibilityNodeInfo) -> Boolean): Boolean =
        tree { root ->
            var node = find(root) { it.isVisibleToUser && matches(it, value) } ?: return@tree false
            try {
                repeat(5) {
                    val editor = find(node) { it.isVisibleToUser && it.isEditable }
                    if (editor != null) {
                        try { return@tree editor.refresh() && action(editor) } finally { editor.recycle() }
                    }
                    val parent = node.parent ?: return@tree false
                    node.recycle(); node = parent
                }
                false
            } finally { node.recycle() }
        } == true

    private fun edit(value: String, input: String, expectedText: String = input) {
        reveal(value)
        await("Editor did not receive focus: $value") {
            withEditor(value) { editor ->
                if (editor.isFocused) true else {
                    editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    false
                }
            }
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input)
        }
        await("Editor did not accept the text: $value") {
            withEditor(value) { editor ->
                editor.isFocused && editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }
        await("Editor did not show the new text: $value") {
            withEditor(value) { it.isFocused && it.text?.toString().orEmpty() == expectedText }
        }
    }

    private fun done() {
        var delivered = false
        instrumentation.runOnMainSync {
            fun visit(view: View): Boolean {
                if (view.hasFocus() && view.onCheckIsTextEditor()) {
                    val connection = view.onCreateInputConnection(EditorInfo())
                    if (connection != null) return connection.performEditorAction(EditorInfo.IME_ACTION_DONE)
                }
                if (view is ViewGroup) repeat(view.childCount) { if (visit(view.getChildAt(it))) return true }
                return false
            }
            delivered = visit(requireNotNull(activity).window.decorView)
        }
        assertTrue("Focused real editor rejected IME Done", delivered)
        instrumentation.waitForIdleSync()
    }

    @Suppress("DEPRECATION")
    private fun click(value: String) {
        reveal(value)
        await("No clickable setting: $value") {
            tree { root ->
                var node = find(root) { it.isVisibleToUser && matches(it, value) } ?: return@tree false
                try {
                    repeat(8) {
                        if (node.isClickable && node.isEnabled) return@tree node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        val parent = node.parent ?: return@tree false
                        node.recycle(); node = parent
                    }
                    false
                } finally { node.recycle() }
            } == true
        }
    }

    private fun screen(block: () -> Unit) {
        ActivityScenario.launch<DebugTvPreviewActivity>(Intent(context, DebugTvPreviewActivity::class.java)
            .putExtra("screen", "contract_host").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { target ->
                activity = target
                scrollState = ScrollState(0)
                val original = requireNotNull(target.window.callback)
                target.window.callback = object : Window.Callback by original {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        val before = Prefs.AUDIO_LATENCY_MS.read(prefs) to Prefs.AUDIO_ADAPTIVE_STEP.read(prefs)
                        val consumed = original.dispatchKeyEvent(event)
                        if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                            Log.d("SettingsWindowKey", "action=${event.action} key=${event.keyCode} before=$before after=${Prefs.AUDIO_LATENCY_MS.read(prefs) to Prefs.AUDIO_ADAPTIVE_STEP.read(prefs)} consumed=$consumed")
                        }
                        return consumed
                    }
                }
                target.setContent { CompositionLocalProvider(LocalContext provides isolated) {
                    AirPlayTheme { SettingsScreen(model, scrollState) }
                } }
            }
            await("Settings screen not rendered") { texts()?.any { it.contains(label(R.string.setting_server_name)) } == true }
            try { block() } finally {
                scenario.onActivity { it.window.callback = it }
                activity = null
            }
        }
    }

    @Test fun numericPortRejectsInvalidValuesThenNormalizesAndCommitsValidDigits() = screen {
        val port = label(R.string.setting_server_port)
        for (invalid in listOf("", "0", "9999999")) {
            edit(port, invalid, expectedText = invalid.take(6))
            done()
            assertEquals(7000, Prefs.SERVER_PORT.read(prefs))
            assertFalse("Invalid port was persisted", prefs.contains(Prefs.SERVER_PORT.key))
        }
        edit(port, "A70?01b", expectedText = "7001")
        done()
        await("Normalized valid port did not persist") { Prefs.SERVER_PORT.read(prefs) == 7001 }
    }

    @Test fun unchangedPortIsNotWrittenAndReceiverNameIsTrimmed() = screen {
        edit(label(R.string.setting_server_port), "7000")
        done()
        assertFalse("Unchanged default port was rewritten", prefs.contains(Prefs.SERVER_PORT.key))
        edit(label(R.string.setting_server_name), "  Studio Receiver  ")
        done()
        await("Receiver name was not trimmed and persisted") { Prefs.SERVER_NAME.read(prefs) == "Studio Receiver" }
    }

    @Test fun customResolutionRejectsZeroThenCommitsExactSizeAndReturnsToPreset() {
        assertTrue(Prefs.RESOLUTION.write(prefs, "1600x900"))
        screen {
            edit(label(R.string.setting_resolution_width), "0")
            edit(label(R.string.setting_resolution_height), "1080")
            done()
            assertEquals("1600x900", Prefs.RESOLUTION.read(prefs))
            edit(label(R.string.setting_resolution_width), "1920")
            edit(label(R.string.setting_resolution_height), "1200")
            done()
            await("Custom size did not persist") { Prefs.RESOLUTION.read(prefs) == "1920x1200" }
            click("1920x1080")
            await("Preset did not replace custom size") { Prefs.RESOLUTION.read(prefs) == "1920x1080" }
        }
    }

    @Test fun fixedAudioBufferAndOutputFrameFieldsEnforceTheirOwnBounds() {
        assertTrue(Prefs.DEVELOPER_OPTIONS.write(prefs, true))
        assertTrue(Prefs.AUDIO_AUTO_BUFFER.write(prefs, false))
        screen {
            val cushion = label(R.string.setting_audio_cushion_ms)
            for (invalid in listOf("0", "1001")) {
                edit(cushion, invalid); done()
                assertEquals(40, Prefs.AUDIO_CUSHION_MS.read(prefs))
                assertFalse(prefs.contains(Prefs.AUDIO_CUSHION_MS.key))
            }
            edit(cushion, "750"); done()
            await("Fixed cushion not saved") { Prefs.AUDIO_CUSHION_MS.read(prefs) == 750 }
            val frames = label(R.string.setting_oboe_buffer_frames)
            edit(frames, "8193"); done()
            assertEquals(0, Prefs.OBOE_BUFFER_FRAMES.read(prefs))
            assertFalse(prefs.contains(Prefs.OBOE_BUFFER_FRAMES.key))
            edit(frames, "8192"); done()
            await("Maximum output buffer not saved") { Prefs.OBOE_BUFFER_FRAMES.read(prefs) == 8192 }
            edit(frames, "0"); done()
            await("Automatic output buffer not restored") { Prefs.OBOE_BUFFER_FRAMES.read(prefs) == 0 }
        }
    }

    @Test fun standardAndAdvancedSwitchesPersistBothStatesAndRenderTheirSelection() {
        assertTrue(Prefs.DEVELOPER_OPTIONS.write(prefs, true))
        val controls = listOf(
            R.string.setting_boot_auto_start to Prefs.BOOT_AUTO_START,
            R.string.setting_run_in_background to Prefs.RUN_IN_BACKGROUND,
            R.string.setting_castv2 to Prefs.CASTV2_ENABLED,
            R.string.setting_fcast to Prefs.FCAST_ENABLED,
            R.string.setting_dlna to Prefs.DLNA_ENABLED,
            R.string.setting_approve_connections to Prefs.APPROVE_CONNECTIONS,
            R.string.setting_network_recovery to Prefs.NETWORK_RECOVERY,
            R.string.setting_require_pin to Prefs.REQUIRE_PIN,
            R.string.setting_allow_new_conn to Prefs.ALLOW_NEW_CONN,
            R.string.setting_auto_fullscreen to Prefs.AUTO_FULLSCREEN,
            R.string.setting_sw_alac to Prefs.FORCE_SW_ALAC,
            R.string.setting_sw_aac to Prefs.FORCE_SW_AAC,
            R.string.setting_sync_client_volume to Prefs.SYNC_CLIENT_VOLUME,
            R.string.setting_auto_start to Prefs.AUTO_START,
            R.string.setting_keep_screen_on to Prefs.KEEP_SCREEN_ON,
            R.string.setting_idle_preview to Prefs.IDLE_PREVIEW,
            R.string.setting_advertise_video to Prefs.ADVERTISE_VIDEO,
            R.string.setting_advertise_mirroring to Prefs.ADVERTISE_MIRRORING,
            R.string.setting_advertise_audio to Prefs.ADVERTISE_AUDIO,
            R.string.setting_alac to Prefs.ALAC_ENABLED,
            R.string.setting_aac to Prefs.AAC_ENABLED,
            R.string.setting_key_allow_frame_drop to Prefs.KEY_ALLOW_FRAME_DROP,
            R.string.setting_enforce_sdr to Prefs.ENFORCE_SDR,
            R.string.setting_realtime_decoder_priority to Prefs.KEY_PRIORITY,
            R.string.setting_low_latency to Prefs.LOW_LATENCY,
            R.string.setting_scheduled_output_buffer_release to Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE,
            R.string.setting_debug_overlay to Prefs.DEBUG_ENABLED,
            R.string.setting_benchmark_log to Prefs.BENCHMARK_LOG,
        )
        screen {
            for ((id, pref) in controls) {
                val original = pref.read(prefs)
                switch(id, pref, !original)
                switch(id, pref, original)
            }
        }
    }

    @Test fun audioModeChangesExposeTuningAndRemoteAdjustmentsRespectSliderBounds() {
        assertTrue(Prefs.DEVELOPER_OPTIONS.write(prefs, true))
        assertTrue(Prefs.AUDIO_AUTO_BUFFER.write(prefs, false))
        assertTrue(Prefs.AUDIO_ADAPTIVE_STEP.write(prefs, 0))
        screen {
            click(label(R.string.setting_audio_delay))
            reveal(isolated.getString(R.string.audio_delay_value, 250))
            await("Enabled audio delay did not render") { Prefs.AUDIO_LATENCY_MS.read(prefs) == 250 && sliderValue(1000f) == 250f }
            adjustSlider(1000f, KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Remote delay increase failed") { Prefs.AUDIO_LATENCY_MS.read(prefs) == 300 && sliderValue(1000f) == 300f }
            adjustSlider(1000f, KeyEvent.KEYCODE_DPAD_LEFT)
            await("Remote delay decrease failed") { Prefs.AUDIO_LATENCY_MS.read(prefs) == 250 && sliderValue(1000f) == 250f }
            switch(R.string.setting_audio_auto_buffer, Prefs.AUDIO_AUTO_BUFFER, true)
            reveal(isolated.getString(R.string.audio_adaptive_value, Prefs.ADAPTIVE_PERCENTILES[0],
                isolated.resources.getStringArray(R.array.audio_adaptive_step_names)[0]))
            await("Adaptive slider did not render") { sliderValue(4f) == 0f }
            adjustSlider(4f, KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals(0, Prefs.AUDIO_ADAPTIVE_STEP.read(prefs))
            for (step in 1..4) {
                adjustSlider(4f, KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Adaptive step $step not applied") { Prefs.AUDIO_ADAPTIVE_STEP.read(prefs) == step && sliderValue(4f) == step.toFloat() }
            }
            adjustSlider(4f, KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals(4, Prefs.AUDIO_ADAPTIVE_STEP.read(prefs))
            adjustSlider(4f, KeyEvent.KEYCODE_DPAD_LEFT)
            await("Adaptive decrease failed") { Prefs.AUDIO_ADAPTIVE_STEP.read(prefs) == 3 && sliderValue(4f) == 3f }
            switch(R.string.setting_audio_auto_buffer, Prefs.AUDIO_AUTO_BUFFER, false)
            reveal(label(R.string.setting_audio_cushion_ms))
            assertEquals(40, Prefs.AUDIO_CUSHION_MS.read(prefs))
        }
    }
}
