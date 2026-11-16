package io.leancast.domain.ssdp

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class SsdpBoundaryTest {
    private val sender=InetAddress.getByName("192.0.2.10")
    private val identity=SsdpIdentity("uuid:test-device","http://192.0.2.20/desc.xml","Test/1")
    private fun parse(headers:String)=SsdpMessageParser.parse(("M-SEARCH * HTTP/1.1\r\n"+headers+"\r\n\u0000\u0000").toByteArray(),sender,1901)

    @Test fun `empty and unknown discovery headers have explicit rejection reasons`() {
        for (man in listOf("", " \"\" ")) {
            val ignored=parse("MAN: $man\r\nST: ssdp:all\r\n") as SsdpInbound.Ignored
            assertEquals("missing MAN",ignored.reason)
        }
        assertEquals("unknown MAN: notify",(parse("MAN: notify\r\nST: ssdp:all\r\n") as SsdpInbound.Ignored).reason)
        assertEquals("missing ST",(parse("MAN: ssdp:discover\r\nST: \r\n") as SsdpInbound.Ignored).reason)
        assertEquals("missing MAN",(SsdpMessageParser.parse("M-SEARCH".toByteArray(),sender) as SsdpInbound.Ignored).reason)
        for (wire in listOf("\rM-SEARCH\n","\nM-SEARCH\r"))
            assertEquals("missing MAN",(SsdpMessageParser.parse(wire.toByteArray(),sender) as SsdpInbound.Ignored).reason)
        val ignored=SsdpMessageParser.parse(" \r\n".toByteArray(),sender) as SsdpInbound.Ignored
        assertTrue(ignored.reason.startsWith("not M-SEARCH:"))
    }

    @Test fun `absent mx and sender port survive case insensitive header parsing`() {
        val search=parse("bad header\r\n: ignored\r\nman: \"SSDP:DISCOVER\"\r\nst: ssdp:all\r\n") as SsdpInbound.MSearch
        assertEquals("ssdp:all",search.st)
        assertEquals("SSDP:DISCOVER",search.man)
        assertNull(search.mxSeconds)
        assertEquals(sender,search.from)
        assertEquals(1901,search.fromPort)
        assertEquals(0,SsdpInbound.MSearch("ssdp:all",null,null,sender).fromPort)
    }

    @Test fun `uuid searches match our identity case insensitively and reject other devices`() {
        val responder=SsdpResponder(identity)
        fun search(st:String)=SsdpInbound.MSearch(st,null,null,sender)
        val response=responder.responsesFor(search(" UUID:TEST-DEVICE ")).single().decodeToString()
        assertTrue(response.contains("ST: uuid:test-device\r\n"))
        assertTrue(response.contains("USN: uuid:test-device\r\n"))
        assertTrue(response.contains("DATE: "))
        assertTrue(responder.responsesFor(search("uuid:other-device")).isEmpty())
        assertTrue(responder.responseDelayMs(null) in 0..1000)
    }
}
