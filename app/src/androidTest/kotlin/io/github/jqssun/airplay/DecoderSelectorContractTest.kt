package io.github.jqssun.airplay

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.renderer.DecoderSelector
import io.github.jqssun.airplay.renderer.DecoderSelector.Companion.videoCaps
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Real platform codec inventories/capabilities and production fallback policy. */
@RunWith(AndroidJUnit4::class)
class DecoderSelectorContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    // New emulator images can list the goldfish hardware adapter before the
    // standard software decoder. These contracts exercise generic software
    // policy, not the inventory order or hardware-specific performance limits.
    private fun avc() = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.first {
        !it.isEncoder && it.supportedTypes.any { mime -> mime.equals(DecoderSelector.AVC, true) } &&
            (it.name.startsWith("omx.google.", true) || it.name.startsWith("c2.android.", true))
    }
    private fun privateCall(selector: DecoderSelector, name: String, types: Array<Class<*>>, vararg args: Any): Boolean =
        DecoderSelector::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
            .invoke(selector, *args) as Boolean

    @After fun coverage() {
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Test fun softwareSelectionUsesARealDecoderAndRejectsUnsupportedMimeAndSize() {
        val selector = DecoderSelector(context)
        val info = requireNotNull(selector.software(DecoderSelector.AVC, 64, 48))
        assertFalse(info.isEncoder)
        assertTrue(info.supportedTypes.any { it.equals(DecoderSelector.AVC, true) })
        val name = info.name.lowercase(Locale.ROOT)
        val standardSoftwareName = name.startsWith("omx.google.") || name.startsWith("c2.android.")
        if (Build.VERSION.SDK_INT >= 29) assertTrue(info.isSoftwareOnly || standardSoftwareName)
        else assertTrue(standardSoftwareName || (!name.startsWith("omx.") && !name.startsWith("c2.")))
        val caps = info.videoCaps(DecoderSelector.AVC)
        assertTrue(caps.isSizeSupported(64, 48) || caps.isSizeSupported(48, 64))
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val uppercase = DecoderSelector.AVC.uppercase(Locale.ROOT)
            assertEquals(info.name, selector.software(uppercase, 64, 48)?.name)
            assertEquals(info.videoCaps(DecoderSelector.AVC).supportedWidths, info.videoCaps(uppercase).supportedWidths)
        } finally { Locale.setDefault(originalLocale) }
        assertNull(selector.software("video/x-buzin-contract-unsupported", 64, 48))
        val largestAvcDimension = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.asSequence()
            .filter { !it.isEncoder && it.supportedTypes.any { mime -> mime.equals(DecoderSelector.AVC, true) } }
            .map { it.videoCaps(DecoderSelector.AVC) }
            .maxOf { maxOf(it.supportedWidths.upper, it.supportedHeights.upper) }
        assertNull(selector.software(DecoderSelector.AVC, largestAvcDimension + 2, largestAvcDimension + 2))
    }

    @Test fun selectedCodecsBelongToThePlatformInventoryAndForcedHevcNeverInventsOne() {
        val selector = DecoderSelector(context)
        val inventory = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val selected = requireNotNull(selector.avc())
        assertTrue(inventory.any { it.name == selected.name && !it.isEncoder })
        assertTrue(selected.supportedTypes.any { it.equals(DecoderSelector.AVC, true) })
        val hevc = selector.hevc(selected, 64, 48, 30, true)
        if (hevc != null) {
            assertTrue(inventory.any { it.name == hevc.name && !it.isEncoder })
            assertTrue(hevc.supportedTypes.any { it.equals(DecoderSelector.HEVC, true) })
        } else {
            assertNull(selector.hevc(null, 64, 48, 30, false))
        }
        selector.hevc(selected, 64, 48, 30, false)?.let {
            assertEquals("Forced/unforced selection changed decoder identity", hevc?.name, it.name)
        }
    }

    @Test fun untrustedSoftwareHevcRequiresMeasuredAvcAndHevcCapabilities() {
        val hevc = if (Build.VERSION.SDK_INT >= 29) {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && !it.isAlias && it.isSoftwareOnly &&
                    it.supportedTypes.any { mime -> mime.equals(DecoderSelector.HEVC, ignoreCase = true) }
            }
        } else null
        val selector = DecoderSelector(context) { if (hevc == null) emptyArray() else arrayOf(hevc) }
        val forced = selector.hevc(null, 64, 48, 30, force = true)
        if (hevc == null || !DecoderSelector.isEmulatedDevice(Build.HARDWARE, Build.BRAND)) {
            assertNull(forced)
            return
        }

        assertSame(hevc, forced)
        assertNull("Missing AVC evidence must not select untrusted HEVC",
            selector.hevc(null, 64, 48, 30, force = false))
        val avcDecoder = avc()
        val avcCaps = avcDecoder.videoCaps(DecoderSelector.AVC)
        val hevcCaps = hevc.videoCaps(DecoderSelector.HEVC)
        assertNull("Capable AVC must be preferred over untrusted HEVC",
            selector.hevc(avcDecoder, 64, 48, 30, force = false))
        val width = maxOf(avcCaps.supportedWidths.upper, hevcCaps.supportedWidths.upper) + 64
        val height = maxOf(avcCaps.supportedHeights.upper, hevcCaps.supportedHeights.upper) + 64
        assertNull("Neither codec supports the requested size",
            selector.hevc(avcDecoder, width, height, 30, force = false))
    }

    @Test fun avcFallsBackToTheFirstRegularDecoderWhenTheInitialQueryFails() {
        val first = requireNotNull(DecoderSelector(context).avc())
        val queries = mutableListOf<Int>()
        val selector = DecoderSelector(context) { kind ->
            queries += kind
            check(queries.size != 1) { "Codec inventory temporarily unavailable" }
            arrayOf(first)
        }

        assertSame(first, selector.avc())
        assertEquals(List(2) { MediaCodecList.REGULAR_CODECS }, queries)
    }

    @Test fun anEmptyRegularInventoryDoesNotHideAvailableSoftwareDecoders() {
        val softwareAvc = avc()
        val queries = mutableListOf<Int>()
        val selector = DecoderSelector(context) { kind ->
            queries += kind
            if (kind == MediaCodecList.ALL_CODECS) arrayOf(softwareAvc) else emptyArray()
        }

        assertNull(selector.avc())
        assertNull(selector.hevc(null, 64, 48, 30, force = true))
        assertSame(softwareAvc, selector.software(DecoderSelector.AVC, 64, 48))
        assertNull(selector.software(DecoderSelector.HEVC, 64, 48))
        assertEquals(List(3) { MediaCodecList.REGULAR_CODECS } + List(2) { MediaCodecList.ALL_CODECS }, queries)
    }

    @Test fun encodersDoNotBecomeDecodersEvenWhenTheyAdvertiseTheRequestedMime() {
        val avcEncoder = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.first {
            it.isEncoder && it.supportedTypes.any { mime -> mime.equals(DecoderSelector.AVC, ignoreCase = true) }
        }
        val selector = DecoderSelector(context) { arrayOf(avcEncoder) }

        assertNull(selector.avc())
        assertNull(selector.software(DecoderSelector.AVC, 64, 48))
    }

    @Test fun nativeLowLatencyUsesThePlatformCapabilityAndRejectsUnknownMime() {
        val selector = DecoderSelector(context)
        val info = avc()
        val signature = arrayOf<Class<*>>(MediaCodecInfo::class.java, String::class.java)
        val expected = Build.VERSION.SDK_INT >= 30 &&
            info.getCapabilitiesForType(DecoderSelector.AVC).isFeatureSupported("low-latency")
        assertEquals(expected, privateCall(selector, "_lowLatency", signature, info, DecoderSelector.AVC))
        assertFalse(privateCall(selector, "_lowLatency", signature, info, "video/x-buzin-contract-unsupported"))
    }

    @Test fun genericDecoderRetriesRemoveRiskyKeysAndRespectOperatingRateOverride() {
        val selector = DecoderSelector(context)
        val info = avc()
        assertTrue("This emulator contract requires its standard software AVC decoder",
            info.name.startsWith("omx.google.", true) || info.name.startsWith("c2.android.", true))
        val nativeLowLatency = Build.VERSION.SDK_INT >= 30 &&
            info.getCapabilitiesForType(DecoderSelector.AVC).isFeatureSupported("low-latency")
        for (override in listOf(null, false, true)) {
            selector.maxOperatingRate = override
            assertEquals(override, selector.maxOperatingRate)
            for (attempt in 0..5) {
                val format = MediaFormat.createVideoFormat(DecoderSelector.AVC, 64, 48).apply {
                    setInteger("buzin-contract-sentinel", 47)
                }
                val changed = selector.lowLatencyOptions(format, info, DecoderSelector.AVC, attempt)
                assertEquals(attempt < 3, changed)
                assertEquals(attempt == 0, format.containsKey("low-latency"))
                val nativeOnly = attempt == 0 && nativeLowLatency
                assertEquals(attempt < 2 && !nativeOnly, format.containsKey("vdec-lowlatency"))
                assertEquals(attempt < 3 && !nativeOnly && override == true,
                    format.containsKey(MediaFormat.KEY_OPERATING_RATE))
                assertEquals(attempt < 3 && !nativeOnly && override != true,
                    format.containsKey(MediaFormat.KEY_PRIORITY))
                if (format.containsKey(MediaFormat.KEY_OPERATING_RATE))
                    assertEquals(Short.MAX_VALUE.toInt(), format.getInteger(MediaFormat.KEY_OPERATING_RATE))
                if (format.containsKey(MediaFormat.KEY_PRIORITY)) assertEquals(0, format.getInteger(MediaFormat.KEY_PRIORITY))
                assertEquals(47, format.getInteger("buzin-contract-sentinel"))
                assertEquals(64, format.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(48, format.getInteger(MediaFormat.KEY_HEIGHT))
            }
        }
    }

    @Test fun adaptivePlaybackMatchesCapabilitiesAndFailsClosedForUnknownMime() {
        val selector = DecoderSelector(context)
        val info = avc()
        val blocked = listOf("omx.intel", "omx.mtk").any { info.name.startsWith(it, true) }
        val supported = info.getCapabilitiesForType(DecoderSelector.AVC)
            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)
        assertEquals(!blocked && supported, selector.adaptive(info, DecoderSelector.AVC))
        assertEquals(!blocked && supported, selector.adaptive(info, DecoderSelector.AVC.uppercase(Locale.ROOT)))
        assertFalse(selector.adaptive(info, "video/x-buzin-contract-unsupported"))
    }

    @Test fun emulatorDetectionAcceptsEachSupportedHardwareOrBrand() {
        assertTrue(DecoderSelector.isEmulatedDevice("ranchu", "generic"))
        assertTrue(DecoderSelector.isEmulatedDevice("cheets", "chromeos"))
        assertTrue(DecoderSelector.isEmulatedDevice("generic", "Android-x86"))
        assertFalse(DecoderSelector.isEmulatedDevice("qcom", "Amazon"))
    }

    @Test fun legacySoftwareAndOldAdrenoDecodersAreExcludedAtTheirBoundaries() {
        val unsafeSoftware = listOf("OMX.qcom.video.decoder.hevcswvdec", "OMX.SEC.hevc.sw.dec")
        val legacySoftware = listOf("omx.google", "AVCDecoder")
        assertEquals(unsafeSoftware, DecoderSelector.decoderBlacklist(true, 28, 400))
        assertEquals(legacySoftware + "OMX.ffmpeg" + unsafeSoftware + "OMX.qcom.video.decoder.hevc",
            DecoderSelector.decoderBlacklist(false, 28, 399))
        assertEquals(legacySoftware + unsafeSoftware, DecoderSelector.decoderBlacklist(false, 29, 400))
    }

    @Test fun hevcPrefixesRespectAndroidAndDeviceSpecificExceptions() {
        val generic = setOf("omx.exynos", "omx.hisi", "c2.")
        fun prefixes(hardware: String = "physical", sdk: Int = 28, device: String = "generic",
                     adreno: Int = 399, fireOs: Boolean = false, renderer: String = "") =
            DecoderSelector.hevcCodecPrefixes(hardware, sdk, device, adreno, fireOs, renderer).toSet()
        assertEquals(generic, prefixes(sdk = 25, device = "BRAVIA_test"))
        assertEquals(generic + "omx.google" + "omx.nvidia", prefixes(hardware = "ranchu", sdk = 26))
        assertEquals(generic + "omx.nvidia" + "omx.mtk", prefixes(sdk = 26, device = "BRAVIA_test"))
        for (tablet in listOf("ShieldTablet", "mocha")) assertEquals(generic, prefixes(sdk = 26, device = tablet))
        assertEquals(generic + "omx.nvidia", prefixes(sdk = 27))
        assertEquals(generic + "omx.nvidia" + "omx.amlogic" + "omx.realtek", prefixes(sdk = 28))
        assertEquals(generic + "omx.nvidia" + "omx.realtek", prefixes(device = "SABRINA"))
        assertEquals(generic + "omx.mtk" + "omx.amlogic", prefixes(sdk = 22, fireOs = true))
        assertEquals(generic + "omx.mtk", prefixes(sdk = 22, renderer = "powervr rogue"))
        assertEquals(generic + "omx.qcom" + "c2.qti", prefixes(sdk = 22, adreno = 400))
    }

    @Test fun hevcFallbackRequiresMeasuredAvcInsufficiencyAndAdequateHevc() {
        val unused = { error("Unexpected capability query") }
        assertTrue(DecoderSelector.shouldSelectHevc(true, unused, unused, unused))
        assertTrue(DecoderSelector.shouldSelectHevc(false, { true }, unused, unused))
        assertFalse(DecoderSelector.shouldSelectHevc(false, { false }, { null }, unused))
        assertFalse(DecoderSelector.shouldSelectHevc(false, { false }, { true }, unused))
        assertFalse(DecoderSelector.shouldSelectHevc(false, { false }, { false }, { false }))
        assertTrue(DecoderSelector.shouldSelectHevc(false, { false }, { false }, { true }))
    }

    @Test fun hevcTrustRequiresHardwareBeforeAnyPerformanceOrVendorOverride() {
        val unused = { error("Unexpected capability query") }
        val generic = "omx.vendor.hevc.decoder"
        assertFalse(DecoderSelector.shouldTrustHevc("omx.vendor.hevc.sw.decoder", unused, unused, unused, unused))
        assertFalse(DecoderSelector.shouldTrustHevc(generic, { false }, unused, unused, unused))
        assertTrue(DecoderSelector.shouldTrustHevc(generic, { true }, { true }, unused, unused))
        assertTrue(DecoderSelector.shouldTrustHevc(generic, { true }, { false }, { true }, unused))
        assertTrue(DecoderSelector.shouldTrustHevc(generic, { true }, { false }, { false }, { true }))
        assertFalse(DecoderSelector.shouldTrustHevc(generic, { true }, { false }, { false }, { false }))
    }

    @Test fun qualcommRetriesKeepOnlyTheKeysAllowedAtEachAttempt() {
        val selector = DecoderSelector(context)
        val pictureOrder = "vendor.qti-ext-dec-picture-order.enable"
        val lowLatency = "vendor.qti-ext-dec-low-latency.enable"
        val vendorKeys = Build.VERSION.SDK_INT >= 26
        val firstUnchangedAttempt = if (vendorKeys) 5 else 3
        for (codec in listOf("OMX.QCOM.video.decoder.avc", "c2.QTI.avc.decoder")) {
            for (gpu in listOf(619, 620)) {
                for (attempt in 0..5) {
                    val format = MediaFormat.createVideoFormat(DecoderSelector.AVC, 64, 48)
                    val changed = selector.lowLatencyOptions(format, codec, attempt, { false }, { gpu })
                    assertEquals(attempt < firstUnchangedAttempt, changed)
                    assertEquals(attempt < 1, format.containsKey("low-latency"))
                    assertEquals(attempt < 2, format.containsKey("vdec-lowlatency"))
                    assertEquals(attempt < 3 && gpu != 620, format.containsKey(MediaFormat.KEY_OPERATING_RATE))
                    assertEquals(attempt < 3 && gpu == 620, format.containsKey(MediaFormat.KEY_PRIORITY))
                    assertEquals(vendorKeys && attempt < 4, format.containsKey(pictureOrder))
                    assertEquals(vendorKeys && attempt < 5, format.containsKey(lowLatency))
                    if (format.containsKey(pictureOrder)) assertEquals(1, format.getInteger(pictureOrder))
                    if (format.containsKey(lowLatency)) assertEquals(1, format.getInteger(lowLatency))
                }
            }
        }
    }

    @Test fun qualcommOperatingRateUsesTheDetectedRendererAndFallsBackOnGlFailure() {
        val renderers = listOf(
            "Qualcomm Adreno (TM) 620" to false,
            "Adreno (TM) 530 or 620" to false,
            "Adreno (TM) 730" to true,
            "Adreno unknown" to true,
            "Mesa 620" to true,
            null to true,
        )
        fun verify(expectedMaxRate: Boolean, renderer: () -> String?) {
            var queries = 0
            val selector = DecoderSelector(context, readGlRenderer = { queries++; renderer() }) {
                error("Choosing format keys should not enumerate codecs")
            }
            repeat(2) {
                val format = MediaFormat.createVideoFormat(DecoderSelector.AVC, 64, 48)
                assertTrue(selector.lowLatencyOptions(format, "c2.qti.avc.decoder", 2, nativeLowLatency = { false }))
                assertEquals(expectedMaxRate, format.containsKey(MediaFormat.KEY_OPERATING_RATE))
                assertEquals(!expectedMaxRate, format.containsKey(MediaFormat.KEY_PRIORITY))
            }
            assertEquals("GPU detection should be cached for retry attempts", 1, queries)
        }
        for ((renderer, expectedMaxRate) in renderers) verify(expectedMaxRate) { renderer }
        verify(expectedMaxRate = true) { error("EGL is unavailable") }
    }

    @Test fun vendorKeysAndNativeLowLatencyAreAppliedOnlyAtTheirSupportedAttempts() {
        val selector = DecoderSelector(context)
        data class Vendor(val name: String, val keys: Map<String, Int>)
        val vendors = listOf(
            Vendor("omx.hisi.decoder", mapOf(
                "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req" to 1,
                "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy" to -1,
            )),
            Vendor("C2.EXYNOS.decoder", mapOf("vendor.rtc-ext-dec-low-latency.enable" to 1)),
            Vendor("c2.amlogic.decoder", mapOf("vendor.low-latency.enable" to 1)),
        )
        val knownKeys = vendors.flatMap { it.keys.keys }
        for ((name, keys) in vendors) {
            val initial = MediaFormat.createVideoFormat(DecoderSelector.AVC, 64, 48)
            assertTrue(selector.lowLatencyOptions(initial, name, 0, { true }, { error("Unexpected GPU query") }))
            assertEquals(1, initial.getInteger("low-latency"))
            assertFalse(initial.containsKey("vdec-lowlatency"))
            assertFalse(initial.containsKey(MediaFormat.KEY_PRIORITY))
            assertTrue(knownKeys.none(initial::containsKey))

            for (attempt in listOf(3, 4)) {
                val retry = MediaFormat.createVideoFormat(DecoderSelector.AVC, 64, 48)
                val vendorKeys = Build.VERSION.SDK_INT >= 26 && attempt == 3
                assertEquals(vendorKeys, selector.lowLatencyOptions(retry, name, attempt,
                    { error("Unexpected native feature query") }, { error("Unexpected GPU query") }))
                assertEquals(if (vendorKeys) keys else emptyMap<String, Int>(),
                    knownKeys.filter(retry::containsKey).associateWith(retry::getInteger))
            }
        }
    }

    @Test fun portraitFallbackOnlySwapsDimensionsAfterTheOriginalCheckFails() {
        val selector = DecoderSelector(context)
        data class Case(val width: Int, val height: Int, val supported: Set<Pair<Int, Int>>,
            val expected: Boolean, val calls: List<Pair<Int, Int>>)
        for (case in listOf(
            Case(1080, 1920, setOf(1080 to 1920), true, listOf(1080 to 1920)),
            Case(1080, 1920, setOf(1920 to 1080), true, listOf(1080 to 1920, 1920 to 1080)),
            Case(1080, 1920, emptySet(), false, listOf(1080 to 1920, 1920 to 1080)),
            Case(1920, 1080, emptySet(), false, listOf(1920 to 1080)),
            Case(1080, 1080, emptySet(), false, listOf(1080 to 1080)),
        )) {
            val calls = mutableListOf<Pair<Int, Int>>()
            val check: (Int, Int) -> Boolean = { width, height ->
                calls += width to height
                width to height in case.supported
            }
            val result = privateCall(selector, "_portraitSafe", arrayOf(Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, kotlin.jvm.functions.Function2::class.java), case.width, case.height, check)
            assertEquals(case.expected, result)
            assertEquals(case.calls, calls)
        }
    }

    @Test fun codecPrefixPoliciesAreCaseInsensitiveAndDoNotMatchInteriorText() {
        val selector = DecoderSelector(context)
        val types = arrayOf<Class<*>>(List::class.java, String::class.java)
        for (name in listOf("OMX.QCOM.video.decoder.avc", "c2.QTI.avc.decoder"))
            assertTrue(privateCall(selector, "_inList", types, listOf("omx.qcom", "c2.qti"), name))
        for (name in listOf("decoder.omx.qcom", "omx.google.h264.decoder", ""))
            assertFalse(privateCall(selector, "_inList", types, listOf("omx.qcom", "c2.qti"), name))
        assertFalse(privateCall(selector, "_inList", types, emptyList<String>(), "omx.qcom.avc"))
    }

    @Test fun rateQueriesRejectUnsupportedSizesAndRatesWithoutThrowing() {
        val selector = DecoderSelector(context)
        val caps = requireNotNull(avc().getCapabilitiesForType(DecoderSelector.AVC).videoCapabilities)
        val types = arrayOf<Class<*>>(MediaCodecInfo.VideoCapabilities::class.java,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        assertTrue(privateCall(selector, "_rateSupported", types, caps, 64, 48, 30))
        assertFalse(privateCall(selector, "_rateSupported", types, caps,
            caps.supportedWidths.upper + caps.widthAlignment,
            caps.supportedHeights.upper + caps.heightAlignment, 30))
        assertFalse(privateCall(selector, "_rateSupported", types, caps, 64, 48, Int.MAX_VALUE))
        assertFalse(privateCall(selector, "_canMeet", types, caps, 1_000_000, 1_000_000, 30))
    }

    @Test fun performancePointEvidenceTakesPriorityOverRateEstimates() {
        val selector = DecoderSelector(context)
        val unused: (Int, Int) -> Boolean = { _, _ -> error("Performance points already answered this request") }
        assertTrue(selector.canMeetVideoRequest(1080, 1920, true, unused))
        assertFalse(selector.canMeetVideoRequest(1080, 1920, false, unused))

        val measurements = mutableListOf<Pair<Int, Int>>()
        assertTrue(selector.canMeetVideoRequest(1080, 1920, null) { w, h ->
            measurements += w to h
            w == 1920 && h == 1080
        })
        assertEquals(listOf(1080 to 1920, 1920 to 1080), measurements)
        assertFalse(selector.canMeetVideoRequest(1920, 1080, null) { _, _ -> false })
    }

    @Test fun performancePointsCheckResolutionAndFrameRateTogether() {
        val selector = DecoderSelector(context)
        if (Build.VERSION.SDK_INT < 29) {
            val caps = avc().videoCaps(DecoderSelector.AVC)
            val types = arrayOf<Class<*>>(MediaCodecInfo.VideoCapabilities::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            assertTrue(privateCall(selector, "_canMeet", types, caps, 64, 48, 30))
            return
        }

        val small = MediaCodecInfo.VideoCapabilities.PerformancePoint(640, 480, 30)
        val large = MediaCodecInfo.VideoCapabilities.PerformancePoint(1920, 1080, 60)
        assertTrue(selector.performancePointsCoverRequest(listOf(small, large), 1920, 1080, 60))
        assertFalse(selector.performancePointsCoverRequest(listOf(small), 1920, 1080, 30))
        assertFalse(selector.performancePointsCoverRequest(listOf(large), 1920, 1080, 61))
        assertFalse(selector.performancePointsCoverRequest(emptyList(), 640, 480, 30))
    }
}
