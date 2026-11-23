package io.github.jqssun.airplay

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
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
import java.util.concurrent.CopyOnWriteArrayList

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
    private var previousAccessibilityFlags: Int? = null
    private class TestApplication(base: Context) : Application() { init { attachBaseContext(base) } }

    @Before fun setup() {
        val automation = instrumentation.uiAutomation
        val serviceInfo = requireNotNull(automation.serviceInfo)
        previousAccessibilityFlags = serviceInfo.flags
        serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = serviceInfo
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
        previousAccessibilityFlags?.let { flags ->
            val automation = instrumentation.uiAutomation
            val serviceInfo = requireNotNull(automation.serviceInfo)
            serviceInfo.flags = flags
            automation.serviceInfo = serviceInfo
        }
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
        fun isAppRoot(root: AccessibilityNodeInfo) =
            root.refresh() && root.packageName?.toString() == context.packageName && root.childCount > 0

        instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
            try { if (isAppRoot(root)) return block(root) } finally { root.recycle() }
        }
        // The active-window query can briefly drop the app after a TV dialog closes.
        val windows = instrumentation.uiAutomation.windows
        try {
            for (window in windows.sortedByDescending { it.isFocused }) {
                if (!window.isFocused && !window.isActive) continue
                val root = window.root ?: continue
                try { if (isAppRoot(root)) return block(root) } finally { root.recycle() }
            }
        } finally { windows.forEach { it.recycle() } }
        return null
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
            if (node.isVisibleToUser) {
                val range = node.rangeInfo?.let { " range=${it.current}/${it.min}..${it.max}" }.orEmpty()
                val focused = if (node.isFocused) " focused" else ""
                result += "${node.className}:${node.text}:${node.contentDescription}$range$focused"
            }
            repeat(node.childCount) { i -> node.getChild(i)?.let { try { visit(it) } finally { it.recycle() } } }
        }
        visit(root)
        result.take(100)
    }

    @Suppress("DEPRECATION")
    private fun reveal(value: String) {
        var direction = 1
        await("Setting not reachable: $value") {
            val visible = tree { root ->
                find(root) { it.isVisibleToUser && matches(it, value) }?.let { it.recycle(); true } == true
            } == true
            if (visible) return@await true
            // The preference can persist before its new text has been composed.
            // Keep looking until the accessibility tree reflects the settled row.
            runBlocking(Dispatchers.Main) {
                val position = scrollState.value
                val step = (scrollState.viewportSize / 2).coerceAtLeast(1)
                val next = (position + direction * step).coerceIn(0, scrollState.maxValue)
                if (next == position) direction = -direction else scrollState.scrollTo(next)
            }
            false
        }
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
    private fun <T> withSlider(maximum: Float, displayedValue: String, action: (AccessibilityNodeInfo) -> T): T? = tree { root ->
        val byRange = find(root) { it.isVisibleToUser && it.rangeInfo?.max == maximum }
        if (byRange != null) {
            try { return@tree action(byRange) } finally { byRange.recycle() }
        }
        // API22 reports this Compose SeekBar with a zero-width accessibility range.
        // Locate the actual widget beside its rendered value in that case.
        if (Build.VERSION.SDK_INT != 22) return@tree null
        var row = find(root) { it.isVisibleToUser && matches(it, displayedValue) } ?: return@tree null
        try {
            while (row.className != "android.widget.ScrollView") {
                val slider = find(row) {
                    it.isVisibleToUser && it.className == "android.widget.SeekBar" &&
                        it.rangeInfo?.let { range -> range.min == range.max } == true
                }
                if (slider != null) {
                    try { return@tree action(slider) } finally { slider.recycle() }
                }
                val parent = row.parent ?: break
                row.recycle()
                row = parent
            }
            null
        } finally { row.recycle() }
    }

    private fun sliderShows(maximum: Float, expected: Int, displayedValue: String): Boolean =
        withSlider(maximum, displayedValue) { node ->
            val range = node.rangeInfo
            range?.current == expected.toFloat() ||
                (Build.VERSION.SDK_INT == 22 && range != null && range.min == range.max)
        } == true

    private fun adjustSlider(maximum: Float, displayedValue: String, key: Int) {
        await("Slider was not focusable: max=$maximum") {
            withSlider(maximum, displayedValue) { it.isFocused || it.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } == true
        }
        await("Slider did not receive keyboard focus: max=$maximum") {
            withSlider(maximum, displayedValue) { it.isFocused } == true
        }
        instrumentation.sendKeyDownUpSync(key)
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

    @Suppress("DEPRECATION")
    private fun edit(value: String, input: String, expectedText: String = input) {
        reveal(value)
        var target: AccessibilityNodeInfo? = null
        fun focusedEditor() = target?.takeIf { it.refresh() && it.isEditable && it.isFocused }
        try {
            await("Editor did not receive focus: $value") {
                focusedEditor() != null || withEditor(value) { editor ->
                    target?.recycle()
                    target = AccessibilityNodeInfo.obtain(editor)
                    if (editor.isFocused) true else {
                        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        false
                    }
                }
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input)
            }
            // The keyboard can move the label out of the viewport. Keep the
            // editor identified by that label and check its current node state.
            await("Editor did not accept the text: $value") {
                focusedEditor()?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true
            }
            await("Editor did not show the new text: $value") {
                focusedEditor()?.let { it.text?.toString().orEmpty() == expectedText } == true
            }
        } finally {
            target?.recycle()
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

    @Suppress("DEPRECATION")
    private fun <T> withTvButton(
        value: String, action: (AccessibilityNodeInfo, AccessibilityNodeInfo) -> T,
    ): T? = tree { root ->
        var row = find(root) { it.isVisibleToUser && matches(it, value) } ?: return@tree null
        try {
            repeat(4) {
                val button = find(row) { it.isVisibleToUser && it.className == "android.widget.Button" }
                if (button != null) {
                    try { return@tree action(button, row) } finally { button.recycle() }
                }
                val parent = row.parent ?: return@tree null
                row.recycle()
                row = parent
            }
            null
        } finally { row.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun tvControlHasFocus(value: String): Boolean {
        withTvButton(value) { button, row -> button.isFocused || row.isFocused }?.let { return it }
        return tree { root ->
            val focused = find(root) { node ->
                node.isVisibleToUser && node.isFocused && !node.isEditable &&
                    (find(node) { matches(it, value) }?.let { it.recycle(); true } == true)
            }
            focused?.let { it.recycle(); true } ?: false
        } == true
    }

    private fun focusTvControl(value: String) {
        reveal(value)
        repeat(45) {
            if (tvControlHasFocus(value)) return
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            instrumentation.waitForIdleSync()
        }
        await("Remote could not focus $value") {
            tvControlHasFocus(value) ||
                withTvButton(value) { button, row ->
                    button.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                        row.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                } == true
        }
        await("Focus did not land on $value") { tvControlHasFocus(value) }
    }

    private fun pressTvControl(value: String) {
        focusTvControl(value)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun openTvEditor(value: String, focus: () -> Unit = { focusTvControl(value) }) {
        focus()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Remote OK did not open the editor for $value") { withEditor(value) { it.isFocused } }
    }

    private fun screen(tv: Boolean = false, wrapContext: (Context) -> Context = { it }, block: () -> Unit) {
        val baseContext = if (tv) isolated.createConfigurationContext(Configuration(isolated.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }) else isolated
        val uiContext = wrapContext(baseContext)
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
                target.setContent { CompositionLocalProvider(LocalContext provides uiContext) {
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
            val width = label(R.string.setting_resolution_width)
            val height = label(R.string.setting_resolution_height)
            for ((w, h) in listOf("" to "1080", "0" to "1080", "1920" to "", "1920" to "0")) {
                edit(width, w)
                edit(height, h)
                done()
                assertEquals("1600x900", Prefs.RESOLUTION.read(prefs))
            }
            edit(width, "A19?20", expectedText = "1920")
            edit(height, "1x200", expectedText = "1200")
            done()
            await("Custom size did not persist") { Prefs.RESOLUTION.read(prefs) == "1920x1200" }
            click("1920x1080")
            await("Preset did not replace custom size") { Prefs.RESOLUTION.read(prefs) == "1920x1080" }
        }
    }

    @Test fun audioDelayTouchSavesTheReleasedValueAndDisablingClearsIt() {
        assertTrue(Prefs.DEVELOPER_OPTIONS.write(prefs, true))
        assertTrue(Prefs.AUDIO_LATENCY_MS.write(prefs, 250))
        fun delayLabel(value: Int) = isolated.getString(R.string.audio_delay_value, value)
        screen {
            reveal(delayLabel(250))
            val slider = requireNotNull(withSlider(1000f, delayLabel(250)) { node ->
                Rect().apply { node.getBoundsInScreen(this) }
            })
            assertFalse("The delay slider has no visible touch bounds", slider.isEmpty)
            instrumentation.runOnMainSync {
                val decor = requireNotNull(activity).window.decorView
                val origin = IntArray(2).also(decor::getLocationOnScreen)
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(
                        down, SystemClock.uptimeMillis(), action,
                        slider.exactCenterX() - origin[0], slider.exactCenterY() - origin[1], 0,
                    )
                    try {
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        assertTrue("The settings window did not consume the touch", decor.dispatchTouchEvent(event))
                    } finally { event.recycle() }
                }
            }
            await("Releasing the slider did not save the midpoint") {
                Prefs.AUDIO_LATENCY_MS.read(prefs) == 500 && sliderShows(1000f, 500, delayLabel(500))
            }
            val delay = label(R.string.setting_audio_delay)
            click(delay)
            await("Disabling delay did not clear the saved value or remove the slider") {
                Prefs.AUDIO_LATENCY_MS.read(prefs) == -1 && checked(delay) == false &&
                    withSlider(1000f, delayLabel(500)) { true } != true
            }
            click(delay)
            reveal(delayLabel(250))
            await("Re-enabling delay did not restore its default value") {
                Prefs.AUDIO_LATENCY_MS.read(prefs) == 250 && sliderShows(1000f, 250, delayLabel(250))
            }
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

    @Test fun discoveryScopeSwitchesBetweenUsbAndAllNetworks() = screen {
        val all = label(R.string.chip_all_networks)
        val usb = label(R.string.chip_usb_only)
        val allSummary = label(R.string.setting_discovery_all_desc)
        val usbSummary = label(R.string.setting_discovery_usb_desc)
        reveal(allSummary)
        assertFalse(Prefs.USB_ONLY.read(prefs))

        click(usb)
        await("USB-only discovery did not persist") { Prefs.USB_ONLY.read(prefs) }
        reveal(usbSummary)

        click(all)
        await("All-network discovery did not persist") { !Prefs.USB_ONLY.read(prefs) }
        reveal(allSummary)
    }

    @Test fun tvPortEditorValidatesDraftsAndRestoresRemoteFocus() = screen(tv = true) {
        val port = label(R.string.setting_server_port)
        val save = label(R.string.tv_edit_save)
        val cancel = label(R.string.tv_edit_cancel)
        openTvEditor(port)
        edit(port, "x0?", expectedText = "0")
        focusTvControl(cancel)
        repeat(3) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            instrumentation.waitForIdleSync()
            assertFalse("Save became focusable for an invalid port", tvControlHasFocus(save))
        }
        pressTvControl(cancel)
        await("Cancel did not return focus to the port button") { tvControlHasFocus(port) }
        assertFalse(prefs.contains(Prefs.SERVER_PORT.key))

        openTvEditor(port)
        await("The canceled draft was kept") { withEditor(port) { it.text?.toString() == "7000" } }
        edit(port, "A70?01b", expectedText = "7001")
        pressTvControl(save)
        await("Valid port was not saved and refocused") {
            Prefs.SERVER_PORT.read(prefs) == 7001 && tvControlHasFocus(port)
        }
    }

    @Test fun tvCustomResolutionPersistsOnlyAfterBothDimensionsAreSaved() {
        assertTrue(Prefs.RESOLUTION.write(prefs, "1600x900"))
        screen(tv = true) {
            val width = label(R.string.setting_resolution_width)
            val height = label(R.string.setting_resolution_height)
            val save = label(R.string.tv_edit_save)
            fun saveDimension(field: String, input: String, normalized: String) {
                edit(field, input, expectedText = normalized)
                pressTvControl(save)
                await("Dimension editor stayed open: $field") { tvControlHasFocus(field) }
                assertEquals("1600x900", Prefs.RESOLUTION.read(prefs))
            }
            openTvEditor(width)
            saveDimension(width, "A12?80", "1280")
            openTvEditor(height) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Right did not focus the adjacent height field") { tvControlHasFocus(height) }
            }
            saveDimension(height, "7x20", "720")
            pressTvControl(save)
            await("Custom dimensions were not applied together") { Prefs.RESOLUTION.read(prefs) == "1280x720" }
        }
    }

    @Test fun resolutionPresetsSaveEachDeviceOrientation() = screen {
        for ((id, value) in listOf(
            R.string.chip_device_portrait to Prefs.PORTRAIT,
            R.string.chip_device_landscape to Prefs.LANDSCAPE
        )) {
            click(label(id))
            await("Device orientation preset did not persist: $value") { Prefs.RESOLUTION.read(prefs) == value }
        }
        click("1280x720")
        await("Fixed resolution did not replace the device orientation") { Prefs.RESOLUTION.read(prefs) == "1280x720" }
    }

    @Test fun customFrameRateRejectsBlankAndOverflowThenReturnsToPreset() {
        assertTrue(Prefs.MAX_FPS.write(prefs, 48))
        screen {
            val fps = label(R.string.setting_max_fps_placeholder)
            for (invalid in listOf("", "2147483648")) {
                edit(fps, invalid)
                done()
                assertEquals(48, Prefs.MAX_FPS.read(prefs))
            }
            edit(fps, "72")
            done()
            await("Custom frame rate did not persist") { Prefs.MAX_FPS.read(prefs) == 72 }
            click("60")
            await("Preset frame rate did not replace the custom value") { Prefs.MAX_FPS.read(prefs) == 60 }
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

    @Test fun launchOnConnectUsesTheOverlayPermissionOrApplicationSettings() {
        val preference = Prefs.LAUNCH_ON_CONNECT
        assertTrue(prefs.edit().putBoolean(preference.key, false).commit())
        val title = label(R.string.setting_launch_on_connect)
        val operations = CopyOnWriteArrayList<Intent>()
        var fallback = false
        val intercept: (Context) -> Context = { base ->
            object : ContextWrapper(base) {
                override fun startActivity(intent: Intent) {
                    operations += Intent(intent)
                    if (fallback && intent.action == Settings.ACTION_MANAGE_OVERLAY_PERMISSION) {
                        throw ActivityNotFoundException("Overlay settings are unavailable")
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            screen(wrapContext = intercept) {
                switch(R.string.setting_launch_on_connect, preference, true)
                switch(R.string.setting_launch_on_connect, preference, false)
                assertTrue(operations.isEmpty())
            }
            return
        }

        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val packageName = context.packageName
        val output = shell("appops get $packageName SYSTEM_ALERT_WINDOW")
        val original = Regex("SYSTEM_ALERT_WINDOW: ([a-z_]+)").find(output)?.groupValues?.get(1) ?: "default"
        try {
            shell("appops set $packageName SYSTEM_ALERT_WINDOW ignore")
            assertFalse(Settings.canDrawOverlays(context))
            screen(wrapContext = intercept) {
                reveal(title)
                assertEquals(false, checked(title))
                click(title)
                val prompt = label(R.string.setting_launch_on_connect_no_permission)
                await("Overlay permission was not requested") {
                    preference.read(prefs) && operations.size == 1 &&
                        texts()?.any { prompt in it } == true && checked(title) == false
                }
                fallback = true
                click(title)
                await("Could not open application settings after missing overlay activity") { operations.size == 3 }
                assertEquals(listOf(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
                    operations.map { it.action })
                assertTrue(operations.all {
                    it.dataString == "package:$packageName" && it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
                })
            }

            shell("appops set $packageName SYSTEM_ALERT_WINDOW allow")
            assertTrue(Settings.canDrawOverlays(context))
            screen(wrapContext = intercept) {
                reveal(title)
                await("Granted permission did not enable the saved setting") { checked(title) == true }
                switch(R.string.setting_launch_on_connect, preference, false)
                assertEquals(3, operations.size)
            }
        } finally {
            shell("appops set $packageName SYSTEM_ALERT_WINDOW $original")
        }
    }

    @Test fun audioModeChangesExposeTuningAndRemoteAdjustmentsRespectSliderBounds() {
        assertTrue(Prefs.DEVELOPER_OPTIONS.write(prefs, true))
        assertTrue(Prefs.AUDIO_AUTO_BUFFER.write(prefs, false))
        assertTrue(Prefs.AUDIO_ADAPTIVE_STEP.write(prefs, 0))
        fun delayLabel(value: Int) = isolated.getString(R.string.audio_delay_value, value)
        fun adaptiveLabel(step: Int) = isolated.getString(R.string.audio_adaptive_value, Prefs.ADAPTIVE_PERCENTILES[step],
            isolated.resources.getStringArray(R.array.audio_adaptive_step_names)[step])
        screen {
            click(label(R.string.setting_audio_delay))
            reveal(delayLabel(250))
            await("Enabled audio delay did not render") { Prefs.AUDIO_LATENCY_MS.read(prefs) == 250 && sliderShows(1000f, 250, delayLabel(250)) }
            adjustSlider(1000f, delayLabel(250), KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Remote delay increase failed") { Prefs.AUDIO_LATENCY_MS.read(prefs) == 300 && sliderShows(1000f, 300, delayLabel(300)) }
            adjustSlider(1000f, delayLabel(300), KeyEvent.KEYCODE_DPAD_LEFT)
            await("Remote delay decrease failed") { Prefs.AUDIO_LATENCY_MS.read(prefs) == 250 && sliderShows(1000f, 250, delayLabel(250)) }
            switch(R.string.setting_audio_auto_buffer, Prefs.AUDIO_AUTO_BUFFER, true)
            reveal(adaptiveLabel(0))
            await("Adaptive slider did not render") { sliderShows(4f, 0, adaptiveLabel(0)) }
            adjustSlider(4f, adaptiveLabel(0), KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals(0, Prefs.AUDIO_ADAPTIVE_STEP.read(prefs))
            for (step in 1..4) {
                adjustSlider(4f, adaptiveLabel(step - 1), KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Adaptive step $step not applied") { Prefs.AUDIO_ADAPTIVE_STEP.read(prefs) == step && sliderShows(4f, step, adaptiveLabel(step)) }
            }
            adjustSlider(4f, adaptiveLabel(4), KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals(4, Prefs.AUDIO_ADAPTIVE_STEP.read(prefs))
            adjustSlider(4f, adaptiveLabel(4), KeyEvent.KEYCODE_DPAD_LEFT)
            await("Adaptive decrease failed") { Prefs.AUDIO_ADAPTIVE_STEP.read(prefs) == 3 && sliderShows(4f, 3, adaptiveLabel(3)) }
            switch(R.string.setting_audio_auto_buffer, Prefs.AUDIO_AUTO_BUFFER, false)
            reveal(label(R.string.setting_audio_cushion_ms))
            assertEquals(40, Prefs.AUDIO_CUSHION_MS.read(prefs))
        }
    }
}
