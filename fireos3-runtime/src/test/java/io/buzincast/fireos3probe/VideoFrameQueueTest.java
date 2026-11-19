package io.buzincast.fireos3probe;

import org.junit.Test;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class VideoFrameQueueTest {
    @Test public void admissionRunsWithoutHoldingDecoderLockAndRejectsClosedQueue() throws Exception {
        VideoFrameQueue queue=new VideoFrameQueue();
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        queue.admission(peer-> {
            entered.countDown();
            try {release.await(2,TimeUnit.SECONDS);}catch(InterruptedException error) {Thread.currentThread().interrupt();return false;}
            return true;
        });
        FutureTask<Boolean> result=new FutureTask<>(()->queue.onConnectionRequest("127.0.0.1"));
        Thread worker=new Thread(result);worker.start();
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS));queue.close();
        } finally {release.countDown();}
        assertFalse(result.get(2,TimeUnit.SECONDS));worker.join(2000);
        assertFalse(queue.onConnectionRequest("127.0.0.1"));
    }
    @Test public void connectionsTrackIdlePeersAndIgnoreLateCallbacksAfterClose() {
        VideoFrameQueue queue=new VideoFrameQueue();java.util.concurrent.atomic.AtomicInteger changes=new java.util.concurrent.atomic.AtomicInteger();
        queue.connections(changes::incrementAndGet);
        queue.onConnectionChanged(true);queue.onConnectionChanged(true);assertEquals(2,queue.connectionCount());
        queue.onConnectionChanged(false);assertEquals(1,queue.connectionCount());assertEquals(3,changes.get());
        queue.close();assertEquals(0,queue.connectionCount());
        queue.onConnectionChanged(true);queue.onConnectionChanged(false);assertEquals(0,queue.connectionCount());assertEquals(3,changes.get());
    }
    @Test public void encodedVideoChangesModeWithoutWaitingForADisplay() {
        java.util.List<Boolean> modes=new java.util.ArrayList<>();
        VideoFrameQueue queue=new VideoFrameQueue(null,null,modes::add);
        queue.onVideoData(new byte[]{1},0,false);
        queue.onVideoData(new byte[]{2},1,false);
        assertEquals(java.util.Arrays.asList(true),modes);
        queue.onVideoReset();queue.onVideoReset();
        assertEquals(java.util.Arrays.asList(true,false),modes);
        queue.onVideoData(new byte[]{3},2,false);queue.close();
        queue.onVideoData(new byte[]{4},3,false);queue.onVideoReset();
        assertEquals(java.util.Arrays.asList(true,false,true,false),modes);
    }
    @Test public void rejectedVideoCannotEnterMirrorMode() {
        java.util.List<Boolean> modes=new java.util.ArrayList<>();
        VideoFrameQueue queue=new VideoFrameQueue(null,null,modes::add);
        queue.onVideoData(null,0,false);queue.onVideoReset();
        queue.onVideoData(new byte[]{1},0,true);queue.onVideoReset();
        queue.onVideoData(new byte[]{1},-1,false);queue.onVideoReset();
        assertTrue(modes.isEmpty());
    }
    @Test public void buffersQuarterSecondOfSixtyFpsInOrder() throws Exception {
        VideoFrameQueue queue=new VideoFrameQueue();
        for(int i=0;i<15;i++)queue.onVideoData(new byte[]{(byte)i},i*1000000000L/60,false);
        for(int i=0;i<15;i++) {
            VideoFrameQueue.Frame frame=queue.take(0);
            assertEquals(i,frame.data[0]);assertEquals(i*1000000000L/60,frame.timeNs);
        }
        assertNull(queue.take(0));
    }
    @Test public void resetSeparatesSessionsAndDiscardsOldFrames() throws Exception {
        VideoFrameQueue queue = new VideoFrameQueue();
        queue.onVideoData(new byte[]{1}, 0, false);
        queue.onVideoReset();
        assertNull(queue.take(1));
        queue.onVideoData(new byte[]{2}, 1, false);
        VideoFrameQueue.Frame frame = queue.take(1);
        assertEquals(1,frame.generation);assertArrayEquals(new byte[]{2},frame.data);
    }
    @Test public void keepsFrameAndTimestampOrder() throws Exception {
        VideoFrameQueue queue = new VideoFrameQueue();
        queue.onVideoData(new byte[]{1}, 2000, false);
        queue.onVideoData(new byte[]{2}, 3000, false);
        VideoFrameQueue.Frame first = queue.take(1);
        assertArrayEquals(new byte[]{1}, first.data);
        assertEquals(2000, first.timeNs);
        assertEquals(3000, queue.take(1).timeNs);
        assertNull(queue.take(1));
    }

    @Test public void overflowFailsRatherThanContinuingWithCorruptVideo() throws Exception {
        VideoFrameQueue queue = new VideoFrameQueue();
        for (int i = 0; i <= VideoFrameQueue.MAX_FRAMES; i++) queue.onVideoData(new byte[]{1}, i, false);
        assertThrows(IllegalStateException.class, () -> queue.take(1));
    }

    @Test public void byteBudgetIsIndependentOfFrameCount() {
        VideoFrameQueue queue = new VideoFrameQueue();
        queue.onVideoData(new byte[VideoFrameQueue.MAX_BYTES], 0, false);
        queue.onVideoData(new byte[]{1}, 1, false);
        assertThrows(IllegalStateException.class, () -> queue.take(1));
    }

    @Test public void unsupportedCodecIsExplicit() {
        VideoFrameQueue queue = new VideoFrameQueue();
        queue.onVideoData(new byte[]{1}, 1, true);
        assertThrows(IllegalStateException.class, () -> queue.take(1));
    }

    @Test public void closingWakesWaitingDecoderAndRejectsLateCallbacks() throws Exception {
        VideoFrameQueue queue = new VideoFrameQueue();
        FutureTask<VideoFrameQueue.Frame> read = new FutureTask<>(() -> queue.take(10000));
        new Thread(read).start();
        queue.close();
        assertNull(read.get(1, TimeUnit.SECONDS));
        queue.onVideoData(new byte[]{1}, 0, false);
        assertNull(queue.take(1));
    }
}
