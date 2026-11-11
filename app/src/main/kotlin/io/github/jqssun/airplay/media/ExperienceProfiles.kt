package io.github.jqssun.airplay.media

import android.content.SharedPreferences
import androidx.annotation.StringRes
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.buzincast.core.*

object ExperienceProfiles {
    private data class Preset(@StringRes val label: Int, val profile: ExperienceProfile? = null)

    // The keys are persisted preferences, independent of the display language.
    private val presets = linkedMapOf(
        Prefs.DEFAULT_PROFILE to Preset(R.string.setting_profile_default),
        "Cinema" to Preset(R.string.setting_profile_cinema, ExperienceProfile.CINEMA),
        "Apresentação" to Preset(R.string.setting_profile_presentation, ExperienceProfile.PRESENTATION),
        "Jogos" to Preset(R.string.setting_profile_games, ExperienceProfile.GAMES),
    )
    val names = presets.keys.toList()

    @StringRes
    fun labelRes(name: String): Int = presets.getValue(name).label

    fun apply(prefs: SharedPreferences, name: String, capabilities: DeviceCapabilities? = null) {
        require(name in names)
        val plan = presets.getValue(name).profile?.let { QualityProfiles.choose(it, capabilities ?: DeviceCapabilityProbe.read()) }
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
