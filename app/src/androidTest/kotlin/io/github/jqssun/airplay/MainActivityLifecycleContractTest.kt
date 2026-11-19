package io.github.jqssun.airplay

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    private var scenario: ActivityScenario<MainActivity>? = null
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

    private fun launch(): ActivityScenario<MainActivity> {
        val activity = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        scenario = activity
        await("MainActivity did not install its service callbacks") {
            service.logCallback != null && service.pinCallback != null
        }
        return activity
    }
    private fun model(activity: MainActivity) = ViewModelProvider(activity)[MainViewModel::class.java]
    private fun start(activity: ActivityScenario<MainActivity>) {
        main { service.startServer() }
        await("Receiver did not start: ${service.serverState.value}") {
            service.serverState.value == AirPlayService.ServerState.RUNNING
        }
        var viewModel: MainViewModel? = null
        activity.onActivity { viewModel = model(it) }
        await("Activity did not observe the running receiver") {
            viewModel!!.serverState.value == AirPlayService.ServerState.RUNNING
        }
    }
    private fun stopped(activity: ActivityScenario<MainActivity>, expected: AirPlayService.ServerState) {
        activity.moveToState(Lifecycle.State.CREATED)
        assertEquals(Lifecycle.State.CREATED, activity.state)
        main { assertEquals(expected, service.serverState.value) }
    }
    private fun close(activity: ActivityScenario<MainActivity>) {
        activity.close()
        scenario = null
    }
    private fun keepsScreenOn(activity: ActivityScenario<MainActivity>, expected: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            var actual = !expected
            activity.onActivity { actual = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
            if (actual == expected) return
            Thread.sleep(25)
        }
        fail("Keep-screen-on flag did not become $expected")
    }

    @After fun cleanup() {
        try {
            scenario?.close(); scenario = null
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
        lateinit var before: MainActivity
        lateinit var retained: MainViewModel
        activity.onActivity { before = it; retained = model(it) }
        val previousPin = service.pinCallback
        main { service.onDisplayPin("2468") }
        assertEquals("2468", retained.pinCode.value)
        activity.recreate()
        activity.onActivity {
            assertNotSame(before, it)
            assertSame(retained, model(it))
        }
        await("Recreated activity did not replace the old callback") { service.pinCallback !== previousPin && service.pinCallback != null }
        main { assertEquals(AirPlayService.ServerState.RUNNING, service.serverState.value) }
        val logs = MainViewModel::class.java.getDeclaredField("logStore").apply { isAccessible = true }
            .get(retained) as AsyncLogStore
        close(activity)
        main {
            assertEquals(AirPlayService.ServerState.STOPPED, service.serverState.value)
            assertNull(service.logCallback); assertNull(service.pinCallback)
        }
        assertTrue("Final activity destruction kept the ViewModel log worker alive", logs.awaitClosed())
    }

    @Test fun destructionPreservesCallbacksInstalledByAnotherOwner() {
        val activity = launch()
        val logs = mutableListOf<String>()
        val pins = mutableListOf<String?>()
        val replacementLog: (String) -> Unit = { logs += it }
        val replacementPin: (String?) -> Unit = { pins += it }
        main { service.logCallback = replacementLog; service.pinCallback = replacementPin }
        close(activity)
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
        activity.moveToState(Lifecycle.State.RESUMED)
        activity.onActivity {
            assertEquals(AirPlayService.ServerState.STOPPED, service.serverState.value)
            assertFalse(model(it).pref(Prefs.AUTO_START).value)
        }
        close(activity)
    }

    @Test fun backgroundPreferenceKeepsTheRunningReceiverAcrossStopAndResume() {
        set(Prefs.RUN_IN_BACKGROUND, true)
        val activity = launch()
        start(activity)
        stopped(activity, AirPlayService.ServerState.RUNNING)
        activity.moveToState(Lifecycle.State.RESUMED)
        activity.onActivity { assertEquals(AirPlayService.ServerState.RUNNING, service.serverState.value) }
        close(activity)
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
        activity.moveToState(Lifecycle.State.RESUMED)
        main { service.onConnectionDestroy() }
        keepsScreenOn(activity, false)

        val file = File(context.cacheDir, "lifecycle-video-${System.nanoTime()}.mp4")
        assertFalse(file.exists()); videoFixture = file
        instrumentation.context.assets.open("download/sample.mp4").use { input -> file.outputStream().use(input::copyTo) }
        main { service.playLocalVideo(android.net.Uri.fromFile(file).toString()); service.setVideoPlaying(false) }
        keepsScreenOn(activity, true)
        stopped(activity, AirPlayService.ServerState.RUNNING)
        activity.moveToState(Lifecycle.State.RESUMED)
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
        activity.moveToState(Lifecycle.State.RESUMED)
        main { service.onMirrorRunning(false) }
        keepsScreenOn(activity, false)
        stopped(activity, AirPlayService.ServerState.STOPPED)
        close(activity)
    }

    @Test fun pictureInPictureRoundTripKeepsTheSessionAndReturnsToTheSameActivity() {
        pipPolicy("allow")
        val activity = launch()
        start(activity)
        if (!supportsPip()) {
            activity.onActivity { it.enterPip(); assertFalse(it.isInPip.value) }
            close(activity)
            return
        }
        lateinit var owner: MainActivity
        activity.onActivity { owner = it }
        main { service.onConnectionInit() }
        keepsScreenOn(activity, true)
        activity.onActivity { it.enterPip() }
        await("Android did not enter PiP") { owner.isInPip.value && owner.isInPictureInPictureMode }
        main { assertEquals(AirPlayService.ServerState.RUNNING, service.serverState.value) }
        // A lifecycle resume request alone does not expand Android's pinned
        // task. Reopen the existing activity, as a launcher action does.
        main { context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        await("PiP did not return to the full activity") { !owner.isInPip.value && !owner.isInPictureInPictureMode }
        assertEquals(Lifecycle.State.RESUMED, activity.state)
        activity.onActivity { assertSame(owner, it) }
        main { service.onConnectionDestroy() }
        keepsScreenOn(activity, false)
        close(activity)
        main { assertEquals(AirPlayService.ServerState.STOPPED, service.serverState.value) }
    }
}
