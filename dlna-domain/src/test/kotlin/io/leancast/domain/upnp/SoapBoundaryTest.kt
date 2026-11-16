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

    @Test fun `only first action is dispatched and provider failures cannot dispatch partial requests`() {
        val body=envelope(action()+action("Stop"))
        assertEquals("Play",SoapParser.parse(null,body)?.action)
        assertNull(SoapParser.parse(null,body) { throw java.io.IOException("Parser provider unavailable") })
        val argumentBody=envelope(action(args="<CurrentURI>http://example.invalid/movie</CurrentURI>"))
        assertNull(SoapParser.parse(null,argumentBody) { xml->
            val parser=org.kxml2.io.KXmlParser().apply {
                setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES,true)
                setInput(java.io.StringReader(xml))
            }
            object:org.xmlpull.v1.XmlPullParser by parser {
                var truncated=false
                override fun next():Int {
                    if(truncated) return org.xmlpull.v1.XmlPullParser.END_DOCUMENT
                    val event=parser.next()
                    if(event==org.xmlpull.v1.XmlPullParser.START_TAG && parser.name=="CurrentURI") truncated=true
                    return event
                }
            }
        })
    }

    @Test fun `raw nested argument xml and empty arguments are preserved`() {
        val body=envelope(action("SetAVTransportURI",args="<CurrentURI/><CurrentURIMetaData><item id=\"42\"><title>Example</title><empty/></item></CurrentURIMetaData>"))
        val request=SoapParser.parse(null,body)!!
        assertEquals("",request.args["CurrentURI"])
        val metadata = request.args.getValue("CurrentURIMetaData")
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(metadata)))
        assertEquals("item", document.documentElement.tagName)
        assertEquals("42", document.documentElement.getAttribute("id"))
        assertEquals("Example", document.getElementsByTagName("title").item(0).textContent)
        assertEquals(1, document.getElementsByTagName("empty").length)
        assertEquals(2, document.documentElement.childNodes.length)
        assertEquals("title", document.documentElement.firstChild.nodeName)
        assertEquals("empty", document.documentElement.lastChild.nodeName)
        assertFalse(document.documentElement.lastChild.hasChildNodes())
    }

    @Test fun `comments and cdata are accepted but unexpected provider events cannot dispatch`() {
        val body=envelope(action(args="<CurrentURI><!-- sender comment --><![CDATA[http://example.invalid/movie]]></CurrentURI>"))
        assertEquals("http://example.invalid/movie",SoapParser.parse(null,body)?.args?.get("CurrentURI"))
        assertNull(SoapParser.parse(null,body) { xml->
            val parser=org.kxml2.io.KXmlParser().apply {
                setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES,true)
                setInput(java.io.StringReader(xml))
            }
            object:org.xmlpull.v1.XmlPullParser by parser {
                var event=org.xmlpull.v1.XmlPullParser.START_DOCUMENT
                override fun getEventType()=event
                override fun next():Int {
                    val actual=parser.next()
                    event=if(actual==org.xmlpull.v1.XmlPullParser.TEXT) org.xmlpull.v1.XmlPullParser.PROCESSING_INSTRUCTION else actual
                    return event
                }
            }
        })
    }

    @Test fun `didl skips blank titles and title like tags and catches broken nested titles`() {
        for (metadata in listOf(""," ","<item/>","<item><subtitle>Other</subtitle></item>","<item><dc:title><child/></dc:title></item>"))
            assertNull(DidlParser.extractTitle(metadata))
        assertEquals("Second",DidlParser.extractTitle("<item><dc:title> </dc:title><dc:title> Second </dc:title></item>"))
        assertEquals("Plain",DidlParser.extractTitle("<item><title>Plain</title></item>"))
    }

    @Test fun `didl honors declared namespaces while preserving undeclared dc compatibility`() {
        assertNull(DidlParser.extractTitle("<item xmlns:x=\"urn:foreign\"><x:title>Wrong title</x:title></item>"))
        assertEquals("Right title",DidlParser.extractTitle("<item xmlns:x=\"http://purl.org/dc/elements/1.1/\"><x:title>Right title</x:title></item>"))
        assertEquals("Legacy",DidlParser.extractTitle("<item><dc:title>Legacy</dc:title></item>"))
        assertNull(DidlParser.extractTitle("<item><x:title>Undeclared foreign prefix</x:title></item>"))
        assertEquals("Bare title",DidlParser.extractTitle("<legacy:item><title>Bare title</title></legacy:item>"))
        assertNull(DidlParser.extractTitle("<item/>") { _,_->throw java.io.IOException("Provider unavailable") })
    }
}
