package io.github.jqssun.airplay

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.buzincast.core.MediaRequest
import io.buzincast.core.Protocol
import io.buzincast.core.ReceiverPlaybackPort
import io.buzincast.core.SessionKind
import io.buzincast.core.SessionPhase
import io.buzincast.core.SessionToken
import io.buzincast.media.FCastFrame
import io.buzincast.media.MirrorPacket
import io.buzincast.media.MirrorPacketCodec
import io.buzincast.media.SecureMirrorChannel
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.audio.DacpController
import io.github.jqssun.airplay.discovery.FCastReceiver
import io.github.jqssun.airplay.media.MirrorCapture
import io.github.jqssun.airplay.media.MirrorRelay
import io.github.jqssun.airplay.renderer.PlaybackSnapshot
import io.github.jqssun.airplay.viewmodel.MainViewModel
import io.leancast.app.Net
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real bound service and MediaSession contracts without starting a receiver. */
@RunWith(AndroidJUnit4::class)
class ServiceAudioStateContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val mediaSessionField by lazy {
        AirPlayService::class.java.getDeclaredField("mediaSession").apply { isAccessible = true }
    }
    private lateinit var service: AirPlayService
    private lateinit var mediaSession: MediaSessionCompat
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
    private fun withoutAutoLaunch(block: () -> Unit) {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val key = Prefs.LAUNCH_ON_CONNECT.key
        val original = prefs.takeIf { it.contains(key) }?.let(Prefs.LAUNCH_ON_CONNECT::read)
        assertTrue(prefs.edit().putBoolean(key, false).commit())
        try {
            block()
        } finally {
            val editor = prefs.edit()
            if (original == null) editor.remove(key) else editor.putBoolean(key, original)
            assertTrue(editor.commit())
        }
    }

    private fun await(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
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
            mediaSession = requireNotNull(mediaSessionField.get(service) as? MediaSessionCompat)
            controller = MediaControllerCompat(context, mediaSession.sessionToken)
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

    private fun coverPng(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    @Test fun metadataAndLosslessArtReachTheRealMediaSessionAndSurvivePartialMetadata() {
        val data = item("mlit", text("minm", "First title") + text("asar", "Artist") +
            text("asal", "Album") + text("asgn", "Genre") + item("astm", ByteBuffer.allocate(4).putInt(9000).array()))
        val color = Color.rgb(22, 180, 70)
        val png = coverPng(color)
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

    @Test fun mirrorAudioKeepsTheMediaSessionInactiveAndDropsThePreviousTrack() {
        main {
            val emptyTrack = service.trackInfo.value
            val title = "Track before mirroring"
            service.onAudioFormat(2, 352, false)
            service.onMetadata(item("mlit", text("minm", title)))
            service.onProgress(0, 44100, 44100 * 60L)
            assertTrue(service.audioOnly.value)
            assertTrue(mediaSession.isActive)
            assertEquals(title, service.trackInfo.value.title)
            assertTrue(service.positionMs.value > 0)
            assertTrue(service.durationMs.value > 0)

            service.onMirrorRunning(true)
            try {
                assertFalse(service.audioOnly.value)
                assertFalse(mediaSession.isActive)
                assertEquals(emptyTrack, service.trackInfo.value)
                assertEquals(0L, service.positionMs.value)
                assertEquals(0L, service.durationMs.value)
                assertEquals(0L, service.currentPositionMs())

                service.onAudioFormat(2, 352, true)
                assertFalse(service.audioOnly.value)
                assertFalse(mediaSession.isActive)
                assertEquals(emptyTrack, service.trackInfo.value)
            } finally {
                service.onMirrorRunning(false)
            }
            assertTrue(service.audioOnly.value)
            assertTrue(mediaSession.isActive)
            service.onMirrorRunning(false)
            assertTrue(mediaSession.isActive)
            assertEquals(emptyTrack, service.trackInfo.value)
        }
    }

    @Test fun mirrorSizeRejectsInvalidDimensionsAndUpdatesAnOwnedSession() = withoutAutoLaunch {
        main {
            val initialResolution = service.videoResolution.value
            val initialAspect = service.videoAspect.value
            for ((width, height) in listOf(0f to 1080f, 1920f to 0f)) {
                service.onVideoSize(1920f, 1080f, width, height)
                assertNull(service.sessionSnapshot())
                assertFalse(service.mirroringActive.value)
                assertEquals(initialResolution, service.videoResolution.value)
                assertEquals(initialAspect, service.videoAspect.value, 0f)
            }

            service.relay.listen()
            assertTrue(service.relay.state.value.listening)
            service.onConnectionInit()
            service.onDisplayPin("1234")
            val pins = mutableListOf<String?>()
            service.pinCallback = { pins.add(it) }
            service.onMirrorRunning(true)
            service.onVideoSize(1920f, 1080f, 1280f, 720f)
            val first = requireNotNull(service.sessionSnapshot())
            assertEquals(Protocol.AIRPLAY, first.token.protocol)
            assertEquals(SessionKind.MIRROR, first.kind)
            assertEquals(SessionPhase.PLAYING, first.phase)
            assertFalse(service.relay.state.value.listening)
            assertEquals(listOf("1234", null), pins)
            assertTrue(service.mirroringActive.value)
            assertEquals("1280x720", service.videoResolution.value)

            service.onVideoSize(1080f, 1920f, 720f, 1280f)
            assertEquals(first.token, service.sessionSnapshot()?.token)
            assertEquals("720x1280", service.videoResolution.value)
            assertEquals(720f / 1280f, service.videoAspect.value, 0f)
            service.onMirrorRunning(false)
            service.onConnectionDestroy()
            assertFalse(service.mirroringActive.value)
            assertNull(service.sessionSnapshot())
        }
    }

    @Test fun onlyTheFinalSenderDisconnectClearsSharedAudioState() = withoutAutoLaunch {
        main {
            service.onConnectionInit()
            service.onConnectionInit()
            service.onAudioFormat(2, 352, false)
            service.onMetadata(item("mlit", text("minm", "Shared audio")))
            service.onProgress(0, 44100, 44100 * 60L)
            val session = requireNotNull(service.sessionSnapshot())
            assertEquals(SessionKind.AUDIO, session.kind)
            assertEquals(SessionPhase.PLAYING, session.phase)
            assertEquals(2, service.connectionCount.value)

            service.onConnectionDestroy()
            assertEquals(1, service.connectionCount.value)
            assertEquals(session, service.sessionSnapshot())
            assertEquals("Shared audio", service.trackInfo.value.title)
            assertEquals(1000L, service.positionMs.value)
            assertEquals(60000L, service.durationMs.value)
            assertTrue(service.audioOnly.value)
            assertTrue(mediaSession.isActive)

            service.onConnectionDestroy()
            assertEquals(0, service.connectionCount.value)
            assertNull(service.sessionSnapshot())
            assertEquals("", service.trackInfo.value.title)
            assertEquals(0L, service.positionMs.value)
            assertEquals(0L, service.durationMs.value)
            assertFalse(service.audioOnly.value)
            assertFalse(mediaSession.isActive)
        }
    }

    private fun withLocalVideoFixture(block: (String) -> Unit) {
        val file = File.createTempFile("service-video-", ".mp4", context.cacheDir)
        try {
            instrumentation.context.assets.open("download/sample.mp4").use { input -> file.outputStream().use(input::copyTo) }
            block(Uri.fromFile(file).toString())
        } finally {
            service.stopVideoPlayback()
            instrumentation.waitForIdleSync()
            assertTrue(file.delete())
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun externalSubtitleUsesTheBoundPlayerAndKeepsItOnImportFailure() = withoutAutoLaunch {
        val sandbox = File(context.cacheDir, "model-subtitle-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = sandbox
            override fun getCacheDir() = sandbox
        }
        val app = object : Application() { init { attachBaseContext(isolated) } }
        val input = File.createTempFile("service-subtitle-", ".vtt", context.cacheDir)
        val imported = File(sandbox, "external-subtitle.vtt")
        val store = ViewModelStore()
        lateinit var model: MainViewModel
        val renderer = service.airPlayVideoPlayer
        val playerField = renderer.javaClass.getDeclaredField("player").apply { isAccessible = true }
        fun captions() = (playerField.get(renderer) as? ExoPlayer)
            ?.currentMediaItem?.localConfiguration?.subtitleConfigurations
        val title = "BuzinCast subtitle contract"
        val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n$title\n"
        try {
            input.writeText("\uFEFF$vtt")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", input)
            withLocalVideoFixture { location ->
                main {
                    model = MainViewModel(app)
                    store.put("subtitle", model)
                    model.subtitle(uri)
                    model.bindService(service)
                    service.playLocalVideo(location)
                    service.setVideoPlaying(false)
                    model.subtitle(uri)
                }
                await("The imported subtitle did not reach the active player") {
                    var installed = false
                    main { installed = captions()?.singleOrNull()?.uri == Uri.fromFile(imported) }
                    installed
                }
                assertEquals(vtt, imported.readText())
                val invalidUri = Uri.parse("https://example.test/remote.vtt")
                val existingFailures = model.logs.value.count { it.contains("Subtitle load failed:") }
                main { model.subtitle(invalidUri) }
                await("The invalid subtitle did not report an import failure") {
                    model.logs.value.count { it.contains("Subtitle load failed:") } > existingFailures
                }
                main {
                    assertEquals("text/vtt", captions()?.single()?.mimeType)
                    model.subtitle(null)
                }
                await("Removing the subtitle did not update the active player") {
                    var cleared = false
                    main { cleared = captions()?.isEmpty() == true }
                    cleared
                }
            }
        } finally {
            main { store.clear() }
            input.delete()
            sandbox.deleteRecursively()
        }
    }

    @Test fun airplayMirroringReleasesThePreviousLocalPlaybackWithoutDroppingMirrorOwnership() = withoutAutoLaunch {
        withLocalVideoFixture { location ->
            main {
                service.playLocalVideo(location)
                service.setVideoPlaying(false)
                assertTrue(service.videoPlaybackActive.value)
                assertEquals(Protocol.LOCAL, service.networkPlaybackOwner)
                service.onConnectionInit()
                service.onMirrorRunning(true)
                service.onVideoSize(1920f, 1080f, 1280f, 720f)
                assertEquals(Protocol.AIRPLAY, service.sessionSnapshot()?.token?.protocol)
                assertEquals(SessionKind.MIRROR, service.sessionSnapshot()?.kind)
            }
            await("Previous local player did not release") { !service.videoPlaybackActive.value }
            main {
                assertNull(service.networkPlaybackOwner)
                assertEquals(SessionKind.MIRROR, service.sessionSnapshot()?.kind)
                assertTrue(service.mirroringActive.value)
                assertFalse(mediaSession.isActive)
                service.onMirrorRunning(false)
                service.onConnectionDestroy()
                assertNull(service.sessionSnapshot())
            }
        }
    }

    private fun sendRelayVideo(channel: SecureMirrorChannel, width: Int, height: Int, timestamp: Long) {
        // Non-keyframe NAL exercises routing and size changes without a rendering surface.
        val packet = MirrorPacket.Video(byteArrayOf(0, 0, 0, 1, 1, 0), timestamp, width, height, false)
        channel.send(MirrorPacketCodec.encode(packet))
        await("Relay did not present ${width}x$height video") {
            service.mirroringActive.value && service.videoResolution.value == "${width}x$height"
        }
    }

    @Test fun authenticatedRelayOwnsTheMirrorUntilItsEndPacket() = withoutAutoLaunch {
        main { service.relay.listen() }
        withAuthenticatedRelayPeer(service.relay.state.value) { channel ->
            sendRelayVideo(channel, 640, 360, 1_000_000_000)
            val session = requireNotNull(service.sessionSnapshot())
            assertEquals(Protocol.RELAY, session.token.protocol)
            assertEquals(SessionKind.MIRROR, session.kind)
            assertEquals(SessionPhase.PLAYING, session.phase)

            sendRelayVideo(channel, 360, 640, 1_033_000_000)
            assertEquals(session, service.sessionSnapshot())
            assertEquals(360f / 640f, service.videoAspect.value, 0f)

            channel.send(MirrorPacketCodec.encode(MirrorPacket.End))
            await("Relay end did not release display and session ownership") {
                !service.relay.state.value.receiving && !service.mirroringActive.value && service.sessionSnapshot() == null
            }
        }
    }

    @Test fun mirrorCallbacksForwardAudioAndSizedVideoToTheAuthenticatedPeer() = withoutAutoLaunch {
        val packets = LinkedBlockingQueue<MirrorPacket>()
        val peer = MirrorRelay(context, {}, { packets.add(it) }, {}, { true })
        val pcm = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0)
        val configuredVideo = byteArrayOf(0, 0, 0, 1, 0x65, 0x01)
        try {
            peer.listen()
            val destination = peer.state.value
            assertTrue(destination.listening)
            main {
                service.onAudioPcm(pcm, 900, 48_000, 2)
                service.relay.connect(requireNotNull(Net.lanIpv4()), destination.port, destination.code)
            }
            val connecting = context.getString(R.string.relay_connecting)
            // The receiver and sender derive keys concurrently on the emulator.
            await("The service relay did not authenticate to the peer", timeoutMs = 45_000) {
                val sender = service.relay.state.value
                val receiver = peer.state.value
                check(sender.sending || sender.message == connecting) {
                    "Relay sender stopped: ${sender.message}; peer: ${receiver.message}"
                }
                sender.sending && receiver.receiving
            }
            main {
                // The receiver must see the audio first. A video callback before
                // dimensions are known must not emit an unusable video packet.
                service.onVideoData(byteArrayOf(0, 0, 0, 1, 0x65, 0x00), 1_000, false)
                service.onAudioPcm(pcm, 2_000, 48_000, 2)
                service.onVideoSize(640f, 480f, 640f, 480f)
                service.onVideoData(configuredVideo, 3_000, false)
            }
            val audio = packets.poll(10, TimeUnit.SECONDS)
            assertTrue("Expected the audio frame first, received $audio", audio is MirrorPacket.Audio)
            audio as MirrorPacket.Audio
            assertArrayEquals(pcm, audio.data)
            assertEquals(2_000L, audio.ptsNs)
            assertEquals(48_000, audio.sampleRate)
            assertEquals(2, audio.channels)

            val video = packets.poll(10, TimeUnit.SECONDS)
            assertTrue("Expected the configured video frame, received $video", video is MirrorPacket.Video)
            video as MirrorPacket.Video
            assertArrayEquals(configuredVideo, video.data)
            assertEquals(3_000L, video.ptsNs)
            assertEquals(640, video.width)
            assertEquals(480, video.height)
            assertFalse(video.hevc)
        } finally {
            main { service.relay.stop() }
            peer.stop()
        }
    }

    @Test fun relayTeardownDoesNotClearTheAirPlayMirrorThatReplacedIt() = withoutAutoLaunch {
        main { service.relay.listen() }
        withAuthenticatedRelayPeer(service.relay.state.value) { channel ->
            sendRelayVideo(channel, 640, 360, 1_000_000_000)
            val relayToken = requireNotNull(service.sessionSnapshot()).token
            assertEquals(Protocol.RELAY, relayToken.protocol)
            main {
                service.onConnectionInit()
                service.onMirrorRunning(true)
                service.onVideoSize(1920f, 1080f, 1280f, 720f)
            }
            main {
                val airplay = requireNotNull(service.sessionSnapshot())
                assertEquals(Protocol.AIRPLAY, airplay.token.protocol)
                assertNotEquals(relayToken, airplay.token)
                assertFalse(service.relay.state.value.receiving)
                assertTrue(service.mirroringActive.value)
                assertEquals("1280x720", service.videoResolution.value)
                service.onMirrorRunning(false)
                service.onConnectionDestroy()
                assertNull(service.sessionSnapshot())
            }
        }
    }

    @Test fun relayRejectsPeersUntilLocalPlaybackAndTheAirPlayConnectionHaveEnded() = withoutAutoLaunch {
        withLocalVideoFixture { location ->
            fun assertPeerRejected() {
                val listener = service.relay.state.value
                assertTrue(listener.listening)
                Socket("127.0.0.1", listener.port).use { peer ->
                    peer.soTimeout = 5_000
                    assertEquals("Busy service sent pairing data", -1, peer.getInputStream().read())
                }
                assertEquals(listener, service.relay.state.value)
            }

            main {
                service.playLocalVideo(location)
                service.setVideoPlaying(false)
                service.relay.listen()
            }
            val local = requireNotNull(service.sessionSnapshot())
            assertEquals(Protocol.LOCAL, local.token.protocol)
            assertPeerRejected()
            assertEquals(local.token, service.sessionSnapshot()?.token)
            assertTrue(service.videoPlaybackActive.value)
            service.stopVideoPlayback()
            await("Local playback did not end") { service.sessionSnapshot() == null }

            main {
                service.onConnectionInit()
                service.onAudioFormat(2, 352, false)
                service.relay.listen()
            }
            val airplay = requireNotNull(service.sessionSnapshot())
            assertEquals(Protocol.AIRPLAY, airplay.token.protocol)
            assertEquals(SessionKind.AUDIO, airplay.kind)
            assertPeerRejected()
            assertEquals(airplay, service.sessionSnapshot())
            assertEquals(1, service.connectionCount.value)
            main { service.onConnectionDestroy() }

            withAuthenticatedRelayPeer(service.relay.state.value) { channel ->
                sendRelayVideo(channel, 640, 360, 1_000_000_000)
                assertEquals(Protocol.RELAY, service.sessionSnapshot()?.token?.protocol)
                channel.send(MirrorPacketCodec.encode(MirrorPacket.End))
                await("Admitted relay did not release display ownership") { service.sessionSnapshot() == null }
            }
        }
    }

    @Test fun queuedMirrorTakeoverDoesNotStopNewerLocalPlayback() = withoutAutoLaunch {
        withLocalVideoFixture { location ->
            lateinit var successor: SessionToken
            main {
                service.playLocalVideo(location)
                val original = requireNotNull(service.sessionSnapshot()).token
                service.onConnectionInit()
                service.onMirrorRunning(true)
                service.onVideoSize(1920f, 1080f, 1280f, 720f)
                assertEquals(Protocol.AIRPLAY, service.sessionSnapshot()?.token?.protocol)
                service.onMirrorRunning(false)
                service.playLocalVideo(location)
                service.setVideoPlaying(false)
                val replacement = requireNotNull(service.sessionSnapshot()).token
                assertNotEquals(original, replacement)
                successor = replacement
            }
            main {
                service.onConnectionDestroy()
                assertEquals(Protocol.LOCAL, service.networkPlaybackOwner)
                assertEquals(successor, service.sessionSnapshot()?.token)
                assertTrue(service.videoPlaybackActive.value)
            }
        }
    }

    private fun replaceLocalPlayback(location: String): Pair<SessionToken, SessionToken> {
        service.playLocalVideo(location)
        val stale = requireNotNull(service.sessionSnapshot()).token
        service.playLocalVideo(location)
        val current = requireNotNull(service.sessionSnapshot()).token
        assertNotEquals(stale, current)
        return stale to current
    }

    @Test fun playbackErrorsOnlyReachTheCurrentFCastOwner() = withoutAutoLaunch {
        val receiverField = AirPlayService::class.java.getDeclaredField("fcastReceiver").apply { isAccessible = true }
        val portField = AirPlayService::class.java.getDeclaredField("playbackPort").apply { isAccessible = true }
        val playback = portField.get(service) as ReceiverPlaybackPort
        val previousReceiver = receiverField.get(service)
        val preferences = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putBoolean(Prefs.APPROVE_CONNECTIONS.key, false).commit())
        val diagnostics = java.util.concurrent.CopyOnWriteArrayList<String>()

        FCastReceiver(context, playback, "Error forwarding fixture", { diagnostics += it }, requestedPort = 0).use { receiver ->
            receiver.start()
            assertTrue(diagnostics.toString(), receiver.listeningPort > 0)
            Socket(requireNotNull(Net.lanIpv4()), receiver.listeningPort).use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                assertEquals(11, FCastFrame.readFrom(input)?.opcode)
                withLocalVideoFixture { location ->
                    try {
                        main {
                            receiverField.set(service, receiver)
                            val player = service.airPlayVideoPlayer
                            val fail = requireNotNull(player.onError)
                            service.playLocalVideo(location)
                            player.setPlaying(false)
                            val local = requireNotNull(service.sessionSnapshot()).token
                            fail(local, "Local errors do not belong to the FCast client")

                            playback.load(MediaRequest(location, Protocol.FCAST))
                            player.setPlaying(false)
                            val current = requireNotNull(service.sessionSnapshot()).token
                            assertEquals(Protocol.FCAST, current.protocol)
                            fail(local, "Stale errors must be ignored")
                            receiverField.set(service, null)
                            fail(current, "Receiver is unavailable")
                            receiverField.set(service, receiver)
                            fail(current, "Current FCast playback error")
                        }
                        // The receiver sends errors on one executor. The first
                        // error must be the one for the current connected owner.
                        val error = generateSequence { FCastFrame.readFrom(input) }
                            .take(12).firstOrNull { it.opcode == 9 }
                        assertEquals("Current FCast playback error",
                            JSONObject(requireNotNull(error).body).getString("message"))
                        FCastFrame(12).writeTo(socket.getOutputStream())
                        assertTrue(generateSequence { FCastFrame.readFrom(input) }
                            .take(12).any { it.opcode == 13 })
                    } finally {
                        main { receiverField.set(service, previousReceiver) }
                    }
                }
            }
        }
    }

    @Test fun sizeTitleAndEndCallbacksOnlyAffectTheirOwnPlayer() = withoutAutoLaunch {
        withLocalVideoFixture { location ->
            main {
                val (stale, current) = replaceLocalPlayback(location)
                val player = service.airPlayVideoPlayer
                val size = requireNotNull(player.onVideoSize)
                val title = requireNotNull(player.onTitle)
                val ended = requireNotNull(player.onEnded)
                size(current, 640, 480, 4f / 3f)
                title(current, "Current video")

                size(stale, 1920, 1080, 16f / 9f)
                title(stale, "Replaced video")
                ended(stale)
                assertEquals(640 to 480, service.videoPlaybackSize.value)
                assertEquals(4f / 3f, service.videoPlaybackAspect.value, 0f)
                assertEquals("Current video", service.videoTitle.value)
                assertTrue(service.videoPlaybackActive.value)
                assertEquals(current, service.sessionSnapshot()?.token)

                title(current, null)
                assertEquals("", service.videoTitle.value)
                ended(current)
                assertFalse(service.videoPlaybackActive.value)
                assertNull(service.sessionSnapshot())
            }
        }
    }

    @Test fun recoveryAndPlaybackSnapshotsIgnoreReplacedPlayerTokens() = withoutAutoLaunch {
        withLocalVideoFixture { location ->
            main {
                val (stale, current) = replaceLocalPlayback(location)
                val player = service.airPlayVideoPlayer
                val recovery = requireNotNull(player.onRecovery)
                val publish = requireNotNull(player.onPlaybackInfo)
                val buffering = PlaybackSnapshot(2.5f, -1f, 0f, false, true, buffering = true)
                recovery(current, "Waiting for playback")
                assertEquals(SessionPhase.RECOVERING, service.sessionSnapshot()?.phase)
                publish(current, buffering)
                assertEquals(0L, service.videoPlaybackInfo.value.durationMs)
                assertTrue(service.videoPlaybackInfo.value.buffering)

                val ready = PlaybackSnapshot(3.5f, 12.5f, 1.25f, true, true, speed = 1.25f, skipSilence = true)
                publish(stale, ready)
                publish(stale, buffering.copy(position = 9f))
                recovery(stale, "")
                assertEquals(2500L, service.videoPlaybackInfo.value.positionMs)
                assertEquals("Waiting for playback", service.networkMessage.value)
                assertEquals(SessionPhase.RECOVERING, service.sessionSnapshot()?.phase)

                publish(current, ready)
                recovery(current, "")
                val published = service.videoPlaybackInfo.value
                assertEquals(3500L, published.positionMs)
                assertEquals(12500L, published.durationMs)
                assertEquals(1.25f, published.speed, 0f)
                assertTrue(published.playing)
                assertTrue(published.skipSilence)
                assertFalse(published.buffering)
                assertEquals("", service.networkMessage.value)
                assertEquals(SessionPhase.PLAYING, service.sessionSnapshot()?.phase)

                requireNotNull(player.onEnded)(current)
                publish(current, buffering.copy(position = 0f))
                assertFalse(service.videoPlaybackActive.value)
                assertEquals(published, service.videoPlaybackInfo.value)
                assertNull(service.sessionSnapshot())
            }
        }
    }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    @Test fun notificationTracksPinAudioAndRecordingWithWorkingStopAction() {
        val builder = AirPlayService::class.java.getDeclaredMethod("buildNotification").apply { isAccessible = true }
        fun notification() = builder.invoke(service) as Notification
        fun title(value: Notification) = value.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        fun actions(value: Notification) = value.actions.orEmpty().map { it.title.toString() }
        fun recordingNotification(state: MirrorCapture.State): PendingIntent {
            val recording = notification()
            assertEquals(context.getString(R.string.tools_record_title), title(recording))
            assertEquals(state.message, recording.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertEquals(listOf(context.getString(R.string.tools_record_stop)), actions(recording))
            assertNull(MediaStyle.getMediaSession(recording))
            return recording.actions.single().actionIntent
        }
        var stopCapture: PendingIntent? = null
        val artwork = coverPng(Color.rgb(22, 180, 70))

        main {
            val idle = notification()
            assertEquals(context.getString(R.string.notification_title), title(idle))
            assertNotNull(idle.contentIntent)
            assertTrue(idle.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(actions(idle).isEmpty())

            val pin = "4321"
            service.onDisplayPin(pin)
            val pairing = notification()
            assertEquals(context.getString(R.string.notification_pin_title), title(pairing))
            assertEquals(context.getString(R.string.notification_pin_text, pin),
                pairing.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())

            service.onAudioFormat(2, 352, false)
            assertEquals(context.getString(R.string.notification_title), title(notification()))
            service.onMetadata(item("mlit", text("minm", "Notification track") +
                text("asar", "Notification artist") + text("asal", "Notification album")))
            val playing = notification()
            assertEquals("Notification track", title(playing))
            assertEquals("Notification artist", playing.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertEquals("Notification album", playing.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
            assertEquals(mediaSession.sessionToken, MediaStyle.getMediaSession(playing))
            assertEquals(listOf("Prev", "Pause", "Next"), actions(playing))
            service.onCoverArt(artwork)
            val withArtwork = notification()
            assertEquals("Notification track", title(withArtwork))
            assertNotNull(if (Build.VERSION.SDK_INT >= 23) withArtwork.getLargeIcon() else withArtwork.largeIcon)

            val sessionWithoutToken = object : MediaSessionCompat(context, "notification-contract") {
                var hideToken = false
                override fun getSessionToken(): MediaSessionCompat.Token? =
                    if (hideToken) null else super.getSessionToken()
            }
            try {
                // A session can be absent, or its adapter can fail to supply the token.
                sessionWithoutToken.hideToken = true
                for (unavailable in listOf(null, sessionWithoutToken)) {
                    mediaSessionField.set(service, unavailable)
                    val withoutControls = notification()
                    assertEquals("Notification track", title(withoutControls))
                    assertNull(MediaStyle.getMediaSession(withoutControls))
                    assertTrue(actions(withoutControls).isEmpty())
                }
            } finally {
                mediaSessionField.set(service, mediaSession)
                sessionWithoutToken.release()
            }

            if (BuildConfig.RECORDING_ENABLED) {
                service.capture.start()
                assertTrue("Recording did not arm on the test device", service.capture.state.value.armed)
                stopCapture = recordingNotification(service.capture.state.value)
            } else {
                // Exercise presentation with an in-memory snapshot in the regional APK.
                val field = MirrorCapture::class.java.getDeclaredField("mutable").apply { isAccessible = true }
                val state = field.get(service.capture) as MutableStateFlow<MirrorCapture.State>
                val original = state.value
                val armed = MirrorCapture.State(armed = true, message = "Recording notification fixture")
                try {
                    assertFalse(service.capture.active())
                    state.value = armed
                    recordingNotification(armed)
                    assertFalse(service.capture.active())
                } finally {
                    state.value = original
                }
                assertEquals("Notification track", title(notification()))
            }
        }
        if (BuildConfig.RECORDING_ENABLED) {
            requireNotNull(stopCapture).send()
            await("The notification's stop action did not disarm capture") { !service.capture.state.value.armed }
            main { assertEquals("Notification track", title(notification())) }
        }
    }

    @Test fun notificationActionsControlTheConnectedAudioSender() = withoutAutoLaunch {
        val field = AirPlayService::class.java.getDeclaredField("dacpController").apply { isAccessible = true }
        val builder = AirPlayService::class.java.getDeclaredMethod("buildNotification").apply { isAccessible = true }
        val previous = service.dacpController
        DacpHttpContractTest.Sender().use { sender ->
            val dacp = DacpController { _, listener ->
                listener.onServiceResolved(NsdServiceInfo().apply {
                    host = InetAddress.getByName("127.0.0.1")
                    port = sender.port
                })
            }
            try {
                lateinit var actions: List<Notification.Action>
                main {
                    field.set(service, dacp)
                    service.onDacpId("notification-fixture", "notification-contract")
                    service.onAudioFormat(2, 352, false)
                    service.onMetadata(item("mlit", text("minm", "Notification controls fixture")))
                    val notification = builder.invoke(service) as Notification
                    actions = notification.actions.orEmpty().toList()
                }
                assertTrue(dacp.ready)
                assertEquals(listOf("Prev", "Pause", "Next"), actions.map { it.title.toString() })

                fun sendAction(index: Int, expected: List<String>) {
                    actions[index].actionIntent.send()
                    await("Audio sender did not receive notification commands $expected") {
                        sender.requests.map { it.first().split(' ')[1].substringAfterLast('/') } == expected
                    }
                }

                sendAction(0, listOf("previtem"))
                sendAction(1, listOf("previtem", "pause"))
                assertFalse(service.playing.value)
                sendAction(2, listOf("previtem", "pause", "nextitem"))
                sendAction(1, listOf("previtem", "pause", "nextitem", "play"))
                assertTrue(service.playing.value)
                assertTrue(sender.requests.all { request ->
                    request.any { it.equals("Active-Remote: notification-contract", ignoreCase = true) }
                })
            } finally {
                main {
                    field.set(service, previous)
                    dacp.release()
                }
            }
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

    @Test fun advertisedDisplaySizeUsesTheSelectedOrientationAndDecoderBounds() {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val resolution = Prefs.RESOLUTION
        val original = prefs.getString(resolution.key, null)
        val displaySize = AirPlayService::class.java.getDeclaredMethod("_displaySize", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val followsDevice = AirPlayService::class.java.getDeclaredMethod("_orientationFollowsDevice")
            .apply { isAccessible = true }
        fun size(clamp: Boolean) = displaySize.invoke(service, clamp)

        main {
            val (width, height) = service.realDisplaySize()
            assertTrue(width > 0 && height > 0)
            val (shortEdge, longEdge) = listOf(width, height).sorted()
            val portrait = shortEdge to longEdge
            val landscape = longEdge to shortEdge
            val automatic = if (service.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                portrait
            } else {
                landscape
            }
            try {
                assertTrue(resolution.write(prefs, Prefs.PORTRAIT))
                assertEquals(portrait, size(true))
                assertEquals(portrait, size(false))
                assertEquals(false, followsDevice.invoke(service))

                assertTrue(resolution.write(prefs, Prefs.LANDSCAPE))
                assertEquals(landscape, size(true))
                assertEquals(landscape, size(false))
                assertEquals(false, followsDevice.invoke(service))

                assertTrue(resolution.write(prefs, Prefs.AUTO))
                assertEquals(automatic, size(false))
                assertEquals(minOf(automatic.first, 1920) to minOf(automatic.second, 1080), size(true))
                assertEquals(true, followsDevice.invoke(service))

                assertTrue(resolution.write(prefs, "8000x7000"))
                assertEquals(8000 to 7000, size(false))
                assertEquals(1920 to 1080, size(true))
                assertEquals(false, followsDevice.invoke(service))

                assertTrue(resolution.write(prefs, "640x480"))
                assertEquals(640 to 480, size(true))
                assertTrue(resolution.write(prefs, "8000x480"))
                assertEquals(1920 to 480, size(true))
                assertTrue(resolution.write(prefs, "640x7000"))
                assertEquals(640 to 1080, size(true))
            } finally {
                val restore = prefs.edit()
                if (original == null) restore.remove(resolution.key) else restore.putString(resolution.key, original)
                assertTrue(restore.commit())
            }
        }
    }

    @Test fun automaticDisplaySizingHandlesPhysicalPanelsInEitherOrientation() {
        val landscape = 1920 to 1080
        val portrait = 1080 to 1920
        val square = 1080 to 1080
        fun size(panel: Pair<Int, Int>, orientation: Int) =
            receiverDisplaySize(Prefs.AUTO, orientation, panel, clamp = false) {
                error("Unclamped display sizing must not query decoder capabilities")
            }

        for (panel in listOf(landscape, portrait)) {
            assertEquals(landscape, size(panel, Configuration.ORIENTATION_LANDSCAPE))
            assertEquals(portrait, size(panel, Configuration.ORIENTATION_PORTRAIT))
        }
        assertEquals(square, size(square, Configuration.ORIENTATION_LANDSCAPE))
        assertEquals(square, size(square, Configuration.ORIENTATION_PORTRAIT))
    }

    @Test fun networkIdentityKeepsValidPreferencesAndReplacesInvalidFallbackAddresses() {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val key = Prefs.FALLBACK_MAC_ADDRESS
        val saved = prefs.getString(key, null)
        val read = AirPlayService::class.java.getDeclaredMethod("persistedRandomMac").apply { isAccessible = true }
        fun fallback() = read.invoke(service) as ByteArray

        main {
            try {
                val valid = "02:01:AB:CD:EF:FF"
                assertTrue(prefs.edit().putString(key, valid).commit())
                assertArrayEquals(byteArrayOf(2, 1, -85, -51, -17, -1), fallback())
                assertEquals(valid, prefs.getString(key, null))

                for (invalid in listOf(null, "00:00:00:00:00:00", "02:00:00:00:00:00", "12:34:56:78:9a",
                    "12:34:56:78:9a:100", "1:34:56:78:9a:bc", "12:34:56:78:9a:zz")) {
                    assertTrue(prefs.edit().putString(key, invalid).commit())
                    val generated = fallback()
                    assertEquals(6, generated.size)
                    assertEquals(0x0a, generated[0].toInt() and 0x0f)
                    val stored = prefs.getString(key, null)
                    assertEquals(generated.joinToString(":") { "%02x".format(it) }, stored)
                    assertArrayEquals(generated, fallback())
                    assertEquals(stored, prefs.getString(key, null))
                }
            } finally {
                assertTrue(prefs.edit().putString(key, saved).commit())
            }
        }
    }

    @Test fun receiverRejectsMissingAndPlaceholderHardwareAddresses() {
        val method = AirPlayService::class.java.getDeclaredMethod("isUsableMac", ByteArray::class.java)
            .apply { isAccessible = true }
        for (invalid in listOf(null, ByteArray(5), ByteArray(6), byteArrayOf(2, 0, 0, 0, 0, 0))) {
            assertEquals(false, method.invoke(service, invalid))
        }
        assertEquals(true, method.invoke(service, byteArrayOf(2, 0, 0, 0, 0, 1)))
        assertEquals(true, method.invoke(service, byteArrayOf(-86, -69, -52, -35, -18, -1)))
    }

    @Test fun receiverSelectsTheFirstUsableWifiOrEthernetAddress() {
        val wifi = byteArrayOf(6, 1, 2, 3, 4, 5)
        val ethernet = byteArrayOf(10, 5, 4, 3, 2, 1)
        val loopback = AirPlayService.NetworkAddressSource("lo") { throw AssertionError("Read loopback hardware address") }
        val placeholder = AirPlayService.NetworkAddressSource("wlan0") { byteArrayOf(2, 0, 0, 0, 0, 0) }
        val validWifi = AirPlayService.NetworkAddressSource("wlan1") { wifi }
        val validEthernet = AirPlayService.NetworkAddressSource("eth0") { ethernet }
        val unread = AirPlayService.NetworkAddressSource("eth1") { throw AssertionError("Read address beyond the first usable one") }
        main {
            assertArrayEquals(ethernet, service.selectReceiverHardwareAddress {
                sequenceOf(loopback, placeholder, validEthernet, unread)
            })
            assertArrayEquals(wifi, service.selectReceiverHardwareAddress { sequenceOf(loopback, validWifi, unread) })
        }
    }

    @Test fun missingOrInaccessibleNetworkInterfacesReuseTheSavedReceiverAddress() {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val key = Prefs.FALLBACK_MAC_ADDRESS
        val saved = prefs.getString(key, null)
        val fallback = "0a:01:02:03:04:05"
        val expected = byteArrayOf(10, 1, 2, 3, 4, 5)
        main {
            try {
                assertTrue(prefs.edit().putString(key, fallback).commit())
                assertArrayEquals(expected, service.selectReceiverHardwareAddress { emptySequence() })
                assertArrayEquals(expected, service.selectReceiverHardwareAddress {
                    sequenceOf(AirPlayService.NetworkAddressSource("wlan0") { null })
                })
                assertArrayEquals(expected, service.selectReceiverHardwareAddress { throw SocketException("Enumeration failed") })
                assertArrayEquals(expected, service.selectReceiverHardwareAddress {
                    sequenceOf(AirPlayService.NetworkAddressSource("eth0") { throw SocketException("Address inaccessible") })
                })
                assertEquals(fallback, prefs.getString(key, null))
            } finally {
                assertTrue(prefs.edit().putString(key, saved).commit())
            }
        }
    }

    @Test fun audioScanDispatchesBothDirectionsAndResumesThroughTheBoundSender() {
        val field = AirPlayService::class.java.getDeclaredField("dacpController").apply { isAccessible = true }
        val originalDacp = service.dacpController
        val store = ViewModelStore()
        lateinit var model: MainViewModel
        DacpHttpContractTest.Sender().use { sender ->
            val dacp = DacpController { _, listener ->
                listener.onServiceResolved(NsdServiceInfo().apply {
                    host = InetAddress.getByName("127.0.0.1")
                    port = sender.port
                })
            }
            try {
                main {
                    model = MainViewModel(context.applicationContext as android.app.Application)
                    store.put("audio-scan", model)
                    field.set(service, null)
                    model.bindService(service)
                    model.audioScanBegin(true)
                    model.audioScanEnd()
                    field.set(service, dacp)
                    service.onDacpId("scan-fixture", "scan-contract")
                }
                instrumentation.waitForIdleSync()
                assertTrue(dacp.ready)
                main {
                    model.audioScanBegin(true)
                    model.audioScanEnd()
                    model.audioScanBegin(false)
                    model.audioScanEnd()
                    model.unbindService()
                    model.audioScanBegin(true)
                    model.audioScanEnd()
                }
                // Drain the controller queue before checking that absent and detached
                // controller calls sent no extra request.
                dacp.playResume().get(5, TimeUnit.SECONDS)
                val recorded = sender.requests.toList()
                assertEquals(listOf("beginff", "playresume", "beginrew", "playresume", "playresume"),
                    recorded.map { it.first().split(' ')[1].substringAfterLast('/') })
                assertTrue(recorded.all { headers ->
                    headers.any { it.equals("Active-Remote: scan-contract", ignoreCase = true) }
                })
            } finally {
                main {
                    field.set(service, originalDacp)
                    dacp.release()
                    store.clear()
                }
            }
        }
    }

    @Test fun relayListeningRequiresAnIdleBoundReceiver() {
        @Suppress("UNCHECKED_CAST")
        fun <T> flow(name: String): MutableStateFlow<T> =
            AirPlayService::class.java.getDeclaredField(name).apply { isAccessible = true }
                .get(service) as MutableStateFlow<T>
        val connections = flow<Int>("_connectionCount")
        val video = flow<Boolean>("_videoPlaybackActive")
        val store = ViewModelStore()
        main {
            val model = MainViewModel(context.applicationContext as android.app.Application)
            store.put("relay-listener", model)
            val initialConnections = connections.value
            val initialVideo = video.value
            try {
                assertEquals(0, initialConnections)
                assertFalse(initialVideo)
                model.listenRelay()
                assertFalse(service.relay.state.value.listening)

                model.bindService(service)
                connections.value = 1
                model.listenRelay()
                assertFalse(service.relay.state.value.listening)

                connections.value = 0
                video.value = true
                model.listenRelay()
                assertFalse(service.relay.state.value.listening)

                video.value = false
                model.listenRelay()
                val listening = service.relay.state.value
                assertTrue(listening.listening)
                assertTrue(listening.port in 1..65535)
                assertEquals(12, listening.code.length)
                model.updateFromService()
                assertEquals(listening, model.relayState.value)

                model.stopRelay()
                model.updateFromService()
                assertFalse(model.relayState.value.listening)
                assertEquals(0, model.relayState.value.port)
                assertEquals("", model.relayState.value.code)
            } finally {
                service.relay.stop()
                connections.value = initialConnections
                video.value = initialVideo
                model.unbindService()
                store.clear()
            }
        }
    }

    @Test fun mediaSessionRoutesAudioAndVideoToTheirCurrentOwners() = withoutAutoLaunch {
        val field = AirPlayService::class.java.getDeclaredField("dacpController").apply { isAccessible = true }
        val originalDacp = service.dacpController
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
                    service.onDacpId("media-session-fixture", "media-session-contract")
                }
                instrumentation.waitForIdleSync()
                assertTrue(dacp.ready)

                val transport = controller.transportControls
                transport.pause()
                await("MediaSession pause did not reach the audio sender") {
                    !service.playing.value && commands() == listOf("pause")
                }
                transport.play()
                await("MediaSession play did not reach the audio sender") {
                    service.playing.value && commands() == listOf("pause", "play")
                }
                transport.skipToNext()
                transport.skipToPrevious()
                val audioCommands = listOf("pause", "play", "nextitem", "previtem")
                await("MediaSession skip commands did not reach the audio sender") { commands() == audioCommands }

                withLocalVideoFixture { location ->
                    main {
                        service.playLocalVideo(location)
                        service.airPlayVideoPlayer.setPlaying(false)
                    }
                    await("Local video did not become ready and paused") {
                        service.videoPlaybackActive.value && service.videoPlaybackInfo.value.let {
                            !it.playing && it.durationMs == 1000L && !it.buffering
                        }
                    }
                    transport.skipToNext()
                    transport.skipToPrevious()
                    transport.seekTo(400)
                    await("MediaSession did not seek the local video") {
                        service.videoPlaybackActive.value && service.videoPlaybackInfo.value.positionMs == 400L
                    }
                    // The seek callback runs after both skip callbacks. Drain the
                    // sender executor to detect any incorrect video-to-audio routing.
                    dacp.playResume().get(5, TimeUnit.SECONDS)
                    assertEquals(audioCommands + "playresume", commands())

                    transport.stop()
                    await("MediaSession stop did not release local video") {
                        !service.videoPlaybackActive.value && service.sessionSnapshot() == null
                    }
                }
            } finally {
                main {
                    field.set(service, originalDacp)
                    dacp.release()
                }
            }
        }
    }

    @Test fun senderVolumeStepsFollowFeedbackAndStopAtTargetOrOvershoot() {
        val prefs = context.getSharedPreferences(Prefs.NAME, 0)
        val key = Prefs.SYNC_CLIENT_VOLUME.key
        val previousSync = prefs.all[key] as? Boolean
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val receiverVolume = audio.getStreamVolume(stream)
        val receiverIndex = audio.getStreamMaxVolume(stream) / 2
        val originalDacp = service.dacpController
        val field = AirPlayService::class.java.getDeclaredField("dacpController").apply { isAccessible = true }
        val targetField = AirPlayService::class.java.getDeclaredField("_clientVolTarget").apply { isAccessible = true }
        DacpHttpContractTest.Sender().use { sender ->
            val dacp = DacpController { _, listener ->
                listener.onServiceResolved(NsdServiceInfo().apply {
                    host = InetAddress.getByName("127.0.0.1")
                    port = sender.port
                })
            }
            fun commands() = sender.requests.map { it.first().split(' ')[1] }
            fun feedback(db: Float) {
                main { service.onClientVolume(db) }
                instrumentation.waitForIdleSync()
            }
            fun awaitCommand(index: Int, expected: String) {
                await("Missing DACP command $expected at index $index") { sender.requests.size > index }
                assertEquals("/ctrl-int/1/$expected", commands()[index])
                assertTrue(sender.requests[index].any { it.equals("Active-Remote: volume-contract", ignoreCase = true) })
            }
            try {
                main {
                    assertTrue(prefs.edit().putBoolean(key, false).commit())
                    audio.setStreamVolume(stream, receiverIndex, 0)
                    field.set(service, dacp)
                    service.onDacpId("volume-fixture", "volume-contract")
                }
                instrumentation.waitForIdleSync()
                assertTrue(dacp.ready)
                feedback(-22.5f)
                assertEquals(0.25f, service.clientVolume(), 0.001f)
                assertEquals(-22.5f, service.onClientVolumeQuery(), 0.001f)

                main { service.setClientVolume(0.75f) }
                awaitCommand(0, "volumeup")
                feedback(-15f)
                awaitCommand(1, "volumeup")
                feedback(-6f) // 80%, beyond the requested 75%.
                // A following request drains the same real HTTP executor.
                dacp.playResume().get(5, TimeUnit.SECONDS)
                assertEquals(listOf("/ctrl-int/1/volumeup", "/ctrl-int/1/volumeup", "/ctrl-int/1/playresume"), commands())

                main { service.setClientVolume(0.2f) }
                awaitCommand(3, "volumedown")
                feedback(-24f) // Exactly 20%.
                dacp.playResume().get(5, TimeUnit.SECONDS)
                assertEquals("/ctrl-int/1/playresume", commands().last())
                assertEquals("Convergence sent an extra volume step", 5, commands().size)
                assertEquals(0.2f, service.clientVolume(), 0.001f)

                main {
                    service.setClientVolume(0.55f)
                    service.setClientVolume(0.9f)
                }
                awaitCommand(5, "volumeup")
                await("Missing feedback did not release the pending sender volume target") {
                    var inactive = false
                    main { inactive = targetField.getFloat(service) < 0f }
                    inactive
                }
                feedback(-18f) // Late feedback must not resume the expired request.
                dacp.playResume().get(5, TimeUnit.SECONDS)
                assertEquals("Late feedback sent a volume command after timeout", 7, commands().size)
                assertEquals("/ctrl-int/1/playresume", commands().last())

                // A sender that reports the same level must never be driven indefinitely.
                val firstRepeatedStep = commands().size
                main { service.setClientVolume(0.95f) }
                repeat(32) { step ->
                    awaitCommand(firstRepeatedStep + step, "volumeup")
                    feedback(-18f)
                }
                dacp.playResume().get(5, TimeUnit.SECONDS)
                assertEquals("Unchanged feedback exceeded the 32-command limit", firstRepeatedStep + 33, commands().size)
                assertEquals("/ctrl-int/1/playresume", commands().last())
                assertEquals("Independent sender control changed receiver volume", receiverIndex, audio.getStreamVolume(stream))
            } finally {
                main {
                    field.set(service, originalDacp)
                    dacp.release()
                    val editor = prefs.edit()
                    if (previousSync != null) editor.putBoolean(key, previousSync) else editor.remove(key)
                    assertTrue(editor.commit())
                }
                instrumentation.waitForIdleSync()
                main { audio.setStreamVolume(stream, receiverVolume, 0) }
            }
        }
    }

    @Test fun synchronizedMuteRestoresReceiverVolumeWhenTheSenderDisconnects() {
        val prefs = context.getSharedPreferences(Prefs.NAME, 0)
        val syncKey = Prefs.SYNC_CLIENT_VOLUME.key
        val launchKey = Prefs.LAUNCH_ON_CONNECT.key
        val previousSync = prefs.all[syncKey] as? Boolean
        val previousLaunch = prefs.all[launchKey] as? Boolean
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val originalVolume = audio.getStreamVolume(stream)
        val maxVolume = audio.getStreamMaxVolume(stream)
        assertTrue("Expected an adjustable emulator media stream", maxVolume > 2 && !audio.isVolumeFixed)
        val receiverIndex = kotlin.math.round(maxVolume / 2f).toInt()
        try {
            main {
                assertTrue(prefs.edit().putBoolean(syncKey, true).putBoolean(launchKey, false).commit())
            }
            instrumentation.waitForIdleSync()
            val requested = receiverIndex.toFloat() / maxVolume
            main { service.setClientVolume(requested) }
            await("Setting synchronized volume did not change the media stream") { audio.getStreamVolume(stream) == receiverIndex }
            assertEquals(-30f + 30f * requested, service.onClientVolumeQuery(), 0.001f)
            instrumentation.waitForIdleSync()
            main { service.onConnectionInit() }

            main { service.onClientVolume(-144f) }
            await("Sender mute did not mute the media stream") { audio.getStreamVolume(stream) == 0 }
            assertEquals(-144f, service.onClientVolumeQuery(), 0f)
            main { service.onClientVolume(-144f) }
            instrumentation.waitForIdleSync()
            assertEquals(0, audio.getStreamVolume(stream))

            main { service.onConnectionDestroy() }
            await("Disconnect did not restore the volume before mute") { audio.getStreamVolume(stream) == receiverIndex }
            assertEquals(0, service.connectionCount.value)
            assertEquals(requested, service.clientVolume(), 0.001f)
        } finally {
            main {
                if (service.connectionCount.value > 0) service.onConnectionDestroy()
                val editor = prefs.edit()
                if (previousSync == null) editor.remove(syncKey) else editor.putBoolean(syncKey, previousSync)
                if (previousLaunch == null) editor.remove(launchKey) else editor.putBoolean(launchKey, previousLaunch)
                assertTrue(editor.commit())
            }
            instrumentation.waitForIdleSync()
            main { audio.setStreamVolume(stream, originalVolume, 0) }
        }
    }
}
