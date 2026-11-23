package io.github.jqssun.airplay

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.content.ServiceConnection
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.buzincast.core.AsyncLogStore
import io.github.jqssun.airplay.media.MirrorCapture
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

/** Bound-service recording actions, real files/provider and captured external intents. */
@RunWith(AndroidJUnit4::class)
class RecordingViewModelContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var service: AirPlayService
    private lateinit var connection: ServiceConnection
    private lateinit var model: MainViewModel
    private lateinit var store: ViewModelStore
    private lateinit var root: File
    private var bound = false
    private val owned = mutableListOf<File>()
    private val launches = mutableListOf<Intent>()
    private var rejectLaunch = false
    private lateinit var prefix: String
    private var originalLaunch: Boolean? = null
    private var launchPresent = false
    private val preferences get() = context.getSharedPreferences("recording-model-contract", 0)
    private class TestApplication(base: Context) : Application() { init { attachBaseContext(base) } }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            main { ready = predicate() }
            if (ready) return
            Thread.sleep(20)
        }
        fail(message)
    }
    private fun logStore() = MainViewModel::class.java.getDeclaredField("logStore")
        .apply { isAccessible = true }.get(model) as AsyncLogStore

    @Before fun bindStoppedService() {
        // Cache the application's provider roots before overriding filesDir for log isolation.
        val authority = "${context.packageName}.fileprovider"
        FileProvider.getUriForFile(context, authority, File(context.filesDir, "recordings/provider-root-check.mkv"))
        prefix = "recording-model-${System.nanoTime()}"
        root = File(context.cacheDir, prefix).apply { assertTrue(mkdirs()) }
        assertTrue(preferences.edit().clear().commit())
        val shared = context.getSharedPreferences(Prefs.NAME, 0)
        launchPresent = shared.contains(Prefs.LAUNCH_ON_CONNECT.key)
        originalLaunch = Prefs.LAUNCH_ON_CONNECT.read(shared)
        assertTrue(shared.edit().putBoolean(Prefs.LAUNCH_ON_CONNECT.key, false).commit())
        val connected = CountDownLatch(1)
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = requireNotNull((binder as? AirPlayService.LocalBinder)?.service)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        bound = context.bindService(Intent(context, AirPlayService::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue(bound)
        assertTrue("Service binder did not arrive", connected.await(15, TimeUnit.SECONDS))
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getCacheDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = preferences
            override fun startActivity(intent: Intent) {
                if (rejectLaunch) throw ActivityNotFoundException("Contract sharing target unavailable")
                launches += Intent(intent)
            }
        }
        main {
            assertEquals(AirPlayService.ServerState.STOPPED, service.serverState.value)
            model = MainViewModel(TestApplication(isolated))
            store = ViewModelStore().apply { put("recording", model) }
            model.bindService(service)
        }
        service.capture.directory.mkdirs()
    }

    private fun file(suffix: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): File =
        File(service.capture.directory, "$prefix-$suffix").also { file ->
            assertFalse(file.exists())
            owned += file
            file.writeBytes(bytes)
        }
    private fun ourRecordings() = model.recordings.value.filter { it.name.startsWith(prefix) }

    @After fun cleanup() {
        try {
            if (::service.isInitialized) service.capture.close()
            if (::model.isInitialized) {
                val logs = logStore()
                assertTrue(logs.flush())
                main { model.unbindService(); store.clear() }
                assertTrue("ViewModel log worker survived clearing", logs.awaitClosed())
            }
        } finally {
            if (bound) {
                context.unbindService(connection)
                context.stopService(Intent(context, AirPlayService::class.java))
                bound = false
                await("Service did not reach DESTROYED") { service.lifecycle.currentState == Lifecycle.State.DESTROYED }
            }
            owned.asReversed().forEach { if (it.exists()) assertTrue("Could not remove owned fixture ${it.name}", it.delete()) }
            originalLaunch?.let { previous ->
                val editor = context.getSharedPreferences(Prefs.NAME, 0).edit()
                if (launchPresent) editor.putBoolean(Prefs.LAUNCH_ON_CONNECT.key, previous)
                else editor.remove(Prefs.LAUNCH_ON_CONNECT.key)
                assertTrue(editor.commit())
            }
            assertTrue(preferences.edit().clear().commit())
            if (::root.isInitialized) assertTrue(root.deleteRecursively())
            if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
        }
    }

    @Test fun recordingListingUsesOnlyRegularFilesAndKeepsNewestFirst() {
        val old = file("old.mkv")
        val recent = file("recent.mkv", ByteArray(7) { it.toByte() })
        file("notes.txt")
        val folder = File(service.capture.directory, "$prefix-directory.mkv")
        assertTrue(folder.mkdir()); owned += folder
        assertTrue(old.setLastModified(1_000_000))
        assertTrue(recent.setLastModified(2_000_000))
        main { model.refreshRecordings() }
        val expected = if (BuildConfig.RECORDING_ENABLED) {
            listOf(recent.name to 7L, old.name to 3L)
        } else {
            emptyList()
        }
        assertEquals(expected, ourRecordings().map { it.name to it.bytes })
    }

    @Test fun listingClearsPreviousEntriesWhenDetachedOrStorageDisappears() {
        val captureContext = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?) = root
        }
        val fixture = MirrorCapture(captureContext) {}
        val capture = AirPlayService::class.java.getDeclaredField("capture\$delegate").apply { isAccessible = true }
        val original = capture.get(service)
        try {
            main {
                capture.set(service, lazyOf(fixture))
                assertFalse(fixture.directory.exists())
                model.refreshRecordings()
                assertTrue(model.recordings.value.isEmpty())

                assertTrue(fixture.directory.mkdir())
                val sample = File(fixture.directory, "local.mkv")
                sample.writeBytes(byteArrayOf(1, 2, 3))
                model.refreshRecordings()
                val expected = if (BuildConfig.RECORDING_ENABLED) listOf(sample.name) else emptyList()
                assertEquals(expected, model.recordings.value.map { it.name })

                model.unbindService()
                model.refreshRecordings()
                assertTrue(model.recordings.value.isEmpty())

                model.bindService(service)
                model.refreshRecordings()
                assertEquals(expected, model.recordings.value.map { it.name })
                assertTrue(sample.delete())
                assertTrue(fixture.directory.delete())
                model.refreshRecordings()
                assertTrue(model.recordings.value.isEmpty())
            }
        } finally {
            main { capture.set(service, original) }
        }
    }

    @Test fun recordingActionsRejectTraversalWrongExtensionsDirectoriesAndMissingFiles() {
        val recording = file("valid.mkv")
        val wrong = file("notes.txt")
        val outside = File(service.capture.directory.parentFile, "$prefix-outside.mkv")
        assertFalse(outside.exists()); owned += outside; outside.writeText("Keep this sibling")
        val folder = File(service.capture.directory, "$prefix-directory.mkv")
        assertTrue(folder.mkdir()); owned += folder
        val original = outside.readBytes()
        main {
            for (name in listOf("../${outside.name}", wrong.name, folder.name, "$prefix-missing.mkv")) {
                model.deleteRecording(name)
                model.shareRecording(name)
                model.playRecording(name)
            }
            assertFalse(service.videoPlaybackActive.value)
            assertTrue(launches.isEmpty())
            model.deleteRecording(recording.name)
        }
        assertArrayEquals(original, outside.readBytes())
        assertTrue(wrong.exists()); assertTrue(folder.isDirectory)
        assertEquals(!BuildConfig.RECORDING_ENABLED, recording.exists())
        assertFalse(ourRecordings().any { it.name == recording.name })
    }

    @Test fun sharingUsesReadableContentUriAndReportsAnUnavailableSharingTarget() {
        val payload = byteArrayOf(7, 9, 11, 13)
        val recording = file("share.mkv", payload)
        main { model.shareRecording(recording.name) }
        if (!BuildConfig.RECORDING_ENABLED) {
            assertTrue(launches.isEmpty()); assertTrue(recording.exists()); return
        }
        val chooser = launches.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        @Suppress("DEPRECATION")
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("video/x-matroska", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        assertArrayEquals(payload, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        main { rejectLaunch = true; model.shareRecording(recording.name) }
        assertTrue(logStore().flush())
        assertTrue(model.logs.value.any { "Nenhum aplicativo para compartilhar. Arquivo: ${recording.name}" in it })
        assertEquals(1, launches.size)
        assertArrayEquals(payload, recording.readBytes())
    }

    @Test fun armedCaptureDefersRefreshAndDeletionUntilTheCaptureStops() {
        val first = file("first.mkv")
        main { model.refreshRecordings(); model.toggleRecording(); model.updateFromService() }
        if (!BuildConfig.RECORDING_ENABLED) {
            assertFalse(service.capture.active()); assertFalse(model.captureState.value.armed)
            main { model.deleteRecording(first.name) }
            assertTrue(first.exists()); return
        }
        assertTrue(service.capture.active()); assertTrue(model.captureState.value.armed)
        val before = model.recordings.value
        val second = file("second.mkv")
        main { model.refreshRecordings(); model.deleteRecording(first.name) }
        assertEquals(before, model.recordings.value)
        assertTrue(first.exists())
        main { model.toggleRecording() }
        service.capture.close()
        main { model.updateFromService(); model.refreshRecordings(); model.deleteRecording(first.name) }
        assertFalse(model.captureState.value.armed)
        assertFalse(first.exists())
        assertTrue(ourRecordings().any { it.name == second.name })
    }

    @Test fun localPlaybackUsesAValidatedRecordingAndDisconnectedActionsStayHarmless() {
        val bytes = instrumentation.context.assets.open("download/sample.mp4").use { it.readBytes() }
        val recording = file("local.mkv", bytes)
        main {
            model.playRecording(recording.name)
            assertEquals(BuildConfig.RECORDING_ENABLED, service.videoPlaybackActive.value)
            if (BuildConfig.RECORDING_ENABLED) {
                val location = Uri.parse(service.videoLocation.value)
                assertEquals("content", location.scheme)
                assertArrayEquals(bytes, context.contentResolver.openInputStream(location)!!.use { it.readBytes() })
            }
            model.unbindService()
            model.refreshRecordings()
            model.shareRecording(recording.name); model.playRecording(recording.name)
            model.deleteRecording(recording.name); model.toggleRecording()
            model.listenRelay(); model.stopRelay(); model.connectRelay(" 127.0.0.1 ", 1, "unused")
            assertTrue(model.recordings.value.isEmpty())
            assertTrue(launches.isEmpty())
        }
        assertTrue(recording.exists())
    }
}
