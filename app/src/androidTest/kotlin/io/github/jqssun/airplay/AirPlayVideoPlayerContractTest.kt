package io.github.jqssun.airplay

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.buzincast.core.Protocol
import io.buzincast.core.SessionCoordinator
import io.buzincast.core.SessionKind
import io.buzincast.core.SessionToken
import io.github.jqssun.airplay.renderer.AirPlayVideoPlayer
import io.github.jqssun.airplay.renderer.PlaybackSnapshot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs Media3 on Android using the synthetic local video fixture. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AirPlayVideoPlayerContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val sessions = SessionCoordinator()
    private val snapshots = mutableListOf<Pair<SessionToken, PlaybackSnapshot>>()
    private val recoveries = mutableListOf<Pair<SessionToken, String>>()
    private val failures = mutableListOf<Pair<SessionToken, String>>()
    private val ended = mutableListOf<SessionToken>()
    private lateinit var renderer: AirPlayVideoPlayer
    private lateinit var fixture: File
    private var subtitle: File? = null

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!main(condition)) {
            assertTrue(description, SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(20)
        }
    }

    private fun token() = requireNotNull(sessions.claim(Protocol.AIRPLAY, SessionKind.MEDIA, replace = true))
    private fun playerField(name: String) = AirPlayVideoPlayer::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(renderer)
    private fun player() = playerField("player") as ExoPlayer

    @Before fun prepare() {
        fixture = File.createTempFile("playback-contract", ".mp4", context.cacheDir)
        instrumentation.context.assets.open("download/sample.mp4").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        renderer = main {
            AirPlayVideoPlayer(context).apply {
                onPlaybackInfo = { owner, snapshot -> snapshots += owner to snapshot }
                onRecovery = { owner, message -> recoveries += owner to message }
                onError = { owner, message -> failures += owner to message }
                onEnded = { ended += it }
            }
        }
    }

    @After fun cleanup() {
        if (::renderer.isInitialized) {
            renderer.stop()
            main { }
        }
        subtitle?.delete()
        if (::fixture.isInitialized) fixture.delete()
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Test fun pausedVideoPreservesSurfacePositionAndSubtitleControls() {
        val owner = token()
        val firstTexture = SurfaceTexture(false)
        val secondTexture = SurfaceTexture(false)
        val first = Surface(firstTexture)
        val second = Surface(secondTexture)
        try {
            renderer.setSurface(first)
            renderer.play(Uri.fromFile(fixture).toString(), 0f, token = owner)
            renderer.setRate(0f, owner)
            await("The paused fixture never reached a known duration") {
                snapshots.lastOrNull()?.second?.let { it.ready && !it.playWhenReady && it.duration == 1f } == true
            }

            renderer.setSurface(second)
            renderer.clearSurface(first)
            renderer.setScrubbing(true)
            main {
                assertSame(second, playerField("pendingSurface"))
                assertTrue(player().isScrubbingModeEnabled)
            }
            renderer.setScrubbing(false)
            renderer.scrub(0.25f, owner)
            renderer.setSkipSilence(true)
            renderer.setSpeed(1.5f, owner)
            await("The player did not publish the updated paused position and controls") {
                snapshots.last().second.let {
                    it.position == 0.25f && it.skipSilence && it.speed == 1.5f && it.rate == 0f && !it.playWhenReady
                }
            }
            renderer.seekBy(-1_000)
            main { assertEquals(0L, player().currentPosition) }
            renderer.scrub(0.25f)
            main { assertEquals(250L, player().currentPosition) }

            val caption = File.createTempFile("playback-contract", ".vtt", context.cacheDir).also { subtitle = it }
            caption.writeText("WEBVTT\n\n00:00:00.200 --> 00:00:00.800\nBuzinCast\n")
            renderer.setSubtitle(Uri.fromFile(caption))
            main {
                val configs = requireNotNull(player().currentMediaItem?.localConfiguration).subtitleConfigurations
                assertEquals(Uri.fromFile(caption), configs.single().uri)
                assertEquals(250L, player().currentPosition)
                assertFalse(player().playWhenReady)
            }
            renderer.setSubtitle(null)
            renderer.clearSurface(second)
            main {
                assertTrue(requireNotNull(player().currentMediaItem?.localConfiguration).subtitleConfigurations.isEmpty())
                assertNull(playerField("pendingSurface"))
            }
            renderer.stop(owner)
            main {
                assertNull(playerField("player"))
                assertEquals(owner to PlaybackSnapshot(0f, -1f, 0f, false, false), snapshots.last())
            }
        } finally {
            renderer.stop()
            main { }
            second.release()
            secondTexture.release()
            first.release()
            firstTexture.release()
        }
    }

    @Test fun commandsAndCallbacksFromAReplacedSessionLeaveItsSuccessorIntact() {
        val first = token()
        val url = Uri.fromFile(fixture).toString()
        renderer.play(url, 0f, token = first)
        renderer.setRate(0f, first)
        val previous = main { playerField("activeListener") as Player.Listener }
        val current = token()
        renderer.play(url, 0f, token = current)
        renderer.setRate(0f, current)
        renderer.setClientGain(0.75f, current)
        renderer.setRate(2f, first)
        renderer.setClientGain(0.25f, first)
        renderer.setPlaying(true, first)
        renderer.setSpeed(3f, first)
        renderer.scrub(0.5f, first)
        renderer.stop(first)
        val networkFailure = PlaybackException("Offline", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

        main {
            var title: String? = null
            var size: Triple<Int, Int, Float>? = null
            renderer.onTitle = { _, name -> title = name }
            renderer.onVideoSize = { _, width, height, aspect -> size = Triple(width, height, aspect) }
            val active = playerField("activeListener") as Player.Listener
            val cues = CueGroup(listOf(Cue.Builder().setText("Caption").build()), 0)
            active.onCues(cues)
            active.onMediaMetadataChanged(MediaMetadata.Builder().setTitle("Current").build())
            active.onVideoSizeChanged(VideoSize(320, 180, 2f))
            assertEquals(Triple(640, 180, 640f / 180), size)
            active.onVideoSizeChanged(VideoSize.UNKNOWN)
            assertEquals(Triple(640, 180, 640f / 180), size)
            val snapshotCount = snapshots.size
            val recoveryCount = recoveries.size

            previous.onCues(CueGroup(emptyList(), 0))
            previous.onMediaMetadataChanged(MediaMetadata.EMPTY)
            previous.onVideoSizeChanged(VideoSize(640, 320))
            previous.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
            previous.onPlaybackStateChanged(Player.STATE_READY)
            previous.onPlaybackStateChanged(Player.STATE_ENDED)
            previous.onPlayerError(networkFailure)
            assertEquals("Caption", renderer.subtitleText.value)
            assertEquals("Current", title)
            assertEquals(Triple(640, 180, 640f / 180), size)
            assertEquals(snapshotCount, snapshots.size)
            assertEquals(recoveryCount, recoveries.size)
            assertTrue(failures.isEmpty())
            assertTrue(ended.isEmpty())
            assertFalse(player().playWhenReady)
            assertEquals(0L, player().currentPosition)
            assertEquals(1f, player().playbackParameters.speed, 0f)
            assertEquals(0.75f, player().volume, 0f)

            active.onPlayerError(networkFailure)
            assertEquals(current, recoveries.last().first)
            assertTrue(recoveries.last().second.isNotBlank())
            assertTrue(failures.isEmpty())
            active.onCues(CueGroup(emptyList(), 0))
            active.onMediaMetadataChanged(MediaMetadata.EMPTY)
            assertEquals("", renderer.subtitleText.value)
            assertNull(title)
        }
    }

    @Test fun missingLocalVideoReportsAnErrorAndEndsTheOwningSession() {
        val owner = token()
        val missing = File(fixture.parentFile, "${fixture.name}.missing")
        renderer.play(Uri.fromFile(missing).toString(), 0f, token = owner)
        await("The missing fixture did not end playback") { ended.contains(owner) }
        main {
            assertEquals(listOf(owner), ended)
            assertEquals(owner to "ERROR_CODE_IO_FILE_NOT_FOUND", failures.single())
            assertTrue(recoveries.none { it.second.isNotEmpty() })
        }
    }
}
