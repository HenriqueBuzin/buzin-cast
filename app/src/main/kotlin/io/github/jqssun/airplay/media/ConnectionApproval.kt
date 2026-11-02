package io.github.jqssun.airplay.media

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One bounded request; trust is temporary and scoped to protocol + peer IP. */
class ConnectionApproval(private val notify: () -> Unit) {
    data class Request(val id: Long, val protocol: String, val peer: String)
    private data class Pending(val request: Request, val epoch: Long, val latch: CountDownLatch = CountDownLatch(1), var accepted: Boolean = false)
    private val lock = Any()
    private val _request = MutableStateFlow<Request?>(null)
    val request = _request.asStateFlow()
    private var pending: Pending? = null
    private val grants = linkedMapOf<String, Long>()
    private val denied = linkedMapOf<String,Long>()
    private var sequence = 0L
    private var epoch = 0L
    @Volatile private var closed = false
    fun open() { synchronized(lock) { closed = false } }
    fun ask(protocol: String, peer: String): Boolean {
        val key = "$protocol:$peer"
        val item = synchronized(lock) {
            if (closed) return false
            val now = SystemClock.elapsedRealtime()
            grants.entries.removeAll { it.value <= now }
            denied.entries.removeAll { it.value <= now }
            if (denied.containsKey(key)) return false
            if (grants.containsKey(key)) return true
            val current = pending
            if (current != null && (current.request.protocol != protocol || current.request.peer != peer)) return false
            current ?: Pending(Request(++sequence,protocol,peer.take(128)), epoch).also { pending = it; _request.value = it.request; notify() }
        }
        val signalled = try { item.latch.await(25,TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        synchronized(lock) {
            if (closed || item.epoch != epoch) return false
            if (pending === item) { pending = null; _request.value = null }
            if (!closed && signalled && item.accepted) {
                if (grants.size >= 16) grants.remove(grants.keys.first())
                grants[key] = SystemClock.elapsedRealtime() + 5 * 60_000
                return true
            }
            if (denied.size >= 16) denied.remove(denied.keys.first())
            denied[key] = SystemClock.elapsedRealtime() + 30_000
        }
        return false
    }
    fun answer(id: Long, allow: Boolean) = synchronized(lock) {
        pending?.takeIf { it.request.id == id }?.let { it.accepted = allow; it.latch.countDown(); _request.value = null }
    }
    fun close() = synchronized(lock) {
        closed = true; epoch++; grants.clear(); denied.clear(); pending?.latch?.countDown(); pending = null; _request.value = null
    }
}
