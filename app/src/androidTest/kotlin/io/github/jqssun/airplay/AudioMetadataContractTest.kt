package io.github.jqssun.airplay

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.audio.DmapParser
import io.github.jqssun.airplay.audio.TrackInfo
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** Independent wire fixtures for metadata received from a sender. */
@RunWith(AndroidJUnit4::class)
class AudioMetadataContractTest {
    private fun item(tag: String, payload: ByteArray) =
        tag.toByteArray(Charsets.US_ASCII) + ByteBuffer.allocate(4).putInt(payload.size).array() + payload
    private fun text(tag: String, value: String) = item(tag, value.toByteArray(Charsets.UTF_8))

    @After fun dump() {
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
            DebugCoverage.dump(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test fun nestedMetadataPreservesUnicodeAndUnsignedIntegerWidths() {
        val integers = linkedMapOf(
            "astm" to byteArrayOf(1, 2, 3, 4), "astn" to byteArrayOf(255.toByte()),
            "asdk" to byteArrayOf(128.toByte(), 1), "asts" to byteArrayOf(),
            "miid" to byteArrayOf(0, 0, 0, 42), "mcti" to byteArrayOf(3),
            "mper" to byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), "asai" to byteArrayOf(4),
            "asri" to byteArrayOf(5), "asci" to byteArrayOf(6), "asgi" to byteArrayOf(7))
        val expected = mapOf("astm" to 16909060L, "astn" to 255L, "asdk" to 32769L,
            "asts" to 0L, "miid" to 42L, "mcti" to 3L, "mper" to 72623859790382856L,
            "asai" to 4L, "asri" to 5L, "asci" to 6L, "asgi" to 7L)
        var payload = text("minm", "Canção 🎵") + text("asar", "Artista") + text("asal", "Álbum") + text("asgn", "Jazz")
        for ((tag, value) in integers) payload += item(tag, value)
        val parsed = DmapParser.parse(item("mlcl", item("mcon", item("mlit", payload))))
        val nested = ((parsed["mlcl"] as Map<*, *>)["mcon"] as Map<*, *>)["mlit"] as Map<*, *>
        for ((tag, value) in expected) assertEquals(tag, value, nested[tag])
        assertEquals(TrackInfo("Canção 🎵", "Artista", "Álbum", "Jazz", 16909060L), TrackInfo.fromDmap(parsed))
    }

    @Test fun malformedLengthsStopParsingWithoutDiscardingEarlierCompleteItems() {
        val valid = text("minm", "First")
        for (length in listOf(-1, 1, Int.MAX_VALUE)) {
            val invalid = "asar".toByteArray() + ByteBuffer.allocate(4).putInt(length).array()
            assertEquals(mapOf("minm" to "First"), DmapParser.parse(valid + invalid))
        }
        for (size in 0..7) {
            assertTrue(DmapParser.parse(ByteArray(size)).isEmpty())
            assertEquals(mapOf("minm" to "First"), DmapParser.parse(valid + ByteArray(size)))
        }
        assertEquals(mapOf("minm" to "Last", "unkn" to ""),
            DmapParser.parse(valid + text("minm", "Last") + text("unkn", "")))
        assertEquals(mapOf("mlit" to emptyMap<String, Any>()), DmapParser.parse(item("mlit", byteArrayOf())))
    }

    @Test fun trackFlatteningUsesDefaultsAndRetainsExistingArtwork() {
        assertEquals(TrackInfo(), TrackInfo.fromDmap(emptyMap()))
        assertEquals(TrackInfo(), TrackInfo.fromDmap(mapOf("minm" to 1L, "asar" to 2L,
            "asal" to 3L, "asgn" to 4L, "astm" to "not an integer")))
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            val track = TrackInfo.fromDmap(linkedMapOf("minm" to "Outer", "mlit" to mapOf("minm" to "Inner")), bitmap)
            assertEquals("Inner", track.title)
            assertSame(bitmap, track.coverArt)
            assertEquals("Outer", TrackInfo.fromDmap(linkedMapOf("mlit" to mapOf("minm" to "Inner"), "minm" to "Outer")).title)
        } finally { bitmap.recycle() }
    }
}
