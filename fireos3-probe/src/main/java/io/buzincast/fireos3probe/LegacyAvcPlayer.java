package io.buzincast.fireos3probe;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;

/** API 17 streaming decoder. All MediaCodec operations belong to one worker. */
final class LegacyAvcPlayer {
    interface StopSignal { boolean stopped(); }
    interface Listener { void decodedFrame(); }
    private final Surface surface;
    private final VideoFrameQueue queue;
    private final Listener listener;
    private final AudioPlaybackClock clock;
    volatile int waitedFrames, droppedFrames, renderedFrames;
    volatile long lastDelayNs = AudioPlaybackClock.UNKNOWN;
    volatile long lastRenderedPtsNs = AudioPlaybackClock.UNKNOWN;
    volatile long lastRenderDelayNs = AudioPlaybackClock.UNKNOWN;
    volatile int queuedFrames, outputFrames;
    // The codec gets small relative tags; Unix NTP stays outside the platform codec.
    // This also preserves nanoseconds and separates resets/clock jumps from decoder time.
    private final LinkedHashMap<Long,Long> timestamps = new LinkedHashMap<>();
    private long nextTokenUs = 1000;
    private final MediaCodec.BufferInfo output = new MediaCodec.BufferInfo();
    private MediaCodec codec;
    private ByteBuffer[] inputs;
    private AvcConfig config;
    private int generation = -1;

    LegacyAvcPlayer(Surface surface, VideoFrameQueue queue, Listener listener, AudioPlaybackClock clock) {
        this.surface = surface; this.queue = queue; this.listener = listener; this.clock = clock;
    }

    void run(StopSignal stop) throws IOException, InterruptedException {
        try {
            while (!stop.stopped()) {
                int current = queue.generation();
                if (current != generation) { releaseCodec(); generation = current; }
                VideoFrameQueue.Frame frame = queue.take(codec == null ? 1000 : 10);
                if (frame != null) {
                    if (frame.generation != generation) {
                        releaseCodec(); generation = frame.generation;
                    }
                    AvcConfig received = AvcConfig.fromAnnexB(frame.data);
                    if (received != null && (config == null || !config.matches(received))) {
                        configure(received);
                    }
                    if (codec != null) enqueue(frame, stop);
                }
                drain(stop);
            }
        } finally { releaseCodec(); }
    }

    private void configure(AvcConfig next) throws IOException {
        releaseCodec();
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", next.width, next.height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(next.spsWithStartCode()));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(next.ppsWithStartCode()));
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, VideoFrameQueue.MAX_BYTES);
        codec = MediaCodec.createDecoderByType("video/avc");
        codec.configure(format, surface, null, 0);
        codec.start();
        inputs = codec.getInputBuffers();
        config = next;
    }

    private void enqueue(VideoFrameQueue.Frame frame, StopSignal stop) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime()+1000;
        while (!stop.stopped()) {
            int index = codec.dequeueInputBuffer(10000);
            if (index >= 0) {
                ByteBuffer buffer = inputs[index]; buffer.clear();
                if (frame.data.length > buffer.remaining()) throw new IllegalStateException("H.264 frame exceeds decoder input capacity");
                buffer.put(frame.data);
                if (timestamps.size() >= 64) throw new IllegalStateException("H.264 output timestamp budget exceeded");
                long token = nextTokenUs; nextTokenUs += 1000;
                codec.queueInputBuffer(index, 0, frame.data.length, token, 0);
                timestamps.put(token,frame.timeNs); queuedFrames++;
                return;
            }
            drain(stop);
            if (SystemClock.elapsedRealtime() > deadline) throw new IllegalStateException("H.264 decoder input stalled");
        }
    }

    private void drain(StopSignal stop) throws InterruptedException {
        if (codec == null) return;
        for (int attempt=0; attempt<32; attempt++) {
            int index = codec.dequeueOutputBuffer(output, 0);
            if (index >= 0) {
                boolean frame = output.size > 0 && (output.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                boolean render = false;
                long sourcePts = AudioPlaybackClock.UNKNOWN;
                try {
                    if (frame) {
                        outputFrames++;
                        Long pts = timestamps.remove(output.presentationTimeUs);
                        if (pts == null) throw new IllegalStateException("H.264 output has no source timestamp");
                        sourcePts = pts;
                        render = readyToRender(pts,stop);
                    }
                }
                finally { codec.releaseOutputBuffer(index, render); }
                if (frame && !render) droppedFrames++;
                if (render) {
                    lastRenderDelayNs=lastDelayNs; lastRenderedPtsNs=sourcePts;
                    renderedFrames++; listener.decodedFrame();
                }
            } else if (index != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && index != MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                return;
            }
        }
    }

    private boolean readyToRender(long ptsNs, StopSignal stop) throws InterruptedException {
        long started = System.nanoTime();
        boolean waited = false;
        while (!stop.stopped() && generation == queue.generation()) {
            long now = System.nanoTime(), delay = clock.delayNs(ptsNs, now);
            lastDelayNs = delay;
            if (delay == AudioPlaybackClock.UNKNOWN) return true; // video-only / audio underrun
            if (delay < -150000000L || delay > 250000000L || now-started > 250000000L) return false;
            if (delay <= 8000000L) return true;
            if (!waited) { waitedFrames++; waited=true; }
            Thread.sleep(Math.min(5, Math.max(1, (delay-8000000L)/1000000L)));
        }
        return false;
    }

    private void releaseCodec() {
        MediaCodec previous = codec;
        codec = null; config = null; inputs = null; timestamps.clear(); nextTokenUs=1000;
        if (previous != null) previous.release();
    }
}
