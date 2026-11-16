package io.buzincast.fireos3probe;

import java.util.ArrayDeque;

/** Bounded, non-blocking receiver handoff. Format/flush generations discard stale audio. */
final class AudioPacketQueue {
    static final int MAX_PACKET = 64 * 1024, MAX_BYTES = 256 * 1024, MAX_PACKETS = 64;
    static final class Packet {
        final byte[] data;
        final long timeNs;
        final int codec, spf, generation;
        Packet(byte[] data, long timeNs, int codec, int spf, int generation) {
            this.data = data; this.timeNs = timeNs; this.codec = codec; this.spf = spf; this.generation = generation;
        }
    }
    private final ArrayDeque<Packet> packets = new ArrayDeque<>();
    private int bytes, codec, spf, generation;
    private boolean closed;
    private String failure;
    private float volumeDb;

    static boolean supported(int codec, int spf) {
        return (codec == 2 && spf == 352) || (codec == 4 && (spf == 960 || spf == 1024)) ||
                (codec == 8 && (spf == 480 || spf == 512));
    }
    private static int defaultSpf(int codec) { return codec == 2 ? 352 : codec == 4 ? 1024 : 480; }

    synchronized void format(int codec, int spf) {
        if (closed) return;
        if (spf == 0) spf = defaultSpf(codec);
        clear(); generation++;
        this.codec = codec; this.spf = spf;
        failure = supported(codec, spf) ? null : "Unsupported receiver audio format: " + codec + "/" + spf;
        notifyAll();
    }
    synchronized void offer(byte[] data, int codec, long timeNs) {
        if (closed || failure != null) return;
        if (this.codec != codec) format(codec, defaultSpf(codec));
        if (failure != null) return;
        if (data == null || data.length == 0 || data.length > MAX_PACKET || timeNs < 0) {
            failure = "Invalid receiver audio packet";
        } else if (packets.size() >= MAX_PACKETS || data.length > MAX_BYTES-bytes) {
            failure = "Receiver audio queue exceeded its budget";
        } else {
            packets.addLast(new Packet(data, timeNs, codec, spf, generation)); bytes += data.length;
        }
        if (failure != null) clear();
        notifyAll();
    }
    synchronized Packet take(long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1000000L;
        while (packets.isEmpty() && !closed && failure == null && timeoutMs > 0) {
            wait(timeoutMs);
            timeoutMs = Math.max(0, (deadline-System.nanoTime())/1000000L);
        }
        if (failure != null) throw new IllegalStateException(failure);
        Packet packet = packets.pollFirst();
        if (packet != null) bytes -= packet.data.length;
        return packet;
    }
    synchronized void flush() {
        if (closed) return;
        clear(); generation++; failure = null; notifyAll();
    }
    synchronized int generation() { return generation; }
    synchronized void volume(float db) {
        if (!Float.isNaN(db) && !Float.isInfinite(db)) volumeDb = Math.max(-144, Math.min(0, db));
    }
    synchronized double volumeDb() { return volumeDb; }
    synchronized float gain() { return volumeDb <= -144 ? 0 : (float)Math.pow(10, volumeDb / 20.0); }
    synchronized void close() { closed = true; clear(); notifyAll(); }
    private void clear() { packets.clear(); bytes = 0; }
}
