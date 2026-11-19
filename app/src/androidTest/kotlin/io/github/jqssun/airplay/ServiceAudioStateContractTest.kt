package io.github.jqssun.airplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.service.AirPlayService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real bound service and MediaSession contracts without starting a receiver. */
@RunWith(AndroidJUnit4::class)
class ServiceAudioStateContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var service: AirPlayService
    private lateinit var controller: MediaControllerCompat
    private lateinit var connection: ServiceConnection
    private var binding = false
    private var originalApproval: Boolean? = null
    private var originalApprovalPresent = false
    private val publishedPlayback = AtomicReference<PlaybackStateCompat?>()
    private val callback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            publishedPlayback.set(state)
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun playback() = requireNotNull(publishedPlayback.get()) { "No published playback state" }
    private fun metadata() = requireNotNull(controller.metadata) { "No published metadata" }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail(message)
    }

    @Before fun bindStoppedService() {
        val prefs = context.getSharedPreferences(Prefs.NAME, 0)
        originalApprovalPresent = prefs.contains(Prefs.APPROVE_CONNECTIONS.key)
        originalApproval = Prefs.APPROVE_CONNECTIONS.read(prefs)
        val connected = CountDownLatch(1)
        val value = AtomicReference<AirPlayService?>()
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                value.set((binder as? AirPlayService.LocalBinder)?.service)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        binding = context.bindService(Intent(context, AirPlayService::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue("Service binding rejected", binding)
        assertTrue("Service binder did not arrive", connected.await(15, TimeUnit.SECONDS))
        service = requireNotNull(value.get()) { "Unexpected service binder" }
        main {
            assertEquals(AirPlayService.ServerState.STOPPED, service.serverState.value)
            val field = AirPlayService::class.java.getDeclaredField("mediaSession").apply { isAccessible = true }
            val session = requireNotNull(field.get(service) as? MediaSessionCompat)
            controller = MediaControllerCompat(context, session.sessionToken)
            controller.registerCallback(callback, Handler(Looper.getMainLooper()))
        }
    }

    @After fun destroyServiceAndRestore() {
        try {
            if (::service.isInitialized) main {
                if (::controller.isInitialized) controller.unregisterCallback(callback)
                service.pinCallback = null
                assertEquals("Audio-state contracts started a receiver", AirPlayService.ServerState.STOPPED,
                    service.serverState.value)
            }
        } finally {
            if (binding) {
                context.unbindService(connection)
                context.stopService(Intent(context, AirPlayService::class.java))
                binding = false
            }
            if (::service.isInitialized) await("Bound service did not reach DESTROYED") {
                var destroyed = false
                main { destroyed = service.lifecycle.currentState == Lifecycle.State.DESTROYED }
                destroyed
            }
            originalApproval?.let {
                val prefs = context.getSharedPreferences(Prefs.NAME, 0)
                val editor = prefs.edit()
                if (originalApprovalPresent) editor.putBoolean(Prefs.APPROVE_CONNECTIONS.key, it)
                else editor.remove(Prefs.APPROVE_CONNECTIONS.key)
                assertTrue("Preference restoration did not flush", editor.commit())
            }
            if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
        }
    }

    @Test fun pauseFreezesExtrapolatedPositionAndResumeContinuesFromIt() {
        main { service.onProgress(0, 44100, 44100 * 60L) }
        await("Position did not extrapolate from the sender report") { service.currentPositionMs() >= 1300 }
        var frozen = -1L
        main {
            val started = SystemClock.elapsedRealtime()
            val before = service.currentPositionMs()
            service.onAudioTeardown()
            val finished = SystemClock.elapsedRealtime()
            frozen = service.currentPositionMs()
            assertFalse(service.playing.value)
            assertTrue("Pause rewound from $before to $frozen", frozen >= before)
            assertTrue("Pause moved beyond elapsed time", frozen <= before + finished - started + 1)
            assertEquals(frozen, service.positionMs.value)
        }
        await("Pause did not reach the real media session") {
            publishedPlayback.get()?.let { it.state == PlaybackStateCompat.STATE_PAUSED && it.position == frozen } == true
        }
        Thread.sleep(150)
        assertEquals("Paused position continued advancing", frozen, service.currentPositionMs())
        main { service.togglePlayPause() }
        assertTrue(service.playing.value)
        await("Resume did not continue from frozen position") { service.currentPositionMs() >= frozen + 150 }
    }

    @Test fun repeatedRemotePlayDoesNotRewindPositionOrItsPublishedAnchor() {
        main { service.onProgress(0, 44100, 44100 * 60L) }
        await("Initial progress was not published") {
            publishedPlayback.get()?.let { it.state == PlaybackStateCompat.STATE_PLAYING && it.position == 1000L } == true
        }
        await("Position did not advance") { service.currentPositionMs() >= 1300 }
        var before = -1L
        var updated = -1L
        main {
            before = service.currentPositionMs()
            updated = playback().lastPositionUpdateTime
            controller.transportControls.play()
        }
        await("Remote play did not publish a new state") {
            publishedPlayback.get()?.let { it.lastPositionUpdateTime > updated } == true
        }
        assertTrue(service.playing.value)
        assertEquals(PlaybackStateCompat.STATE_PLAYING, playback().state)
        assertTrue("Remote play published a stale position", playback().position >= before)
        assertTrue("Remote play rewound the extrapolation", service.currentPositionMs() >= before)
    }

    @Test fun degenerateProgressKeepsPausedStateAndCurrentPositionClampsToDuration() {
        main {
            service.onProgress(44100, 0, 44100 * 3L)
            assertEquals(0L, service.positionMs.value)
            assertEquals(2000L, service.durationMs.value)
            service.onProgress(0, 44100 * 9L, 44100 * 2L)
            assertEquals(2000L, service.currentPositionMs())
            service.onAudioTeardown()
            val position = service.positionMs.value
            val duration = service.durationMs.value
            assertTrue("Pausing moved position outside the duration", position in 0..duration)
            service.onProgress(100, 200, 100)
            service.onProgress(100, 200, 0)
            assertFalse("Degenerate progress resumed playback", service.playing.value)
            assertEquals(position, service.positionMs.value)
            assertEquals(duration, service.durationMs.value)
        }
    }

    private fun item(tag: String, payload: ByteArray): ByteArray =
        tag.toByteArray(Charsets.US_ASCII) + ByteBuffer.allocate(4).putInt(payload.size).array() + payload
    private fun text(tag: String, value: String) = item(tag, value.toByteArray(Charsets.UTF_8))

    @Test fun metadataAndLosslessArtReachTheRealMediaSessionAndSurvivePartialMetadata() {
        val data = item("mlit", text("minm", "First title") + text("asar", "Artist") +
            text("asal", "Album") + text("asgn", "Genre") + item("astm", ByteBuffer.allocate(4).putInt(9000).array()))
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val color = Color.rgb(22, 180, 70)
        val png = try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            }
        } finally { bitmap.recycle() }
        lateinit var cover: Bitmap
        main {
            service.onMetadata(data)
            service.onCoverArt(png)
            assertEquals("First title", service.trackInfo.value.title)
            assertEquals("Artist", service.trackInfo.value.artist)
            assertEquals("Album", service.trackInfo.value.album)
            assertEquals("Genre", service.trackInfo.value.genre)
            assertEquals(9000L, service.durationMs.value)
            cover = requireNotNull(service.trackInfo.value.coverArt)
            assertEquals(color, cover.getPixel(4, 4))
        }
        await("Metadata/artwork were not published to the media session") {
            controller.metadata?.let {
                it.getString(MediaMetadataCompat.METADATA_KEY_TITLE) == "First title" &&
                    it.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART) != null
            } == true
        }
        assertEquals(9000L, metadata().getLong(MediaMetadataCompat.METADATA_KEY_DURATION))
        assertEquals(color, requireNotNull(metadata().getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)).getPixel(4, 4))
        main {
            service.onCoverArt(byteArrayOf(1, 2, 3))
            assertSame("Invalid artwork replaced the valid image", cover, service.trackInfo.value.coverArt)
            service.onMetadata(item("mlit", text("minm", "Second title")))
            assertEquals("Partial metadata discarded duration", 9000L, service.durationMs.value)
            assertSame("Partial metadata discarded artwork", cover, service.trackInfo.value.coverArt)
        }
        await("Partial metadata was not published") {
            controller.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) == "Second title"
        }
    }

    @Test fun duplicatePinsAreCoalescedButChangedPinsReachTheConsumer() {
        val pins = mutableListOf<String?>()
        main {
            service.pinCallback = { pins.add(it) }
            assertEquals("New listener must receive the current empty PIN", listOf<String?>(null), pins)
            service.onDisplayPin("1234")
            service.onDisplayPin("1234")
            service.onDisplayPin("5678")
            assertEquals(listOf(null, "1234", "5678"), pins)
            val replacement = mutableListOf<String?>()
            service.pinCallback = { replacement.add(it) }
            assertEquals(listOf("5678"), replacement)
        }
    }

    @Test fun disabledApprovalAndBothLoopbackAddressesBypassThePrompt() {
        val prefs = context.getSharedPreferences(Prefs.NAME, 0)
        main {
            Prefs.APPROVE_CONNECTIONS.write(prefs, false)
            assertTrue(service.authorizeConnection("DLNA", "192.0.2.10"))
            Prefs.APPROVE_CONNECTIONS.write(prefs, true)
            assertTrue(service.authorizeConnection("AirPlay", "127.0.0.1"))
            assertTrue(service.authorizeConnection("AirPlay", "::1"))
        }
    }
}
