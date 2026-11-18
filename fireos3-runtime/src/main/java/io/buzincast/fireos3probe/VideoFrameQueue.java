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
    private volatile int activeConnections;
    private volatile Runnable connectionsChanged;
    void connections(Runnable changed) {connectionsChanged=changed;}
    int connectionCount() {return activeConnections;}
    @SuppressWarnings("unused") public void onConnectionChanged(boolean connected) {
        Runnable changed;
        synchronized(this) {
            if(closed)return;
            activeConnections=Math.max(0,activeConnections+(connected?1:-1));changed=connectionsChanged;
        }
        if(changed!=null)changed.run();
    }
    private String failure;
    private int generation;
    private final AudioPacketQueue audio;
    private final Runnable reset;
    interface Mirror {void changed(boolean active);}
    private final Mirror mirror;
    private boolean mirroring;
    interface Controls {void identity(String id,String token);void stopped();}
    private volatile Controls controls;
    interface Media {void metadata(byte[] bytes);void artwork(byte[] bytes);void progress(long start,long current,long end);}
    private volatile Media media;
    void media(Media media) {this.media=media;}
    @SuppressWarnings("unused") public synchronized void onMetadata(byte[] bytes) {Media target=media;if(target!=null)target.metadata(bytes);}
    @SuppressWarnings("unused") public synchronized void onArtwork(byte[] bytes) {Media target=media;if(target!=null)target.artwork(bytes);}
    @SuppressWarnings("unused") public synchronized void onProgress(long start,long current,long end) {Media target=media;if(target!=null)target.progress(start,current,end);}
    void controls(Controls controls) {this.controls=controls;}
    @SuppressWarnings("unused") public void onDacpId(String id,String token) {
        Controls target=controls;if(target!=null)target.identity(id,token);
    }
    @SuppressWarnings("unused") public void onSenderStopped() {
        Controls target=controls;if(target!=null)target.stopped();
    }

    VideoFrameQueue() { this(null); }
    VideoFrameQueue(AudioPacketQueue audio) { this(audio,null); }
    VideoFrameQueue(AudioPacketQueue audio, Runnable reset) { this(audio,reset,null); }
    VideoFrameQueue(AudioPacketQueue audio,Runnable reset,Mirror mirror) {this.audio=audio;this.reset=reset;this.mirror=mirror;}
    private void mirrorMode(boolean active) {
        if(mirroring==active)return;
        mirroring=active;if(mirror!=null)mirror.changed(active);
    }
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
            mirrorMode(true);
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
        mirrorMode(false);
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
        activeConnections=0;connectionsChanged=null;
        mirrorMode(false);
        controls=null;
        media=null;
        frames.clear();
        bytes = 0;
        notifyAll();
    }
}
