package io.github.jqssun.airplay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.buzincast.core.AsyncLogStore
import io.buzincast.core.MediaRequest
import io.buzincast.core.Protocol
import io.buzincast.core.ReceiverPlaybackPort
import io.buzincast.core.SessionKind
import io.github.jqssun.airplay.audio.DacpController
import io.github.jqssun.airplay.media.NetworkRecovery
import io.github.jqssun.airplay.renderer.AirPlayVideoPlayer
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.viewmodel.MainViewModel
import io.leancast.app.Net
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
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
        bindReceiver()
    }

    private fun bindReceiver() {
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

    private fun stopped(activity: MainActivity, expected: AirPlayService.ServerState, scenario: String = "background") {
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
            assertEquals("Receiver state after $scenario", expected, service.serverState.value)
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
        val observed = mutableSetOf<Activity>()
        await("The lifecycle test's activities were not destroyed") {
            // The receiver may relaunch MainActivity without the test's intent extra.
            val open = Stage.entries.filter { it != Stage.DESTROYED }
                .flatMap(activityMonitor::getActivitiesInStage)
                .filter { it is MainActivity || it.intent?.getStringExtra("lifecycle_contract_owner") == activityToken }
            observed.addAll(open)
            open.filterNot(Activity::isFinishing).forEach(Activity::finish)
            observed.all(Activity::isDestroyed)
        }
        cover = null
    }

    private fun keepsScreenOn(activity: MainActivity, expected: Boolean) {
        await("Keep-screen-on flag did not become $expected") {
            val set = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            set == expected
        }
    }

    private fun awaitListener(host: String, port: Int, probe: (Socket) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6_000
        var lastFailure: IOException? = null
        do {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 1_000)
                    socket.soTimeout = 1_000
                    if (probe(socket)) return
                }
            } catch (failure: IOException) {
                lastFailure = failure
            }
            Thread.sleep(35)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Listener on port $port did not respond before the deadline", lastFailure)
    }

    private fun expectRtsp(port: Int) = awaitListener("127.0.0.1", port) { socket ->
        socket.getOutputStream().write("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
        val reply = socket.getInputStream().bufferedReader().readLine()
        if (reply != null) assertTrue("Unexpected RTSP response: $reply", reply.startsWith("RTSP/1.0 200"))
        reply != null
    }

    @Test fun launchOnConnectBringsBackTheActivityOnlyForTheFirstSender() {
        set(Prefs.RUN_IN_BACKGROUND, true)
        val activity = launch()
        start(activity)
        assertTrue("Android did not accept Home before the connection test",
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        await("Home did not background the idle receiver") {
            activityMonitor.getLifecycleStageOf(activity) == Stage.STOPPED &&
                service.serverState.value == AirPlayService.ServerState.RUNNING
        }
        var shown: MainActivity? = null
        try {
            fun assertBackgroundedWithSenders(count: Int) {
                instrumentation.waitForIdleSync()
                main {
                    assertEquals(count, service.connectionCount.value)
                    assertEquals(Stage.STOPPED, activityMonitor.getLifecycleStageOf(activity))
                    assertTrue(activityMonitor.getActivitiesInStage(Stage.RESUMED).none { it is MainActivity })

                }
            }

            main { service.onConnectionInit() }
            assertBackgroundedWithSenders(1)

            set(Prefs.LAUNCH_ON_CONNECT, true)
            main { service.onConnectionInit() }
            assertBackgroundedWithSenders(2)

            main {
                service.onConnectionDestroy()
                service.onConnectionDestroy()
                service.onConnectionInit()
            }
            await("The next first sender did not show the receiver app") {
                shown = activityMonitor.getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
                shown != null
            }
            main {
                assertEquals(1, service.connectionCount.value)
                assertEquals(AirPlayService.ServerState.RUNNING, service.serverState.value)
            }
        } finally {
            main {
                repeat(service.connectionCount.value) { service.onConnectionDestroy() }
                if (shown !== activity) shown?.finish()
            }
            finishActivities()
        }
    }

    @Test fun nativeInitializationFailuresReleaseTheServiceAndAllowRetry() {
        org.junit.Assume.assumeTrue("Requires instrumented native initialization fixture",
            runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        System.loadLibrary("airplay_native")
        val activity = launch()
        main {
            val messages = CopyOnWriteArrayList<String>()
            val previous = service.logCallback
            service.logCallback = { message -> messages += message; previous?.invoke(message) }
            try {
                for (stage in DebugCoverage.InitFailure.entries) {
                    messages.clear()
                    assertEquals(stage.name, 1, DebugCoverage.withInitFailure(stage) { service.startServer() })
                    assertEquals(stage.name, AirPlayService.ServerState.ERROR, service.serverState.value)
                    assertTrue(messages.toString(), messages.any { it.contains("Native AirPlay init failed") })
                    for (field in listOf("airPlayReceiver", "wakeLock", "nsdManager")) {
                        val value = AirPlayService::class.java.getDeclaredField(field).apply { isAccessible = true }.get(service)
                        assertNull("Resource retained after $stage: $field", value)
                    }
                }
            } finally {
                service.logCallback = previous
            }
        }
        start(activity)
    }

    @Test fun occupiedPortDuringForegroundRestartClosesTheListenerAndAllowsRetry() {
        val portPreference = Prefs.SERVER_PORT
        val savedPort = portPreference.read(preferences)
        val hadPort = preferences.contains(portPreference.key)
        val foregroundFlag = AirPlayService::class.java.getDeclaredField("foregroundStarted")
            .apply { isAccessible = true }
        val initialPort = ServerSocket(0).use { it.localPort }
        try {
            assertTrue(portPreference.write(preferences, initialPort))
            val activity = launch()
            start(activity)
            main { assertTrue(foregroundFlag.getBoolean(service)) }
            expectRtsp(initialPort)

            val nextPort = ServerSocket(0).use { occupied ->
                assertTrue(portPreference.write(preferences, occupied.localPort))
                await("The busy port did not stop foreground playback") {
                    service.serverState.value == AirPlayService.ServerState.ERROR &&
                        model(activity).serverState.value == AirPlayService.ServerState.ERROR &&
                        !foregroundFlag.getBoolean(service)
                }
                val failure = runCatching { Socket("127.0.0.1", initialPort).use { } }.exceptionOrNull()
                assertTrue("The previous RTSP listener is still reachable: $failure", failure is ConnectException)
                occupied.localPort
            }

            start(activity)
            main { assertTrue(foregroundFlag.getBoolean(service)) }
            expectRtsp(nextPort)
        } finally {
            main { service.stopServer() }
            val restore = preferences.edit()
            if (hadPort) restore.putInt(portPreference.key, savedPort) else restore.remove(portPreference.key)
            assertTrue(restore.commit())
        }
    }

    @Test fun foregroundStartActionReleasesAnOccupiedPortFailureAndRetries() {
        val portPreference = Prefs.SERVER_PORT
        val savedPort = portPreference.read(preferences)
        val hadPort = preferences.contains(portPreference.key)
        val foreground = AirPlayService::class.java.getDeclaredField("foregroundStarted").apply { isAccessible = true }
        val intent = Intent(context, AirPlayService::class.java).setAction(AirPlayService.ACTION_START_SERVER)
        // Start with a saved port. Updating it while the service is already
        // observing preferences can schedule a restart during the RTSP check.
        val idleService = service
        context.unbindService(connection)
        bound = false
        await("Idle receiver did not stop before port setup") {
            idleService.lifecycle.currentState == Lifecycle.State.DESTROYED
        }
        try {
            val (port, activity) = ServerSocket(0).use { occupied ->
                assertTrue(portPreference.write(preferences, occupied.localPort))
                bindReceiver()
                val activity = launch()
                main { ContextCompat.startForegroundService(context, intent) }
                await("Start action did not report an occupied listener") {
                    model(activity).serverState.value == AirPlayService.ServerState.ERROR && !foreground.getBoolean(service)
                }
                occupied.localPort to activity
            }
            main { ContextCompat.startForegroundService(context, intent) }
            await("Start action did not recover after the port was released") {
                model(activity).serverState.value == AirPlayService.ServerState.RUNNING && foreground.getBoolean(service)
            }
            expectRtsp(port)
        } finally {
            main { service.stopServer() }
            val restore = preferences.edit()
            if (hadPort) restore.putInt(portPreference.key, savedPort) else restore.remove(portPreference.key)
            assertTrue(restore.commit())
        }
    }

    @Test fun networkRecoveryRestartsOnlyAnOptedInRunningLanReceiver() {
        for ((pref, value) in listOf(Prefs.NETWORK_RECOVERY to true, Prefs.USB_ONLY to false)) {
            original[pref.key] = if (preferences.contains(pref.key)) pref.read(preferences) else null
            if (pref.read(preferences) != value) set(pref, value)
        }
        val watcher = AirPlayService::class.java.getDeclaredField("networkRecovery").apply { isAccessible = true }
        val changed = NetworkRecovery::class.java.getDeclaredField("changed").apply { isAccessible = true }
        val receiver = AirPlayService::class.java.getDeclaredField("airPlayReceiver").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val networkChanged = changed.get(watcher.get(service)) as (Boolean) -> Unit
        val activity = launch()

        main {
            networkChanged(true)
            assertEquals(context.getString(R.string.network_available), service.networkMessage.value)
            assertEquals(AirPlayService.ServerState.STOPPED, service.serverState.value)
            assertNull(receiver.get(service))
        }
        start(activity)
        val lanReceiver = requireNotNull(receiver.get(service))
        main {
            networkChanged(false)
            assertEquals(context.getString(R.string.network_waiting), service.networkMessage.value)
            assertSame(lanReceiver, receiver.get(service))
            set(Prefs.NETWORK_RECOVERY, false)
            networkChanged(true)
            assertEquals(context.getString(R.string.network_available), service.networkMessage.value)
            assertSame(lanReceiver, receiver.get(service))
            set(Prefs.NETWORK_RECOVERY, true)
            networkChanged(true)
            assertEquals(context.getString(R.string.network_recovered), service.networkMessage.value)
            assertEquals(AirPlayService.ServerState.RUNNING, service.serverState.value)
            assertNotSame(lanReceiver, requireNotNull(receiver.get(service)))
        }
        expectRtsp(Prefs.SERVER_PORT.read(preferences))
        val recoveredReceiver = requireNotNull(receiver.get(service))
        set(Prefs.USB_ONLY, true)
        await("USB-only restart did not replace the LAN receiver") {
            service.serverState.value == AirPlayService.ServerState.RUNNING && receiver.get(service) !== recoveredReceiver
        }
        main {
            val usbReceiver = requireNotNull(receiver.get(service))
            networkChanged(true)
            assertEquals(context.getString(R.string.network_available), service.networkMessage.value)
            assertSame(usbReceiver, receiver.get(service))
        }
    }

    @Test fun optionalReceiversFollowServicePreferencesAndUsbMode() {
        val optional = listOf(Prefs.DLNA_ENABLED, Prefs.FCAST_ENABLED, Prefs.CASTV2_ENABLED)
        for (pref in optional + Prefs.USB_ONLY) {
            original[pref.key] = if (preferences.contains(pref.key)) pref.read(preferences) else null
            set(pref, false)
        }
        val activity = launch()
        start(activity)
        val messages = CopyOnWriteArrayList<String>()
        val prefixes = listOf("DLNA experimental receiver started on ", "FCast v2 experimental listening on ",
            "Cast V2 experimental listening on ")
        val host = requireNotNull(Net.lanIpv4())
        val previous = service.logCallback
        main { service.logCallback = { message -> messages += message; previous?.invoke(message) } }

        fun portsAfter(marker: Int): List<Int> {
            await("Optional listeners did not start") {
                prefixes.all { prefix -> messages.drop(marker).any { it.startsWith(prefix) } }
            }
            return prefixes.map { prefix ->
                val reported = messages.drop(marker).first { it.startsWith(prefix) }.removePrefix(prefix)
                assertTrue("Unexpected listener endpoint: $reported", reported.startsWith("$host:"))
                reported.substringAfter(':').substringBefore(';').toInt()
            }
        }

        fun assertClosed(ports: List<Int>) {
            for (port in ports) {
                val error = runCatching { Socket(host, port).use { } }.exceptionOrNull()
                assertTrue("Listener $port still accepts connections: $error", error is ConnectException)
            }
        }

        try {
            val enabled = preferences.edit()
            optional.forEach { enabled.putBoolean(it.key, true) }
            assertTrue(enabled.commit())
            val lanPorts = portsAfter(0)
            lanPorts.forEach { port -> awaitListener(host, port, Socket::isConnected) }

            val usbMarker = messages.size
            set(Prefs.USB_ONLY, true)
            await("USB-only restart did not reach the running state") {
                service.serverState.value == AirPlayService.ServerState.RUNNING &&
                    messages.drop(usbMarker).any { it.startsWith("Server started on port ") }
            }
            assertClosed(lanPorts)

            val lanMarker = messages.size
            set(Prefs.USB_ONLY, false)
            val restoredPorts = portsAfter(lanMarker)
            restoredPorts.forEach { port -> awaitListener(host, port, Socket::isConnected) }
            main { service.stopServer() }
            assertClosed(restoredPorts)
        } finally {
            main { service.logCallback = previous }
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
            try {
                if (bound) {
                    context.unbindService(connection)
                    context.stopService(Intent(context, AirPlayService::class.java))
                    bound = false
                    await("Receiver service did not reach DESTROYED") {
                        service.lifecycle.currentState == Lifecycle.State.DESTROYED
                    }
                }
            } finally {
                val restore = preferences.edit()
                original.forEach { (key, value) -> if (value == null) restore.remove(key) else restore.putBoolean(key, value) }
                assertTrue(restore.commit())
                if (Build.VERSION.SDK_INT >= 26) previousPipMode?.let {
                    shell("appops set ${context.packageName} PICTURE_IN_PICTURE $it")
                    assertEquals("PiP policy was not restored", previousPipEffectiveMode, effectivePipMode())
                    android.util.Log.i("LifecycleContract", "PiP policy restored: $it, effective=$previousPipEffectiveMode")
                }
                videoFixture?.let { if (it.exists()) assertTrue(it.delete()) }
                if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
            }
        }
    }

    @Test fun configurationRecreationKeepsTheIdleReceiverAndReplacesOnlyItsOwnCallbacks() {
        set(Prefs.AUTO_START, true)
        val activity = launch()
        await("The enabled preference did not start the receiver on binding") {
            service.serverState.value == AirPlayService.ServerState.RUNNING &&
                model(activity).serverState.value == AirPlayService.ServerState.RUNNING
        }
        val receiverField = AirPlayService::class.java.getDeclaredField("airPlayReceiver").apply { isAccessible = true }
        lateinit var receiver: Any
        lateinit var retained: MainViewModel
        main {
            receiver = requireNotNull(receiverField.get(service))
            retained = model(activity)
        }
        val previousPin = service.pinCallback
        main { service.onDisplayPin("2468") }
        assertEquals("2468", retained.pinCode.value)
        main { activity.recreate() }
        await("Android did not replace the recreated activity") {
            activity.isDestroyed && resumedActivity<MainActivity>() != null
        }
        main { assertSame(retained, model(requireNotNull(resumedActivity<MainActivity>()))) }
        await("Recreated activity did not replace the old callback") { service.pinCallback !== previousPin && service.pinCallback != null }
        main {
            assertEquals(AirPlayService.ServerState.RUNNING, service.serverState.value)
            assertSame("Activity recreation restarted the receiver", receiver, receiverField.get(service))
        }
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
        stopped(activity, AirPlayService.ServerState.RUNNING, "backgrounding with a connected sender")
        resume(activity)
        main { service.onConnectionDestroy() }
        keepsScreenOn(activity, false)

        val file = File(context.cacheDir, "lifecycle-video-${System.nanoTime()}.mp4")
        assertFalse(file.exists()); videoFixture = file
        instrumentation.context.assets.open("download/sample.mp4").use { input -> file.outputStream().use(input::copyTo) }
        main { service.playLocalVideo(android.net.Uri.fromFile(file).toString()); service.setVideoPlaying(false) }
        keepsScreenOn(activity, true)
        stopped(activity, AirPlayService.ServerState.RUNNING, "backgrounding with local video")
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
        stopped(activity, AirPlayService.ServerState.RUNNING, "backgrounding with mirroring")
        resume(activity)
        main { service.onMirrorRunning(false) }
        keepsScreenOn(activity, false)
        stopped(activity, AirPlayService.ServerState.STOPPED, "backgrounding after mirroring ended")
        finishActivities()
    }

    @Test fun aSenderConnectingDuringBackgroundTransitionKeepsTheReceiverRunning() {
        pipPolicy("ignore")
        val activity = launch()
        start(activity)
        var connectedAtStop = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                assertEquals(0, model(activity).connectionCount.value)
                service.onConnectionInit()
                connectedAtStop = true
            }
        }
        main { activity.lifecycle.addObserver(observer) }
        try {
            stopped(activity, AirPlayService.ServerState.RUNNING, "a sender joining during onStop")
            assertTrue("The transition did not attach a sender", connectedAtStop)
        } finally {
            main {
                activity.lifecycle.removeObserver(observer)
                if (connectedAtStop) service.onConnectionDestroy()
            }
        }
        resume(activity)
        stopped(activity, AirPlayService.ServerState.STOPPED, "the sender disconnecting")
        finishActivities()
    }

    @Test fun disconnectingTheActivityBindingDoesNotStopTheReceiverOrEnterPictureInPicture() {
        pipPolicy("allow")
        val activity = launch()
        start(activity)
        main { service.onConnectionInit() }
        keepsScreenOn(activity, true)
        val binding = MainActivity::class.java.getDeclaredField("connection").apply { isAccessible = true }
            .get(activity) as ServiceConnection
        val receiver = Intent(context, AirPlayService::class.java)
        val component = requireNotNull(receiver.component)
        main {
            binding.onServiceDisconnected(component)
            assertEquals("The presentation snapshot still has the last connection", 1, model(activity).connectionCount.value)
            instrumentation.callActivityOnUserLeaving(activity)
            assertFalse("A disconnected activity entered PiP", activity.isInPip.value)
        }
        try {
            stopped(activity, AirPlayService.ServerState.RUNNING, "backgrounding while disconnected from the receiver")
        } finally {
            main { binding.onServiceConnected(component, service.LocalBinder()) }
        }
        resume(activity)
        main { service.onConnectionDestroy() }
        keepsScreenOn(activity, false)
        stopped(activity, AirPlayService.ServerState.STOPPED, "backgrounding after reconnecting to an idle receiver")
        finishActivities()
    }

    @Test fun modelKeepsMirroringAndPlaybackSurfacesIndependentAcrossReplacementAndDetach() {
        val model = model(launch())
        val mirrorSurface = service.videoRenderer.javaClass.getDeclaredField("displaySurface").apply { isAccessible = true }
        val playbackSurface = AirPlayVideoPlayer::class.java.getDeclaredField("pendingSurface").apply { isAccessible = true }
        val firstTexture = SurfaceTexture(false)
        val secondTexture = SurfaceTexture(false)
        val first = Surface(firstTexture)
        val second = Surface(secondTexture)
        fun assertSurfaces(mirror: Surface?, playback: Surface?) {
            assertSame(mirror, mirrorSurface.get(service.videoRenderer))
            assertSame(playback, playbackSurface.get(service.airPlayVideoPlayer))
        }
        try {
            main {
                model.onSurfaceAvailable(first)
                model.onVideoPlaybackSurfaceAvailable(second)
                assertSurfaces(first, second)

                model.onSurfaceAvailable(second)
                model.onVideoPlaybackSurfaceAvailable(first)
                model.onSurfaceDestroyed(first)
                model.onVideoPlaybackSurfaceDestroyed(second)
                assertSurfaces(second, first)

                model.unbindService()
                try {
                    model.onSurfaceDestroyed(second)
                    model.onVideoPlaybackSurfaceDestroyed(first)
                    model.onSurfaceAvailable(first)
                    model.onVideoPlaybackSurfaceAvailable(second)
                    assertSurfaces(second, first)
                } finally {
                    model.bindService(service)
                }

                model.onSurfaceDestroyed(second)
                assertSurfaces(null, first)
                model.onVideoPlaybackSurfaceDestroyed(first)
                assertSurfaces(null, null)
            }
        } finally {
            main {
                for (surface in listOf(first, second)) {
                    service.clearVideoSurface(surface)
                    service.clearVideoPlaybackSurface(surface)
                }
            }
            first.release()
            second.release()
            firstTexture.release()
            secondTexture.release()
        }
    }

    @Test fun modelScrubsBoundPlaybackAndClearsTheTargetWhenMediaChanges() {
        val activity = launch()
        val model = model(activity)
        main {
            model.unbindService()
            try {
                model.startServer()
                assertEquals(AirPlayService.ServerState.STOPPED, service.serverState.value)
            } finally {
                model.bindService(service)
            }
        }
        start(activity)
        val file = File.createTempFile("model-scrub-", ".mp4", context.cacheDir).also { videoFixture = it }
        instrumentation.context.assets.open("playback/remote-seek.mp4").use { input -> file.outputStream().use(input::copyTo) }
        val location = android.net.Uri.fromFile(file).toString()
        val playerField = AirPlayVideoPlayer::class.java.getDeclaredField("player").apply { isAccessible = true }
        main {
            model.unbindService()
            try {
                model.stopServer()
                assertEquals(AirPlayService.ServerState.RUNNING, service.serverState.value)
            } finally {
                model.bindService(service)
            }
            service.playLocalVideo(location)
            service.setVideoPlaying(false)
        }
        await("The fixture duration did not reach the model") {
            model.videoPlaybackActive.value && model.videoDurationMs.value == 40_000L
        }
        fun player() = playerField.get(service.airPlayVideoPlayer) as ExoPlayer
        main { model.startVideoScrub() }
        await("Scrubbing mode did not reach the player") { player().isScrubbingModeEnabled }
        main {
            model.scrubVideoTo(9_000L)
            assertEquals(9_000L, model.videoScrubPositionMs.value)
        }
        await("The player did not seek to the scrub target") {
            player().currentPosition == 9_000L
        }
        main {
            model.endVideoScrub()
            model.setVideoSkipSilence(true)
            assertTrue(model.videoSkipSilence.value)
        }
        await("The player did not settle after seeking and enabling silence skipping") {
            !player().isScrubbingModeEnabled && player().skipSilenceEnabled &&
                model.videoDurationMs.value == 40_000L
        }
        main {
            model.startVideoScrub()
            model.scrubVideoTo(17_000L)
            model.endVideoScrub()
            assertEquals(17_000L, model.videoScrubPositionMs.value)
            service.playLocalVideo(location)
            model.updateFromService()
            assertNull("A new playback kept the previous scrub target", model.videoScrubPositionMs.value)
            assertFalse("A new playback kept the previous optimistic option", model.videoSkipSilence.value)
        }
    }

    @Test fun modelControlsReceiverAndPrefersVideoMetadataOverTheAudioTitle() {
        val activity = launch()
        val model = model(activity)
        main { model.startServer() }
        await("Model Start did not reach the receiver") {
            service.serverState.value == AirPlayService.ServerState.RUNNING &&
                model.serverState.value == AirPlayService.ServerState.RUNNING
        }
        val file = File.createTempFile("video-title-", ".mp4", context.cacheDir).also { videoFixture = it }
        instrumentation.context.assets.open("playback/remote-seek.mp4").use { input -> file.outputStream().use(input::copyTo) }
        val playerField = AirPlayVideoPlayer::class.java.getDeclaredField("player").apply { isAccessible = true }
        val audioTitle = "Title from sender audio"
        val videoTitle = "Title from video item"
        main {
            service.playLocalVideo(android.net.Uri.fromFile(file).toString())
            model.setVideoPlaying(false)
            val bytes = audioTitle.toByteArray(Charsets.UTF_8)
            service.onMetadata(ByteBuffer.allocate(8 + bytes.size)
                .put("minm".toByteArray(Charsets.US_ASCII)).putInt(bytes.size).put(bytes).array())
        }
        await("The player did not expose the fallback title") {
            model.videoPlaybackActive.value && service.videoTitle.value.isEmpty() && model.videoTitle.value == audioTitle
        }
        main {
            val player = playerField.get(service.airPlayVideoPlayer) as ExoPlayer
            val item = requireNotNull(player.currentMediaItem)
            player.replaceMediaItem(0, item.buildUpon()
                .setMediaMetadata(MediaMetadata.Builder().setTitle(videoTitle).build()).build())
        }
        await("Video metadata did not take precedence") {
            service.trackInfo.value.title == audioTitle && service.videoTitle.value == videoTitle &&
                model.videoTitle.value == videoTitle
        }
        main { model.stopServer() }
        await("Model Stop did not stop the receiver and release playback") {
            service.serverState.value == AirPlayService.ServerState.STOPPED &&
                model.serverState.value == AirPlayService.ServerState.STOPPED &&
                !model.videoPlaybackActive.value
        }
    }

    @Test fun playbackControlsStayWithTheOwnerAcrossFcastAirplayAndLocalHandoffs() {
        val activity = launch()
        start(activity)
        val file = File.createTempFile("media-ownership-", ".mp4", context.cacheDir).also { videoFixture = it }
        instrumentation.context.assets.open("playback/remote-seek.mp4").use { input -> file.outputStream().use(input::copyTo) }
        val location = android.net.Uri.fromFile(file).toString()
        val port = AirPlayService::class.java.getDeclaredField("playbackPort").apply { isAccessible = true }
            .get(service) as ReceiverPlaybackPort
        val playerField = AirPlayVideoPlayer::class.java.getDeclaredField("player").apply { isAccessible = true }
        fun player() = playerField.get(service.airPlayVideoPlayer) as ExoPlayer

        main {
            service.relay.listen()
            assertTrue(service.relay.state.value.listening)
            port.load(MediaRequest(location, Protocol.FCAST))
            assertFalse(service.relay.state.value.listening)
            assertEquals(Protocol.FCAST, service.networkPlaybackOwner)
            assertEquals(SessionKind.MEDIA, service.sessionSnapshot()?.kind)
            port.playing(Protocol.FCAST, false)
            port.speed(Protocol.FCAST, 1.25f)
            port.gain(Protocol.FCAST, 0.5f)
            port.seek(Protocol.FCAST, 0.25f)
            port.playing(Protocol.DLNA, true)
            port.speed(Protocol.DLNA, 2f)
            port.gain(Protocol.DLNA, 0.9f)
            port.stop(Protocol.DLNA)
        }
        main {
            assertFalse(player().playWhenReady)
            assertEquals(1.25f, player().playbackParameters.speed, 0f)
            assertEquals(0.5f, player().volume, 0f)
            service.onConnectionInit()
            val blocked = runCatching { port.load(MediaRequest(location, Protocol.DLNA)) }.exceptionOrNull()
            assertEquals("Another display session is active", blocked?.message)
            assertEquals(Protocol.FCAST, service.networkPlaybackOwner)
            service.onVideoPlay(location, 0f)
            service.onVideoRate(0f)
        }
        await("AirPlay did not claim and pause playback") {
            service.networkPlaybackOwner == Protocol.AIRPLAY && !player().playWhenReady
        }
        main {
            service.onVideoRate(1.5f)
            service.onVideoRate(0f)
        }
        await("AirPlay rate commands were not applied") {
            !player().playWhenReady && player().playbackParameters.speed == 1.5f
        }
        main { service.onVideoScrub(12f) }
        await("The AirPlay seek did not update the paused player and its published position") {
            !player().playWhenReady && player().currentPosition == 12_000L &&
                service.videoPlaybackInfo.value.positionMs == 12_000L
        }
        main {
            assertFalse(player().playWhenReady)
            assertEquals(1.5f, player().playbackParameters.speed, 0f)
            service.onVideoStop()
            service.playLocalVideo(location)
            service.setVideoPlaying(false)
        }
        var localPosition = 0L
        main {
            assertEquals(Protocol.LOCAL, service.networkPlaybackOwner)
            assertFalse(player().playWhenReady)
            localPosition = player().currentPosition
            service.onVideoRate(2f)
            service.onVideoScrub(0.5f)
            port.seek(Protocol.FCAST, 0.5f)
            service.onConnectionDestroy()
        }
        main {
            assertEquals(Protocol.LOCAL, service.networkPlaybackOwner)
            assertTrue(service.videoPlaybackActive.value)
            assertFalse(player().playWhenReady)
            assertEquals(1f, player().playbackParameters.speed, 0f)
            assertEquals(localPosition, player().currentPosition)
            port.stop(Protocol.LOCAL)
            assertNull(service.networkPlaybackOwner)
            assertFalse(service.videoPlaybackActive.value)
        }
    }

    @Test fun queuedAirPlayVideoRequestsRespectReceiverStopAndRestart() {
        val activity = launch()
        val file = File.createTempFile("queued-airplay-", ".mp4", context.cacheDir).also { videoFixture = it }
        instrumentation.context.assets.open("playback/remote-seek.mp4").use { input ->
            file.outputStream().use(input::copyTo)
        }
        val location = android.net.Uri.fromFile(file).toString()
        try {
            main {
                service.onVideoPlay(location, 0f)
                service.startServer()
            }
            await("The receiver did not start after an early video callback") {
                service.serverState.value == AirPlayService.ServerState.RUNNING
            }
            main { assertFalse("A callback from before startup opened a player", service.videoPlaybackActive.value) }

            main {
                service.onVideoPlay(location, 0f)
                service.stopServer()
            }
            main {
                assertEquals(AirPlayService.ServerState.STOPPED, service.serverState.value)
                assertFalse("A queued callback opened a player after shutdown", service.videoPlaybackActive.value)
            }

            start(activity)
            main {
                repeat(2) { service.onConnectionInit() }
                service.onVideoSessionPoll()
                assertTrue("A fresh sender poll did not mark video as pending", service.videoSessionPending())
                service.onVideoPlay(location, 0f)
            }
            await("The restarted receiver did not accept a new video request") {
                service.videoPlaybackActive.value && service.networkPlaybackOwner == Protocol.AIRPLAY
            }
            val originalToken = requireNotNull(service.sessionSnapshot()).token
            main { service.onConnectionDestroy() }
            main {
                assertEquals(1, service.connectionCount.value)
                // The decoder may finish preparing while the first sender disconnects.
                assertEquals(originalToken, service.sessionSnapshot()?.token)
                assertEquals(Protocol.AIRPLAY, service.networkPlaybackOwner)
                assertTrue(service.videoPlaybackActive.value)
                service.onConnectionDestroy()
            }
            await("Disconnecting the last sender did not stop its video") {
                service.connectionCount.value == 0 && !service.videoPlaybackActive.value && service.sessionSnapshot() == null
            }

            main {
                service.onConnectionInit()
                service.onVideoPlay(location, 0f)
            }
            await("The next AirPlay sender did not start video") {
                service.videoPlaybackActive.value && service.networkPlaybackOwner == Protocol.AIRPLAY
            }
            main { service.onVideoStop() }
            await("The AirPlay stop command did not release its video") {
                !service.videoPlaybackActive.value && service.sessionSnapshot() == null
            }
            main {
                service.onVideoSessionPoll()
                assertFalse("A late sender poll reopened the stopped video", service.videoSessionPending())
                assertEquals(1, service.connectionCount.value)
                service.onVideoPlay(location, 0f)
            }
            await("The stopped AirPlay sender did not start its next video") {
                service.videoPlaybackActive.value && service.networkPlaybackOwner == Protocol.AIRPLAY
            }
            main {
                service.onConnectionDestroy()
                service.playLocalVideo(location)
                service.setVideoPlaying(false)
            }
            main {
                assertEquals(0, service.connectionCount.value)
                assertEquals(Protocol.LOCAL, service.networkPlaybackOwner)
                assertTrue(service.videoPlaybackActive.value)
            }
        } finally {
            main { service.stopServer() }
            finishActivities()
        }
    }

    @Test fun narrowVideoEntersPictureInPictureAndKeepsThePausedSession() {
        pipPolicy("allow")
        val activity = launch()
        start(activity)
        if (!supportsPip()) {
            main { activity.enterPip(); assertFalse(activity.isInPip.value) }
            finishActivities()
            return
        }
        val file = File.createTempFile("pip-tall-", ".mp4", context.cacheDir).also { videoFixture = it }
        instrumentation.context.assets.open("playback/tall-pip.mp4").use { input ->
            file.outputStream().use(input::copyTo)
        }
        main {
            service.playLocalVideo(android.net.Uri.fromFile(file).toString())
            service.setVideoPlaying(false)
        }
        await("The decoder did not report the video's 1:4 aspect ratio") {
            model(activity).videoPlaybackActive.value &&
                kotlin.math.abs(model(activity).videoPlaybackAspect.value - .25f) < .001f
        }
        val session = requireNotNull(service.sessionSnapshot()).token
        instrumentation.waitForIdleSync()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val params = MainActivity::class.java.getDeclaredMethod("_pipParams").apply { isAccessible = true }
            main {
                val current = params.invoke(activity) as android.app.PictureInPictureParams
                assertTrue("Active video must enable Android's automatic PiP", current.isAutoEnterEnabled)
                assertEquals(android.util.Rational(419, 1000), current.aspectRatio)
            }
            shell("input keyevent KEYCODE_HOME")
        } else {
            assertTrue("Android did not accept the Home action",
                instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        }
        await("Android did not pin the narrow playback activity") {
            activity.isInPip.value && activity.isInPictureInPictureMode
        }
        main {
            assertEquals(session, service.sessionSnapshot()?.token)
            assertTrue(service.videoPlaybackActive.value)
        }

        main { context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        await("Narrow playback did not return from PiP") {
            !activity.isInPip.value && resumedActivity<MainActivity>() === activity
        }
        main {
            assertEquals(session, service.sessionSnapshot()?.token)
            assertFalse(model(activity).videoPlaying.value)
            service.stopVideoPlayback()
        }
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
        main {
            assertEquals(0, model(activity).connectionCount.value)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                instrumentation.callActivityOnUserLeaving(activity)
                assertFalse("An idle receiver entered PiP", activity.isInPictureInPictureMode)
            }
            service.onConnectionInit()
            assertEquals("The ViewModel must still be awaiting its next poll", 0, model(activity).connectionCount.value)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) instrumentation.callActivityOnUserLeaving(activity)
            else activity.enterPip()
        }
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

    @Test fun approvalDialogAnswersTheBoundReceiverAndTreatsBackAsDenial() {
        val activity = launch()
        start(activity)
        val executor = Executors.newSingleThreadExecutor()
        val title = context.getString(R.string.connection_approval_title)
        val steps = listOf(null to false, R.string.connection_decline to false, R.string.btn_allow to true)

        fun visibleControl(label: String): Rect {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < deadline) {
                nodeBounds(label)?.let { return it }
                Thread.sleep(30)
            }
            throw AssertionError("Approval dialog did not show $label")
        }

        try {
            steps.forEachIndexed { index, (action, expected) ->
                val peer = "192.0.2.${index + 1}"
                val response = executor.submit<Boolean> { service.approval.ask("AirPlay", peer) }
                visibleControl(title)
                visibleControl("AirPlay • $peer")
                if (action == null) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                } else {
                    val control = visibleControl(context.getString(action))
                    pointerGesture(control.exactCenterX(), control.exactCenterY(), control.exactCenterY())
                }
                assertEquals("Wrong approval response for decision ${index + 1}", expected,
                    response.get(10, TimeUnit.SECONDS))
                await("The receiver still exposed the answered approval request") {
                    service.approval.request.value == null && model(activity).pendingApproval.value == null
                }
            }
        } finally {
            executor.shutdownNow()
            finishActivities()
        }
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
            awaitVolumeControl(R.string.cd_rewind, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { it.isVisibleToUser }
            lateinit var receiverBounds: Rect
            var lastBounds: Rect? = null
            var stableSince = 0L
            awaitVolumeControl(R.string.volume_server, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { node ->
                assertTrue(node.isEnabled)
                val bounds = Rect().also(node::getBoundsInScreen)
                if (!node.isVisibleToUser || bounds.isEmpty || bounds != lastBounds) {
                    lastBounds = bounds
                    stableSince = SystemClock.elapsedRealtime()
                    return@awaitVolumeControl false
                }
                receiverBounds = bounds
                SystemClock.elapsedRealtime() - stableSince >= 150L
            }
            await("The emulator did not apply the initial media volume $initial") {
                audio.getStreamVolume(stream) == initial
            }
            val touchY = receiverBounds.exactCenterY()
            val thumbX = receiverBounds.left + receiverBounds.width() * initial.toFloat() / maximum
            val targetX = receiverBounds.left + receiverBounds.width() * 0.85f
            val events = mutableListOf<String>()
            var minimumDuringDrag = initial
            lateinit var originalCallback: Window.Callback
            main {
                originalCallback = activity.window.callback
                activity.window.callback = object : Window.Callback by originalCallback {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        val accepted = originalCallback.dispatchTouchEvent(event)
                        val volume = audio.getStreamVolume(stream)
                        minimumDuringDrag = minOf(minimumDuringDrag, volume)
                        events += "${MotionEvent.actionToString(event.actionMasked)} " +
                            "(${event.x}, ${event.y}) tool=${event.getToolType(0)} " +
                            "handled=$accepted volume=$volume"
                        return accepted
                    }
                }
            }
            try {
                pointerGesture(thumbX, touchY, touchY, endX = targetX)
            } finally {
                main { activity.window.callback = originalCallback }
            }
            awaitAudioGesture("receiver-volume", activity, receiverBounds) {
                try {
                    await("Dragging receiver volume from $initial did not raise the media stream") {
                        audio.getStreamVolume(stream) > initial + 1
                    }
                } catch (failure: AssertionError) {
                    throw AssertionError(
                        "${failure.message}; volume=${audio.getStreamVolume(stream)}/$maximum; events=$events", failure)
                }
            }
            val raised = audio.getStreamVolume(stream)
            assertTrue("Dragging right lowered the volume below $initial: $events", minimumDuringDrag >= initial)
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left on receiver volume did not lower the media stream one step") { audio.getStreamVolume(stream) == raised - 1 }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right on receiver volume did not restore the media stream one step") { audio.getStreamVolume(stream) == raised }
            pointerGesture(receiverBounds.left.toFloat() - 24, touchY, touchY - receiverBounds.height() * 2f)
            main { assertFalse(model(activity).clientVolumeAdjustable.value) }
            awaitVolumeControl(R.string.volume_client, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { node ->
                if (!node.isVisibleToUser) return@awaitVolumeControl false
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

    @Test fun remoteSeekAccumulatesWhileControlsAreHiddenAndResetsAfterFeedback() {
        val activity = launch()
        val file = File.createTempFile("remote-seek-", ".mp4", context.cacheDir).also { videoFixture = it }
        instrumentation.context.assets.open("playback/remote-seek.mp4").use { input ->
            file.outputStream().use(input::copyTo)
        }
        val playerField = AirPlayVideoPlayer::class.java.getDeclaredField("player").apply { isAccessible = true }
        fun player() = playerField.get(service.airPlayVideoPlayer) as? ExoPlayer

        main {
            service.playLocalVideo(android.net.Uri.fromFile(file).toString())
            service.setVideoPlaying(false)
        }
        try {
            await("The paused video did not become seekable") {
                val receiver = player()
                model(activity).videoPlaybackActive.value && receiver != null &&
                    receiver.playbackState == androidx.media3.common.Player.STATE_READY &&
                    !receiver.playWhenReady && receiver.duration >= 30_000
            }
            fun seekLabel(sign: String, seconds: Long) =
                sign + context.getString(R.string.seconds_short, seconds)
            val cumulative = seekLabel("+", 20)
            val backwards = seekLabel("-", 10)

            // Keep consecutive presses in the same main-loop turn. Waiting for
            // the first accessibility label can consume the feedback window.
            main {
                repeat(2) {
                    assertTrue("Right press did not reach the player",
                        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
                }
            }
            awaitVisiblePlayerLabel(cumulative)
            await("Two remote presses did not seek 20 seconds") {
                player()?.currentPosition?.let { it in 20_000L..20_999L } == true
            }
            val expiry = SystemClock.elapsedRealtime() + 4_000
            while (nodeBounds(cumulative) != null && SystemClock.elapsedRealtime() < expiry) {
                Thread.sleep(20)
            }
            assertNull("Remote seek feedback did not expire", nodeBounds(cumulative))

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            awaitVisiblePlayerLabel(backwards)
            await("The remote left press did not seek back ten seconds") {
                player()?.currentPosition?.let { it in 10_000L..10_999L } == true
            }
        } finally {
            main { service.stopVideoPlayback() }
            finishActivities()
        }
    }

    private fun awaitVisiblePlayerLabel(label: String) {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (nodeBounds(label) != null) return
            Thread.sleep(20)
        }
        fail("The player did not show $label")
    }

    private fun nodeBounds(label: String): Rect? {
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        val root = automation.rootInActiveWindow ?: return null
        fun visit(node: AccessibilityNodeInfo): Rect? {
            if (node.isVisibleToUser && (node.text?.toString() == label ||
                    node.contentDescription?.toString() == label)) {
                return Rect().also(node::getBoundsInScreen).takeUnless(Rect::isEmpty)
            }
            repeat(node.childCount) { index ->
                val child = node.getChild(index) ?: return@repeat
                try { visit(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        return try { if (root.refresh() && root.packageName?.toString() == context.packageName) visit(root) else null }
            finally { root.recycle() }
    }

    @Test fun verticalVideoGesturesAdjustOnlyTheirOwnSide() {
        val activity = launch()
        val file = File.createTempFile("vertical-gestures-", ".mp4", context.cacheDir).also { videoFixture = it }
        instrumentation.context.assets.open("playback/remote-seek.mp4").use { input ->
            file.outputStream().use(input::copyTo)
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val originalVolume = audio.getStreamVolume(stream)
        val initialVolume = audio.getStreamMaxVolume(stream) / 2
        val initialBrightness = .35f
        try {
            main {
                service.playLocalVideo(android.net.Uri.fromFile(file).toString())
                service.setVideoPlaying(false)
            }
            await("The paused video did not become seekable") {
                model(activity).videoPlaybackActive.value && !model(activity).videoPlaying.value &&
                    model(activity).videoDurationMs.value >= 30_000
            }
            await("Fullscreen video did not settle in its landscape window") {
                val decor = activity.window.decorView
                activity.hasWindowFocus() && decor.width > decor.height &&
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            val viewport = Rect()
            main {
                val decor = activity.window.decorView
                val location = IntArray(2).also(decor::getLocationOnScreen)
                viewport.set(location[0], location[1], location[0] + decor.width, location[1] + decor.height)
                activity.window.attributes = activity.window.attributes.apply { screenBrightness = initialBrightness }
                audio.setStreamVolume(stream, initialVolume, 0)
            }
            assertFalse(viewport.isEmpty)
            fun tapVideo() {
                val x = viewport.left + viewport.width() * .25f
                pointerGesture(x, viewport.exactCenterY(), viewport.exactCenterY())
            }
            if (nodeBounds("CC") == null) tapVideo()
            awaitVisiblePlayerLabel("CC")
            tapVideo()
            val controlsDeadline = SystemClock.elapsedRealtime() + 3_000
            while (nodeBounds("CC") != null && SystemClock.elapsedRealtime() < controlsDeadline) Thread.sleep(25)
            assertNull("The playback controls still cover the video", nodeBounds("CC"))
            await("The fixture did not apply its starting media volume") { audio.getStreamVolume(stream) == initialVolume }
            val fromY = viewport.top + viewport.height() * .84f
            val toY = viewport.top + viewport.height() * .16f
            pointerGesture(viewport.left + viewport.width() * .25f, fromY, toY)
            awaitAudioGesture("video-brightness", activity, viewport) {
                await("The left side did not increase window brightness") {
                    activity.window.attributes.screenBrightness > initialBrightness
                }
            }
            assertEquals("A brightness gesture changed media volume", initialVolume, audio.getStreamVolume(stream))
            var raisedBrightness = initialBrightness
            main { raisedBrightness = activity.window.attributes.screenBrightness }

            pointerGesture(viewport.left + viewport.width() * .75f, fromY, toY)
            await("The right side did not increase media volume") { audio.getStreamVolume(stream) > initialVolume }
            main {
                assertEquals("A volume gesture changed window brightness",
                    raisedBrightness, activity.window.attributes.screenBrightness, 0.001f)
                assertFalse("The gestures resumed the paused video", model(activity).videoPlaying.value)
            }
            val raisedVolume = audio.getStreamVolume(stream)
            pointerGesture(viewport.left + viewport.width() * .25f, toY, fromY)
            await("Dragging down on the left did not lower brightness") {
                activity.window.attributes.screenBrightness < raisedBrightness
            }
            assertEquals("Lowering brightness changed media volume", raisedVolume, audio.getStreamVolume(stream))

            main { service.stopVideoPlayback() }
            await("Leaving the video did not release the brightness override") {
                !model(activity).videoPlaybackActive.value &&
                    activity.window.attributes.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        } finally {
            main {
                service.stopVideoPlayback()
                audio.setStreamVolume(stream, originalVolume, 0)
            }
            finishActivities()
        }
    }

    @Test fun pendingVideoKeepsItsToolbarUntilRemoteBackCancelsIt() {
        val activity = launch()
        val handler = Handler(Looper.getMainLooper())
        var polls = 0
        val poll = object : Runnable {
            override fun run() {
                service.onVideoSessionPoll()
                model(activity).updateFromService()
                polls++
                handler.postDelayed(this, 250)
            }
        }
        handler.post(poll)
        try {
            await("The sender polls did not reach the pending player") {
                model(activity).videoSessionPending.value && !model(activity).videoPlaybackActive.value
            }
            awaitVisiblePlayerLabel("CC")
            var x = 0f
            var y = 0f
            var pollsBeforeTap = 0
            main {
                val decor = activity.window.decorView
                val location = IntArray(2)
                decor.getLocationOnScreen(location)
                x = location[0] + decor.width * .25f
                y = location[1] + decor.height * .5f
                pollsBeforeTap = polls
            }
            pointerGesture(x, y, y)
            await("Tapping the pending player changed its playback state") {
                polls >= pollsBeforeTap + 3 && model(activity).videoSessionPending.value &&
                    !model(activity).videoPlaybackActive.value
            }
            assertNotNull("The pending toolbar was hidden by a tap", nodeBounds("CC"))

            var pollsBeforeBack = 0
            main { pollsBeforeBack = polls }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Remote Back did not cancel pending playback while keeping the app open") {
                polls >= pollsBeforeBack + 2 && resumedActivity<MainActivity>() === activity &&
                    !model(activity).videoSessionPending.value && !model(activity).videoPlaybackActive.value
            }
            awaitVisiblePlayerLabel(context.getString(R.string.tab_overview))
        } finally {
            handler.removeCallbacks(poll)
            finishActivities()
        }
    }

    @Test fun tappingVideoTogglesOnlyTheControlsForItsCurrentLockState() {
        val activity = launch()
        val file = File.createTempFile("touch-controls-", ".mp4", context.cacheDir).also { videoFixture = it }
        instrumentation.context.assets.open("playback/remote-seek.mp4").use { input ->
            file.outputStream().use(input::copyTo)
        }
        val lock = context.getString(R.string.cd_lock_controls)
        val unlock = context.getString(R.string.cd_unlock_controls)

        fun waitForControls(toolbar: Boolean, unlockOnly: Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 8_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if ((nodeBounds("CC") != null) == toolbar && (nodeBounds(unlock) != null) == unlockOnly) return
                Thread.sleep(25)
            }
            fail("Expected toolbar visibility $toolbar and unlock visibility $unlockOnly")
        }
        fun tapBounds(bounds: Rect) {
            val x = bounds.exactCenterX()
            val y = bounds.exactCenterY()
            pointerGesture(x, y, y)
        }

        try {
            main {
                service.playLocalVideo(android.net.Uri.fromFile(file).toString())
                service.setVideoPlaying(false)
            }
            await("The paused video did not become seekable") {
                model(activity).videoPlaybackActive.value && !model(activity).videoPlaying.value &&
                    model(activity).videoDurationMs.value >= 30_000
            }
            val videoBounds = Rect()
            main {
                val decor = activity.window.decorView
                val location = IntArray(2)
                decor.getLocationOnScreen(location)
                videoBounds.set(location[0], location[1], location[0] + decor.width, location[1] + decor.height)
            }
            assertFalse(videoBounds.isEmpty)
            fun tapVideoBackground() {
                val x = videoBounds.left + videoBounds.width() * .25f
                val y = videoBounds.exactCenterY()
                pointerGesture(x, y, y)
            }
            waitForControls(toolbar = false, unlockOnly = false)

            tapVideoBackground()
            waitForControls(toolbar = true, unlockOnly = false)
            tapVideoBackground()
            waitForControls(toolbar = false, unlockOnly = false)
            tapVideoBackground()
            waitForControls(toolbar = true, unlockOnly = false)

            tapBounds(requireNotNull(nodeBounds(lock)))
            waitForControls(toolbar = false, unlockOnly = true)
            tapVideoBackground()
            waitForControls(toolbar = false, unlockOnly = false)
            tapVideoBackground()
            waitForControls(toolbar = false, unlockOnly = true)
            tapBounds(requireNotNull(nodeBounds(unlock)))
            waitForControls(toolbar = true, unlockOnly = false)

            main {
                assertTrue(model(activity).videoPlaybackActive.value)
                assertFalse("Tapping the video background changed the paused playback state", model(activity).videoPlaying.value)
            }
        } finally {
            main { service.stopVideoPlayback() }
            finishActivities()
        }
    }

    @Test fun rotatingThePlayerPreservesThePausedPlaybackSession() {
        val activity = launch()
        val file = File.createTempFile("player-rotation-", ".mp4", context.cacheDir).also { videoFixture = it }
        instrumentation.context.assets.open("playback/remote-seek.mp4").use { input ->
            file.outputStream().use(input::copyTo)
        }
        val location = android.net.Uri.fromFile(file).toString()
        main {
            service.playLocalVideo(location)
            service.setVideoPlaying(false)
        }
        try {
            try {
                await("The video did not reach its paused controls") {
                    model(activity).videoPlaybackActive.value && !model(activity).videoPlaying.value &&
                        model(activity).videoPlaybackSize.value == (64 to 64)
                }
            } catch (failure: AssertionError) {
                val state = model(activity)
                throw AssertionError("Paused playback state: active=${state.videoPlaybackActive.value}, " +
                    "playing=${state.videoPlaying.value}, size=${state.videoPlaybackSize.value}", failure)
            }
            val token = requireNotNull(service.sessionSnapshot()).token
            var orientation = Configuration.ORIENTATION_UNDEFINED
            main { orientation = activity.resources.configuration.orientation }
            assertTrue(orientation == Configuration.ORIENTATION_LANDSCAPE ||
                orientation == Configuration.ORIENTATION_PORTRAIT)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            repeat(2) {
                awaitVolumeControl(R.string.cd_rotate) { node ->
                    if (!node.isVisibleToUser || !node.isEnabled) return@awaitVolumeControl false
                    var control = AccessibilityNodeInfo.obtain(node)
                    try {
                        while (!control.isClickable) {
                            val parent = control.parent ?: return@awaitVolumeControl false
                            control.recycle()
                            control = parent
                        }
                        control.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } finally {
                        control.recycle()
                    }
                }
                val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
                val requested = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                orientation = if (landscape) Configuration.ORIENTATION_PORTRAIT
                    else Configuration.ORIENTATION_LANDSCAPE
                var actualRequest = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                try {
                    await("The rotate control did not request orientation $requested") {
                        actualRequest = activity.requestedOrientation
                        actualRequest == requested
                    }
                } catch (failure: AssertionError) {
                    throw AssertionError("Rotation requested $actualRequest, expected $requested", failure)
                }
                await("Android did not apply orientation $orientation after accepting the request") {
                    activity.resources.configuration.orientation == orientation
                }
                main {
                    assertSame(activity, resumedActivity<MainActivity>())
                    assertEquals(token, service.sessionSnapshot()?.token)
                    assertEquals(location, service.videoLocation.value)
                    assertFalse(model(activity).videoPlaying.value)
                }
            }
        } finally {
            main { service.stopVideoPlayback() }
            finishActivities()
        }
    }

    @Test fun miniAudioPlayerTracksMetadataAndReturnsFromToolsToTheOverview() {
        val activity = launch()
        val tools = context.getString(R.string.tab_tools)
        val unknown = context.getString(R.string.unknown_track)
        val rewind = context.getString(R.string.cd_rewind)
        val title = "Mini player contract track"
        val artist = "Mini player contract artist"
        val album = "Mini player contract album"

        fun tap(label: String) {
            lateinit var bounds: Rect
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (true) {
                val candidate = nodeBounds(label)
                if (candidate != null) { bounds = candidate; break }
                assertTrue("Control not visible: $label", SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(30)
            }
            pointerGesture(bounds.exactCenterX(), bounds.exactCenterY(), bounds.exactCenterY())
        }

        fun item(tag: String, data: ByteArray) = ByteBuffer.allocate(8 + data.size)
            .put(tag.toByteArray(Charsets.US_ASCII)).putInt(data.size).put(data).array()

        start(activity)
        try {
            main {
                service.onConnectionInit()
                service.onAudioFormat(2, 352, false)
            }
            awaitVolumeControl(R.string.cd_rewind, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { it.isVisibleToUser }
            tap(tools)
            // The overview's rewind action is absent while the Tools tab shows the mini player.
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (nodeBounds(unknown) == null || nodeBounds(rewind) != null) {
                assertTrue("Tools did not show the mini player with an unknown title",
                    SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(30)
            }

            val cover = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val art = try {
                cover.eraseColor(0xFF13A56A.toInt())
                ByteArrayOutputStream().use { stream ->
                    assertTrue(cover.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    stream.toByteArray()
                }
            } finally { cover.recycle() }
            main {
                val track = item("minm", title.toByteArray()) + item("asar", artist.toByteArray()) + item("asal", album.toByteArray())
                service.onMetadata(item("mlit", track))
                service.onCoverArt(art)
            }
            tap(title)
            awaitVolumeControl(R.string.cd_rewind, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { it.isVisibleToUser }
            awaitVolumeControl(R.string.cd_cover_art, scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { it.isVisibleToUser }
            for (label in listOf(title, artist, album)) {
                awaitAudioNode(label, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { node ->
                    node.isVisibleToUser && node.text?.toString() == label
                }
            }
        } finally {
            main { if (service.connectionCount.value > 0) service.onConnectionDestroy() }
            finishActivities()
        }
    }

    @Test fun audioScanButtonsResumeTheSenderWhenTouchEndsOrCancels() {
        val activity = launch()
        val field = AirPlayService::class.java.getDeclaredField("dacpController").apply { isAccessible = true }
        start(activity)
        val original = service.dacpController
        DacpHttpContractTest.Sender().use { sender ->
            val dacp = DacpController { _, listener ->
                listener.onServiceResolved(NsdServiceInfo().apply {
                    host = InetAddress.getByName("127.0.0.1")
                    port = sender.port
                })
            }
            fun commands() = sender.requests.map { it.first().split(' ')[1].substringAfterLast('/') }
            try {
                main {
                    field.set(service, dacp)
                    service.onConnectionInit()
                    service.onAudioFormat(2, 352, false)
                    service.onDacpId("touch-scan-fixture", "touch-scan-contract")
                }
                await("The audio screen did not bind to the sender") { model(activity).clientVolumeAdjustable.value }

                fun hold(label: Int, expected: List<String>, completion: Int) {
                    val bounds = Rect()
                    var stableSince = 0L
                    awaitVolumeControl(label, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { node ->
                        val current = Rect().also(node::getBoundsInScreen)
                        if (!node.isVisibleToUser || current.isEmpty || current != bounds) {
                            bounds.set(current)
                            stableSince = SystemClock.elapsedRealtime()
                            return@awaitVolumeControl false
                        }
                        SystemClock.elapsedRealtime() - stableSince >= 150L
                    }
                    val started = SystemClock.uptimeMillis()
                    fun dispatch(action: Int) {
                        val event = fingerEvent(started, action, bounds.exactCenterX(), bounds.exactCenterY())
                        try { sendTouch(event) } finally { event.recycle() }
                    }
                    dispatch(MotionEvent.ACTION_DOWN)
                    try {
                        awaitAudioGesture("audio-scan", activity, bounds) {
                            await("Press did not send the scan command before release") { commands() == expected }
                        }
                    } finally {
                        dispatch(completion)
                    }
                    await("End of the touch did not resume playback") { commands() == expected + "playresume" }
                }

                hold(R.string.cd_fast_forward, listOf("beginff"), MotionEvent.ACTION_UP)
                hold(R.string.cd_rewind, listOf("beginff", "playresume", "beginrew"), MotionEvent.ACTION_CANCEL)
                assertTrue(sender.requests.all { request ->
                    request.any { it.equals("Active-Remote: touch-scan-contract", ignoreCase = true) }
                })
            } finally {
                main {
                    field.set(service, original)
                    dacp.release()
                    if (service.connectionCount.value > 0) service.onConnectionDestroy()
                }
                finishActivities()
            }
        }
    }

    private fun sendTouch(event: MotionEvent) {
        instrumentation.sendPointerSync(event)
    }

    private fun fingerEvent(start: Long, action: Int, x: Float, y: Float): MotionEvent {
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val position = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        return MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, 1, arrayOf(pointer), arrayOf(position),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun awaitAudioGesture(name: String, activity: MainActivity, bounds: Rect, wait: () -> Unit) {
        try {
            wait()
        } catch (failure: AssertionError) {
            val visible = Rect()
            var windowFocused = false
            main {
                activity.window.decorView.getWindowVisibleDisplayFrame(visible)
                windowFocused = activity.hasWindowFocus()
            }
            saveContractScreenshot("main-contract-$name.png")
            throw AssertionError("${failure.message}; control=$bounds, window=$visible, focused=$windowFocused", failure)
        }
    }

    private fun pointerGesture(startX: Float, startY: Float, endY: Float, endX: Float = startX) {
        val time = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val event = fingerEvent(time, action, x, y)
            try { sendTouch(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, startX, startY)
        if (startX != endX || startY != endY) {
            for (step in 1..5) {
                Thread.sleep(16)
                send(MotionEvent.ACTION_MOVE,
                    startX + (endX - startX) * step / 5, startY + (endY - startY) * step / 5)
            }
            // Keep the finger still before release so the scroll does not fling.
            Thread.sleep(120)
            send(MotionEvent.ACTION_MOVE, endX, endY)
        }
        send(MotionEvent.ACTION_UP, endX, endY)
    }

    private fun awaitVolumeControl(
        label: Int,
        scrollAction: Int? = null,
        action: (AccessibilityNodeInfo) -> Boolean,
    ) {
        val description = context.getString(label)
        awaitAudioNode(description, scrollAction) { node ->
            node.contentDescription?.toString() == description && action(node)
        }
    }

    private fun awaitAudioNode(label: String, scrollAction: Int?, matches: (AccessibilityNodeInfo) -> Boolean) {
        val automation = instrumentation.uiAutomation
        val visibleControls = linkedSetOf<String>()
        val windowPackages = linkedSetOf<String>()
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (!node.refresh()) return false
            val nodeDescription = node.contentDescription?.toString()
            if (!nodeDescription.isNullOrBlank()) {
                val bounds = Rect().also(node::getBoundsInScreen)
                visibleControls += "$nodeDescription bounds=$bounds enabled=${node.isEnabled} " +
                    "focusable=${node.isFocusable} focused=${node.isFocused} actions=${node.actionList.map { it.id }}"
            }
            if (matches(node)) return true
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { if (visit(child)) return true } finally { child.recycle() }
            }
            return false
        }
        fun scroll(node: AccessibilityNodeInfo, action: Int): Boolean {
            if (!node.refresh()) return false
            if (node.isVisibleToUser && node.isScrollable && node.performAction(action)) return true
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { if (scroll(child, action)) return true } finally { child.recycle() }
            }
            return false
        }
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + 15000
            var nextScroll = SystemClock.elapsedRealtime() + 1000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
                val roots = automation.windows.mapNotNull { window ->
                    try { window.root } finally { window.recycle() }
                }.toMutableList()
                if (roots.none { it.packageName?.toString() == context.packageName }) {
                    automation.rootInActiveWindow?.let(roots::add)
                }
                try {
                    val appRoots = roots.filter {
                        val owner = it.packageName?.toString().orEmpty()
                        windowPackages += owner
                        owner == context.packageName
                    }
                    if (appRoots.any(::visit)) return
                    val now = SystemClock.elapsedRealtime()
                    if (scrollAction != null && now >= nextScroll) {
                        appRoots.any { scroll(it, scrollAction) }
                        nextScroll = now + 400
                    }
                } finally { roots.forEach { it.recycle() } }
                Thread.sleep(50)
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
        saveContractScreenshot("main-contract-failure.png")
        fail("Control did not expose its expected state: $label. Windows: $windowPackages. Visible controls: $visibleControls")
    }

    private fun saveContractScreenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            try {
                File(context.filesDir, name).outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { image.recycle() }
        }
    }
}
