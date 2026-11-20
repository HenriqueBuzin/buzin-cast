package io.github.jqssun.airplay.renderer

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.VideoCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.GLES20
import android.os.Build
import android.util.Log
import java.util.Locale

// moonlight-android MediaCodecHelper
class DecoderSelector(private val ctx: Context) {

    var maxOperatingRate: Boolean? = null

    private val emulator = isEmulatedDevice(Build.HARDWARE, Build.BRAND)

    private val glRenderer by lazy {
        runCatching { EglCore().use { GLES20.glGetString(GLES20.GL_RENDERER) ?: "" } }
            .getOrDefault("").lowercase().also { Log.i(TAG, "gl renderer: $it") }
    }
    private val adreno by lazy {
        if ("adreno" !in glRenderer) -1
        else Regex("\\d{3}").findAll(glRenderer).lastOrNull()?.value?.toInt() ?: -1
    }
    private val fireOs by lazy {
        ctx.packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
            Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
    }

    private val blacklist by lazy {
        decoderBlacklist(emulator, Build.VERSION.SDK_INT, adreno)
    }

    private val hevcWhitelist by lazy {
        hevcCodecPrefixes(Build.HARDWARE, Build.VERSION.SDK_INT, Build.DEVICE, adreno, fireOs, glRenderer)
    }

    fun avc(): MediaCodecInfo? = _probableSafe(AVC, CodecProfileLevel.AVCProfileHigh) ?: _first(AVC)

    fun hevc(avc: MediaCodecInfo?, w: Int, h: Int, fps: Int, force: Boolean): MediaCodecInfo? {
        val info = _probableSafe(HEVC, -1) ?: return null
        return info.takeIf {
            shouldSelectHevc(
                force = force,
                trusted = { _hevcWhitelisted(info) },
                avcMeetsRequest = {
                    Log.i(TAG, "hevc decoder not whitelisted: ${info.name}")
                    avc?.videoCaps(AVC)?.let { _canMeet(it, w, h, fps) }
                },
                hevcMeetsRequest = { _canMeet(info.videoCaps(HEVC), w, h, fps) },
            )
        }
    }

    fun software(mime: String, w: Int, h: Int): MediaCodecInfo? {
        val decoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.asSequence()
            .filter { !it.isEncoder && it.supportsMime(mime) }
        fun find(qualifies: (MediaCodecInfo) -> Boolean) = decoders.firstOrNull {
            qualifies(it) && _portraitSafe(w, h, it.videoCaps(mime)::isSizeSupported)
        }

        // Some Android images omit the software flag from their platform codecs.
        if (Build.VERSION.SDK_INT >= 29) {
            return find { it.isSoftwareOnly } ?: find { it.hasPlatformSoftwareName() }
        }
        return find { it.hasPlatformSoftwareName() ||
            (!it.name.startsWith("omx.", true) && !it.name.startsWith("c2.", true)) }
    }

    private fun MediaCodecInfo.hasPlatformSoftwareName() =
        name.startsWith("omx.google.", true) || name.startsWith("c2.android.", true)

