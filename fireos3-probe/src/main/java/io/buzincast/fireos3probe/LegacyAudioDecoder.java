package io.buzincast.fireos3probe;

import java.io.Closeable;
import java.io.IOException;

/** Serialized ownership keeps reads, decoder calls and native destruction disjoint. */
final class LegacyAudioDecoder implements Closeable {
    private long handle;

    LegacyAudioDecoder(int codec, int samplesPerFrame) throws IOException {
        System.loadLibrary("fireos3_probe");
        handle = create(codec, samplesPerFrame);
        if (handle == 0) throw new IOException("Unsupported legacy audio format: " + codec + "/" + samplesPerFrame);
    }

    synchronized void decode(byte[] packet, long ptsNs) throws IOException {
        if (handle == 0 || !decodePacket(handle, packet, ptsNs)) throw new IOException("Legacy audio decode failed");
    }

    synchronized int read(short[] output, int frames) throws IOException {
        int count = handle == 0 ? -1 : readPcm(handle, output, frames);
        if (count < 0) throw new IOException("Legacy audio output failed");
        return count;
    }

    synchronized long[] stats() { return handle == 0 ? new long[]{0, 0} : statistics(handle); }
    synchronized long[] timing() { return handle == 0 ? new long[0] : readTiming(handle); }

    @Override public synchronized void close() {
        if (handle != 0) { destroy(handle); handle = 0; }
    }

    private static native long create(int codec, int samplesPerFrame);
    private static native boolean decodePacket(long handle, byte[] packet, long ptsNs);
    private static native int readPcm(long handle, short[] output, int frames);
    private static native long[] statistics(long handle);
    private static native long[] readTiming(long handle);
    private static native void destroy(long handle);
}
