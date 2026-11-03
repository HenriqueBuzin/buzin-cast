package io.buzincast.core
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ReceiverGroupTest {
    @Test fun `startup failure after shutdown does not close the same receiver twice`() {
        val entered = CountDownLatch(1)
        val failStart = CountDownLatch(1)
        val closes = AtomicInteger()
        val failures = AtomicInteger()
        val group = ReceiverGroup { failures.incrementAndGet() }
        val receiver = object : ReceiverAdapter {
            override fun start() {
                entered.countDown()
                check(failStart.await(5, TimeUnit.SECONDS))
                error("Socket binding failed")
            }

            override fun close() { closes.incrementAndGet() }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val startup = executor.submit { group.start(receiver) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            group.close()
            failStart.countDown()
            startup.get(5, TimeUnit.SECONDS)
            assertEquals(1, closes.get())
            assertEquals(1, failures.get())
        } finally {
            failStart.countDown()
            group.close()
            executor.shutdownNow()
        }
    }
    private class Receiver(val failStart:Boolean=false,val failClose:Boolean=false):ReceiverAdapter {
        var starts=0;var closes=0
        override fun start(){starts++;if(failStart)error("start failed")}
        override fun close(){closes++;if(failClose)error("close failed")}
    }
    @Test fun `start once close all once and refuse work after shutdown`() {
        val group=ReceiverGroup();val a=Receiver();val b=Receiver()
        group.start(a);group.start(a);group.start(b);group.close();group.close()
        assertEquals(1,a.starts);assertEquals(1,a.closes);assertEquals(1,b.closes)
        val late=Receiver();group.start(late);assertEquals(0,late.starts);assertEquals(1,late.closes)
    }
    @Test fun `one failed receiver cannot prevent cleanup of others`() {
        val errors=mutableListOf<Exception>();val group=ReceiverGroup { errors.add(it) }
        val failed=Receiver(failStart=true);val badClose=Receiver(failClose=true);val healthy=Receiver()
        group.start(failed);group.start(healthy);group.start(badClose);group.close()
        assertEquals(1,failed.closes);assertEquals(1,healthy.closes);assertEquals(1,badClose.closes);assertEquals(2,errors.size)
    }
    @Test fun `shutdown racing with startup still releases admitted receiver`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val closed=CountDownLatch(1)
        val group=ReceiverGroup()
        val receiver=object:ReceiverAdapter {
            override fun start(){entered.countDown();release.await()}
            override fun close(){closed.countDown();release.countDown()}
        }
        val worker=Thread { group.start(receiver) }.apply { start() }
        assertTrue(entered.await(5,TimeUnit.SECONDS));group.close()
        worker.join(5000);assertFalse(worker.isAlive);assertEquals(0,closed.count)
    }
}
