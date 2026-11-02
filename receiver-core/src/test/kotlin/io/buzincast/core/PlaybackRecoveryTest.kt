package io.buzincast.core
import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun `network errors back off three times then stop`() {
        for(code in listOf(2000,2001,2002)) {
            val recovery=PlaybackRecovery()
            assertEquals(listOf(1500L,3000L,4500L),List(3){recovery.nextDelay(code,true)})
            repeat(20){assertNull(recovery.nextDelay(code,true))}
            assertEquals(3,recovery.attempt)
            recovery.reset();assertEquals(0,recovery.attempt);assertEquals(1500L,recovery.nextDelay(code,true))
        }
    }
    @Test fun `disabled recovery and codec errors never spend retry budget`() {
        val recovery=PlaybackRecovery()
        assertNull(recovery.nextDelay(2000,false))
        for(code in listOf(0,1000,2003,3000,4001,6001))assertNull(recovery.nextDelay(code,true))
        assertEquals(0,recovery.attempt)
    }
}
