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
    interface Listener { void decodedFrame(); default void resized(int width,int height) {} }
    private final OwnedTargetSlot<Surface> targets=new OwnedTargetSlot<>();
    private OwnedTargetSlot.Target<Surface> target;
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> detachReplies=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean displayClosed;
    private final VideoFrameQueue queue;
    private final Listener listener;
    private final AudioPlaybackClock clock;
    private final boolean lowLatency;
    volatile int waitedFrames, droppedFrames, renderedFrames;
    volatile long lastDelayNs = AudioPlaybackClock.UNKNOWN;
    volatile long lastRenderedPtsNs = AudioPlaybackClock.UNKNOWN;
    volatile long lastRenderDelayNs = AudioPlaybackClock.UNKNOWN;
    volatile long lastQueueDelayNs,lastDecodeDelayNs;
    volatile int queuedFrames, outputFrames;
    volatile int skippedWithoutTarget,skippedUntilIdr;
    // The codec gets small relative tags; Unix NTP stays outside the platform codec.
    // This also preserves nanoseconds and separates resets/clock jumps from decoder time.
    private static final class Pending {
        final long pts,queuedNs;
        Pending(long pts,long queuedNs) { this.pts=pts;this.queuedNs=queuedNs; }
    }
    private final LinkedHashMap<Long,Pending> timestamps = new LinkedHashMap<>();
    private long nextTokenUs = 1000;
    private final MediaCodec.BufferInfo output = new MediaCodec.BufferInfo();
    private MediaCodec codec;
    private ByteBuffer[] inputs;
    private AvcConfig config;
    private int generation = -1;

    LegacyAvcPlayer(Surface surface, VideoFrameQueue queue, Listener listener, AudioPlaybackClock clock) {
        this(surface,queue,listener,clock,false);
    }
    LegacyAvcPlayer(Surface surface,VideoFrameQueue queue,Listener listener,AudioPlaybackClock clock,boolean lowLatency) {
        setSurface(surface,null);this.queue = queue; this.listener = listener; this.clock = clock;
        this.lowLatency=lowLatency;
    }

    void setSurface(Surface surface,Runnable whenReleased) {
        targets.request(surface,surface==null?null:()-> {
            try {surface.release();} finally {if(whenReleased!=null)whenReleased.run();}
        });
    }
    /** Acknowledge only after the decoder has stopped using its old Surface. */
    void detachSurface(Runnable detached) {
        targets.request(null,null);
        if(displayClosed) {detached.run();return;}
        detachReplies.add(detached);
        if(displayClosed && detachReplies.remove(detached))detached.run();
    }
    private void acknowledgeDetach() {Runnable reply;while((reply=detachReplies.poll())!=null)reply.run();}
    void closeSurface() { try {targets.close();}finally {displayClosed=true;acknowledgeDetach();} }
    private void applyTarget() {
        if(targets.changed()) {releaseCodec();target=targets.activate();}
        if(target==null && !targets.changed())acknowledgeDetach();
    }

    void run(StopSignal stop) throws IOException, InterruptedException {
        try {
            while (!stop.stopped()) {
                int current = queue.generation();
                if (current != generation) { releaseCodec();config=null;generation = current; }
                applyTarget();
                VideoFrameQueue.Frame frame = queue.take(codec == null ? 50 : 10);
                applyTarget();
                if (frame != null) {
                    if (frame.generation != generation) {
                        releaseCodec();config=null;generation = frame.generation;
                    }
                    AvcConfig received = AvcConfig.fromAnnexB(frame.data);
                    if (received != null && (config == null || !config.matches(received))) {
                        releaseCodec();config=received;
                    }
                    if(target==null) {skippedWithoutTarget++;continue;}
                    // A fresh API17 codec cannot resume from dependent P/B frames.
                    // Keep the parameter sets across display changes, not stream resets.
                    if(codec==null) {
                        if(config==null || !AvcConfig.containsIdr(frame.data)) {skippedUntilIdr++;continue;}
                        configure(config);
                    }
                    if (codec != null) enqueue(frame, stop);
                }
                drain(stop);
            }
        } finally { try {releaseCodec();} finally {closeSurface();} }
    }

    private void configure(AvcConfig next) throws IOException {
        releaseCodec();
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", next.width, next.height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(next.spsWithStartCode()));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(next.ppsWithStartCode()));
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, VideoFrameQueue.MAX_BYTES);
        if(lowLatency && android.os.Build.VERSION.SDK_INT>=30)format.setInteger("low-latency",1);
        codec = MediaCodec.createDecoderByType("video/avc");
        codec.configure(format, target.value, null, 0);
        codec.start();
        inputs = codec.getInputBuffers();
        config = next;
        listener.resized(next.width,next.height);
    }

    private void enqueue(VideoFrameQueue.Frame frame, StopSignal stop) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime()+1000;
        while (!stop.stopped()) {
            if(targets.changed())return;
            int index = codec.dequeueInputBuffer(10000);
            if (index >= 0) {
                ByteBuffer buffer = inputs[index]; buffer.clear();
                if (frame.data.length > buffer.remaining()) throw new IllegalStateException("H.264 frame exceeds decoder input capacity");
                buffer.put(frame.data);
                if (timestamps.size() >= 64) throw new IllegalStateException("H.264 output timestamp budget exceeded");
                long token = nextTokenUs; nextTokenUs += 1000;
                long queuedNs=System.nanoTime();lastQueueDelayNs=queuedNs-frame.receivedNs;
                codec.queueInputBuffer(index, 0, frame.data.length, token, 0);
                timestamps.put(token,new Pending(frame.timeNs,queuedNs)); queuedFrames++;
                return;
            }
            drain(stop);
            if (SystemClock.elapsedRealtime() > deadline) throw new IllegalStateException("H.264 decoder input stalled");
        }
    }

    private void drain(StopSignal stop) throws InterruptedException {
        if (codec == null) return;
        for (int attempt=0; attempt<32; attempt++) {
            if(targets.changed())return;
            int index = codec.dequeueOutputBuffer(output, 0);
            if (index >= 0) {
                boolean frame = output.size > 0 && (output.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                boolean render = false;
                long sourcePts = AudioPlaybackClock.UNKNOWN;
                try {
                    if (frame) {
                        outputFrames++;
                        Pending pending = timestamps.remove(output.presentationTimeUs);
                        if (pending == null) throw new IllegalStateException("H.264 output has no source timestamp");
                        sourcePts = pending.pts;lastDecodeDelayNs=System.nanoTime()-pending.queuedNs;
                        render = readyToRender(sourcePts,stop) && !targets.changed();
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
        long unknownSince=0;
        boolean waited = false;
        while (!stop.stopped() && generation == queue.generation() && !targets.changed()) {
            long now = System.nanoTime(), delay = clock.delayNs(ptsNs, now);
            lastDelayNs = delay;
            if (delay == AudioPlaybackClock.UNKNOWN) {
                if(unknownSince==0)unknownSince=now;
                // A short hole in PCM metadata must not immediately turn an
                // already-synchronized stream into video-only. Pure video and
                // explicit flush still render immediately; recovery is bounded.
                if(clock.recentlyActive(now) && now-unknownSince<100000000L && now-started<250000000L) {
                    if(!waited) {waitedFrames++;waited=true;}
                    Thread.sleep(2);continue;
                }
                return true;
            }
            unknownSince=0;
            if (delay < -150000000L || delay > 250000000L || now-started > 250000000L) return false;
            if (delay <= 8000000L) return true;
            if (!waited) { waitedFrames++; waited=true; }
            Thread.sleep(Math.min(5, Math.max(1, (delay-8000000L)/1000000L)));
        }
        return false;
    }

    private void releaseCodec() {
        MediaCodec previous = codec;
        codec = null; inputs = null; timestamps.clear(); nextTokenUs=1000;
        if (previous != null) previous.release();
    }
}
