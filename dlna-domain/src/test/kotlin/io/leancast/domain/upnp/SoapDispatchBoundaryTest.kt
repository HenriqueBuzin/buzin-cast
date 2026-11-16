package io.leancast.domain.upnp

import io.leancast.domain.cast.*
import org.junit.Assert.*
import org.junit.Test

class SoapDispatchBoundaryTest {
    private class Port:PlayerPort {
        var seek:Long?=null
        var plays=0
        var prepares=0
        var stops=0
        override fun prepareAndPlay(uri:String) { prepares++ }
        override fun play() { plays++ }
        override fun pause() {}
        override fun stop() { stops++ }
        override fun seekTo(ms:Long) { seek=ms }
        override fun currentPositionMs()=-1L
        override fun currentDurationMs()=-1L
    }
    private val port=Port()
    private val session=CastSession(port)
    private val router=SoapRouter(session)
    private fun route(service:UpnpService,action:String,vararg args:Pair<String,String>)=router.route(SoapRequest(service,action,args.toMap()))

    @Test fun `seek without unit target or valid time is an acknowledged no op`() {
        session.setUri("http://example.invalid/movie",null)
        session.play()
        assertTrue(route(UpnpService.AVT,"GetCurrentTransportActions").body.contains("<Actions>Stop</Actions>"))
        for(args in listOf(emptyArray(),arrayOf("Target" to "0:00:01"),arrayOf("Unit" to "REL_TIME"),arrayOf("Unit" to "REL_TIME","Target" to "bad"))) {
            assertEquals(200,route(UpnpService.AVT,"Seek",*args).httpStatus)
            assertNull(port.seek)
        }
        assertEquals(0L,session.positionMs())
        assertEquals(0L,session.durationMs())
    }

    @Test fun `empty session reports zero tracks and no transport actions`() {
        for(action in listOf("GetPositionInfo","GetMediaInfo","GetCurrentTransportActions")) {
            val result=route(UpnpService.AVT,action)
            assertEquals(200,result.httpStatus)
            when(action) {
                "GetPositionInfo" -> assertTrue(result.body.contains("<Track>0</Track>"))
                "GetMediaInfo" -> assertTrue(result.body.contains("<NrTracks>0</NrTracks>"))
                else -> assertTrue(result.body.contains("<Actions></Actions>"))
            }
        }
        route(UpnpService.AVT,"SetAVTransportURI")
        assertEquals(0,port.prepares)
    }

    @Test fun `rendering and connection actions return service specific envelopes and reject unknown actions`() {
        for((service,actions) in listOf(UpnpService.RCS to listOf("SetMute","GetMute","ListPresets","SelectPreset","GetVolumeDBRange"),UpnpService.CM to listOf("GetCurrentConnectionInfo")))
            for(action in actions) {
                val result=route(service,action)
                assertEquals(200,result.httpStatus)
                assertTrue(result.body.contains("$action"+"Response"))
                assertTrue(result.body.contains(service.serviceType))
            }
        for(service in listOf(UpnpService.RCS,UpnpService.CM))
            assertEquals(401,route(service,"Unknown").httpStatus)
        session.setVolume(42)
        for(args in listOf(emptyArray(),arrayOf("DesiredVolume" to "bad"))) {
            assertEquals(200,route(UpnpService.RCS,"SetVolume",*args).httpStatus)
            assertEquals(42,session.volume)
        }
        assertTrue(SoapResponses.getMute(true).contains("<CurrentMute>1</CurrentMute>"))
        assertEquals("&amp;&lt;&gt;&quot;&apos;",XmlUtil.escape("&<>\"'"))
        assertEquals("",XmlUtil.escape(null))
    }

    @Test fun `resume error and prepared callbacks respect session lifecycle`() {
        session.resume() // no stored media
        assertEquals(0,port.prepares)
        session.setUri("http://example.invalid/movie",null)
        session.resume()
        session.resume() // already preparing
        assertEquals(1,port.prepares)
        session.onPlayerPrepared()
        session.pause()
        session.pause() // repeated pause has no running elapsed-clock anchor
        session.resume()
        assertEquals(1,port.plays)
        session.onPlayerPrepared() // duplicate preparation callback
        assertEquals(TransportState.PLAYING,session.state)
        session.onPlayerError()
        val stopped=port.stops
        session.onPlayerError()
        assertEquals(stopped,port.stops)
        session.seekTo(1000)
        assertNull(port.seek)
        assertEquals(0L,session.positionMs())
        assertEquals(0L,session.durationMs())
    }

    @Test fun `advertised control endpoints match service route segments at the configured origin`() {
        val description=RendererDescription("Test TV","uuid:test","Test","Renderer","http://192.0.2.20:49152")
        val xml=DeviceDescriptionDoc(description).deviceXml()
        val document=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.ByteArrayInputStream(xml.toByteArray()))
        val controls=document.getElementsByTagName("controlURL")
        val advertised=(0 until controls.length).map { controls.item(it).textContent }.toSet()
        val origin=java.net.URI(description.baseUrl)
        val routed=UpnpService.entries.map { service->origin.resolve("/ctl/${service.pathSegment}") }
        assertEquals(advertised,routed.map { it.path }.toSet())
        assertTrue(routed.all { it.host=="192.0.2.20" && it.port==49152 })
    }
}
