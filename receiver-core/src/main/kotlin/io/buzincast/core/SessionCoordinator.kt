package io.buzincast.core

enum class Protocol { AIRPLAY, DLNA, FCAST, CAST_V2, RELAY, LOCAL }
enum class SessionKind { MIRROR, AUDIO, MEDIA, PHOTO }
enum class SessionPhase { CONNECTING, PLAYING, RECOVERING }
@ConsistentCopyVisibility
data class SessionToken internal constructor(val generation: Long, val protocol: Protocol)
data class ReceiverSession(val token: SessionToken, val kind: SessionKind, val phase: SessionPhase)

/** One owner for the display/player. Tokens make late callbacks harmless after replacement. */
class SessionCoordinator {
    private var generation = 0L
    private var active: ReceiverSession? = null
    @Synchronized fun snapshot(): ReceiverSession? = active
    @Synchronized fun claim(protocol: Protocol, kind: SessionKind, replace: Boolean = false): SessionToken? {
        if (active != null && !replace) return null
        val token = SessionToken(++generation, protocol)
        active = ReceiverSession(token, kind, SessionPhase.CONNECTING)
        return token
    }
    @Synchronized fun owns(token: SessionToken): Boolean = active?.token == token
    @Synchronized fun transition(token: SessionToken, phase: SessionPhase): Boolean {
        val session = active?.takeIf { it.token == token } ?: return false
        active = session.copy(phase = phase)
        return true
    }
    @Synchronized fun finish(token: SessionToken): Boolean {
        if (active?.token != token) return false
        active = null
        return true
    }
    @Synchronized fun reset() { active = null; generation++ }
}
