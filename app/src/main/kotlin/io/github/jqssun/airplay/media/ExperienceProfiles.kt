package io.github.jqssun.airplay.media

import android.content.SharedPreferences
import io.github.jqssun.airplay.Prefs

object ExperienceProfiles {
    val names = listOf("Cinema", "Apresentação", "Jogos")
    fun apply(prefs: SharedPreferences, name: String) {
        require(name in names)
        // One atomic preference edit produces one debounced receiver restart.
        prefs.edit().putString("experience_profile", name)
            .putString(Prefs.RESOLUTION.key, if (name == "Jogos") "1280x720" else "1920x1080")
            .putInt(Prefs.MAX_FPS.key, if (name == "Cinema") 30 else 60)
            .putString(Prefs.H265.key, Prefs.OFF)
            .putBoolean(Prefs.LOW_LATENCY.key, name == "Jogos")
            .putBoolean(Prefs.AUDIO_AUTO_BUFFER.key, name != "Jogos")
            .putInt(Prefs.AUDIO_CUSHION_MS.key, if (name == "Jogos") 25 else 80)
            .putInt(Prefs.AUDIO_ADAPTIVE_STEP.key, if (name == "Cinema") 4 else 2)
            .putBoolean(Prefs.SYNC_CLIENT_VOLUME.key, true).apply()
    }
}
