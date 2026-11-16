package io.github.jqssun.airplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import io.github.jqssun.airplay.audio.DacpController
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** HTTP contract against a real local sender with a supplied resolved endpoint. */
@RunWith(AndroidJUnit4::class)
class DacpHttpContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val controllers = mutableListOf<DacpController>()

    private fun controller() = DacpController(context).also { controllers += it }

    // Supply a resolved local endpoint without depending on multicast availability.
    // The real executor, HttpURLConnection and all HTTP bytes remain unmodified.
    private fun resolved(controller: DacpController, port: Int) {
        controller.update("", "contract-token")
        DacpController::class.java.getDeclaredField("host").apply { isAccessible = true }.set(controller, "127.0.0.1")
        DacpController::class.java.getDeclaredField("port").apply { isAccessible = true }.setInt(controller, port)
        assertTrue(controller.ready)
    }

    @After fun cleanup() {
        controllers.forEach(DacpController::release)
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    private class Sender(private val status: Int = 200, private val drop: Boolean = false) : Closeable {
        private val socket = ServerSocket(0)
        private val running = AtomicBoolean(true)
        val requests = CopyOnWriteArrayList<List<String>>()
        val port get() = socket.localPort
        private val worker = Thread({
            while (running.get()) {
                try { socket.accept().use(::respond) }
                catch (_: IOException) { if (!running.get()) break }
            }
        }, "dacp-http-contract").apply { isDaemon = true; start() }

        private fun respond(peer: Socket) {
            peer.soTimeout = 5000
            val reader = peer.getInputStream().bufferedReader(Charsets.US_ASCII)
            val lines = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                lines += line
            }
            requests += lines.toList()
            if (drop) return
            peer.getOutputStream().write("HTTP/1.1 $status Contract\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            peer.getOutputStream().flush()
        }

        override fun close() {
            running.set(false); socket.close(); worker.join(6000)
            assertFalse("DACP fixture did not stop", worker.isAlive)
        }
    }

    private fun fails(future: ListenableFuture<Unit>): Throwable {
        val error = assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
        return requireNotNull(error.cause)
    }

    @Test fun unresolvedEndpointAndResetReturnFailedFuturesWithoutSending() {
        val controller = controller()
        assertFalse(controller.ready)
        assertTrue(fails(controller.play()) is IOException)
        Sender().use { sender ->
            resolved(controller, sender.port)
            controller.activeRemote = ""
            assertFalse(controller.ready)
            assertTrue(fails(controller.pause()) is IOException)
            controller.activeRemote = "contract-token"
            controller.reset()
            assertEquals("", controller.dacpId)
            assertEquals("", controller.activeRemote)
            assertFalse(controller.ready)
            assertTrue(fails(controller.nextItem()) is IOException)
            assertTrue(sender.requests.isEmpty())
        }
        controller.release()
        assertTrue(fails(controller.prevItem()) is IOException)
    }

    @Test fun everyRemoteActionUsesItsGetPathAndActiveRemoteHeader() {
        Sender().use { sender ->
            val controller = controller()
            resolved(controller, sender.port)
            val commands = listOf(
                "play" to controller::play, "pause" to controller::pause,
                "nextitem" to controller::nextItem, "previtem" to controller::prevItem,
                "volumeup" to controller::volumeUp, "volumedown" to controller::volumeDown,
                "beginff" to controller::beginFastForward, "beginrew" to controller::beginRewind,
                "playresume" to controller::playResume,
            )
            for ((_, command) in commands) assertEquals(Unit, command().get(5, TimeUnit.SECONDS))
            assertEquals(commands.size, sender.requests.size)
            for ((index, request) in sender.requests.withIndex()) {
                assertEquals("GET /ctrl-int/1/${commands[index].first} HTTP/1.1", request.first())
                assertTrue(request.any { it.equals("Active-Remote: contract-token", ignoreCase = true) })
                assertTrue(request.any { it.equals("Host: 127.0.0.1:${sender.port}", ignoreCase = true) })
            }
        }
    }

    @Test fun acceptsAllSuccessfulHttpStatusesAndRejectsClientAndServerErrors() {
        val controller = controller()
        for (status in listOf(204, 299, 300, 404, 500)) {
            Sender(status).use { sender ->
                resolved(controller, sender.port)
                if (status < 300) assertEquals(Unit, controller.play().get(5, TimeUnit.SECONDS))
                else {
                    val error = fails(controller.play())
                    assertTrue(error is IOException)
                    assertEquals("HTTP $status", error.message)
                }
            }
        }
    }

    @Test fun closedConnectionCompletesTheFutureWithAnError() {
        Sender(drop = true).use { sender ->
            val controller = controller()
            resolved(controller, sender.port)
            assertTrue(fails(controller.play()) is IOException)
            assertTrue(sender.requests.isNotEmpty())
        }
    }

    @Test fun endpointResolvedAfterReleaseReturnsAFailedFutureInsteadOfThrowing() {
        Sender().use { sender ->
            val controller = controller()
            controller.release()
            resolved(controller, sender.port)
            assertTrue(fails(controller.play()) is RejectedExecutionException)
            assertTrue(sender.requests.isEmpty())
        }
    }
}
