package io.github.jqssun.airplay

import android.os.Looper
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.audio.DacpPlayer
import io.github.jqssun.airplay.audio.DacpController
import io.github.jqssun.airplay.audio.TrackInfo
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.google.common.util.concurrent.ListenableFuture
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Public Media3 state is checked on the player's real application looper. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DacpPlayerContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @After fun dump() {
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
            DebugCoverage.dump(instrumentation.targetContext)
    }

    @Test fun senderSnapshotControlsPlaylistMetadataArtworkPositionAndDuration() {
        instrumentation.runOnMainSync {
            var snapshot = DacpPlayer.Snapshot(TrackInfo(), null, 0, false, false)
            var position = 1234L
            val updates = mutableListOf<Boolean>()
            val player = DacpPlayer(Looper.getMainLooper(), { null }, { snapshot }, { position }, updates::add)
            try {
                assertEquals(0, player.mediaItemCount)
                assertEquals(Player.Commands.EMPTY, player.availableCommands)
                assertEquals(Player.STATE_IDLE, player.playbackState)
                val artwork = byteArrayOf(1, 2, 3, 4)
                snapshot = DacpPlayer.Snapshot(TrackInfo("Canção", "Artist", "Album"), artwork, 120000, true, true)
                player.refresh()
                assertEquals(3, player.mediaItemCount)
                assertEquals(1, player.currentMediaItemIndex)
                assertEquals(Player.STATE_READY, player.playbackState)
                assertTrue(player.playWhenReady)
                assertEquals(1234L, player.currentPosition)
                assertEquals(120000L, player.duration)
                assertEquals("Canção", player.mediaMetadata.title.toString())
                assertEquals("Artist", player.mediaMetadata.artist.toString())
                assertEquals("Album", player.mediaMetadata.albumTitle.toString())
                assertArrayEquals(artwork, player.mediaMetadata.artworkData)
                assertFalse(player.isCurrentMediaItemSeekable)
                for (command in listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE))
                    assertTrue("Missing command $command", player.isCommandAvailable(command))
                assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
                assertEquals("current", player.currentTimeline.getWindow(1, Timeline.Window()).uid)
                snapshot = snapshot.copy(artworkData = null, durationMs = 0, playing = false)
                position = 2500
                player.refresh()
                assertNull(player.mediaMetadata.artworkData)
                assertEquals(C.TIME_UNSET, player.duration)
                assertEquals(2500L, player.currentPosition)
                assertFalse(player.playWhenReady)
                // A missing sender is safe even while its last snapshot is active.
                player.play(); player.pause(); player.seekToNextMediaItem(); player.seekToPreviousMediaItem()
                assertTrue("Missing sender changed remote playback state", updates.isEmpty())
                snapshot = snapshot.copy(active = false)
                player.refresh()
                assertEquals(0, player.mediaItemCount)
                assertEquals(Player.Commands.EMPTY, player.availableCommands)
            } finally { player.release() }
        }
    }

    @Test fun playerCommandsReachTheResolvedSenderAndUpdatePlayingAfterSuccess() {
        val requests = CopyOnWriteArrayList<String>()
        val updates = CopyOnWriteArrayList<Boolean>()
        val resolutions = mutableListOf<NsdManager.ResolveListener>()
        val controller = DacpController { _, callback -> resolutions += callback }
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = Thread({
            try {
                repeat(6) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        requests += requireNotNull(reader.readLine())
                        val headers = mutableListOf<String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers += line
                        }
                        check(headers.any { it.equals("Active-Remote: player-token", ignoreCase = true) })
                        socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
            } catch (_: java.io.IOException) { /* close unblocks accept during failed assertions */ }
        }, "dacp-player-sender").apply { isDaemon = true; start() }
        var player: DacpPlayer? = null
        try {
            controller.update("player-fixture", "player-token")
            resolutions.single().onServiceResolved(NsdServiceInfo().apply {
                host = InetAddress.getByName("127.0.0.1"); port = server.localPort
            })
            instrumentation.runOnMainSync {
                player = DacpPlayer(Looper.getMainLooper(), { controller },
                    { DacpPlayer.Snapshot(TrackInfo(), null, 10000, false, true) }, { 0L }, { updates += it })
            }
            fun invoke(name: String, vararg args: Any): ListenableFuture<*> {
                var result: ListenableFuture<*>? = null
                instrumentation.runOnMainSync {
                    val method = DacpPlayer::class.java.declaredMethods.single { it.name == name }
                    method.isAccessible = true
                    result = method.invoke(player, *args) as ListenableFuture<*>
                }
                return requireNotNull(result)
            }
            invoke("handleSetPlayWhenReady", true).get(5, TimeUnit.SECONDS)
            invoke("handleSetPlayWhenReady", false).get(5, TimeUnit.SECONDS)
            for (command in listOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
                invoke("handleSeek", 1, 0L, command).get(5, TimeUnit.SECONDS)
            worker.join(5000)
            assertFalse("Sender worker did not finish", worker.isAlive)
            assertEquals(listOf(true, false), updates.toList())
            assertEquals(listOf("play", "pause", "nextitem", "nextitem", "previtem", "previtem")
                .map { "GET /ctrl-int/1/$it HTTP/1.1" }, requests.toList())
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            controller.release(); server.close(); worker.join(5000)
            assertFalse("Sender cleanup left a worker", worker.isAlive)
        }
    }

    @Test fun unresolvedSenderFailureDoesNotConfirmAPlaybackChange() {
        val updates = CopyOnWriteArrayList<Boolean>()
        val controller = DacpController { _, _ -> }
        var player: DacpPlayer? = null
        var pending: ListenableFuture<*>? = null
        try {
            instrumentation.runOnMainSync {
                player = DacpPlayer(Looper.getMainLooper(), { controller },
                    { DacpPlayer.Snapshot(TrackInfo(), null, -1, false, true) }, { 0L }, { updates += it })
                assertEquals(C.TIME_UNSET, requireNotNull(player).duration)
                val method = DacpPlayer::class.java.getDeclaredMethod("handleSetPlayWhenReady", Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                pending = method.invoke(player, true) as ListenableFuture<*>
            }
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) {
                requireNotNull(pending).get(5, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is java.io.IOException)
            assertTrue("Failed remote play was confirmed locally", updates.isEmpty())
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            controller.release()
        }
    }
}
