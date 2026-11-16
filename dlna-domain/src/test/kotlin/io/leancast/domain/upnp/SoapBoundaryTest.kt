package io.leancast.domain.upnp

import org.junit.Assert.*
import org.junit.Test

class SoapBoundaryTest {
    private val avt="urn:schemas-upnp-org:service:AVTransport:1"
    private fun envelope(content:String)="<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Header/><s:Body>$content</s:Body></s:Envelope>"
    private fun action(name:String="Play",namespace:String=avt,args:String="<InstanceID>0</InstanceID>")="<u:$name xmlns:u=\"$namespace\">$args</u:$name>"

    @Test fun `header and body must agree on both service and action`() {
        val body=envelope(action())
        assertNull(SoapParser.parse("$avt#Stop",body))
        assertNull(SoapParser.parse("urn:schemas-upnp-org:service:RenderingControl:1#Play",body))
        for (header in listOf("","#Play","unknown#Play","$avt#"," \"$avt# \" "))
            assertEquals("Play",SoapParser.parse(header,body)?.action)
        assertEquals("Play",SoapParser.parse(" \"$avt#Play\" ",body)?.action)
    }

    @Test fun `empty missing and foreign service bodies cannot dispatch`() {
        for (body in listOf(envelope(""),"<Envelope/>",envelope(action(namespace="urn:other")),"<Envelope><Body><Play/></Body></Envelope>"))
            assertNull(SoapParser.parse("$avt#Play",body))
    }

    @Test fun `raw nested argument xml and empty arguments are preserved`() {
        val body=envelope(action("SetAVTransportURI",args="<CurrentURI/><CurrentURIMetaData><item id=\"42\"><title>Example</title><empty/></item></CurrentURIMetaData>"))
        val request=SoapParser.parse(null,body)!!
        assertEquals("",request.args["CurrentURI"])
        assertEquals("<item id=\"42\"><title>Example</title><empty></empty></item>",request.args["CurrentURIMetaData"])
    }

    @Test fun `didl skips blank titles and title like tags and catches broken nested titles`() {
        for (metadata in listOf(""," ","<item/>","<item><subtitle>Other</subtitle></item>","<item><dc:title><child/></dc:title></item>"))
            assertNull(DidlParser.extractTitle(metadata))
        assertEquals("Second",DidlParser.extractTitle("<item><dc:title> </dc:title><dc:title> Second </dc:title></item>"))
        assertEquals("Plain",DidlParser.extractTitle("<item><title>Plain</title></item>"))
    }
}
