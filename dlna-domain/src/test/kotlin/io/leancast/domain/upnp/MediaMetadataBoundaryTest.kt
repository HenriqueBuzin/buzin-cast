package io.leancast.domain.upnp

import io.leancast.domain.cast.MediaHttpHeaders
import org.junit.Assert.*
import org.junit.Test

class MediaMetadataBoundaryTest {
    @Test fun `missing uri and non web schemes do not synthesize network metadata`() {
        assertNull(MimeInference.guessMimeType(null))
        assertTrue(MediaHttpHeaders.forUri(null).isEmpty())
        assertTrue(MediaHttpHeaders.forUri("file:///movie.mp4").isEmpty())
        assertEquals(MediaHttpHeaders.USER_AGENT,MediaHttpHeaders.forUri("HTTPS://example.invalid/movie")["User-Agent"])
        assertNull(UpnpTime.parseHmsToMs(null))
    }

    @Test fun `last change emits no actions for unknown state and preserves escaped metadata`() {
        val event=LastChangeEvent.avTransport("UNKNOWN",null,"0:00:00","<item title=\"A & B\"/>")
        assertTrue(event.contains("CurrentTransportActions val=&quot;&quot;"))
        assertTrue(event.contains("CurrentTrackURI val=&quot;&quot;"))
        assertTrue(event.contains("CurrentTrackMetaData"))
        assertTrue(event.contains("&amp;lt;item"))
        assertTrue(LastChangeEvent.avTransport("STOPPED",null,"0:00:00").contains("CurrentTransportActions val=&quot;&quot;"))
        assertTrue(LastChangeEvent.renderingControl(12,false).contains("Mute channel=&quot;Master&quot; val=&quot;0&quot;"))
    }
}
