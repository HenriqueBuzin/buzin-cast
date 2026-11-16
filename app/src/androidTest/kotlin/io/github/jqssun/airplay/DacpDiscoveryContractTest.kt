package io.github.jqssun.airplay

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.audio.DacpController
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

/** Framework callback ordering, with real service-info values and no multicast dependency. */
@RunWith(AndroidJUnit4::class)
class DacpDiscoveryContractTest {
    private data class Request(val service: NsdServiceInfo, val listener: NsdManager.ResolveListener)
    private val requests = mutableListOf<Request>()
    private val controllers = mutableListOf<DacpController>()
    private fun controller() = DacpController { info, listener -> requests += Request(info, listener) }
        .also { controllers += it }
    private fun resolved(port: Int = 4321) = NsdServiceInfo().apply {
        host = InetAddress.getByName("127.0.0.1")
        setPort(port)
    }

    @After fun cleanup() {
        controllers.forEach(DacpController::release)
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess)
            DebugCoverage.dump(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test fun emptyIdentifierDoesNotResolveAndNonemptyIdentifierUsesDacpServiceType() {
        val controller = controller()
        controller.update("", "token")
        assertTrue(requests.isEmpty())
        assertFalse(controller.ready)
        controller.update("ABC123", "token")
        val request = requests.single()
        assertEquals("iTunes_Ctrl_ABC123", request.service.serviceName)
        assertEquals("_dacp._tcp", request.service.serviceType)
        assertFalse(controller.ready)
        request.listener.onServiceResolved(resolved())
        assertTrue(controller.ready)
    }

    @Test fun resolutionFailureAndFrameworkExceptionsLeaveTheControllerUnavailable() {
        val controller = controller()
        controller.update("missing", "token")
        val request = requests.single()
        request.listener.onResolveFailed(request.service, NsdManager.FAILURE_INTERNAL_ERROR)
        assertFalse(controller.ready)
        val throwing = DacpController { _, _ -> throw IllegalStateException("resolver unavailable") }
            .also { controllers += it }
        throwing.update("sender", "token")
        assertFalse(throwing.ready)
    }

    @Test fun switchingSenderCannotUseThePreviouslyResolvedEndpoint() {
        val controller = controller()
        controller.update("first", "token-one")
        requests.last().listener.onServiceResolved(resolved())
        assertTrue(controller.ready)
        controller.update("second", "token-two")
        assertFalse("New sender must resolve before accepting commands", controller.ready)
    }

    @Test fun staleCallbackCannotResolveANewerSender() {
        val controller = controller()
        controller.update("first", "token-one")
        val old = requests.last()
        controller.update("second", "token-two")
        old.listener.onServiceResolved(resolved())
        assertFalse("Old callback must not supply the second sender's address", controller.ready)
        requests.last().listener.onServiceResolved(resolved(4322))
        assertTrue(controller.ready)
    }

    @Test fun lateCallbackAfterResetCannotReappearWhenOnlyTheTokenChanges() {
        val controller = controller()
        controller.update("sender", "token")
        val old = requests.single()
        controller.reset()
        old.listener.onServiceResolved(resolved())
        controller.update("", "another-token")
        assertFalse("Reset must invalidate pending resolution", controller.ready)
    }

    @Test fun resolutionWithoutHostOrUsablePortCannotEnableControls() {
        val controller = controller()
        controller.update("no-host", "token")
        requests.last().listener.onServiceResolved(NsdServiceInfo().apply { setPort(4321) })
        assertFalse(controller.ready)
        controller.update("no-port", "token")
        requests.last().listener.onServiceResolved(resolved(0))
        assertFalse(controller.ready)
    }
}
