package io.buzincast.fireos3probe;

import java.util.ArrayDeque;

/** Bounded handoff from native receiver threads to the API 17 decoder worker. */
final class VideoFrameQueue {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    // A quarter-second at 60 fps fits the scheduler's maximum wait. Encoded
    // memory remains independently capped at 4 MiB, including large IDRs.
    static final int MAX_FRAMES = 16;
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private int bytes;
    private boolean closed;
    private String failure;
    private int generation;
    private final AudioPacketQueue audio;
    private final Runnable reset;
    interface Controls {void identity(String id,String token);void stopped();}
    private volatile Controls controls;
    void controls(Controls controls) {this.controls=controls;}
    @SuppressWarnings("unused") public void onDacpId(String id,String token) {
        Controls target=controls;if(target!=null)target.identity(id,token);
    }
    @SuppressWarnings("unused") public void onSenderStopped() {
        Controls target=controls;if(target!=null)target.stopped();
    }

    VideoFrameQueue() { this(null); }
    VideoFrameQueue(AudioPacketQueue audio) { this(audio,null); }
    VideoFrameQueue(AudioPacketQueue audio, Runnable reset) { this.audio = audio; this.reset=reset; }
    @SuppressWarnings("unused") public boolean audioEnabled() { return audio != null; }
    @SuppressWarnings("unused") public void onAudioData(byte[] data, int codec, long timeNs) {
        if (audio != null) audio.offer(data, codec, timeNs);
    }
    @SuppressWarnings("unused") public void onAudioFormat(int codec, int spf) { if (audio != null) audio.format(codec, spf); }
    @SuppressWarnings("unused") public void onAudioFlush() { if (audio != null) audio.flush(); }
    @SuppressWarnings("unused") public void onAudioVolume(float db) { if (audio != null) audio.volume(db); }
    @SuppressWarnings("unused") public double getAudioVolume() { return audio == null ? 0 : audio.volumeDb(); }

    static final class Frame {
        final byte[] data;
        final long timeNs;
        final long receivedNs=System.nanoTime();
        final int generation;
        Frame(byte[] data, long timeNs, int generation) { this.data = data; this.timeNs = timeNs; this.generation = generation; }
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
            frames.addLast(new Frame(data, timeNs, generation));
            bytes += data.length;
        }
        if (failure != null) { frames.clear(); bytes = 0; }
        notifyAll();
    }

    @SuppressWarnings("unused")
    public synchronized void onVideoReset() {
        if (closed) return;
        frames.clear();
        bytes = 0;
        failure = null;
        generation++;
        if (reset != null) reset.run();
        notifyAll();
    }

    synchronized int generation() { return generation; }

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
        controls=null;
        frames.clear();
        bytes = 0;
        notifyAll();
    }
}
