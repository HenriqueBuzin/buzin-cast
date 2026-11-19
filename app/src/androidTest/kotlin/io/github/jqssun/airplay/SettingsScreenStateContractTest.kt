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
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.setContent
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
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != context.packageName || root.childCount == 0) return null
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
        for (attempt in 0 until 20) {
            val visible = tree { find(it) { node -> node.isVisibleToUser && matches(node, value) }?.let { node ->
                node.recycle(); true
            } ?: false } == true
            if (visible) return
            val moved = tree { find(it) { node -> node.isScrollable }?.let { node ->
                try { node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } finally { node.recycle() }
            } ?: false } == true
            if (!moved) break
            Thread.sleep(120)
        }
        fail("Setting not reachable: $value; nodes=${texts()}")
    }

    @Suppress("DEPRECATION")
    private fun edit(value: String, input: String) {
        reveal(value)
        await("No editable setting: $value") {
            tree { root ->
                var node = find(root) { it.isVisibleToUser && matches(it, value) } ?: return@tree false
                try {
                    repeat(5) {
                        val editor = find(node) { it.isVisibleToUser && it.isEditable }
                        if (editor != null) {
                            try {
                                if (!editor.isFocused && !editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return@tree false
                                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input) }
                                return@tree editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            } finally { editor.recycle() }
                        }
                        val parent = node.parent ?: return@tree false
                        node.recycle(); node = parent
                    }
                    false
                } finally { node.recycle() }
            } == true
        }
        instrumentation.waitForIdleSync()
    }

    private fun done() {
        var delivered = false
        instrumentation.runOnMainSync {
            fun visit(view: View): Boolean {
                if (view.onCheckIsTextEditor()) {
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
                target.setContent { CompositionLocalProvider(LocalContext provides isolated) {
                    AirPlayTheme { SettingsScreen(model) }
                } }
            }
            await("Settings screen not rendered") { texts()?.any { it.contains(label(R.string.setting_server_name)) } == true }
            try { block() } finally { activity = null }
        }
    }

    @Test fun numericPortRejectsInvalidValuesThenNormalizesAndCommitsValidDigits() = screen {
        val port = label(R.string.setting_server_port)
        for (invalid in listOf("", "0", "9999999")) {
            edit(port, invalid)
            done()
            assertEquals(7000, Prefs.SERVER_PORT.read(prefs))
            assertFalse("Invalid port was persisted", prefs.contains(Prefs.SERVER_PORT.key))
        }
        edit(port, "A70?01b")
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
}
