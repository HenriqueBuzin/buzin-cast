package io.buzincast.core

import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

interface LogStorage {
    fun read(limit: Int): List<String>
    fun write(lines: List<String>)
}
class FileLogStorage(private val file: File) : LogStorage {
    override fun read(limit: Int): List<String> {
        val lines = ArrayDeque<String>()
        if (file.isFile) file.bufferedReader().useLines { source -> source.forEach {
            if (lines.size == limit) lines.removeFirst()
            lines.addLast(it.take(2000))
        } }
        return lines.toList()
    }
    override fun write(lines: List<String>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.bufferedWriter().use { writer -> lines.forEach { writer.append(it).append('\n') } }
        check(temporary.renameTo(file)) { "Cannot replace log snapshot" }
    }
}

/** Bounded nonblocking ingestion; load, batched writes and rotation run on one IO worker. */
class AsyncLogStore(
    private val storage: LogStorage,
    private val maxLines: Int = 2000,
    capacity: Int = 256,
    private val publish: (List<String>) -> Unit = {},
    private val failure: (Exception) -> Unit = {},
) : AutoCloseable {
    private sealed interface Command {
        data class Append(val line: String) : Command
        data object Clear : Command
        data class Barrier(val latch: CountDownLatch) : Command
    }
    private val queue = ArrayBlockingQueue<Command>(capacity)
    private val ingestion = Any()
    @Volatile private var closed = false
    @Volatile private var current: List<String> = emptyList()
    val dropped = AtomicLong()
    init { require(maxLines > 0) } // ArrayBlockingQueue already validates capacity.
    private val worker = Thread({ run() }, "buzin-log-io").apply { isDaemon = true; start() }
    fun snapshot(): List<String> = current.toList()
    fun append(line: String): Boolean = synchronized(ingestion) {
        if (closed) return false
        queue.offer(Command.Append(line.replace('\n', ' ').replace('\r', ' ').take(2000))).also {
            if (!it) dropped.incrementAndGet()
        }
    }
    fun clear(): Boolean = synchronized(ingestion) {
        if (closed) return false
        // Discard only pending append commands; barriers must always be acknowledged.
        queue.removeAll { it is Command.Append }
        queue.offer(Command.Clear)
    }
    /** Test/export boundary, never call on the Android main thread. */
    fun flush(timeoutMs: Long = 5000): Boolean {
        val latch = CountDownLatch(1)
        val accepted = synchronized(ingestion) { !closed && queue.offer(Command.Barrier(latch)) }
        return accepted && latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }
    override fun close() { synchronized(ingestion) { closed = true }; worker.interrupt() }
    fun awaitClosed(timeoutMs: Long = 5000): Boolean { worker.join(timeoutMs); return !worker.isAlive }
    private fun run() {
        val lines = ArrayDeque<String>()
        try { storage.read(maxLines).takeLast(maxLines).forEach { lines.addLast(it.take(2000)) } }
        catch (e: Exception) { failure(e) }
        current = lines.toList(); publish(current)
        while (!closed || queue.isNotEmpty()) {
            val first = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null } ?: continue
            val batch = arrayListOf(first)
            queue.drainTo(batch, 127)
            var dirty = false
            val barriers = mutableListOf<CountDownLatch>()
            batch.forEach { command -> when (command) {
                is Command.Append -> { if (lines.size == maxLines) lines.removeFirst(); lines.addLast(command.line); dirty = true }
                Command.Clear -> { lines.clear(); dirty = true }
                is Command.Barrier -> barriers.add(command.latch)
            } }
            if (dirty) {
                current = lines.toList(); publish(current)
                try { storage.write(current) } catch (e: Exception) { failure(e) }
            }
            barriers.forEach { it.countDown() }
        }
    }
}
