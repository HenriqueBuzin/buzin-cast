package io.github.jqssun.airplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.leancast.domain.upnp.DidlParser
import io.leancast.domain.upnp.SoapParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/** Exercise the Android XML provider in addition to the JVM provider contracts. */
@RunWith(AndroidJUnit4::class)
class SoapMetadataContractTest {
    private val dc = "http://purl.org/dc/elements/1.1/"
    private fun envelope(args: String, declarations: String = "") =
        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" $declarations><s:Body><u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$args</u:SetAVTransportURI></s:Body></s:Envelope>"""
    private fun metadata(xml: String, declarations: String = "") =
        requireNotNull(SoapParser.parse(null, envelope("<CurrentURIMetaData>$xml</CurrentURIMetaData>", declarations)))
            .args.getValue("CurrentURIMetaData")
    private fun parser(xml: String) = XmlPullParserFactory.newInstance().newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(StringReader(xml))
    }

    @After fun checkpoint() {
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
            DebugCoverage.dump(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test fun rawMetadataRetainsTextAndAttributeCharacters() {
        val result = metadata("""<item xmlns:dc="$dc" xmlns:m="urn:meta" m:label="A &amp; B &quot;Q&quot;"><dc:title>Ação &amp; Música &lt;Ao vivo&gt;</dc:title><res>https://example.invalid/?a=1&amp;b=2</res></item>""")
        assertEquals("Ação & Música <Ao vivo>", DidlParser.extractTitle(result))
        val parsed = parser(result)
        var item = false
        var resource = false
        while (parsed.next() != XmlPullParser.END_DOCUMENT) {
            if (parsed.eventType != XmlPullParser.START_TAG) continue
            when (parsed.name) {
                "item" -> { item = true; assertEquals("A & B \"Q\"", parsed.getAttributeValue("urn:meta", "label")) }
                "res" -> { resource = true; assertEquals("https://example.invalid/?a=1&b=2", parsed.nextText()) }
            }
        }
        assertTrue(item); assertTrue(resource)
    }

    @Test fun inheritedAndShadowedNamespacesRetainTheirBindings() {
        val result = metadata("""<item xmlns="urn:didl" xml:lang="pt"><x:title xmlns:x="urn:foreign">Wrong</x:title><d:title>Correct</d:title><plain xmlns="">A &amp; B</plain></item>""", """xmlns:d="$dc" xmlns:x="urn:outer"""")
        assertEquals("Correct", DidlParser.extractTitle(result))
        val parsed = parser(result)
        val titleNamespaces = mutableListOf<String>()
        var plain = false
        while (parsed.next() != XmlPullParser.END_DOCUMENT) {
            if (parsed.eventType != XmlPullParser.START_TAG) continue
            when (parsed.name) {
                "item" -> assertEquals("pt", parsed.getAttributeValue("http://www.w3.org/XML/1998/namespace", "lang"))
                "title" -> titleNamespaces += parsed.namespace
                "plain" -> { plain = true; assertEquals("", parsed.namespace); assertEquals("A & B", parsed.nextText()) }
            }
        }
        assertTrue(plain)
        assertEquals(listOf("urn:foreign", dc), titleNamespaces)
    }

    @Test fun escapedMetadataAndPlainUriAreDecodedOnlyOnce() {
        val original = """<item xmlns:dc="$dc"><dc:title>A &amp; B</dc:title></item>"""
        val escaped = original.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val request = requireNotNull(SoapParser.parse(null, envelope("<CurrentURI>https://example.invalid/?a=1&amp;b=2</CurrentURI><CurrentURIMetaData>$escaped</CurrentURIMetaData>")))
        assertEquals("https://example.invalid/?a=1&b=2", request.args["CurrentURI"])
        assertEquals(original, request.args["CurrentURIMetaData"])
        assertEquals("A & B", DidlParser.extractTitle(request.args["CurrentURIMetaData"]))
    }
}
