package io.buzincast.fireos3probe;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;

/** API 17 streaming decoder. All MediaCodec operations belong to one worker. */
final class LegacyAvcPlayer {
    interface StopSignal { boolean stopped(); }
    interface Listener { void decodedFrame(); }
    private final Surface surface;
    private final VideoFrameQueue queue;
    private final Listener listener;
    private final MediaCodec.BufferInfo output = new MediaCodec.BufferInfo();
    private MediaCodec codec;
    private ByteBuffer[] inputs;
    private AvcConfig config;
    private int generation = -1;

    LegacyAvcPlayer(Surface surface, VideoFrameQueue queue, Listener listener) {
        this.surface = surface; this.queue = queue; this.listener = listener;
    }

    void run(StopSignal stop) throws IOException, InterruptedException {
        try {
            while (!stop.stopped()) {
                int current = queue.generation();
                if (current != generation) { releaseCodec(); generation = current; }
                VideoFrameQueue.Frame frame = queue.take(10);
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
                drain();
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

    private void enqueue(VideoFrameQueue.Frame frame, StopSignal stop) {
        long deadline = SystemClock.elapsedRealtime()+1000;
        while (!stop.stopped()) {
            int index = codec.dequeueInputBuffer(10000);
            if (index >= 0) {
                ByteBuffer buffer = inputs[index]; buffer.clear();
                if (frame.data.length > buffer.remaining()) throw new IllegalStateException("H.264 frame exceeds decoder input capacity");
                buffer.put(frame.data);
                codec.queueInputBuffer(index, 0, frame.data.length, frame.timeNs/1000L, 0);
                return;
            }
            drain();
            if (SystemClock.elapsedRealtime() > deadline) throw new IllegalStateException("H.264 decoder input stalled");
        }
    }

    private void drain() {
        if (codec == null) return;
        for (int attempt=0; attempt<32; attempt++) {
            int index = codec.dequeueOutputBuffer(output, 0);
            if (index >= 0) {
                boolean frame = output.size > 0 && (output.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                codec.releaseOutputBuffer(index, frame);
                if (frame) listener.decodedFrame();
            } else if (index != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && index != MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                return;
            }
        }
    }

    private void releaseCodec() {
        MediaCodec previous = codec;
        codec = null; config = null; inputs = null;
        if (previous != null) previous.release();
    }
}
