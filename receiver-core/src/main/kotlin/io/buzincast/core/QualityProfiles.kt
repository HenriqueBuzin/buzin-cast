package io.buzincast.core

data class VideoMode(val width: Int, val height: Int, val fps: Int)
data class DeviceCapabilities(val modes: Set<VideoMode>, val lowLatency: Boolean)
data class QualityPlan(val mode: VideoMode, val lowLatency: Boolean, val autoBuffer: Boolean, val cushionMs: Int, val adaptiveStep: Int)
enum class ExperienceProfile { CINEMA, PRESENTATION, GAMES }

object QualityProfiles {
    fun choose(profile: ExperienceProfile, device: DeviceCapabilities): QualityPlan {
        val candidates = when (profile) {
            ExperienceProfile.CINEMA -> listOf(VideoMode(1920,1080,30), VideoMode(1280,720,30))
            ExperienceProfile.PRESENTATION -> listOf(VideoMode(1920,1080,60), VideoMode(1920,1080,30), VideoMode(1280,720,60), VideoMode(1280,720,30))
            ExperienceProfile.GAMES -> listOf(VideoMode(1280,720,60), VideoMode(1280,720,30))
        }
        val mode = candidates.firstOrNull { it in device.modes }
            ?: device.modes.filter { it.width > 0 && it.height > 0 && it.fps > 0 }
                .minWithOrNull(compareBy<VideoMode> { it.width.toLong()*it.height }.thenBy { it.fps })
            ?: error("No supported video mode")
        return QualityPlan(mode, profile == ExperienceProfile.GAMES && device.lowLatency,
            profile != ExperienceProfile.GAMES, if (profile == ExperienceProfile.GAMES) 25 else 80,
            if (profile == ExperienceProfile.CINEMA) 4 else 2)
    }
}
