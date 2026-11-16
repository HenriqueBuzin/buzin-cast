package io.buzincast.fireos3probe;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProbeActivity extends Activity {
    private static final String EMULATOR_SAMPLE_URL = "http://10.0.2.2:18765/h264_sample.mp4";
    private static final int MAX_SAMPLE_BYTES = 64 * 1024;
    private boolean decoding;
    private CountDownLatch previewFrame;
    private final AtomicInteger previewUpdates=new AtomicInteger();
    private LegacyReceiverSession liveSession;
    private Thread stackCheckWorker;
    private TextureView previewView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if(getIntent().hasExtra("external_dns_port")) {externalDiscovery();return;}
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);

        TextView device = new TextView(this);
        device.setText(getString(R.string.probe_device, Build.MODEL, Build.VERSION.SDK_INT, Build.CPU_ABI));
        content.addView(device);

        LinearLayout checks = new LinearLayout(this);
        LinearLayout playback = new LinearLayout(this);
        content.addView(checks);
        content.addView(playback);

        Button run = new Button(this);
        run.setText(R.string.probe_run);
        checks.addView(run, new LinearLayout.LayoutParams(0, -2, 1));

        Button network = new Button(this);
        network.setText(R.string.probe_run_network);
        checks.addView(network, new LinearLayout.LayoutParams(0, -2, 1));

        Button callback = new Button(this);
        callback.setText(R.string.probe_run_callback);
        playback.addView(callback, new LinearLayout.LayoutParams(0, -2, 1));

        Button exit = new Button(this);
        exit.setText(R.string.probe_exit);
        exit.setOnClickListener(view -> finish());
        playback.addView(exit, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout liveControls = new LinearLayout(this);
        content.addView(liveControls);
        Button live = new Button(this);
        live.setText(R.string.probe_live_start);
        liveControls.addView(live, new LinearLayout.LayoutParams(0, -2, 1));
        Button streamChecks = new Button(this);
        streamChecks.setText(R.string.probe_stream_checks);
        liveControls.addView(streamChecks, new LinearLayout.LayoutParams(0, -2, 1));

        TextView parserResult = new TextView(this);
        TextView receiverResult = new TextView(this);
        TextView decoderResult = new TextView(this);
        content.addView(parserResult);
        content.addView(receiverResult);
        content.addView(decoderResult);
        TextureView preview = new TextureView(this);
        previewView = preview;
        content.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {}
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                stopLive();
                if (stackCheckWorker != null) stackCheckWorker.interrupt();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                previewUpdates.incrementAndGet();
                if (previewFrame != null) {
                    previewFrame.countDown();
                }
            }
        });
        run.setOnClickListener(view -> {
            if (decoding || liveSession != null) return;
            parserResult.setText(checkParser());
            startDecoder(decoderResult, receiverResult, null, null, false);
        });
        network.setOnClickListener(view -> {
            if (decoding || liveSession != null) {
                return;
            }
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null) {
                decoderResult.setText(R.string.probe_surface_unavailable);
                return;
            }
            previewFrame = new CountDownLatch(1);
            startDecoder(decoderResult, null, new Surface(texture), previewFrame, false);
        });
        callback.setOnClickListener(view -> {
            if (decoding || liveSession != null) return;
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null) { decoderResult.setText(R.string.probe_surface_unavailable); return; }
            previewFrame = new CountDownLatch(1);
            startDecoder(decoderResult, null, new Surface(texture), previewFrame, true);
        });
        live.setOnClickListener(view -> {
            if (liveSession != null) { live.setEnabled(false); stopLive(); return; }
            if (decoding || preview.getSurfaceTexture() == null) return;
            decoderResult.setText(R.string.probe_live_starting);
            live.setText(R.string.probe_live_stop);
            liveSession = new LegacyReceiverSession(this, new Surface(preview.getSurfaceTexture()), receiverIdentity(),
                    new LegacyReceiverSession.Listener() {
                        private boolean failed;
                        @Override public void ready(String name) {
                            runOnUiThread(() -> { if (!isDestroyed()) decoderResult.setText(getString(R.string.probe_live_ready, name)); });
                        }
                        @Override public void failed(Exception error) {
                            failed = true;
                            runOnUiThread(() -> { if (!isDestroyed()) decoderResult.setText(getString(R.string.probe_live_failed, error.getMessage())); });
                        }
                        @Override public void stopped() {
                            runOnUiThread(() -> {
                                liveSession = null;
                                if (!isDestroyed()) {
                                    live.setEnabled(true); live.setText(R.string.probe_live_start);
                                    preview.setVisibility(android.view.View.VISIBLE);
                                    if (!failed) decoderResult.setText(R.string.probe_live_stopped);
                                }
                            });
                        }
                    },new ReceiverOptions("Buzin Cast API17 Profile",17001,
                            getIntent().getBooleanExtra("probe_games",false)?1280:1920,
                            getIntent().getBooleanExtra("probe_games",false)?720:1080,
                            getIntent().getBooleanExtra("probe_games",false)?60:30,false,25,90));
            liveSession.start();
        });
        streamChecks.setOnClickListener(view -> {
            if (decoding || liveSession != null || preview.getSurfaceTexture() == null) return;
            decoding = true;
            previewFrame = new CountDownLatch(1);
            CountDownLatch displayed = previewFrame;
            Surface surface = new Surface(preview.getSurfaceTexture());
            SurfaceTexture targetTexture=preview.getSurfaceTexture();
            decoderResult.setText(R.string.probe_stream_checking);
            stackCheckWorker = new Thread(() -> {
                String result;
                try {
                    StreamingSelfTest.run(this, surface,targetTexture,receiverIdentity(),displayed,previewUpdates);
                    result = getString(R.string.probe_stream_passed);
                } catch (Exception | LinkageError error) {
                    result = getString(R.string.probe_stream_failed, error.getMessage());
                }
                String status = result;
                runOnUiThread(() -> {
                    decoding = false; previewFrame = null; stackCheckWorker = null;
                    if (!isDestroyed()) decoderResult.setText(status);
                });
            }, "fireos3-stream-selftest");
            stackCheckWorker.start();
        });
        setContentView(content);
        run.requestFocus();
    }
    private void externalDiscovery() {
        TextView status=new TextView(this);status.setText("External DNS check running");setContentView(status);
        if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0 ||
                !(Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu"))) {
            status.setText("External DNS check refused: emulator only");return;
        }
        String ip=getIntent().getStringExtra("external_dns_address");int port=getIntent().getIntExtra("external_dns_port",0);
        new Thread(()-> {
            String result;
            try {
                java.net.InetAddress address=java.net.InetAddress.getByName(ip);
                if(!(address instanceof java.net.Inet4Address) || !address.isSiteLocalAddress() ||
                        java.net.NetworkInterface.getByInetAddress(address)==null || port<1 || port>65535)
                    throw new IOException("Discovery target is not a local IPv4 receiver");
                StreamingSelfTest.checkDiscovery(address,port);result="External DNS check passed";
            } catch(Exception error) {result="External DNS check failed: "+error;}
            String message=result;runOnUiThread(()-> {if(!isDestroyed())status.setText(message);});
        },"external-dns-check").start();
    }

    private void startDecoder(TextView status, TextView receiverStatus, Surface surface, CountDownLatch frameShown, boolean callbackPath) {
        if (decoding || liveSession != null) {
            return;
        }
        decoding = true;
        status.setText(callbackPath ? R.string.probe_decoding_callback
                : (surface == null ? R.string.probe_decoding : R.string.probe_decoding_network));
        new Thread(() -> {
            String receiverResult = receiverStatus == null ? null : checkReceiver();
            String result;
            try {
                result = checkDecoder(surface, frameShown, callbackPath);
            } finally {
                if (surface != null) {
                    surface.release();
                }
            }
            runOnUiThread(() -> {
                decoding = false;
                previewFrame = null;
                if (!isFinishing() && !isDestroyed()) {
                    if (receiverStatus != null) {
                        receiverStatus.setText(receiverResult);
                    }
                    status.setText(result);
                }
            });
        }, "fireos3-decoder-probe").start();
    }

    private String receiverIdentity() {
        android.content.SharedPreferences preferences = getSharedPreferences("receiver", MODE_PRIVATE);
        String identity = preferences.getString("identity", null);
        if (identity == null) {
            byte[] address = new byte[6]; new java.security.SecureRandom().nextBytes(address);
            address[0] = (byte)((address[0] & 0xFC) | 2);
            StringBuilder text = new StringBuilder();
            for (byte octet : address) { if (text.length() > 0) text.append(':'); text.append(String.format(java.util.Locale.US, "%02X", octet & 255)); }
            identity = text.toString(); preferences.edit().putString("identity", identity).apply();
        }
        return identity;
    }

    private void stopLive() {
        if (liveSession != null) {
            liveSession.stop();
            if (previewView != null) previewView.setVisibility(android.view.View.INVISIBLE);
        }
    }

    @Override protected void onStop() {
        stopLive();
        if (stackCheckWorker != null) stackCheckWorker.interrupt();
        super.onStop();
    }

    private String checkParser() {
        try {
            System.loadLibrary("fireos3_probe");
            return getString(checkNativeParser() ? R.string.probe_parser_ok : R.string.probe_parser_failed);
        } catch (LinkageError error) {
            return getString(R.string.probe_library_failed, error.getMessage());
        }
    }

    private String checkReceiver() {
        long handle = 0;
        File key = null;
        try {
            System.loadLibrary("fireos3_probe");
            key = File.createTempFile("probe-raop", ".key", getCacheDir());
            handle = startReceiver(key.getAbsolutePath(), null);
            if (handle == 0) {
                return getString(R.string.probe_receiver_start_failed);
            }
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress("127.0.0.1", receiverPort(handle)), 2000);
                client.setSoTimeout(2000);
                client.getOutputStream().write("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n".getBytes("US-ASCII"));
                BufferedReader response = new BufferedReader(new InputStreamReader(client.getInputStream(), "US-ASCII"));
                String status = response.readLine();
                if (!"RTSP/1.0 200 OK".equals(status)) {
                    return getString(R.string.probe_receiver_failed, status);
                }
                for (String header; (header = response.readLine()) != null && !header.isEmpty();) {
                    if (header.startsWith("Public:") && header.contains("SETUP") && header.contains("RECORD")) {
                        return getString(R.string.probe_receiver_passed);
                    }
                }
                return getString(R.string.probe_receiver_failed, getString(R.string.probe_receiver_missing_methods));
            }
        } catch (IOException | LinkageError | RuntimeException error) {
            return getString(R.string.probe_receiver_failed, error.getMessage());
        } finally {
            if (handle != 0) {
                stopReceiver(handle);
            }
            if (key != null) {
                key.delete();
            }
        }
    }

    private String checkDecoder(Surface surface, CountDownLatch frameShown, boolean callbackPath) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        File networkSample = null;
        File receiverKey = null;
        long receiver = 0;
        VideoFrameQueue frameQueue = new VideoFrameQueue();
        try {
            System.loadLibrary("fireos3_probe");
            if (surface == null || callbackPath) {
                try (AssetFileDescriptor sample = getResources().openRawResourceFd(R.raw.h264_sample)) {
                    extractor.setDataSource(sample.getFileDescriptor(), sample.getStartOffset(), sample.getLength());
                }
            } else {
                networkSample = File.createTempFile("probe-avc", ".mp4", getCacheDir());
                fetchNetworkSample(networkSample);
                extractor.setDataSource(networkSample.getAbsolutePath());
            }
            MediaFormat format = extractor.getTrackFormat(0);
            extractor.selectTrack(0);
            decoder = MediaCodec.createDecoderByType("video/avc");
            decoder.configure(format, surface, null, 0);
            decoder.start();
            if (callbackPath) {
                receiverKey = File.createTempFile("probe-callback", ".key", getCacheDir());
                receiver = startReceiver(receiverKey.getAbsolutePath(), frameQueue);
                if (receiver == 0) throw new IllegalStateException("Receiver callback session could not start");
            }

            ByteBuffer[] inputBuffers = decoder.getInputBuffers();
            AvcInput frameInput = new AvcInput(decoder, inputBuffers);
            MediaCodec.BufferInfo output = new MediaCodec.BufferInfo();
            boolean inputEnded = false;
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (SystemClock.elapsedRealtime() < deadline) {
                int inputIndex = inputEnded ? -1 : decoder.dequeueInputBuffer(10000);
                if (inputIndex >= 0) {
                    ByteBuffer input = inputBuffers[inputIndex];
                    input.clear();
                    int size = extractor.readSampleData(input, 0);
                    inputEnded = size < 0;
                    if (inputEnded) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        long timeNs = extractor.getSampleTime() * 1000L;
                        if (callbackPath) {
                            if (!replayReceiverVideo(receiver, input, size, timeNs)) {
                                throw new IllegalStateException("Receiver callback replay failed");
                            }
                            VideoFrameQueue.Frame frame = frameQueue.take(1000);
                            if (frame == null) throw new IllegalStateException("Receiver callback timed out");
                            input.clear();
                            input.put(frame.data);
                            decoder.queueInputBuffer(inputIndex, 0, frame.data.length, frame.timeNs / 1000L, 0);
                        } else {
                            frameInput.replay(inputIndex, size, timeNs);
                        }
                        extractor.advance();
                    }
                }
                int outputIndex = decoder.dequeueOutputBuffer(output, 10000);
                if (outputIndex >= 0) {
                    boolean frame = output.size > 0 && (output.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                    decoder.releaseOutputBuffer(outputIndex, surface != null && frame);
                    if (frame) {
                        if (frameShown == null) {
                            return getString(R.string.probe_decoder_passed);
                        }
                        return getString(frameShown.await(2, TimeUnit.SECONDS)
                                ? (callbackPath ? R.string.probe_callback_passed : R.string.probe_network_passed)
                                : (callbackPath ? R.string.probe_callback_not_shown : R.string.probe_frame_not_shown));
                    }
                    if ((output.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
            return getString(R.string.probe_decoder_no_frame);
        } catch (IOException | LinkageError | RuntimeException error) {
            return getString(R.string.probe_decoder_failed, error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return getString(R.string.probe_frame_not_shown);
        } finally {
            // Stop native callbacks before disposing their queue or the decoder.
            if (receiver != 0) stopReceiver(receiver);
            frameQueue.close();
            if (receiverKey != null) receiverKey.delete();
            if (decoder != null) {
                decoder.release();
            }
            extractor.release();
            if (networkSample != null) {
                networkSample.delete();
            }
        }
    }

    private void fetchNetworkSample(File destination) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(EMULATOR_SAMPLE_URL).openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        connection.setInstanceFollowRedirects(false);
        long deadline = SystemClock.elapsedRealtime() + 4000;
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Unexpected response from sample server");
            }
            try (InputStream source = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[4096];
                int total = 0;
                int count;
                while ((count = source.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_SAMPLE_BYTES || SystemClock.elapsedRealtime() > deadline) {
                        throw new IOException("Network sample exceeded the size or time limit");
                    }
                    output.write(buffer, 0, count);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static final class AvcInput {
        private final MediaCodec decoder;
        private final ByteBuffer[] buffers;
        private int inputIndex;

        AvcInput(MediaCodec decoder, ByteBuffer[] buffers) {
            this.decoder = decoder;
            this.buffers = buffers;
        }

        void replay(int index, int length, long timeNs) {
            inputIndex = index;
            if (!replayVideoFrame(this, buffers[index], length, timeNs)) {
                throw new IllegalStateException("Native frame callback failed");
            }
        }

        // Called synchronously from JNI on the decoder worker.
        @SuppressWarnings("unused")
        private void onVideoData(byte[] data, long timeNs, boolean isH265) {
            if (isH265) {
                throw new IllegalArgumentException("Expected H.264");
            }
            ByteBuffer input = buffers[inputIndex];
            input.clear();
            input.put(data);
            decoder.queueInputBuffer(inputIndex, 0, data.length, timeNs / 1000L, 0);
        }
    }

    private static boolean checkNativeParser() { return NativeReceiver.checkNativeParser(); }
    private static long startReceiver(String keyPath, VideoFrameQueue callback) { return NativeReceiver.startReceiver(keyPath,callback); }
    static void stopReceiver(long handle) { NativeReceiver.stopReceiver(handle); }
    static int receiverPort(long handle) { return NativeReceiver.receiverPort(handle); }
    private static boolean replayVideoFrame(AvcInput callback, ByteBuffer input, int length, long timeNs) {
        return NativeReceiver.replayVideoFrame(callback,input,length,timeNs);
    }
    static boolean replayReceiverVideo(long handle, ByteBuffer input, int length, long timeNs) {
        return NativeReceiver.replayReceiverVideo(handle,input,length,timeNs);
    }
}
