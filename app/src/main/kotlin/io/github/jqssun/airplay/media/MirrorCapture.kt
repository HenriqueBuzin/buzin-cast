package io.github.jqssun.airplay.media

import android.content.Context
import android.os.StatFs
import io.buzincast.media.*
import io.github.jqssun.airplay.R
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
            mutable.value = State(message = context.getString(R.string.capture_need_space))
            return
        }
        queue.clear(); terminalFailure = null; running.set(true)
        mutable.value = State(armed = true, message = context.getString(R.string.capture_waiting_keyframe))
        changed()
        worker = Thread({
            val file = File(directory, "Buzin-${System.currentTimeMillis()}.mkv")
            val recorder = MatroskaRecorder(file)
            val recordingMessage = context.getString(R.string.capture_recording_file, file.name)
            var failure: String? = null
            var lastSpaceCheck = 0L
            try {
                while (running.get() || queue.isNotEmpty()) {
                    val packet = queue.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    recorder.write(packet)
                    if (recorder.videoFrames > 0) {
                        val firstFrame = !mutable.value.recording
                        mutable.value = State(true, true, recordingMessage)
                        if (firstFrame) changed()
                    }
                    if (System.currentTimeMillis() - lastSpaceCheck > 3000) {
                        lastSpaceCheck = System.currentTimeMillis()
                        check(StatFs(directory.path).availableBytes > 32L * 1024 * 1024) {
                            context.getString(R.string.capture_space_exhausted)
                        }
                    }
                }
            } catch (e: Exception) { failure = e.localizedMessage ?: context.getString(R.string.capture_failed) }
            finally {
                running.set(false)
                runCatching { recorder.close() }.onFailure { failure = it.message }
                if (recorder.videoFrames == 0L) file.delete()
                val result = if (recorder.videoFrames > 0) {
                    context.getString(R.string.capture_saved_file, file.name)
                } else {
                    context.getString(R.string.capture_no_keyframe)
                }
                mutable.value = State(message = failure ?: terminalFailure ?: result)
                queue.clear(); changed()
            }
        }, "buzin-recorder").apply { start() }
    }

    fun offer(packet: MirrorPacket) {
        if (running.get() && !queue.offer(packet)) {
            terminalFailure = context.getString(R.string.capture_slow_storage)
            running.set(false); changed()
        }
    }

    @Synchronized fun stop() {
        running.set(false)
        if (worker?.isAlive == true) {
            mutable.value = mutable.value.copy(message = context.getString(R.string.capture_finishing))
        }
        changed()
    }
    fun close() { stop(); worker?.join(2000) }
    fun active() = running.get()
}
