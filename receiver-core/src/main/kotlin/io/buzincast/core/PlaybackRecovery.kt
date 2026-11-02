package io.buzincast.core

/** Retry budget belongs to one media session, never to the device lifetime. */
class PlaybackRecovery {
    var attempt: Int = 0
        private set
    fun nextDelay(errorCode: Int, enabled: Boolean): Long? {
        if (!enabled || errorCode !in setOf(2000,2001,2002) || attempt >= 3) return null
        attempt++
        return attempt * 1500L
    }
    fun reset() { attempt = 0 }
}
