package io.buzincast.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AsyncLogStoreTest {
    private class MemoryStorage : LogStorage {
        @Volatile var lines=listOf("old")
        val writers=mutableSetOf<String>()
        override fun read(limit:Int)=lines.takeLast(limit)
        override fun write(lines:List<String>){this.lines=lines.toList();writers.add(Thread.currentThread().name)}
    }
    @Test fun `load append clear and close preserve ordering and rotate`() {
        val disk=MemoryStorage();val log=AsyncLogStore(disk,maxLines=3)
        try {
            repeat(8){assertTrue(log.append("line-$it"))};assertTrue(log.flush())
            assertEquals(listOf("line-5","line-6","line-7"),disk.lines)
            assertTrue(log.clear());assertTrue(log.append("after-clear"));assertTrue(log.flush())
            assertEquals(listOf("after-clear"),log.snapshot());assertEquals(log.snapshot(),disk.lines)
            assertEquals(setOf("buzin-log-io"),disk.writers)
        } finally {log.close();assertTrue(log.awaitClosed())}
        assertFalse(log.append("closed"));assertFalse(log.clear());assertFalse(log.flush())
    }
    @Test fun `slow storage cannot block caller or grow queues without bound`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val disk=object:LogStorage {
            override fun read(limit:Int):List<String>{entered.countDown();release.await();return emptyList()}
            override fun write(lines:List<String>){}
        }
        val log=AsyncLogStore(disk,capacity=2)
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertTrue(log.append("a"));assertTrue(log.append("b"));assertFalse(log.append("overflow"))
            assertEquals(1,log.dropped.get());assertFalse(log.flush(10))
            assertTrue(log.clear());assertTrue(log.append("fresh"))
            release.countDown()
        } finally {release.countDown();log.close();assertTrue(log.awaitClosed())}
        assertEquals(listOf("fresh"),log.snapshot())
    }
    @Test fun `storage failures do not lose in-memory logs or kill worker`() {
        val failures=AtomicInteger()
        val disk=object:LogStorage {
            override fun read(limit:Int):List<String> = throw java.io.IOException("read")
            override fun write(lines:List<String>){throw java.io.IOException("write")}
        }
        val log=AsyncLogStore(disk,failure={failures.incrementAndGet()})
        try {log.append("safe");assertTrue(log.flush());assertEquals(listOf("safe"),log.snapshot());assertTrue(failures.get()>=2)}
        finally{log.close();assertTrue(log.awaitClosed())}
    }
    @Test fun `queued logs survive asynchronous initial load`() {
        val disk=MemoryStorage();val log=AsyncLogStore(disk)
        try {log.append("new");assertTrue(log.flush());assertEquals(listOf("old","new"),log.snapshot())}
        finally{log.close();assertTrue(log.awaitClosed())}
    }
    @Test fun `file snapshots replace atomically and restore bounded history`() {
        val directory=java.nio.file.Files.createTempDirectory("buzin-logs").toFile()
        try {
            val storage=FileLogStorage(File(directory,"logs.txt"))
            assertEquals(emptyList<String>(),storage.read(3))
            storage.write(listOf("one","two","three","four"));assertEquals(listOf("three","four"),storage.read(2))
            storage.write(emptyList());assertEquals(emptyList<String>(),storage.read(2))
        }finally{directory.deleteRecursively()}
    }
    @Test fun `multiline and long messages cannot bypass log size limit`() {
        val log=AsyncLogStore(MemoryStorage())
        try {log.clear();log.append("a\nb\r"+"x".repeat(4000));assertTrue(log.flush());val line=log.snapshot().single();assertEquals(2000,line.length);assertFalse(line.contains('\n'));assertFalse(line.contains('\r'))}
        finally{log.close();assertTrue(log.awaitClosed())}
    }
    @Test fun `flush times out when accepted behind slow storage and recovers`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val storage=object:LogStorage {
            override fun read(limit:Int):List<String>{entered.countDown();release.await();return emptyList()}
            override fun write(lines:List<String>){}
        }
        val log=AsyncLogStore(storage)
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertFalse(log.flush(5))
            release.countDown();assertTrue(log.flush())
            assertTrue(log.snapshot().isEmpty())
        } finally {release.countDown();log.close();assertTrue(log.awaitClosed())}
    }
    @Test fun `snapshot replacement failure preserves existing target`() {
        val directory=java.nio.file.Files.createTempDirectory("buzin-log-failure").toFile()
        try {
            val destination=File(directory,"blocked").apply{mkdirs()}
            File(destination,"keep").writeText("existing")
            assertThrows(IllegalStateException::class.java){FileLogStorage(destination).write(listOf("new"))}
            assertEquals("existing",File(destination,"keep").readText())
        } finally {directory.deleteRecursively()}
    }
    @Test fun `nonpositive history limit is rejected before starting worker`() {
        assertThrows(IllegalArgumentException::class.java){AsyncLogStore(MemoryStorage(),maxLines=0)}
        assertThrows(IllegalArgumentException::class.java){AsyncLogStore(MemoryStorage(),capacity=0)}
    }
}
