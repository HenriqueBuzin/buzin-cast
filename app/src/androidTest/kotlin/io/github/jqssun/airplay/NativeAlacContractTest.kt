package io.github.jqssun.airplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Non-silent stereo traverses the real RAOP callback, ALAC engine and PCM tap. */
@RunWith(AndroidJUnit4::class)
class NativeAlacContractTest {
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun softwareAlacPreservesEveryStereoSampleAndTheShortFinalPacket() =
        decodeFixture("native_audio", intArrayOf(4096, 128), 4096)

    @Test fun defaultRaopFrameSizeDecodesEveryStereoAlacSample() =
        decodeFixture("native_audio/raop352", intArrayOf(352), 0)

    @Test fun automaticDecoderSelectionPreservesEveryStereoAlacSample() =
        decodeFixture("native_audio/raop352", intArrayOf(352), 352, forceSoftware = false)

    @Test fun enabledCaptureWithoutAConsumerCanAttachALaterSinkWithoutLosingStereoSamples() =
        decodeFixture("native_audio", intArrayOf(4096, 128), 4096, attachSinkAfterFirstDecode = true)

    private fun decodeFixture(assetPath: String, frameCounts: IntArray, samplesPerFrame: Int,
                              attachSinkAfterFirstDecode: Boolean = false, forceSoftware: Boolean = true) {
        assumeTrue("Requires coverage JNI fixture", runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val encoded = assets.open("$assetPath/alac16.packets").use { it.readBytes() }
        val manifest = JSONObject(assets.open("$assetPath/alac16.json").bufferedReader().use { it.readText() })
        assertEquals(manifest.getString("packets_sha256"), sha256(encoded))
        val packets = mutableListOf<ByteArray>()
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            while (input.available() > 0) {
                val length = input.readInt()
                assertTrue(length in 1..8192 && length <= input.available())
                packets += ByteArray(length).also { input.readFully(it) }
            }
        }
        assertEquals(frameCounts.size, packets.size)
        assertEquals(frameCounts.sum(), manifest.getInt("frames"))
        val base = System.nanoTime()
        var offset = 0L
        val timestamps = frameCounts.map { count ->
            val pts = base + offset * 1_000_000_000L / 44100
            offset += count
            // MediaCodec carries presentation timestamps at microsecond precision.
            pts / 1000 * 1000
        }.toLongArray()
        val expected = ByteBuffer.allocate(frameCounts.sum() * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frameCounts.sum()) { frame ->
            expected.putShort(((frame * 97) % 30001 - 15000).toShort())
            expected.putShort(((frame * 137 + 19) % 28001 - 14000).toShort())
        }
        assertEquals(manifest.getString("pcm_sha256"), sha256(expected.array()))
        System.loadLibrary("airplay_native")
        try {
            val records = DebugCoverage.nativeDecodeAlac(packets.toTypedArray(), timestamps, samplesPerFrame,
                attachSinkAfterFirstDecode, forceSoftware)
            assertEquals(packets.size, records.size)
            val actual = ByteBuffer.allocate(expected.capacity())
            records.forEachIndexed { index, bytes ->
                val record = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
                assertEquals(timestamps[index], record.long)
                assertEquals(44100, record.int)
                assertEquals(2, record.int)
                assertEquals(frameCounts[index] * 4, record.remaining())
                actual.put(ByteArray(record.remaining()).also { record.get(it) })
            }
            assertEquals(expected.capacity(), actual.position())
            assertArrayEquals("ALAC changed samples, channel order, or final-packet length", expected.array(), actual.array())
        } finally { DebugCoverage.dump(instrumentation.targetContext) }
    }
}
