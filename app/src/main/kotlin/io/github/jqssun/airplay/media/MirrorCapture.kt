package io.github.jqssun.airplay.media

import android.content.Context
import android.os.StatFs
import io.buzincast.media.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Disk I/O is isolated from the native decoder and UI. No unbounded queues. */
class MirrorCapture(private val context: Context, private val changed: () -> Unit) {
    data class State(val armed: Boolean = false, val recording: Boolean = false, val message: String = "")
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    val directory: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings")
    private val queue = ArrayBlockingQueue<MirrorPacket>(120)
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile private var terminalFailure: String? = null

    @Synchronized fun start() {
        if (running.get() || worker?.isAlive == true) return
        directory.mkdirs()
        if (StatFs(directory.path).availableBytes < 128L * 1024 * 1024) {
            mutable.value = State(message = "Espaço insuficiente: libere pelo menos 128 MB.")
            return
        }
        queue.clear(); terminalFailure = null; running.set(true)
        mutable.value = State(armed = true, message = "Aguardando quadro-chave. Se necessário, reconecte o AirPlay.")
        changed()
        worker = Thread({
            val file = File(directory, "Buzin-${System.currentTimeMillis()}.mkv")
            val recorder = MatroskaRecorder(file)
            var failure: String? = null
            var lastSpaceCheck = 0L
            try {
                while (running.get() || queue.isNotEmpty()) {
                    val packet = queue.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    recorder.write(packet)
                    if (recorder.videoFrames > 0) {
                        val firstFrame = !mutable.value.recording
                        mutable.value = State(true, true, "Gravando ${file.name}")
                        if (firstFrame) changed()
                    }
                    if (System.currentTimeMillis() - lastSpaceCheck > 3000) {
                        lastSpaceCheck = System.currentTimeMillis()
                        check(StatFs(directory.path).availableBytes > 32L * 1024 * 1024) { "Gravação encerrada: pouco espaço disponível." }
                    }
                }
            } catch (e: Exception) { failure = e.message ?: "Falha ao gravar" }
            finally {
                running.set(false)
                runCatching { recorder.close() }.onFailure { failure = it.message }
                if (recorder.videoFrames == 0L) file.delete()
                mutable.value = State(message = failure ?: terminalFailure ?: if (recorder.videoFrames > 0) "Salvo: ${file.name}" else "Nenhum quadro-chave recebido; nenhum arquivo salvo.")
                queue.clear(); changed()
            }
        }, "buzin-recorder").apply { start() }
    }

    fun offer(packet: MirrorPacket) {
        if (running.get() && !queue.offer(packet)) {
            terminalFailure = "Gravação encerrada: armazenamento não acompanhou a transmissão."
            running.set(false); changed()
        }
    }

    @Synchronized fun stop() { running.set(false); if (worker?.isAlive == true) mutable.value = mutable.value.copy(message = "Finalizando gravação…"); changed() }
    fun close() { stop(); worker?.join(2000) }
    fun active() = running.get()
}
