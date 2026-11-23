package io.github.jqssun.airplay

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.buzincast.core.AsyncLogStore
import io.github.jqssun.airplay.ui.LogsScreen
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real Compose accessibility actions and FileProvider; never launches a share target. */
@RunWith(AndroidJUnit4::class)
class LogsScreenContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var root: File
    private lateinit var app: RecordingApplication
    private lateinit var model: MainViewModel
    private lateinit var store: ViewModelStore
    private var workerClosed = false
    private val preferences get() = context.getSharedPreferences("logs-screen-contract", Context.MODE_PRIVATE)
    private val logs get() = MainViewModel::class.java.getDeclaredField("logStore")
        .apply { isAccessible = true }.get(model) as AsyncLogStore
    private class RecordingApplication(base: Context) : Application() {
        init { attachBaseContext(base) }
        val choosers = CopyOnWriteArrayList<Intent>()
        val attempts = AtomicInteger()
        @Volatile var failLaunch = false
        override fun startActivity(intent: Intent) {
            attempts.incrementAndGet()
            if (failLaunch) throw ActivityNotFoundException("Intentional chooser failure")
            choosers += Intent(intent)
        }
    }
    @Before fun setup() {
        // Resolve provider roots from the real app context before introducing
        // isolated cache directories; FileProvider caches this metadata per process.
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(context.cacheDir, "logs-contract-root-check"))
        root = File(context.cacheDir, "logs-screen-${System.nanoTime()}").apply { mkdirs() }
        val files = File(root, "files").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        preferences.edit().clear().commit()
        app = RecordingApplication(object : ContextWrapper(context) {
            override fun getFilesDir() = files
            override fun getCacheDir() = cache
            override fun getSharedPreferences(name: String, mode: Int) = preferences
        })
        instrumentation.runOnMainSync {
            model = MainViewModel(app)
            store = ViewModelStore().apply { put("logs-screen", model) }
        }
        assertTrue(logs.flush())
    }
    @After fun cleanup() {
        if (!workerClosed) assertTrue(logs.flush())
        val worker = logs
        instrumentation.runOnMainSync { store.clear() }
        assertTrue("Log worker leaked", worker.awaitClosed())
        preferences.edit().clear().commit()
        assertTrue(root.deleteRecursively())
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(30)
        }
        fail(message)
    }
    private data class Node(val text: String, val description: String, val visible: Boolean, val clickable: Boolean)
    @Suppress("DEPRECATION")
    private fun snapshot(): List<Node>? {
        val tree = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        try {
            if (tree.packageName?.toString() != context.packageName || tree.childCount == 0) return null
            val result = mutableListOf<Node>()
            fun visit(node: AccessibilityNodeInfo) {
                result += Node(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), node.isVisibleToUser, node.isClickable)
                repeat(node.childCount) { index ->
                    node.getChild(index)?.let { child -> try { visit(child) } finally { child.recycle() } }
                }
            }
            visit(tree)
            return result.takeIf { nodes -> nodes.any { it.text == context.getString(R.string.tab_logs) } }
        } finally { tree.recycle() }
    }
    @Suppress("DEPRECATION")
    private fun click(description: String) {
        var accepted = false
        try { await("Missing actionable log control: $description") {
            val tree = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
            try {
                if (tree.packageName?.toString() != context.packageName) return@await false
                fun visit(node: AccessibilityNodeInfo): Boolean {
                    if (node.isVisibleToUser && node.contentDescription?.toString() == description) {
                        // Compose may expose an icon label below its clickable
                        // semantics node. Activate its nearest clickable ancestor.
                        var control: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
                        try {
                            while (control != null && control.packageName?.toString() == context.packageName) {
                                if (control.isClickable) return control.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                val parent = control.parent
                                control.recycle(); control = parent
                            }
                        } finally { control?.recycle() }
                    }
                    repeat(node.childCount) { index ->
                        node.getChild(index)?.let { child ->
                            try { if (visit(child)) return true } finally { child.recycle() }
                        }
                    }
                    return false
                }
                accepted = visit(tree)
                accepted
            } finally { tree.recycle() }
        } } catch (error: AssertionError) {
            val controls = snapshot()?.filter { it.description.isNotBlank() || it.clickable }
            throw AssertionError("${error.message}; exposed controls: $controls", error)
        }
        assertTrue(accepted)
    }
    private fun screen(block: () -> Unit) {
        ActivityScenario.launch<DebugTvPreviewActivity>(Intent(context, DebugTvPreviewActivity::class.java).putExtra("screen", "contract_host")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { AirPlayTheme { LogsScreen(model) } } }
            await("Log screen did not compose") { snapshot() != null }
            block()
        }
    }
    private fun visible(token: String): Boolean = snapshot()?.any { it.visible && token in it.text } == true
    private fun append(message: String) { model.addLog(message); assertTrue(logs.flush()) }
    @Test fun emptyScreenAppendsScrollsToTheLatestEntryAndClearsThroughItsButton() = screen {
        assertTrue(model.logs.value.isEmpty())
        append("screen-contract-first")
        await("First log missing") { visible("screen-contract-first") }
        repeat(100) { model.addLog("screen-contract-row-$it") }
        assertTrue(logs.flush())
        await("Log list did not scroll to its last row and hide its first row") {
            val nodes = snapshot() ?: return@await false
            nodes.any { it.visible && "screen-contract-row-99" in it.text } &&
                nodes.none { it.visible && "screen-contract-first" in it.text }
        }
        click(context.getString(R.string.btn_clear))
        await("Clear button did not clear the model and visible rows") {
            val nodes = snapshot() ?: return@await false
            model.logs.value.isEmpty() && nodes.none { "screen-contract-" in it.text }
        }
        assertTrue(logs.flush())
        assertTrue(logs.snapshot().isEmpty())
        append("screen-contract-after-clear")
        await("Log list failed to recover after clearing") { visible("screen-contract-after-clear") }
        assertEquals(1, model.logs.value.size)
    }
    @Suppress("DEPRECATION")
    private fun sharedText(index: Int): String {
        val chooser = app.choosers[index]
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        return requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    @Test fun exportButtonSharesExactlyTheEmptyAndUnicodeLogSnapshots() = screen {
        click(context.getString(R.string.btn_export))
        await("Empty log chooser missing") { app.choosers.size == 1 }
        assertEquals("", sharedText(0))
        append("ação 日本語 åäö")
        append("second exported line")
        val expected = model.logs.value.joinToString("\n")
        click(context.getString(R.string.btn_export))
        await("Populated log chooser missing") { app.choosers.size == 2 }
        assertEquals(expected, sharedText(1))
        assertEquals(2, app.attempts.get())
    }
    @Test fun failedChooserLaunchIsContainedAndTheNextExportStillWorks() = screen {
        append("chooser recovery")
        app.failLaunch = true
        click(context.getString(R.string.btn_export))
        await("Failing chooser was never attempted") { app.attempts.get() == 1 }
        instrumentation.waitForIdleSync()
        assertTrue(app.choosers.isEmpty())
        app.failLaunch = false
        click(context.getString(R.string.btn_export))
        await("Export did not recover after a failed chooser") { app.choosers.size == 1 }
        assertEquals(model.logs.value.joinToString("\n"), sharedText(0))
        assertEquals(2, app.attempts.get())
    }

    @Test fun exportWithAClosedLogWorkerDoesNotShareAStaleSnapshot() {
        append("not available for export")
        logs.close()
        workerClosed = true
        assertTrue(logs.awaitClosed())

        lateinit var export: Job
        instrumentation.runOnMainSync {
            val owner = requireNotNull(model.viewModelScope.coroutineContext[Job])
            val existing = owner.children.toSet()
            model.exportLogs()
            export = owner.children.single { it !in existing }
        }
        runBlocking { withTimeout(10_000) { export.join() } }
        assertFalse(export.isCancelled)
        assertEquals(0, app.attempts.get())
        assertTrue(app.choosers.isEmpty())
        assertFalse(File(app.cacheDir, "airplay_logs.txt").exists())
        assertTrue(model.logs.value.single().endsWith("not available for export"))
    }
}
