package io.buzincast.fireos3probe;

import org.junit.Test;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class VideoFrameQueueTest {
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
