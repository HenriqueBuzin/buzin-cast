package io.buzincast.fireos3probe;

import org.junit.Test;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class AudioPacketQueueTest {
    @Test public void preservesNegotiatedFrameLengthAndUnsyncedTimestamp() throws Exception {
        AudioPacketQueue queue = new AudioPacketQueue();
        queue.format(8, 512); queue.offer(new byte[]{3}, 8, 0);
        AudioPacketQueue.Packet packet = queue.take(0);
        assertEquals(512, packet.spf); assertEquals(0, packet.timeNs);
        assertArrayEquals(new byte[]{3}, packet.data);
    }
    @Test public void legacyPacketsSelectTheirDefaultFormat() throws Exception {
        AudioPacketQueue queue = new AudioPacketQueue();
        queue.offer(new byte[]{1}, 2, 10);
        assertEquals(352, queue.take(0).spf);
        queue.offer(new byte[]{1}, 4, 20);
        assertEquals(1024, queue.take(0).spf);
    }
    @Test public void formatAndFlushDiscardStalePackets() throws Exception {
        AudioPacketQueue queue = new AudioPacketQueue();
        queue.format(4, 960); queue.offer(new byte[]{1}, 4, 10);
        int previous = queue.generation();
        queue.format(8, 480);
        assertTrue(queue.generation() > previous); assertNull(queue.take(0));
        queue.offer(new byte[]{2}, 8, 20); queue.flush(); assertNull(queue.take(0));
        queue.offer(new byte[]{3}, 8, 30); assertEquals(480, queue.take(0).spf);
    }
    @Test public void overflowFailsAndFlushCanRecover() throws Exception {
        AudioPacketQueue queue = new AudioPacketQueue();
        for (int i=0; i<=AudioPacketQueue.MAX_PACKETS; i++) queue.offer(new byte[]{1}, 2, i);
        assertThrows(IllegalStateException.class, () -> queue.take(0));
        queue.flush(); queue.offer(new byte[]{2}, 2, 1);
        assertArrayEquals(new byte[]{2}, queue.take(0).data);
    }
    @Test public void byteLimitAndPacketLimitAreIndependent() {
        AudioPacketQueue queue = new AudioPacketQueue();
        for (int i=0; i<4; i++) queue.offer(new byte[AudioPacketQueue.MAX_PACKET], 2, i);
        queue.offer(new byte[]{1}, 2, 4);
        assertThrows(IllegalStateException.class, () -> queue.take(0));
    }
    @Test public void invalidFormatAndPacketAreExplicitFailures() {
        AudioPacketQueue queue = new AudioPacketQueue();
        queue.format(8, 1024); assertThrows(IllegalStateException.class, () -> queue.take(0));
        queue.format(2, 352); queue.offer(new byte[AudioPacketQueue.MAX_PACKET+1], 2, 1);
        assertThrows(IllegalStateException.class, () -> queue.take(0));
    }
    @Test public void senderVolumeIsBoundedAndRejectsNonFiniteValues() {
        AudioPacketQueue queue = new AudioPacketQueue();
        queue.volume(-12); assertEquals(Math.pow(10,-12/20.0), queue.gain(), 0.00001);
        queue.volume(Float.NaN); queue.volume(Float.POSITIVE_INFINITY); assertEquals(-12, queue.volumeDb(), 0);
        queue.volume(100); assertEquals(1, queue.gain(), 0);
        queue.volume(-1000); assertEquals(0, queue.gain(), 0);
    }
    @Test public void closeWakesReaderAndIgnoresLateCallbacks() throws Exception {
        AudioPacketQueue queue = new AudioPacketQueue();
        FutureTask<AudioPacketQueue.Packet> read = new FutureTask<>(() -> queue.take(10000));
        new Thread(read).start(); queue.close();
        assertNull(read.get(1, TimeUnit.SECONDS));
        queue.format(2, 352); queue.offer(new byte[]{1}, 2, 1); assertNull(queue.take(0));
    }
}
