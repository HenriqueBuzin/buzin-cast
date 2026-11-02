package io.github.jqssun.airplay.media

import android.content.SharedPreferences
import io.github.jqssun.airplay.Prefs
import io.buzincast.core.*

object ExperienceProfiles {
    val names = listOf("Cinema", "Apresentação", "Jogos")
    fun apply(prefs: SharedPreferences, name: String, capabilities: DeviceCapabilities = DeviceCapabilityProbe.read()) {
        require(name in names)
        val profile = ExperienceProfile.entries[names.indexOf(name)]
        val plan = QualityProfiles.choose(profile, capabilities)
        // One atomic preference edit produces one debounced receiver restart.
        prefs.edit().putString("experience_profile", name)
            .putString(Prefs.RESOLUTION.key, "${plan.mode.width}x${plan.mode.height}")
            .putInt(Prefs.MAX_FPS.key, plan.mode.fps)
            .putString(Prefs.H265.key, Prefs.OFF)
            .putBoolean(Prefs.LOW_LATENCY.key, plan.lowLatency)
            .putBoolean(Prefs.AUDIO_AUTO_BUFFER.key, plan.autoBuffer)
            .putInt(Prefs.AUDIO_CUSHION_MS.key, plan.cushionMs)
            .putInt(Prefs.AUDIO_ADAPTIVE_STEP.key, plan.adaptiveStep)
            .putBoolean(Prefs.SYNC_CLIENT_VOLUME.key, true).apply()
    }
}
