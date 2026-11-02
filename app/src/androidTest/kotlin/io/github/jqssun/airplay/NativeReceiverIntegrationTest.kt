package io.github.jqssun.airplay

import android.content.*
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.discovery.AirPlayReceiverAdapter
import io.github.jqssun.airplay.service.AirPlayService
import io.leancast.app.Net
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real JNI engine and RTSP socket, exercising service/adapter/NSD startup and teardown. */
@RunWith(AndroidJUnit4::class)
class NativeReceiverIntegrationTest {
    @Test fun nativeReceiverAnswersRtspAndReleasesItsSocket() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(Prefs.NAME,0)
        val approval = Prefs.APPROVE_CONNECTIONS.read(prefs)
        val pin = Prefs.REQUIRE_PIN.read(prefs)
        val launch = Prefs.LAUNCH_ON_CONNECT.read(prefs)
        val originalPort = Prefs.SERVER_PORT.read(prefs)
        Prefs.APPROVE_CONNECTIONS.write(prefs,false); Prefs.REQUIRE_PIN.write(prefs,false); Prefs.LAUNCH_ON_CONNECT.write(prefs,false)
        val activity = instrumentation.startActivitySync(Intent(context, DebugTvPlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val bound = CountDownLatch(1)
        var service: AirPlayService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { service = (binder as AirPlayService.LocalBinder).service; bound.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        var binding = false
        var actualPort = 0
        try {
            ContextCompat.startForegroundService(context, Intent(context, AirPlayService::class.java).setAction(AirPlayService.ACTION_START_SERVER))
            binding = context.bindService(Intent(context, AirPlayService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue(binding); assertTrue(bound.await(10, TimeUnit.SECONDS))
            val receiver = requireNotNull(service)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (receiver.serverState.value != AirPlayService.ServerState.RUNNING) { assertTrue("Native server startup failed", System.nanoTime() < deadline); Thread.sleep(50) }
            val field = AirPlayService::class.java.getDeclaredField("airPlayReceiver").apply { isAccessible = true }
            val port = (field.get(receiver) as AirPlayReceiverAdapter).port
            actualPort = port
            Socket(requireNotNull(Net.lanIpv4()), port).use { socket ->
                socket.soTimeout = 5000
                // Fragment the request to exercise actual stream framing rather than callback injection.
                socket.getOutputStream().write("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n".toByteArray())
                Thread.sleep(20)
                socket.getOutputStream().write("User-Agent: BuzinIntegration\r\n\r\n".toByteArray())
                val input = socket.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) { val value = input.read(); assertTrue(value >= 0); head.append(value.toChar()); assertTrue(head.length < 16384) }
                assertTrue(head.toString(),head.toString().startsWith("RTSP/1.0 200"))
                assertTrue(head.toString(),head.toString().contains("CSeq: 1", ignoreCase = true))
            }
            instrumentation.runOnMainSync { receiver.stopServer() }
            java.net.ServerSocket(0).use { occupied ->
                Prefs.SERVER_PORT.write(prefs,occupied.localPort)
                instrumentation.runOnMainSync { receiver.startServer() }
                assertEquals(AirPlayService.ServerState.ERROR,receiver.serverState.value)
                // Cleanup after failure must not destroy the adapter's engine twice.
                instrumentation.runOnMainSync { receiver.stopServer() }
                assertEquals(AirPlayService.ServerState.STOPPED,receiver.serverState.value)
            }
            Prefs.SERVER_PORT.write(prefs,originalPort)
            instrumentation.runOnMainSync { receiver.startServer() }
            assertEquals(AirPlayService.ServerState.RUNNING,receiver.serverState.value)
        } finally {
            // A bound service may intentionally outlive stopService; exercise the
            // same explicit stop operation exposed by the app, then release bindings.
            instrumentation.runOnMainSync { service?.stopServer(); activity.finish() }
            context.stopService(Intent(context, AirPlayService::class.java))
            if (binding) context.unbindService(connection)
            instrumentation.waitForIdleSync()
            Prefs.SERVER_PORT.write(prefs,originalPort)
            Prefs.APPROVE_CONNECTIONS.write(prefs,approval); Prefs.REQUIRE_PIN.write(prefs,pin); Prefs.LAUNCH_ON_CONNECT.write(prefs,launch)
            if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
        }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            val connectionProbe = runCatching { Socket(requireNotNull(Net.lanIpv4()), actualPort) }.getOrNull() ?: break
            connectionProbe.close()
            assertTrue("Native socket still accepts after service shutdown", System.nanoTime() < end)
            Thread.sleep(50)
        }
    }
}
