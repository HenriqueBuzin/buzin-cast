package io.buzincast.fireos3probe;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;
import java.io.IOException;

/** Single owner for codec, jitter reads and legacy AudioTrack. No audio work on network threads. */
final class LegacyAudioPlayer {
    interface StopSignal { boolean stopped(); }
    private final AudioPacketQueue queue;
    private final boolean muted;
    private final AudioPlaybackClock clock;
    private final int cushionMs,percentile;
    volatile long decodedSamples, writtenFrames, playedFrames;
    volatile int playEvents;
    volatile int tunedCushionMs;
    volatile boolean running, outputActive;
    volatile float focusGain=1;
    private final Runnable outputChanged;
    interface PcmTap {void decoded(short[] pcm,int offset,int count,long ptsNs);}
    volatile PcmTap tap;

    LegacyAudioPlayer(AudioPacketQueue queue, boolean muted, AudioPlaybackClock clock) {
        this(queue,muted,clock,null);
    }
    LegacyAudioPlayer(AudioPacketQueue queue,boolean muted,AudioPlaybackClock clock,ReceiverOptions options) {
        this(queue,muted,clock,options,()->{});
    }
    LegacyAudioPlayer(AudioPacketQueue queue,boolean muted,AudioPlaybackClock clock,ReceiverOptions options,Runnable outputChanged) {
        this.queue = queue; this.muted = muted; this.clock = clock;
        this.outputChanged=outputChanged;
        cushionMs=options==null?20:options.cushionMs;percentile=options==null?95:options.percentile;
    }

    void run(StopSignal stop) throws Exception {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        LegacyAudioDecoder decoder = null;
        AudioTrack track = null;
        int generation = -1;
        short[] pcm = new short[882]; // 10 ms stereo
        long firstSignalFrame = -1;
        boolean signalled = false;
        float lastGain = 0;
        long lastPacketNs = 0;
        AudioPacketQueue.Packet pending = null;
        try {
            running = true;
            while (!stop.stopped()) {
                // A sender can disappear without a flush/teardown callback.
                // Release output after silence on the encoded input, then rebuild on demand.
                if (generation != queue.generation() || (decoder != null && System.nanoTime()-lastPacketNs > 2000000000L)) {
                    if (decoder != null) { decoder.close(); decoder = null; }
                    if (track != null) { release(track); track = null; output(false); }
                    generation = queue.generation(); writtenFrames = playedFrames = decodedSamples = 0;
                    firstSignalFrame = -1; signalled = false;
                    clock.reset();
                }
                AudioPacketQueue.Packet packet = pending != null ? pending : queue.take(decoder == null ? 1000 : 0);
                pending = null;
                for (int budget = 0; packet != null && budget < 8; budget++) {
                    if (packet.generation != queue.generation()) {
                        packet = budget == 7 ? null : queue.take(0); continue;
                    }
                    if (packet.generation != generation) { pending = packet; break; }
                    if (decoder == null) {
                        decoder = new LegacyAudioDecoder(packet.codec, packet.spf,cushionMs,percentile);
                        track = createTrack();
                        track.setStereoVolume(0, 0); // apply sender volume before the first PCM write
                        lastGain = 0;
                        track.play();
                        output(true);
                    }
                    decoder.decode(packet.data, packet.timeNs);
                    lastPacketNs = System.nanoTime();
                    long[] stats=decoder.stats();decodedSamples=stats[0];tunedCushionMs=(int)stats[2];
                    // Do not remove a ninth packet without processing it.
                    packet = budget == 7 ? null : queue.take(0);
                }
                if (decoder == null || generation != queue.generation()) continue;
                decoder.read(pcm, 441);
                long[] timing = decoder.timing();
                PcmTap sink=tap;
                if(sink!=null)for(int i=0;i<timing.length;i+=3) {
                    int start=(int)timing[i]*2,count=(int)timing[i+1]*2;
                    if(start>=0 && count>0 && start<=pcm.length-count && timing[i+2]>=0)sink.decoded(pcm,start,count,timing[i+2]);
                }
                if (firstSignalFrame < 0) {
                    for (int i=0; i<pcm.length; i++) if (pcm[i] != 0) {
                        firstSignalFrame = writtenFrames + i/2; break;
                    }
                }
                float gain = muted ? 0 : queue.gain()*focusGain;
                if (gain != lastGain) { track.setStereoVolume(gain, gain); lastGain = gain; }
                int offset = 0;
                while (offset < pcm.length && !stop.stopped() && generation == queue.generation()) {
                    int wrote = track.write(pcm, offset, pcm.length-offset);
                    if (wrote <= 0) throw new IOException("Receiver AudioTrack write failed: " + wrote);
                    offset += wrote;
                }
                for (int i=0; i<timing.length; i+=3) {
                    long accepted = Math.min(timing[i+1], offset/2L-timing[i]);
                    if (accepted > 0) clock.submit(writtenFrames+timing[i], accepted, timing[i+2]);
                }
                writtenFrames += offset/2;
                playedFrames = clock.updateHead(track.getPlaybackHeadPosition(), System.nanoTime());
                if (!signalled && firstSignalFrame >= 0 && playedFrames > firstSignalFrame) {
                    signalled = true; playEvents++;
                }
            }
        } finally {
            try { if (track != null) release(track); }
            finally { if (decoder != null) decoder.close(); clock.reset(); output(false); running = false; }
        }
    }
    private void output(boolean active) {if(outputActive==active)return;outputActive=active;outputChanged.run();}

    private static AudioTrack createTrack() throws IOException {
        int minimum = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IOException("Legacy PCM output unsupported");
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, 44100, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, 17640), AudioTrack.MODE_STREAM);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) { track.release(); throw new IOException("Legacy PCM output init failed"); }
        return track;
    }
    private static void release(AudioTrack track) {
        try { if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.stop(); }
        finally { track.release(); }
    }
}
