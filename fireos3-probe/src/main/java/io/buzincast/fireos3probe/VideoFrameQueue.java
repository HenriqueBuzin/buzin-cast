package io.buzincast.fireos3probe;

import java.util.ArrayDeque;

/** Bounded handoff from native receiver threads to the API 17 decoder worker. */
final class VideoFrameQueue {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final int MAX_FRAMES = 8;
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private int bytes;
    private boolean closed;
    private String failure;

    static final class Frame {
        final byte[] data;
        final long timeNs;
        Frame(byte[] data, long timeNs) { this.data = data; this.timeNs = timeNs; }
    }

    // JNI owns a fresh byte[] per invocation. Do not block a network worker.
    @SuppressWarnings("unused")
    public synchronized void onVideoData(byte[] data, long timeNs, boolean h265) {
        if (closed || failure != null) return;
        if (h265 || data == null || data.length == 0 || timeNs < 0) {
            failure = "Unsupported or invalid H.264 frame";
        } else if (frames.size() >= MAX_FRAMES || data.length > MAX_BYTES - bytes) {
            failure = "Receiver video queue exceeded its budget";
        } else {
            frames.addLast(new Frame(data, timeNs));
            bytes += data.length;
        }
        if (failure != null) { frames.clear(); bytes = 0; }
        notifyAll();
    }

    synchronized Frame take(long timeoutMs) throws InterruptedException {
        long remaining = timeoutMs;
        long start = System.nanoTime();
        while (frames.isEmpty() && !closed && failure == null && remaining > 0) {
            wait(remaining);
            remaining = timeoutMs - (System.nanoTime() - start) / 1_000_000L;
        }
        if (failure != null) throw new IllegalStateException(failure);
        if (frames.isEmpty()) return null;
        Frame frame = frames.removeFirst();
        bytes -= frame.data.length;
        return frame;
    }

    synchronized void close() {
        closed = true;
        frames.clear();
        bytes = 0;
        notifyAll();
    }
}
