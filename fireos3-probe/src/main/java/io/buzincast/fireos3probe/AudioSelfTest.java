package io.buzincast.fireos3probe;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real native decoding and API17 AudioTrack playback, muted during automated checks. */
final class AudioSelfTest {
    static void run(Context context) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = context.getAssets().open("audio_samples.json")) {
            byte[] chunk = new byte[4096]; int count;
            while ((count = input.read(chunk)) >= 0) bytes.write(chunk, 0, count);
        }
        JSONArray fixtures = new JSONArray(bytes.toString("UTF-8"));
        if (fixtures.length() != 4) throw new IOException("Missing legacy audio fixtures");
        for (int i = 0; i < fixtures.length(); i++) {
            JSONObject fixture = fixtures.getJSONObject(i);
            int ct = fixture.getInt("codec"), spf = fixture.getInt("samplesPerFrame");
            try (LegacyAudioDecoder decoder = new LegacyAudioDecoder(ct, spf)) {
                JSONArray packets = fixture.getJSONArray("packets");
                long pts = System.nanoTime();
                for (int p = 0; p < packets.length(); p++) {
                    decoder.decode(Base64.decode(packets.getString(p), Base64.DEFAULT), pts);
                    pts += spf * 1000000000L / 44100;
                }
                long[] stats = decoder.stats();
                if (stats[0] < spf * 2L || stats[1] < 100 || stats[1] > 16000)
                    throw new IOException("Invalid decoded audio: " + ct + ": " + stats[0] + "/" + stats[1]);
                // A peak counter alone cannot prove the shared timeline was populated.
                boolean audiblePcm = false;
                short[] rendered = null;
                short[] pcm = new short[882];
                for (int n = 0; n < 30; n++) {
                    if (decoder.read(pcm, 441) != pcm.length) throw new IOException("Truncated PCM");
                    for (short value : pcm) if (value != 0) {
                        audiblePcm = true;
                        if (rendered == null) rendered = pcm.clone();
                        break;
                    }
                }
                if (!audiblePcm) throw new IOException("Timeline produced only silence for " + ct);
                checkAudioTrack(rendered);
            }
        }
    }

    private static void checkAudioTrack(short[] decodedPcm) throws Exception {
        int minimum = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IOException("PCM output unsupported");
        AudioTrack output = new AudioTrack(AudioManager.STREAM_MUSIC, 44100,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minimum, 17640), AudioTrack.MODE_STREAM);
        try {
            if (output.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("AudioTrack init failed");
            output.setStereoVolume(0, 0);
            output.play();
            short[] samples = new short[8820];
            for (int n = 0; n < samples.length; n++) samples[n] = decodedPcm[n % decodedPcm.length];
            int written = 0;
            while (written < samples.length) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                int result = output.write(samples, written, samples.length-written);
                if (result <= 0) throw new IOException("AudioTrack write failed: " + result);
                written += result;
            }
            long deadline = SystemClock.elapsedRealtime()+3000;
            while (output.getPlaybackHeadPosition() == 0 && SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(20);
            if (output.getPlaybackHeadPosition() == 0) throw new IOException("PCM playback clock did not advance");
        } finally {
            try { if (output.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) output.stop(); }
            finally { output.release(); }
        }
    }
}
