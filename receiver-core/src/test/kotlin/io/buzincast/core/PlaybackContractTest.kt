package io.buzincast.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackContractTest {
    @Test fun `playback snapshots retain defaults and isolate pause seek and buffering updates`() {
        val initial = PlaybackInfo()
        assertEquals(0L, initial.positionMs); assertEquals(0L, initial.durationMs)
        assertTrue(initial.playing); assertEquals(1f, initial.speed, 0f)
        assertFalse(initial.skipSilence); assertFalse(initial.buffering)
        val changed = initial.copy(positionMs=2500, durationMs=90000, playing=false,
            speed=1.5f, skipSilence=true, buffering=true)
        assertEquals(2500L, changed.positionMs); assertEquals(90000L, changed.durationMs)
        assertFalse(changed.playing); assertEquals(1.5f, changed.speed, 0f)
        assertTrue(changed.skipSilence); assertTrue(changed.buffering)
        assertEquals(0L, initial.positionMs); assertTrue(initial.playing)
    }

    @Test fun `media requests preserve sender transport metadata when starting at another position`() {
        val simple = MediaRequest("https://example.invalid/movie", Protocol.DLNA)
        assertEquals("https://example.invalid/movie", simple.location)
        assertEquals(Protocol.DLNA, simple.protocol); assertEquals(0f, simple.time, 0f)
        assertNull(simple.mime); assertTrue(simple.headers.isEmpty())
        val request = MediaRequest(simple.location, Protocol.FCAST, 12f, "video/mp4", mapOf("Accept" to "video/mp4"))
        val resumed = request.copy(time=25f)
        assertEquals(request.location, resumed.location); assertEquals(Protocol.FCAST, resumed.protocol)
        assertEquals(25f, resumed.time, 0f); assertEquals("video/mp4", resumed.mime)
        assertEquals(mapOf("Accept" to "video/mp4"), resumed.headers); assertEquals(12f, request.time, 0f)
        val token = SessionCoordinator().claim(request.protocol, SessionKind.MEDIA)!!
        assertEquals(request.protocol, token.protocol)
    }
}
