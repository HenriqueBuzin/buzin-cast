package io.buzincast.core

/** Owns optional receivers for one service generation; closes all even if one fails. */
class ReceiverGroup(private val failure: (Exception) -> Unit = {}) : AutoCloseable {
    private val lock = Any()
    private val active = linkedSetOf<ReceiverAdapter>()
    private var closed = false
    fun start(receiver: ReceiverAdapter) {
        val admitted = synchronized(lock) { if (closed) null else active.add(receiver) }
        if (admitted == false) return
        if (admitted == null) { release(receiver); return }
        try { receiver.start() }
        catch (e: Exception) {
            if (synchronized(lock) { active.remove(receiver) }) release(receiver)
            failure(e)
        }
    }
    override fun close() {
        val receivers = synchronized(lock) {
            closed = true
            active.toList().also { active.clear() }
        }
        receivers.asReversed().forEach(::release)
    }
    private fun release(receiver: ReceiverAdapter) {
        try { receiver.close() } catch (e: Exception) { failure(e) }
    }
}
