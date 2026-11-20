package io.github.jqssun.airplay

import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.buzincast.core.AsyncLogStore
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.viewmodel.MainViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Android-driven lifecycle transitions with the real bound receiver and ViewModel. */
@RunWith(AndroidJUnit4::class)
class MainActivityLifecycleContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences(Prefs.NAME, 0)
    private lateinit var service: AirPlayService
    private lateinit var connection: ServiceConnection
    private var bound = false
    private val activityToken = System.nanoTime().toString()
    private val activityMonitor get() = ActivityLifecycleMonitorRegistry.getInstance()
    private var cover: DebugTvPreviewActivity? = null
    private val original = linkedMapOf<String, Boolean?>()
    private var videoFixture: File? = null
    private var previousPipMode: String? = null
    private var previousPipEffectiveMode: Int? = null
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            Thread.sleep(25)
        }
        fail(message)
    }
    private fun set(pref: Pref<Boolean>, value: Boolean) {
        assertTrue(preferences.edit().putBoolean(pref.key, value).commit())
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @androidx.annotation.ChecksSdkIntAtLeast(api = 26)
    private fun supportsPip() = Build.VERSION.SDK_INT >= 26 &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    @androidx.annotation.RequiresApi(26)
    @Suppress("DEPRECATION")
    private fun effectivePipMode(): Int = (context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
        .checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.packageName)
    private fun pipPolicy(mode: String) {
        if (!supportsPip()) return
        val before = shell("appops get ${context.packageName} PICTURE_IN_PICTURE")
        previousPipEffectiveMode = effectivePipMode()
        previousPipMode = Regex("PICTURE_IN_PICTURE: ([a-z_]+)").find(before)?.groupValues?.get(1)
            ?: when (previousPipEffectiveMode) {
                AppOpsManager.MODE_ALLOWED -> "allow"
                AppOpsManager.MODE_IGNORED -> "ignore"
                AppOpsManager.MODE_ERRORED -> "deny"
                AppOpsManager.MODE_DEFAULT -> "default"
                AppOpsManager.MODE_FOREGROUND -> "foreground"
                else -> error("Unexpected PiP policy $previousPipEffectiveMode")
            }
        assertTrue(previousPipMode in setOf("allow", "ignore", "deny", "default", "foreground"))
        shell("appops set ${context.packageName} PICTURE_IN_PICTURE $mode")
        val expected = when (mode) {
            "allow" -> AppOpsManager.MODE_ALLOWED
            "ignore" -> AppOpsManager.MODE_IGNORED
            else -> error("Unsupported test PiP policy $mode")
        }
        // AppOps may omit an entry when its mode equals the platform default.
        // Check the actual permission decision rather than CLI formatting.
        assertEquals("Requested PiP policy did not take effect", expected, effectivePipMode())
    }

    @Before fun bindReceiverWithoutStartingIt() {
        for ((pref, value) in listOf(Prefs.AUTO_START to false, Prefs.RUN_IN_BACKGROUND to false,
                Prefs.LAUNCH_ON_CONNECT to false, Prefs.KEEP_SCREEN_ON to true)) {
            original[pref.key] = if (preferences.contains(pref.key)) pref.read(preferences) else null
            set(pref, value)
        }
        val ready = CountDownLatch(1)
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = requireNotNull((binder as? AirPlayService.LocalBinder)?.service)
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        bound = context.bindService(Intent(context, AirPlayService::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue(bound)
        assertTrue("Receiver did not bind", ready.await(15, TimeUnit.SECONDS))
        main { assertEquals(AirPlayService.ServerState.STOPPED, service.serverState.value) }
    }

    private fun launch(): MainActivity {
        var activity: MainActivity? = null
        main {
            context.startActivity(Intent(context, MainActivity::class.java)
                .putExtra("lifecycle_contract_owner", activityToken)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        await("MainActivity did not install its service callbacks") {
            activity = resumedActivity<MainActivity>()
            activity != null && service.logCallback != null && service.pinCallback != null
        }
        return requireNotNull(activity)
    }

    // The receiver can continue rendering while the lifecycle advances. Observe
    // the instrumented Android stages instead of waiting for global UI idleness.
    private inline fun <reified T : Activity> resumedActivity(): T? =
        activityMonitor.getActivitiesInStage(Stage.RESUMED).filterIsInstance<T>()
            .singleOrNull { it.intent?.getStringExtra("lifecycle_contract_owner") == activityToken }

    private fun model(activity: MainActivity) = ViewModelProvider(activity)[MainViewModel::class.java]
    private fun start(activity: MainActivity) {
        main { service.startServer() }
        await("Receiver did not start: ${service.serverState.value}") {
            service.serverState.value == AirPlayService.ServerState.RUNNING
        }
        await("Activity did not observe the running receiver") {
            model(activity).serverState.value == AirPlayService.ServerState.RUNNING
        }
    }

    private fun stopped(activity: MainActivity, expected: AirPlayService.ServerState) {
        check(cover == null)
        main {
            activity.startActivity(Intent(activity, DebugTvPreviewActivity::class.java)
                .putExtra("screen", "contract_host")
                .putExtra("lifecycle_contract_owner", activityToken))
        }
        await("The foreground activity did not stop MainActivity") {
            cover = resumedActivity<DebugTvPreviewActivity>()
            cover != null && activityMonitor.getLifecycleStageOf(activity) == Stage.STOPPED
        }
        main {
            assertEquals(Lifecycle.State.CREATED, activity.lifecycle.currentState)
            assertEquals(expected, service.serverState.value)
        }
    }

    private fun resume(activity: MainActivity) {
        val foreground = requireNotNull(cover)
        main { foreground.finish() }
        await("MainActivity did not resume when the foreground activity finished") {
            foreground.isDestroyed && activityMonitor.getLifecycleStageOf(activity) == Stage.RESUMED
        }
        cover = null
    }

    private fun finishActivities() {
        // Also find a replacement activity if recreation failed before the test
        // could save its reference.
        var open = emptyList<Activity>()
        main {
            open = Stage.entries.filter { it != Stage.DESTROYED }
                .flatMap(activityMonitor::getActivitiesInStage)
                .filter { it.intent?.getStringExtra("lifecycle_contract_owner") == activityToken }
            open.forEach(Activity::finish)
        }
        await("The lifecycle test's activities were not destroyed") { open.all { it.isDestroyed } }
        cover = null
    }

    private fun keepsScreenOn(activity: MainActivity, expected: Boolean) {
        await("Keep-screen-on flag did not become $expected") {
            val set = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            set == expected
        }
    }

    @Test fun receiverReadvertisesDisplayOnlyForChangedAutomaticOrientation() {
        val resolution = Prefs.RESOLUTION
        val saved = preferences.getString(resolution.key, null)
        val activity = launch()
        val advertisements = CopyOnWriteArrayList<String>()
        fun reportOrientation(changed: Boolean) {
            val config = Configuration(service.resources.configuration)
            if (changed) {
                config.orientation = if (config.orientation == Configuration.ORIENTATION_PORTRAIT) {
                    Configuration.ORIENTATION_LANDSCAPE
                } else Configuration.ORIENTATION_PORTRAIT
            }
            service.onConfigurationChanged(config)
        }

        try {
            assertTrue(resolution.write(preferences, Prefs.AUTO))
            start(activity)
            main {
                val previousLog = service.logCallback
                service.logCallback = { message ->
                    if (message.startsWith("Advertising ")) advertisements += message
                    previousLog?.invoke(message)
                }
                try {
                    val advertisedSize = service.videoResolution.value
                    reportOrientation(changed = false)
                    assertTrue("A repeated configuration must keep its advertisement", advertisements.isEmpty())
                    reportOrientation(changed = true)
                    reportOrientation(changed = true)
                    assertEquals(listOf("Advertising $advertisedSize from next session"), advertisements)

                    assertTrue(resolution.write(preferences, Prefs.LANDSCAPE))
                    reportOrientation(changed = false)
                    assertEquals("A fixed resolution must keep its advertisement", 1, advertisements.size)

                    service.stopServer()
                    assertTrue(resolution.write(preferences, Prefs.AUTO))
                    reportOrientation(changed = true)
                    assertEquals("A stopped receiver cannot advertise a new resolution", 1, advertisements.size)
                } finally {
                    service.logCallback = previousLog
                }
            }
        } finally {
            main { service.stopServer() }
            val restore = preferences.edit()
            if (saved == null) restore.remove(resolution.key) else restore.putString(resolution.key, saved)
            assertTrue(restore.commit())
        }
    }

    @After fun cleanup() {
        try {
            finishActivities()
            if (::service.isInitialized) main {
                service.logCallback = null; service.pinCallback = null
                service.stopServer()
            }
        } finally {
            if (bound) {
                context.unbindService(connection)
                context.stopService(Intent(context, AirPlayService::class.java))
                bound = false
                await("Receiver service did not reach DESTROYED") {
                    service.lifecycle.currentState == Lifecycle.State.DESTROYED
                }
            }
            videoFixture?.let { if (it.exists()) assertTrue(it.delete()) }
            val restore = preferences.edit()
            original.forEach { (key, value) -> if (value == null) restore.remove(key) else restore.putBoolean(key, value) }
            assertTrue(restore.commit())
            if (Build.VERSION.SDK_INT >= 26) previousPipMode?.let {
                shell("appops set ${context.packageName} PICTURE_IN_PICTURE $it")
                assertEquals("PiP policy was not restored", previousPipEffectiveMode, effectivePipMode())
                android.util.Log.i("LifecycleContract", "PiP policy restored: $it, effective=$previousPipEffectiveMode")
            }
            if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
        }
    }

    @Test fun configurationRecreationKeepsTheIdleReceiverAndReplacesOnlyItsOwnCallbacks() {
        val activity = launch()
        start(activity)
        lateinit var retained: MainViewModel
        main { retained = model(activity) }
        val previousPin = service.pinCallback
        main { service.onDisplayPin("2468") }
        assertEquals("2468", retained.pinCode.value)
        main { activity.recreate() }
        await("Android did not replace the recreated activity") {
            activity.isDestroyed && resumedActivity<MainActivity>() != null
        }
        main { assertSame(retained, model(requireNotNull(resumedActivity<MainActivity>()))) }
        await("Recreated activity did not replace the old callback") { service.pinCallback !== previousPin && service.pinCallback != null }
        main { assertEquals(AirPlayService.ServerState.RUNNING, service.serverState.value) }
        val logs = MainViewModel::class.java.getDeclaredField("logStore").apply { isAccessible = true }
            .get(retained) as AsyncLogStore
        finishActivities()
        main {
            assertEquals(AirPlayService.ServerState.STOPPED, service.serverState.value)
            assertNull(service.logCallback); assertNull(service.pinCallback)
        }
        assertTrue("Final activity destruction kept the ViewModel log worker alive", logs.awaitClosed())
    }

    @Test fun destructionPreservesCallbacksInstalledByAnotherOwner() {
        launch()
        val logs = mutableListOf<String>()
        val pins = mutableListOf<String?>()
        val replacementLog: (String) -> Unit = { logs += it }
        val replacementPin: (String?) -> Unit = { pins += it }
        main { service.logCallback = replacementLog; service.pinCallback = replacementPin }
        finishActivities()
        main {
            assertSame(replacementLog, service.logCallback)
            assertSame(replacementPin, service.pinCallback)
            service.logCallback?.invoke("replacement owner")
            service.onDisplayPin("1357")
        }
        assertTrue(logs.contains("replacement owner"))
        assertEquals("1357", pins.last())
    }

    @Test fun anIdleForegroundReceiverStopsAndDoesNotAutoRestartWhenTheActivityResumes() {
        val activity = launch()
        start(activity)
        keepsScreenOn(activity, false)
        stopped(activity, AirPlayService.ServerState.STOPPED)
        resume(activity)
        main {
            assertEquals(AirPlayService.ServerState.STOPPED, service.serverState.value)
            assertFalse(model(activity).pref(Prefs.AUTO_START).value)
        }
        finishActivities()
    }

    @Test fun backgroundPreferenceKeepsTheRunningReceiverAcrossStopAndResume() {
        set(Prefs.RUN_IN_BACKGROUND, true)
        val activity = launch()
        start(activity)
        stopped(activity, AirPlayService.ServerState.RUNNING)
        resume(activity)
        main { assertEquals(AirPlayService.ServerState.RUNNING, service.serverState.value) }
        finishActivities()
        main { assertEquals(AirPlayService.ServerState.RUNNING, service.serverState.value) }
    }

    @Test fun activeSendersLocalVideoAndMirroringPreventIdleShutdownAndControlTheWakeFlag() {
        // Android otherwise keeps this active session visible in PiP instead of
        // reaching onStop. Exercise the real user-denied-PiP state for this case.
        pipPolicy("ignore")
        val activity = launch()
        start(activity)
        main { service.onConnectionInit() }
        keepsScreenOn(activity, true)
        stopped(activity, AirPlayService.ServerState.RUNNING)
        resume(activity)
        main { service.onConnectionDestroy() }
        keepsScreenOn(activity, false)

        val file = File(context.cacheDir, "lifecycle-video-${System.nanoTime()}.mp4")
        assertFalse(file.exists()); videoFixture = file
        instrumentation.context.assets.open("download/sample.mp4").use { input -> file.outputStream().use(input::copyTo) }
        main { service.playLocalVideo(android.net.Uri.fromFile(file).toString()); service.setVideoPlaying(false) }
        keepsScreenOn(activity, true)
        stopped(activity, AirPlayService.ServerState.RUNNING)
        resume(activity)
        main { service.stopVideoPlayback() }
        await("Local video did not stop") { !service.videoPlaybackActive.value }
        keepsScreenOn(activity, false)

        main { service.onVideoSize(64f, 48f, 64f, 48f) }
        keepsScreenOn(activity, true)
        set(Prefs.KEEP_SCREEN_ON, false)
        keepsScreenOn(activity, false)
        set(Prefs.KEEP_SCREEN_ON, true)
        keepsScreenOn(activity, true)
        stopped(activity, AirPlayService.ServerState.RUNNING)
        resume(activity)
        main { service.onMirrorRunning(false) }
        keepsScreenOn(activity, false)
        stopped(activity, AirPlayService.ServerState.STOPPED)
        finishActivities()
    }

    @Test fun pictureInPictureRoundTripKeepsTheSessionAndReturnsToTheSameActivity() {
        pipPolicy("allow")
        val activity = launch()
        start(activity)
        if (!supportsPip()) {
            main { activity.enterPip(); assertFalse(activity.isInPip.value) }
            finishActivities()
            return
        }
        main { service.onConnectionInit() }
        keepsScreenOn(activity, true)
        main { activity.enterPip() }
        await("Android did not enter PiP") { activity.isInPip.value && activity.isInPictureInPictureMode }
        main { assertEquals(AirPlayService.ServerState.RUNNING, service.serverState.value) }
        // A lifecycle resume request alone does not expand Android's pinned
        // task. Reopen the existing activity, as a launcher action does.
        main { context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        await("PiP did not return to the same resumed activity") {
            !activity.isInPip.value && !activity.isInPictureInPictureMode && resumedActivity<MainActivity>() === activity
        }
        main { assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState) }
        main { service.onConnectionDestroy() }
        keepsScreenOn(activity, false)
        finishActivities()
        main { assertEquals(AirPlayService.ServerState.STOPPED, service.serverState.value) }
    }

    @Test fun audioSessionExposesReceiverVolumeButKeepsUnavailableSenderVolumeDisabled() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val originalVolume = audio.getStreamVolume(stream)
        val maximum = audio.getStreamMaxVolume(stream)
        assertTrue("Expected adjustable emulator media volume", maximum > 1 && !audio.isVolumeFixed)
        val initial = maximum / 2
        val activity = launch()
        start(activity)
        try {
            main {
                audio.setStreamVolume(stream, initial, 0)
                service.onConnectionInit()
                service.onAudioFormat(2, 352, false)
                service.onProgress(0L, 44100L, 44100L * 90)
            }
            awaitVolumeControl(R.string.cd_rewind) { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds.isEmpty) return@awaitVolumeControl false
                // Swipe beside the playback buttons to reveal both sliders.
                val x = (bounds.left - bounds.width() * 3).coerceAtLeast(1).toFloat()
                pointerGesture(x, bounds.exactCenterY(), (bounds.exactCenterY() - bounds.height() * 5).coerceAtLeast(1f))
                true
            }
            lateinit var receiverBounds: Rect
            awaitVolumeControl(R.string.volume_server) { node ->
                assertTrue(node.isEnabled)
                receiverBounds = Rect().also(node::getBoundsInScreen)
                !receiverBounds.isEmpty
            }
            val touchX = receiverBounds.left + receiverBounds.width() * 0.85f
            val touchY = receiverBounds.exactCenterY()
            pointerGesture(touchX, touchY, touchY)
            await("Touch on receiver volume did not raise the media stream") { audio.getStreamVolume(stream) > initial + 1 }
            val raised = audio.getStreamVolume(stream)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left on receiver volume did not lower the media stream one step") { audio.getStreamVolume(stream) == raised - 1 }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right on receiver volume did not restore the media stream one step") { audio.getStreamVolume(stream) == raised }
            pointerGesture(receiverBounds.left.toFloat() - 24, touchY, touchY - receiverBounds.height() * 2f)
            main { assertFalse(model(activity).clientVolumeAdjustable.value) }
            awaitVolumeControl(R.string.volume_client) { node ->
                if (Build.VERSION.SDK_INT >= 24) {
                    assertFalse(node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id })
                }
                true
            }
        } finally {
            main {
                if (service.connectionCount.value > 0) service.onConnectionDestroy()
                audio.setStreamVolume(stream, originalVolume, 0)
            }
            finishActivities()
        }
    }

    private fun pointerGesture(x: Float, startY: Float, endY: Float) {
        val time = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, startY)
        if (startY != endY) {
            for (step in 1..5) {
                Thread.sleep(16)
                send(MotionEvent.ACTION_MOVE, startY + (endY - startY) * step / 5)
            }
        }
        send(MotionEvent.ACTION_UP, endY)
    }

    private fun awaitVolumeControl(label: Int, action: (AccessibilityNodeInfo) -> Boolean) {
        val description = context.getString(label)
        val automation = instrumentation.uiAutomation
        val visibleControls = linkedSetOf<String>()
        fun visit(node: AccessibilityNodeInfo): Boolean {
            val nodeDescription = node.contentDescription?.toString()
            if (!nodeDescription.isNullOrBlank()) {
                val bounds = Rect().also(node::getBoundsInScreen)
                visibleControls += "$nodeDescription bounds=$bounds enabled=${node.isEnabled} " +
                    "focusable=${node.isFocusable} focused=${node.isFocused} actions=${node.actionList.map { it.id }}"
            }
            if (nodeDescription == description && action(node)) return true
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { if (visit(child)) return true } finally { child.recycle() }
            }
            return false
        }
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val tree = automation.rootInActiveWindow
            if (tree != null) {
                try { if (tree.refresh() && visit(tree)) return } finally { tree.recycle() }
            }
            Thread.sleep(50)
        }
        automation.takeScreenshot()?.let { image ->
            try {
                File(context.filesDir, "main-contract-failure.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { image.recycle() }
        }
        fail("Audio volume control did not expose its expected state: $description. Visible controls: $visibleControls")
    }
}
