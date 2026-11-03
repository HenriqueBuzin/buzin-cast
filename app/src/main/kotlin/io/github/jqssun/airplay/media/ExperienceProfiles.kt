package io.github.jqssun.airplay.media

import android.content.SharedPreferences
import io.github.jqssun.airplay.Prefs
import io.buzincast.core.*

object ExperienceProfiles {
    private val presets = linkedMapOf("Cinema" to ExperienceProfile.CINEMA,
        "Apresentação" to ExperienceProfile.PRESENTATION, "Jogos" to ExperienceProfile.GAMES)
    val names = listOf(Prefs.DEFAULT_PROFILE) + presets.keys
    fun apply(prefs: SharedPreferences, name: String, capabilities: DeviceCapabilities? = null) {
        require(name in names)
        val plan = presets[name]?.let { QualityProfiles.choose(it, capabilities ?: DeviceCapabilityProbe.read()) }
        // One atomic preference edit produces one debounced receiver restart.
        prefs.edit().putString(Prefs.EXPERIENCE_PROFILE.key, name)
            .putString(Prefs.RESOLUTION.key, plan?.mode?.let { "${it.width}x${it.height}" } ?: Prefs.RESOLUTION.def)
            .putInt(Prefs.MAX_FPS.key, plan?.mode?.fps ?: Prefs.MAX_FPS.def)
            .putString(Prefs.H265.key, if (plan == null) Prefs.H265.def else Prefs.OFF)
            .putBoolean(Prefs.LOW_LATENCY.key, plan?.lowLatency ?: Prefs.LOW_LATENCY.def)
            .putBoolean(Prefs.AUDIO_AUTO_BUFFER.key, plan?.autoBuffer ?: Prefs.AUDIO_AUTO_BUFFER.def)
            .putInt(Prefs.AUDIO_CUSHION_MS.key, plan?.cushionMs ?: Prefs.AUDIO_CUSHION_MS.def)
            .putInt(Prefs.AUDIO_ADAPTIVE_STEP.key, plan?.adaptiveStep ?: Prefs.AUDIO_ADAPTIVE_STEP.def)
            .putBoolean(Prefs.SYNC_CLIENT_VOLUME.key, Prefs.SYNC_CLIENT_VOLUME.def).apply()
    }
}
