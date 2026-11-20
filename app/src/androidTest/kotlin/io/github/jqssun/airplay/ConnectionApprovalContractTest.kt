package io.github.jqssun.airplay

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.media.ConnectionApproval
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ConnectionApprovalContractTest {
    private class ImmediateDecision {
        var now = 0L
        var allow: Boolean? = true
        val requests = mutableListOf<ConnectionApproval.Request>()
        val approval: ConnectionApproval = ConnectionApproval({ now }, 0, ::onRequest)

        private fun onRequest() {
            val request = requireNotNull(approval.request.value)
            requests.add(request)
            allow?.let { approval.answer(request.id, it) }
        }
    }

    @After fun checkpointCoverage() {
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) {
            DebugCoverage.dump(InstrumentationRegistry.getInstrumentation().targetContext)
        }
    }

    @Test fun grantAndDenialExpireAtTheirExactDeadlines() {
        val fixture = ImmediateDecision()
        with(fixture) {
            assertTrue(approval.ask("AirPlay", "192.0.2.1"))
            allow = false
            assertFalse(approval.ask("DLNA", "192.0.2.1"))
            allow = true
            now = 29_999
            assertFalse(approval.ask("DLNA", "192.0.2.1"))
            assertEquals(2, requests.size)
            now = 30_000
            assertTrue(approval.ask("DLNA", "192.0.2.1"))
            now = 299_999
            assertTrue(approval.ask("AirPlay", "192.0.2.1"))
            assertEquals(3, requests.size)
            now = 300_000
            assertTrue(approval.ask("AirPlay", "192.0.2.1"))
            assertEquals(4, requests.size)
        }
    }

    @Test fun grantAndDenialCachesEvictTheOldestEntryAfterSixteenDecisions() {
        for (decision in listOf(true, false)) {
            val fixture = ImmediateDecision().apply { allow = decision }
            repeat(17) { index -> assertEquals(decision, fixture.approval.ask("AirPlay", "192.0.2.${index + 1}")) }
            assertEquals(decision, fixture.approval.ask("AirPlay", "192.0.2.17"))
            assertEquals(17, fixture.requests.size)
            assertEquals(decision, fixture.approval.ask("AirPlay", "192.0.2.1"))
            assertEquals(18, fixture.requests.size)
        }
    }

    @Test fun timedOutRequestIsClearedAndIgnoresLateApproval() {
        val fixture = ImmediateDecision().apply { allow = null }
        val approval = fixture.approval
        assertFalse(approval.ask("AirPlay", "192.0.2.1"))
        val expired = fixture.requests.single()
        assertNull(approval.request.value)
        approval.answer(expired.id, true)
        assertFalse(approval.ask("AirPlay", expired.peer))
        assertEquals(1, fixture.requests.size)
        fixture.now = 30_000
        fixture.allow = true
        assertTrue(approval.ask("AirPlay", expired.peer))
        assertTrue(fixture.requests.last().id > expired.id)
    }

    @Test fun identicalWaitersShareOneRequestWhileOtherPeersAndProtocolsAreDenied() {
        val notifications = LinkedBlockingQueue<ConnectionApproval.Request>()
        lateinit var approval: ConnectionApproval
        approval = ConnectionApproval { notifications.offer(requireNotNull(approval.request.value)) }
        val first = FutureTask { approval.ask("AirPlay", "192.0.2.1") }
        val duplicate = FutureTask { approval.ask("AirPlay", "192.0.2.1") }
        val owner = Thread(first)
        val waiter = Thread(duplicate)
        try {
            owner.start()
            val request = requireNotNull(notifications.poll(5, TimeUnit.SECONDS))
            assertFalse(approval.ask("AirPlay", "192.0.2.2"))
            assertFalse(approval.ask("DLNA", "192.0.2.1"))
            waiter.start()
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (waiter.state != Thread.State.TIMED_WAITING && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(5)
            assertEquals(Thread.State.TIMED_WAITING, waiter.state)
            assertTrue(notifications.isEmpty())
            approval.answer(request.id, true)
            assertTrue(first.get(5, TimeUnit.SECONDS))
            assertTrue(duplicate.get(5, TimeUnit.SECONDS))
            assertNull(approval.request.value)
        } finally {
            approval.close()
            owner.join(5_000)
            waiter.join(5_000)
        }
    }

    @Test fun closeAndReopenDiscardsPriorAnswersAndInterruptionIsPreserved() {
        lateinit var approval: ConnectionApproval
        approval = ConnectionApproval({ 0L }, 0) {
            approval.answer(requireNotNull(approval.request.value).id, true)
            approval.close()
            approval.open()
        }
        assertFalse(approval.ask("AirPlay", "192.0.2.1"))
        assertNull(approval.request.value)

        val published = LinkedBlockingQueue<Boolean>()
        val interruptible = ConnectionApproval { published.offer(true) }
        val result = FutureTask { interruptible.ask("AirPlay", "192.0.2.2") to Thread.currentThread().isInterrupted }
        val caller = Thread(result)
        try {
            caller.start()
            assertEquals(true, published.poll(5, TimeUnit.SECONDS))
            caller.interrupt()
            assertEquals(false to true, result.get(5, TimeUnit.SECONDS))
            assertNull(interruptible.request.value)
        } finally {
            interruptible.close()
            caller.join(5_000)
        }
    }
}
