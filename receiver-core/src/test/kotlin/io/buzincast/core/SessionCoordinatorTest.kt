package io.buzincast.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionCoordinatorTest {
    @Test fun `every protocol and mode owns a session until finished`() {
        for (protocol in Protocol.entries) for (kind in SessionKind.entries) {
            val sessions = SessionCoordinator()
            val token = sessions.claim(protocol,kind)!!
            assertEquals(ReceiverSession(token,kind,SessionPhase.CONNECTING),sessions.snapshot())
            for (phase in SessionPhase.entries) { assertTrue(sessions.transition(token,phase)); assertEquals(phase,sessions.snapshot()!!.phase) }
            assertTrue(sessions.owns(token)); assertTrue(sessions.finish(token)); assertNull(sessions.snapshot())
            assertFalse(sessions.finish(token)); assertFalse(sessions.owns(token)); assertFalse(sessions.transition(token,SessionPhase.PLAYING))
        }
    }
    @Test fun `replacement invalidates all old callbacks including same protocol`() {
        for (from in Protocol.entries) for (to in Protocol.entries) {
            val sessions = SessionCoordinator()
            val old = sessions.claim(from,SessionKind.MEDIA)!!
            assertNull(sessions.claim(to,SessionKind.MIRROR))
            val next = sessions.claim(to,SessionKind.MIRROR,replace=true)!!
            assertNotEquals(old,next)
            assertFalse(sessions.finish(old)); assertFalse(sessions.transition(old,SessionPhase.RECOVERING))
            assertTrue(sessions.owns(next)); assertEquals(SessionKind.MIRROR,sessions.snapshot()!!.kind)
        }
    }
    @Test fun `shutdown never resurrects an old generation`() {
        val sessions=SessionCoordinator()
        val old=sessions.claim(Protocol.AIRPLAY,SessionKind.MEDIA)!!
        sessions.reset(); sessions.reset()
        assertNull(sessions.snapshot()); assertFalse(sessions.finish(old))
        val fresh=sessions.claim(Protocol.AIRPLAY,SessionKind.MEDIA)!!
        assertTrue(fresh.generation>old.generation); assertFalse(sessions.transition(old,SessionPhase.PLAYING))
        assertTrue(sessions.owns(fresh))
    }
    @Test fun `simultaneous senders cannot both claim the display`() {
        val sessions=SessionCoordinator(); val ready=CountDownLatch(12); val go=CountDownLatch(1)
        val pool=Executors.newFixedThreadPool(12)
        try {
            val results=(1..12).map { pool.submit<SessionToken?> { ready.countDown(); go.await(); sessions.claim(Protocol.FCAST,SessionKind.MEDIA) } }
            assertTrue(ready.await(5,TimeUnit.SECONDS)); go.countDown()
            assertEquals(1,results.count { it.get(5,TimeUnit.SECONDS)!=null })
        } finally { go.countDown(); pool.shutdownNow() }
    }
    @Test fun `random late stop storms do not stop a newer session`() {
        val sessions=SessionCoordinator(); val random=java.util.Random(42); val stale=mutableListOf<SessionToken>()
        repeat(5000) {
            val current=sessions.claim(Protocol.entries[random.nextInt(Protocol.entries.size)],SessionKind.MEDIA,true)!!
            stale.shuffled(kotlin.random.Random(it)).take(8).forEach { token -> assertFalse(sessions.finish(token)) }
            assertTrue(sessions.owns(current)); stale.add(current)
            if(stale.size>64)stale.removeAt(0)
        }
    }
}
