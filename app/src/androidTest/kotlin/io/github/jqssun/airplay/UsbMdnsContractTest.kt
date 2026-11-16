package io.github.jqssun.airplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** DNS wire boundaries, independent of a physical USB tether or local TVs. */
@RunWith(AndroidJUnit4::class)
class UsbMdnsContractTest {
    private val wireClass = Class.forName("io.github.jqssun.airplay.discovery.UsbMdnsAdvertiserKt")
    private fun wire(method: String, vararg args: Any): Any? =
        wireClass.declaredMethods.single { it.name == method }.apply { isAccessible = true }.invoke(null, *args)
    @Suppress("UNCHECKED_CAST")
    private fun decode(bytes: ByteArray, start: Int = 0) = wire("_readName", bytes, start) as Pair<List<String>, Int>?
    private fun encoded(vararg labels: String): ByteArray = ByteArrayOutputStream().apply {
        for (label in labels) { val bytes = label.toByteArray(); write(bytes.size); write(bytes) }
        write(0)
    }.toByteArray()
    @After fun dump() {
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
            DebugCoverage.dump(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test fun dnsNamesAndCompressionPreserveTheEndOfTheOriginalQuestion() {
        val names = listOf("TV", "_airplay", "_tcp", "local")
        val name = encoded(*names.toTypedArray())
        assertEquals(names to name.size, decode(name))
        val once = name + byteArrayOf(192.toByte(), 0)
        assertEquals(names to once.size, decode(once, name.size))
        val twice = once + byteArrayOf(192.toByte(), name.size.toByte())
        assertEquals(names to twice.size, decode(twice, once.size))
        assertEquals(emptyList<String>() to 1, decode(byteArrayOf(0)))
        assertEquals(listOf("x".repeat(63)) to 65, decode(encoded("x".repeat(63))))
    }

    @Test fun malformedNamesAndReservedLabelEncodingsAreRejected() {
        for (data in listOf(byteArrayOf(), byteArrayOf(1), byteArrayOf(192.toByte()),
            byteArrayOf(192.toByte(), 127), byteArrayOf(192.toByte(), 0),
            byteArrayOf(64) + ByteArray(64) { 97 } + byteArrayOf(0),
            byteArrayOf(128.toByte()) + ByteArray(128) { 97 } + byteArrayOf(0))) {
            assertNull("Accepted malformed DNS name ${data.firstOrNull()}", decode(data))
        }
    }

    @Test fun dnsEncodingTruncatesAtUtf8BoundariesAndNamesCompareWithoutCase() {
        val labels = listOf("a".repeat(62) + "é", "é".repeat(32), "short")
        val encoded = wire("_name", labels) as ByteArray
        assertEquals(listOf("a".repeat(62), "é".repeat(31), "short") to encoded.size, decode(encoded))
        assertEquals(true, wire("_eq", listOf("TV", "LOCAL"), listOf("tv", "local")))
        assertEquals(false, wire("_eq", listOf("TV"), listOf("TV", "local")))
        assertEquals(false, wire("_eq", listOf("TV", "local"), listOf("Other", "local")))
    }
}