    fun adaptive(info: MediaCodecInfo, mime: String) = !_inList(noAdaptive, info.name) &&
        runCatching { info.getCapabilitiesForType(mime.lowercase(Locale.ROOT)).isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback) }
            .getOrDefault(false)

    // most to least risky
    fun lowLatencyOptions(format: MediaFormat, info: MediaCodecInfo, mime: String, tryNum: Int): Boolean =
        lowLatencyOptions(format, info.name, tryNum, { _lowLatency(info, mime) }, { adreno })

    internal fun lowLatencyOptions(
        format: MediaFormat, codecName: String, tryNum: Int,
        nativeLowLatency: () -> Boolean, adrenoModel: () -> Int,
    ): Boolean {
        var set = false
        if (tryNum < 1) {
            format.setInteger("low-latency", 1)
            if (nativeLowLatency()) return true
            set = true
        }
        if (tryNum < 2) {
            format.setInteger("vdec-lowlatency", 1)
            set = true
        }
        if (tryNum < 3) {
            val max = maxOperatingRate ?: (_inList(qti, codecName) && adrenoModel() != 620)
            if (max) format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            else format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            set = true
        }
        if (Build.VERSION.SDK_INT >= 26) {
            if (_inList(qti, codecName)) {
                if (tryNum < 4) { format.setInteger("vendor.qti-ext-dec-picture-order.enable", 1); set = true }
                if (tryNum < 5) { format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1); set = true }
            } else if (tryNum < 4) {
                vendorLowLatency.firstOrNull { (prefixes, _) -> _inList(prefixes, codecName) }?.let { (_, keys) ->
                    keys.forEach { (k, v) -> format.setInteger(k, v) }
                    set = true
                }
            }
        }
        return set
    }

    private fun _decoders(mime: String) =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && (Build.VERSION.SDK_INT < 29 || !info.isAlias) &&
                info.supportsMime(mime) && !_blacklisted(info)
        }

    private fun _blacklisted(info: MediaCodecInfo) =
        (!emulator && Build.VERSION.SDK_INT >= 29 && info.isSoftwareOnly) || _inList(blacklist, info.name)

    private fun _probableSafe(mime: String, profile: Int) = try {
        _knownSafe(mime, profile)
    } catch (e: Exception) {
        Log.w(TAG, "caps query failed", e)
        _first(mime)
    }

    // include exynos omx and qti
    private fun _knownSafe(mime: String, profile: Int): MediaCodecInfo? {
        val all = _decoders(mime)
        return (0..1).firstNotNullOfOrNull { round ->
            all.firstOrNull { info ->
                (round == 1 || _lowLatency(info, mime)) &&
                    (profile == -1 || info.getCapabilitiesForType(mime.lowercase(Locale.ROOT)).profileLevels.any { it.profile == profile })
            }
        }
    }

    private fun _first(mime: String) = _decoders(mime).firstOrNull()

    private fun _lowLatency(info: MediaCodecInfo, mime: String) = Build.VERSION.SDK_INT >= 30 &&
        runCatching { info.getCapabilitiesForType(mime.lowercase(Locale.ROOT)).isFeatureSupported(CodecCapabilities.FEATURE_LowLatency) }
            .getOrDefault(false)

    private fun _hevcWhitelisted(info: MediaCodecInfo): Boolean = shouldTrustHevc(
        codecName = info.name,
        hardwareAllowed = { Build.VERSION.SDK_INT < 29 || (info.isHardwareAccelerated && !info.isSoftwareOnly) },
        // Media performance class 12+ provides 1080p60.
        performanceClassSupported = { Build.VERSION.SDK_INT >= 31 && Build.VERSION.MEDIA_PERFORMANCE_CLASS >= 31 },
        nativeLowLatency = { _lowLatency(info, HEVC) },
        knownVendor = { _inList(hevcWhitelist, info.name) },
    )

    private fun _canMeet(caps: VideoCapabilities, w: Int, h: Int, fps: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val target = VideoCapabilities.PerformancePoint(w, h, fps)
            caps.supportedPerformancePoints?.let { pts -> return pts.any { it.covers(target) } }
        }
        return _portraitSafe(w, h) { cw, ch -> _rateSupported(caps, cw, ch, fps) }
    }

    private fun _rateSupported(caps: VideoCapabilities, w: Int, h: Int, fps: Int) = try {
        val achievable = if (Build.VERSION.SDK_INT >= 23) caps.getAchievableFrameRatesFor(w, h) else null
        achievable?.let { fps <= it.upper } ?: caps.areSizeAndRateSupported(w, h, fps.toDouble())
    } catch (_: IllegalArgumentException) {
        false
    }

    // some decoders might only report landscape limit
    private fun _portraitSafe(w: Int, h: Int, check: (Int, Int) -> Boolean) = check(w, h) || (w < h && check(h, w))

    private fun _inList(prefixes: List<String>, name: String) = prefixes.any { name.startsWith(it, ignoreCase = true) }

    private fun MediaCodecInfo.supportsMime(mime: String) = supportedTypes.any { it.equals(mime, ignoreCase = true) }

    companion object {
        private const val TAG = "DecoderSelector"
        const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
        private val qti = listOf("omx.qcom", "c2.qti")
        private val noAdaptive = listOf("omx.intel", "omx.mtk")
        private val vendorLowLatency = listOf(
            listOf("omx.hisi", "c2.hisi") to mapOf(
                "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req" to 1,
                "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy" to -1,
            ),
            listOf("omx.exynos", "c2.exynos") to mapOf("vendor.rtc-ext-dec-low-latency.enable" to 1),
            listOf("omx.amlogic", "c2.amlogic") to mapOf("vendor.low-latency.enable" to 1),
        )

        internal fun isEmulatedDevice(hardware: String, brand: String) =
            hardware == "ranchu" || hardware == "cheets" || brand == "Android-x86"

        internal fun shouldSelectHevc(
            force: Boolean, trusted: () -> Boolean, avcMeetsRequest: () -> Boolean?, hevcMeetsRequest: () -> Boolean,
        ): Boolean = force || trusted() || (avcMeetsRequest() == false && hevcMeetsRequest())

        internal fun shouldTrustHevc(
            codecName: String, hardwareAllowed: () -> Boolean, performanceClassSupported: () -> Boolean,
            nativeLowLatency: () -> Boolean, knownVendor: () -> Boolean,
        ): Boolean = when {
            "sw" in codecName || !hardwareAllowed() -> false
            performanceClassSupported() || nativeLowLatency() -> true
            else -> knownVendor()
        }

        internal fun decoderBlacklist(emulator: Boolean, sdk: Int, adreno: Int): List<String> = buildList {
            if (!emulator) {
                add("omx.google")
                add("AVCDecoder")
                if (sdk < 29) add("OMX.ffmpeg")
            }
            // These software decoders can crash on HEVC streams.
            add("OMX.qcom.video.decoder.hevcswvdec")
            add("OMX.SEC.hevc.sw.dec")
            if (adreno < 400) add("OMX.qcom.video.decoder.hevc")
        }

        internal fun hevcCodecPrefixes(
            hardware: String, sdk: Int, device: String, adreno: Int, fireOs: Boolean, renderer: String,
        ): List<String> = buildList {
            if (hardware == "ranchu") add("omx.google")
            add("omx.exynos")
            // K1 tablets only partially accelerate HEVC.
            if (sdk >= 26 && device.lowercase() !in listOf("shieldtablet", "mocha")) add("omx.nvidia")
            if (sdk >= 26 && device.startsWith("BRAVIA_")) add("omx.mtk")
            if (sdk >= 28 && !device.equals("sabrina", ignoreCase = true)) add("omx.amlogic")
            if (sdk >= 28) add("omx.realtek")
            add("omx.hisi")
            add("c2.")
            if (adreno >= 400) addAll(qti)
            if (fireOs) {
                add("omx.mtk")
                add("omx.amlogic")
            }
            if ("powervr" in renderer) add("omx.mtk")
        }

        fun MediaCodecInfo.videoCaps(mime: String): VideoCapabilities =
            getCapabilitiesForType(mime.lowercase(Locale.ROOT)).videoCapabilities!!
    }
}
