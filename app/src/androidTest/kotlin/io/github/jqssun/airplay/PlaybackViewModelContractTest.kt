package io.github.jqssun.airplay

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.buzincast.core.AsyncLogStore
import io.github.jqssun.airplay.media.ConnectionApproval
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Disconnected-service UI state, real coroutine timers and isolated persistence. */
@RunWith(AndroidJUnit4::class)
class PlaybackViewModelContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var root: File
    private lateinit var model: MainViewModel
    private lateinit var store: ViewModelStore
    private val preferences get() = context.getSharedPreferences("playback-model-contract", Context.MODE_PRIVATE)
    private class IsolatedApplication(base: Context) : Application() { init { attachBaseContext(base) } }

    @Before fun setup() {
        root = File(context.cacheDir, "playback-model-${System.nanoTime()}").apply { mkdirs() }
        preferences.edit().clear().commit()
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getCacheDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = preferences
        }
        instrumentation.runOnMainSync {
            model = MainViewModel(IsolatedApplication(isolated))
            store = ViewModelStore().apply { put("playback", model) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = MainViewModel::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(model) as T
    private fun <T> state(name: String, value: T) { field<MutableStateFlow<T>>(name).value = value }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(20)
        }
        fail(message)
    }

    @After fun cleanup() {
        val logs = field<AsyncLogStore>("logStore")
        logs.flush()
        instrumentation.runOnMainSync { store.clear() }
        assertTrue("ViewModel kept its log worker alive after clearing", logs.awaitClosed())
        preferences.edit().clear().commit()
        root.deleteRecursively()
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Test fun pendingMediaCannotTogglePlaybackButActiveMediaUpdatesOptimistically() {
        instrumentation.runOnMainSync {
            assertFalse(model.videoPlaybackActive.value)
            assertTrue(model.videoPlaying.value)
            model.setVideoPlaying(false)
            model.toggleVideoPlayPause()
            assertTrue(model.videoPlaying.value)
            assertEquals(0L, model.videoOverlayTick.value)
            state("_videoPlaybackActive", true)
            model.setVideoPlaying(false)
            assertFalse(model.videoPlaying.value)
            assertEquals(1L, model.videoOverlayTick.value)
            model.toggleVideoPlayPause(showOverlay = false)
            assertTrue(model.videoPlaying.value)
            assertEquals("Hidden controls reopened after a gesture", 1L, model.videoOverlayTick.value)
            model.toggleVideoPlayPause()
            assertFalse(model.videoPlaying.value)
            assertEquals(2L, model.videoOverlayTick.value)
            model.stopVideoPlayback()
        }
    }

    @Test fun playbackSpeedClampsAndSilenceSettingsRemainAvailableWhileDisconnected() {
        instrumentation.runOnMainSync {
            for ((requested, expected) in listOf(-1f to 0.2f, 0.2f to 0.2f, 1.25f to 1.25f,
                4f to 4f, 100f to 4f, Float.NEGATIVE_INFINITY to 0.2f, Float.POSITIVE_INFINITY to 4f)) {
                model.setVideoSpeed(requested)
                assertEquals(expected, model.videoSpeed.value, 0f)
            }
            model.setVideoSkipSilence(true)
            assertTrue(model.videoSkipSilence.value)
            model.setVideoSkipSilence(false)
            assertFalse(model.videoSkipSilence.value)
            model.toggleVideoDownload()
            assertNull(model.videoDownloadProgress.value)
            assertEquals("Unavailable download opened controls", 0L, model.videoOverlayTick.value)
        }
    }

    @Test fun scrubClampsItsTargetAndClearsItOncePlaybackCatchesUp() {
        instrumentation.runOnMainSync {
            model.scrubVideoTo(5000)
            model.endVideoScrub()
            assertNull(model.videoScrubPositionMs.value)
            state("_videoDurationMs", 100_000L)
            state("_videoPositionMs", 10_000L)
            model.startVideoScrub()
            model.scrubVideoTo(-1)
            assertEquals(0L, model.videoScrubPositionMs.value)
            model.scrubVideoTo(200_000)
            assertEquals(100_000L, model.videoScrubPositionMs.value)
            model.endVideoScrub()
            assertEquals("Target disappeared before player progress arrived", 100_000L, model.videoScrubPositionMs.value)
            state("_videoPositionMs", 99_000L)
        }
        await("Scrub target did not clear after playback caught up") { model.videoScrubPositionMs.value == null }
    }

    @Test fun scrubEventuallyClearsItsTargetEvenWhenThePlayerNeverCatchesUp() {
        val started = SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync {
            state("_videoDurationMs", 100_000L)
            state("_videoPositionMs", 0L)
            model.startVideoScrub()
            model.scrubVideoTo(80_000)
            model.endVideoScrub()
            assertEquals(80_000L, model.videoScrubPositionMs.value)
        }
        await("Unsettled scrub target never expired") { model.videoScrubPositionMs.value == null }
        assertTrue("Unsettled scrub target was cleared before its timeout",
            SystemClock.elapsedRealtime() - started >= 3000)
    }

    @Test fun aNewScrubCancelsThePreviousSettleJobAndKeepsItsOwnTarget() {
        instrumentation.runOnMainSync {
            state("_videoDurationMs", 100_000L)
            model.scrubVideoTo(80_000)
            model.endVideoScrub()
            val previous = field<Job>("_scrubJob")
            model.startVideoScrub()
            assertTrue("Previous settle job remained active during a new drag", previous.isCancelled)
            model.scrubVideoTo(60_000)
            assertEquals(60_000L, model.videoScrubPositionMs.value)
            state("_videoPositionMs", 60_000L)
            model.endVideoScrub()
            assertNotSame(previous, field<Job>("_scrubJob"))
        }
        await("New scrub did not settle independently") { model.videoScrubPositionMs.value == null }
    }

    @Test fun preferenceFlowsAreStableAndObserveBothModelAndExternalWrites() {
        lateinit var autoStart: StateFlow<Boolean>
        lateinit var resolution: StateFlow<String>
        instrumentation.runOnMainSync {
            autoStart = model.pref(Prefs.AUTO_START)
            resolution = model.pref(Prefs.RESOLUTION)
            assertSame(autoStart, model.pref(Prefs.AUTO_START))
            assertTrue(autoStart.value)
            model.set(Prefs.AUTO_START, false)
            model.set(Prefs.RESOLUTION, "1280x720")
        }
        await("ViewModel preference writes did not reach their flows") { !autoStart.value && resolution.value == "1280x720" }
        preferences.edit().putBoolean(Prefs.AUTO_START.key, true).commit()
        await("External preference write did not reach the cached flow") { autoStart.value }
        instrumentation.runOnMainSync { model.set(Prefs.RESOLUTION, "invalid-resolution") }
        assertEquals("1280x720", resolution.value)
        assertEquals("1280x720", preferences.getString(Prefs.RESOLUTION.key, null))
    }

    @Test fun asynchronousLogsPersistInOrderAndClearBothMemoryAndStorage() {
        model.addLog("playback-one")
        model.addLog("playback-two")
        await("ViewModel logs were not published") { model.logs.value.size == 2 }
        val lines = model.logs.value
        assertTrue(lines[0].matches(Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3} playback-one")))
        assertTrue(lines[1].endsWith("playback-two"))
        assertTrue(field<AsyncLogStore>("logStore").flush())
        assertEquals(lines, File(root, "airplay_logs.txt").readLines())
        model.clearLogs()
        await("Cleared log entries remained visible") { model.logs.value.isEmpty() }
        assertTrue(field<AsyncLogStore>("logStore").flush())
        assertTrue(File(root, "airplay_logs.txt").readText().isEmpty())
    }

    @Test fun aNewDefaultProfileCancelsAnOlderProbeAndRestoresAllTuningDefaults() {
        lateinit var previous: Job
        lateinit var latest: Job
        instrumentation.runOnMainSync {
            model.set(Prefs.RESOLUTION, "1280x720")
            model.set(Prefs.MAX_FPS, 60)
            model.set(Prefs.H265, Prefs.ON)
            model.set(Prefs.LOW_LATENCY, true)
            model.set(Prefs.AUDIO_AUTO_BUFFER, false)
            model.set(Prefs.AUDIO_CUSHION_MS, 750)
            model.set(Prefs.AUDIO_ADAPTIVE_STEP, 0)
            model.set(Prefs.SYNC_CLIENT_VOLUME, false)
            model.applyProfile("Cinema")
            previous = field("profileJob")
            model.applyProfile(Prefs.DEFAULT_PROFILE)
            latest = field("profileJob")
            assertNotSame(previous, latest)
            assertTrue("Superseded device probe was not cancelled", previous.isCancelled)
        }
        await("Profile jobs did not finish after replacement") { previous.isCompleted && latest.isCompleted }
        assertEquals(Prefs.DEFAULT_PROFILE, Prefs.EXPERIENCE_PROFILE.read(preferences))
        assertEquals(Prefs.RESOLUTION.def, Prefs.RESOLUTION.read(preferences))
        assertEquals(Prefs.MAX_FPS.def, Prefs.MAX_FPS.read(preferences))
        assertEquals(Prefs.H265.def, Prefs.H265.read(preferences))
        assertEquals(Prefs.LOW_LATENCY.def, Prefs.LOW_LATENCY.read(preferences))
        assertEquals(Prefs.AUDIO_AUTO_BUFFER.def, Prefs.AUDIO_AUTO_BUFFER.read(preferences))
        assertEquals(Prefs.AUDIO_CUSHION_MS.def, Prefs.AUDIO_CUSHION_MS.read(preferences))
        assertEquals(Prefs.AUDIO_ADAPTIVE_STEP.def, Prefs.AUDIO_ADAPTIVE_STEP.read(preferences))
        assertEquals(Prefs.SYNC_CLIENT_VOLUME.def, Prefs.SYNC_CLIENT_VOLUME.read(preferences))
        assertTrue(field<AsyncLogStore>("logStore").flush())
        assertTrue("Cancellation was reported as a profile error", model.logs.value.isEmpty())
    }

    @Test fun anInvalidProfilePreservesPreferencesAndDoesNotPreventTheNextRequest() {
        lateinit var failed: Job
        instrumentation.runOnMainSync {
            model.set(Prefs.RESOLUTION, "1280x720")
            model.set(Prefs.MAX_FPS, 45)
        }
        val before = preferences.all.toMap()
        instrumentation.runOnMainSync {
            model.applyProfile("not-a-profile")
            failed = field("profileJob")
        }
        await("Invalid profile did not report its failure") {
            failed.isCompleted && model.logs.value.any { "Profile unavailable:" in it }
        }
        assertEquals("Invalid profile partially changed tuning", before, preferences.all)
        instrumentation.runOnMainSync { model.applyProfile(Prefs.DEFAULT_PROFILE) }
        await("Valid profile did not recover after an invalid request") {
            field<Job>("profileJob").isCompleted &&
                Prefs.EXPERIENCE_PROFILE.read(preferences) == Prefs.DEFAULT_PROFILE &&
                Prefs.RESOLUTION.read(preferences) == Prefs.RESOLUTION.def
        }
        assertEquals(Prefs.MAX_FPS.def, Prefs.MAX_FPS.read(preferences))
        assertTrue(field<AsyncLogStore>("logStore").flush())
        assertEquals(1, model.logs.value.count { "Profile unavailable:" in it })
    }

    @Test fun detachedServiceMessagesClearAndLocalPinAndPermissionStatesRemainUsable() {
        instrumentation.runOnMainSync {
            model.subtitleText.value = "Old subtitle"
            model.playbackMessage.value = "Old playback message"
            model.pendingApproval.value = ConnectionApproval.Request(1, "fixture", "127.0.0.1")
            model.unbindService()
            model.subtitle(Uri.parse("file:///unused-subtitle.srt"))
            model.subtitle(null)
            model.answerApproval(1, true)
            model.updateFromService()
            assertEquals("", model.subtitleText.value)
            assertEquals("", model.playbackMessage.value)
            assertNull(model.pendingApproval.value)
            assertNull(model.dacpPlayer)
            model.audioScanBegin(true); model.audioScanBegin(false); model.audioScanEnd()
            model.showPin("1234")
            assertEquals("1234", model.pinCode.value)
            model.dismissPin()
            assertNull(model.pinCode.value)
            model.showPin(null)
            assertNull(model.pinCode.value)
            for (permission in LocalNetworkAccess.entries) {
                model.setLocalNetwork(permission)
                assertEquals(permission, model.localNetwork.value)
            }
        }
    }
}
