package io.github.jqssun.airplay

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.buzincast.core.DeviceCapabilities
import io.buzincast.core.ExperienceProfile
import io.buzincast.core.QualityProfiles
import io.buzincast.core.VideoMode
import io.github.jqssun.airplay.media.DeviceCapabilityProbe
import io.github.jqssun.airplay.media.ExperienceProfiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExperienceProfilesContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("experience-profiles-contract", Context.MODE_PRIVATE)

    @After fun cleanup() {
        assertTrue(preferences.edit().clear().commit())
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Test fun presetsApplyVideoAndAudioChoicesAndDefaultRestoresThem() {
        val cinema = VideoMode(1920, 1080, 30)
        val presentation = VideoMode(1920, 1080, 60)
        val games = VideoMode(1280, 720, 60)
        val device = DeviceCapabilities(setOf(cinema, presentation, games), lowLatency = true)
        val names = listOf(Prefs.DEFAULT_PROFILE, "Cinema", "Apresentação", "Jogos")
        assertEquals(names, ExperienceProfiles.names)
        assertEquals(listOf(R.string.setting_profile_default, R.string.setting_profile_cinema,
            R.string.setting_profile_presentation, R.string.setting_profile_games), names.map(ExperienceProfiles::labelRes))
        assertTrue(Prefs.SERVER_NAME.write(preferences, "Media room"))
        assertTrue(Prefs.TV_MARGIN.write(preferences, 7))
        for ((name, mode) in listOf("Cinema" to cinema, "Apresentação" to presentation, "Jogos" to games)) {
            val interactive = name == "Jogos"
            ExperienceProfiles.apply(preferences, name, device)
            assertEquals(name, Prefs.EXPERIENCE_PROFILE.read(preferences))
            assertEquals("${mode.width}x${mode.height}", Prefs.RESOLUTION.read(preferences))
            assertEquals(mode.fps, Prefs.MAX_FPS.read(preferences))
            assertEquals(Prefs.OFF, Prefs.H265.read(preferences))
            assertEquals(interactive, Prefs.LOW_LATENCY.read(preferences))
            assertEquals(!interactive, Prefs.AUDIO_AUTO_BUFFER.read(preferences))
            assertEquals(if (interactive) 25 else 80, Prefs.AUDIO_CUSHION_MS.read(preferences))
            assertEquals(if (name == "Cinema") 4 else 2, Prefs.AUDIO_ADAPTIVE_STEP.read(preferences))
        }
        ExperienceProfiles.apply(preferences, Prefs.DEFAULT_PROFILE)
        for (preference in listOf(Prefs.EXPERIENCE_PROFILE, Prefs.RESOLUTION, Prefs.MAX_FPS, Prefs.H265,
            Prefs.LOW_LATENCY, Prefs.AUDIO_AUTO_BUFFER, Prefs.AUDIO_CUSHION_MS, Prefs.AUDIO_ADAPTIVE_STEP,
            Prefs.SYNC_CLIENT_VOLUME)) {
            assertEquals(preference.key, preference.def, preference.read(preferences))
        }
        assertEquals("Media room", Prefs.SERVER_NAME.read(preferences))
        assertEquals(7, Prefs.TV_MARGIN.read(preferences))
    }

    @Test fun invalidProfileAndUnsupportedDevicePreserveExistingPreferences() {
        assertTrue(Prefs.EXPERIENCE_PROFILE.write(preferences, "Cinema"))
        assertTrue(Prefs.RESOLUTION.write(preferences, "1280x720"))
        val original = preferences.all.toMap()
        val unsupported = DeviceCapabilities(emptySet(), lowLatency = false)
        assertThrows(IllegalArgumentException::class.java) { ExperienceProfiles.apply(preferences, "Unknown", unsupported) }
        assertThrows(IllegalStateException::class.java) { ExperienceProfiles.apply(preferences, "Jogos", unsupported) }
        assertEquals(original, preferences.all)
    }

    @Test fun aMissingOrUnavailableAvcDecoderDoesNotAdvertiseVideoModes() {
        val expected = DeviceCapabilities(emptySet(), lowLatency = false)
        val otherCodecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter {
            it.isEncoder || it.supportedTypes.none { type -> type.equals("video/avc", ignoreCase = true) }
        }.toTypedArray()
        assertTrue("The emulator must expose codecs other than AVC decoders", otherCodecs.isNotEmpty())
        assertEquals(expected, DeviceCapabilityProbe.read { otherCodecs })
        assertEquals(expected, DeviceCapabilityProbe.read { emptyArray() })
        assertEquals(expected, DeviceCapabilityProbe.read { error("Codec service unavailable") })
    }

    @Test fun implicitProfileUsesTheAvcModesAdvertisedByAndroid() {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filterNot { it.isEncoder }
            .filter { codec -> codec.supportedTypes.any { it.equals("video/avc", ignoreCase = true) } }
            .mapNotNull { codec -> runCatching { codec.getCapabilitiesForType("video/avc") }.getOrNull() }
        val expectedModes = buildSet {
            for ((width, height) in listOf(1280 to 720, 1920 to 1080)) {
                for (fps in listOf(30, 60)) {
                    if (codecs.any { it.videoCapabilities?.areSizeAndRateSupported(width, height, fps.toDouble()) == true }) {
                        add(VideoMode(width, height, fps))
                    }
                }
            }
        }
        assertFalse("The test emulator exposes no supported AVC modes", expectedModes.isEmpty())
        val supportsLowLatency = Build.VERSION.SDK_INT >= 30 && codecs.any {
            it.videoCapabilities != null && it.isFeatureSupported("low-latency")
        }
        val expected = DeviceCapabilities(expectedModes, supportsLowLatency)
        assertEquals(expected, DeviceCapabilityProbe.read())
        val plan = QualityProfiles.choose(ExperienceProfile.GAMES, expected)
        ExperienceProfiles.apply(preferences, "Jogos")
        assertEquals("${plan.mode.width}x${plan.mode.height}", Prefs.RESOLUTION.read(preferences))
        assertEquals(plan.mode.fps, Prefs.MAX_FPS.read(preferences))
        assertEquals(plan.lowLatency, Prefs.LOW_LATENCY.read(preferences))
    }
}
