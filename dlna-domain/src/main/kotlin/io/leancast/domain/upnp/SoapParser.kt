package io.leancast.domain.upnp

import java.io.StringReader
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

/** A parsed SOAP control request. */
data class SoapRequest(
    val service: UpnpService,
    val action: String,
    val args: Map<String, String>,
)

/** Parses SOAP 1.1 control envelopes (UPnP control encoding). */
object SoapParser {

    fun parse(soapActionHeader: String?, body: String): SoapRequest? = parse(soapActionHeader, body, ::newParser)

    /** Provider boundary also lets callers in this module test parser failures. */
    internal fun parse(soapActionHeader: String?, body: String, parserProvider: (String) -> XmlPullParser): SoapRequest? = try {
        val headerRoute = soapActionHeader?.let { routeFromHeader(it) }
        val bodyRoute = routeFromBody(body, parserProvider)
        when {
            bodyRoute == null -> null
            headerRoute != null && (headerRoute.first != bodyRoute.first || headerRoute.second != bodyRoute.second) -> null
            headerRoute != null -> SoapRequest(
                service = headerRoute.first,
                action = headerRoute.second,
                args = bodyRoute.third,
            )
            else -> SoapRequest(
                service = bodyRoute.first,
                action = bodyRoute.second,
                args = bodyRoute.third,
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun routeFromHeader(header: String): Pair<UpnpService, String>? {
        val cleaned = header.trim().trim('"')
        val idx = cleaned.lastIndexOf('#')
        if (idx <= 0) return null
        val service = UpnpService.fromUrn(cleaned.substring(0, idx)) ?: return null
        val action = cleaned.substring(idx + 1).trim()
        if (action.isEmpty()) return null
        return service to action
    }

    /** Resolves service+action+args from the first element inside `Body`, prefix-agnostic. */
    private fun routeFromBody(body: String, parserProvider: (String) -> XmlPullParser): Triple<UpnpService, String, Map<String, String>>? {
        val parser = parserProvider(body)
        var inBody = false
        var actionDepth = -1
        var action: String? = null
        var service: UpnpService? = null
        val args = mutableMapOf<String, String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            val evt = parser.eventType
            val name = parser.name ?: continue
            when {
                evt == XmlPullParser.START_TAG && name == "Body" -> inBody = true
                evt == XmlPullParser.END_TAG && name == "Body" -> break
                inBody && evt == XmlPullParser.START_TAG && action == null -> {
                    action = name.substringAfterLast(':')
                    service = UpnpService.fromUrn(parser.namespace)
                    actionDepth = parser.depth
                }
                // The preceding case handles the first action, so every later
                // start tag here already has an action and an action depth.
                inBody && evt == XmlPullParser.START_TAG &&
                    parser.depth == actionDepth + 1 -> {
                    args[name.substringAfterLast(':')] = captureArgText(parser)
                }
            }
        }
        val a = action ?: return null
        val s = service ?: return null
        return Triple(s, a, args)
    }

    /**
     * Captures an argument's text content, tolerating senders that embed raw
     * (unescaped) XML inside the argument element: child elements are
     * re-serialized into the returned string.
     */
    private fun captureArgText(parser: XmlPullParser): String {
        val depth = parser.depth
        val out = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.TEXT -> out.append(parser.text)
                XmlPullParser.START_TAG -> {
                    out.append('<').append(parser.name)
                    for (i in 0 until parser.attributeCount) {
                        out.append(' ').append(parser.getAttributeName(i))
                            .append("=\"").append(parser.getAttributeValue(i)).append('"')
                    }
                    out.append('>')
                }
                XmlPullParser.END_TAG -> {
                    if (parser.depth == depth) return out.toString()
                    out.append("</").append(parser.name).append('>')
                }
                else -> throw XmlPullParserException("Unexpected event inside SOAP argument")
            }
        }
        throw XmlPullParserException("SOAP argument ended before its closing tag")
    }

    private fun newParser(xml: String): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        return parser
    }
}

/** Extracts `dc:title` from a DIDL-Lite metadata blob; null when absent or malformed. */
object DidlParser {

    fun extractTitle(metadata: String?): String? {
        return extractTitle(metadata) { xml, namespaces ->
            XmlPullParserFactory.newInstance().newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, namespaces)
                setInput(StringReader(xml))
            }
        }
    }

    internal fun extractTitle(metadata: String?, parserProvider: (String, Boolean) -> XmlPullParser): String? {
        if (metadata.isNullOrBlank()) return null
        return try {
            findTitle(metadata, true, parserProvider)
        } catch (_: XmlPullParserException) {
            // Some senders omit xmlns:dc. Keep that legacy spelling available,
            // while a well-formed foreign namespace never takes this fallback.
            try { findTitle(metadata, false, parserProvider) } catch (_: Exception) { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun findTitle(metadata: String, namespaces: Boolean, parserProvider: (String, Boolean) -> XmlPullParser): String? {
        val parser = parserProvider(metadata, namespaces)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && isDcTitle(parser, namespaces)) {
                val text = parser.nextText().trim()
                if (text.isNotEmpty()) return text
            }
        }
        return null
    }

    /**
     * `dc:title` may arrive with a declared namespace or an undeclared `dc:` prefix,
     * so accept either while rejecting other title-like elements with a foreign ns.
     */
    private fun isDcTitle(parser: XmlPullParser, namespaces: Boolean): Boolean {
        // This helper is only called at START_TAG, where name/namespace exist.
        val raw = parser.name
        if (!namespaces) return raw == "title" || raw == "dc:title"
        val ns = parser.namespace
        return raw == "title" && (ns.isEmpty() || ns.startsWith("http://purl.org/dc/"))
    }
}
