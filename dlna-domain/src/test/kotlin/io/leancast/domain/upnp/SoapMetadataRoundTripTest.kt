package io.leancast.domain.upnp

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Document
import org.xml.sax.InputSource

class SoapMetadataRoundTripTest {
    private val dc = "http://purl.org/dc/elements/1.1/"
    private fun body(arguments: String, declarations: String = "") =
        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" $declarations><s:Body><u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$arguments</u:SetAVTransportURI></s:Body></s:Envelope>"""

    private fun metadata(xml: String, declarations: String = ""): String =
        requireNotNull(SoapParser.parse(null, body("<CurrentURIMetaData>$xml</CurrentURIMetaData>", declarations)))
            .args.getValue("CurrentURIMetaData")

    private fun document(xml: String): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(InputSource(StringReader(xml)))

    @Test fun `nested metadata escapes decoded text and attribute values exactly once`() {
        val result = metadata("""<DIDL-Lite xmlns="urn:didl" xmlns:dc="$dc" xmlns:m="urn:meta"><item id="a&amp;b&quot;c&lt;d&gt;e&apos;f" m:label="A &amp; B"><dc:title>A &amp; B &lt;C&gt; "Q"</dc:title><res>https://example.invalid/video?a=1&amp;b=2</res></item></DIDL-Lite>""")
        assertEquals("A & B <C> \"Q\"", DidlParser.extractTitle(result))
        val xml = document(result)
        val item = xml.getElementsByTagNameNS("urn:didl", "item").item(0) as org.w3c.dom.Element
        assertEquals("a&b\"c<d>e'f", item.getAttribute("id"))
        assertEquals("A & B", item.getAttributeNS("urn:meta", "label"))
        assertEquals("https://example.invalid/video?a=1&b=2", xml.getElementsByTagNameNS("urn:didl", "res").item(0).textContent)
    }

    @Test fun `inherited prefixes and xml attributes survive outside the SOAP envelope`() {
        val result = metadata("""<DIDL-Lite><item xml:lang="pt"><t:title>Example</t:title></item></DIDL-Lite>""", """xmlns:t="$dc"""")
        val xml = document(result)
        assertEquals(1, xml.getElementsByTagNameNS(dc, "title").length)
        assertEquals("pt", (xml.getElementsByTagName("item").item(0) as org.w3c.dom.Element)
            .getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang"))
        assertEquals("Example", DidlParser.extractTitle(result))
    }

    @Test fun `foreign title namespaces do not become Dublin Core after SOAP extraction`() {
        assertNull(DidlParser.extractTitle(metadata("""<item xmlns:x="urn:foreign"><x:title>Foreign title</x:title></item>""")))
    }

    @Test fun `prefix shadowing and default namespace reset retain their meaning`() {
        val result = metadata("""<x:container xmlns:x="urn:inner" xmlns="urn:default"><x:value x:label="a&amp;b">v</x:value><child xmlns="">plain</child></x:container>""", """xmlns:x="urn:outer"""")
        val xml = document(result)
        assertEquals("urn:inner", xml.documentElement.namespaceURI)
        val value = xml.getElementsByTagNameNS("urn:inner", "value").item(0) as org.w3c.dom.Element
        assertEquals("a&b", value.getAttributeNS("urn:inner", "label"))
        assertEquals(1, xml.getElementsByTagNameNS(null, "child").length)
    }

    @Test fun `plain arguments and already escaped XML keep their decoded value`() {
        val didl = """<item xmlns:dc="$dc"><dc:title>A &amp; B</dc:title></item>"""
        val parsed = requireNotNull(SoapParser.parse(null, body("<CurrentURI>https://example.invalid/?a=1&amp;b=2</CurrentURI><CurrentURIMetaData>${XmlUtil.escape(didl)}</CurrentURIMetaData><Empty/>")))
        assertEquals("https://example.invalid/?a=1&b=2", parsed.args["CurrentURI"])
        assertEquals(didl, parsed.args["CurrentURIMetaData"])
        assertEquals("", parsed.args["Empty"])
    }

    @Test fun `multiple child elements remain a valid XML fragment`() {
        val result = metadata("""<first value="a&amp;b"/><second><![CDATA[<text> & more]]></second>""")
        val xml = document("<wrapper>$result</wrapper>")
        assertEquals(2, xml.documentElement.childNodes.length)
        assertEquals("<text> & more", xml.getElementsByTagName("second").item(0).textContent)
    }
}
