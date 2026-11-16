package io.buzincast.fireos3probe;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns native receiver, publisher, audio worker, video decoder and Surface. */
public final class LegacyReceiverSession {
    public interface Listener {
        void ready(String name);
        void failed(Exception error);
        void stopped();
        default void videoStarted() {}
        default void videoStopped() {}
        default void videoSize(int width,int height) {}
    }
    private final Context context;
    private final Surface surface;
    private final String identity;
    private final Listener listener;
    private final Object nativeLock = new Object();
    private final AudioPacketQueue audioQueue = new AudioPacketQueue();
    private final VideoFrameQueue queue;
    private final ReceiverOptions options;
    private final AtomicBoolean videoActive = new AtomicBoolean();
    final LegacyAudioPlayer audioPlayer;
    final AudioPlaybackClock playbackClock = new AudioPlaybackClock();
    final LegacyAvcPlayer videoPlayer;
    private volatile Thread audioWorker;
    private volatile Exception audioFailure;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final Thread worker;
    private long handle;
    final CountDownLatch ready = new CountDownLatch(1);
    final CountDownLatch closed = new CountDownLatch(1);
    final CountDownLatch decoded = new CountDownLatch(1);
    volatile int port;
    volatile Exception failure;
    volatile LegacyNetwork network;

    LegacyReceiverSession(Context context, Surface surface, String identity, Listener listener) {
        this(context, surface, identity, listener, false);
    }
    LegacyReceiverSession(Context context, Surface surface, String identity, Listener listener, boolean muted) {
        this(context,surface,identity,listener,muted,null);
    }
    public LegacyReceiverSession(Context context, Surface surface, String identity, Listener listener, ReceiverOptions options) {
        this(context,surface,identity,listener,false,options);
    }
    private LegacyReceiverSession(Context context, Surface surface, String identity, Listener listener, boolean muted, ReceiverOptions options) {
        this.context = context.getApplicationContext(); this.surface = surface;
        this.identity = identity; this.listener = listener; this.options=options;
        queue = new VideoFrameQueue(audioQueue, () -> { videoActive.set(false); listener.videoStopped(); });
        audioPlayer = new LegacyAudioPlayer(audioQueue, muted, playbackClock);
        videoPlayer = new LegacyAvcPlayer(surface, queue, new LegacyAvcPlayer.Listener() {
            @Override public void decodedFrame() {
                decoded.countDown();
                if (videoActive.compareAndSet(false,true)) listener.videoStarted();
            }
            @Override public void resized(int width,int height) { listener.videoSize(width,height); }
        }, playbackClock);
        worker = new Thread(this::run, "fireos3-live-receiver");
    }

    public void start() { worker.start(); }
    public void stop() {
        if (!stopping.compareAndSet(false, true)) return;
        queue.close(); audioQueue.close(); worker.interrupt();
        Thread audio = audioWorker;
        if (audio != null) audio.interrupt();
    }

    private void run() {
        LegacyDiscovery discovery = new LegacyDiscovery();
        WifiManager.MulticastLock multicast = null;
        try {
            System.loadLibrary("fireos3_probe");
            network = LegacyNetwork.select();
            WifiManager wifi = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicast = wifi.createMulticastLock("buzin-api17-probe");
                multicast.setReferenceCounted(false); multicast.acquire();
            }
            String[] video, audio;
            synchronized (nativeLock) {
                handle = NativeReceiver.startNetworkReceiver(new File(context.getFilesDir(), "receiver.key").getAbsolutePath(),
                        queue, identity, network.address.getHostAddress(), network.prefix,
                        options == null ? null : options.name, options == null ? 0 : options.port);
                if (handle == 0) throw new IOException("Native video receiver could not start");
                port = NativeReceiver.receiverPort(handle);
                video = NativeReceiver.receiverTxt(handle, true);
                audio = NativeReceiver.receiverTxt(handle, false);
            }
            if (stopping.get()) return;
            audioWorker = new Thread(() -> {
                try { audioPlayer.run(stopping::get); }
                catch (Exception error) { if (!stopping.get()) audioFailure = error; }
                catch (LinkageError error) { if (!stopping.get()) audioFailure = new IOException("Legacy audio dependency failed", error); }
            }, "fireos3-live-audio");
            audioWorker.start();
            discovery.start(network.address, identity, port, video, audio,options == null ? null : options.name);
            if (stopping.get()) return;
            ready.countDown(); listener.ready(discovery.name());
            videoPlayer.run(() -> stopping.get() || audioFailure != null);
            if (audioFailure != null) throw audioFailure;
        } catch (Exception error) {
            if (!stopping.get()) { failure = error; listener.failed(error); }
        } catch (LinkageError error) {
            failure = new IOException("Legacy receiver dependency could not load", error);
            listener.failed(failure);
        } finally {
            stopping.set(true);
            // An interrupted start must still unregister records and join native workers.
            Thread.interrupted();
            try { discovery.close(); } catch (IOException error) { if (failure == null) failure = error; }
            synchronized (nativeLock) {
                if (handle != 0) { NativeReceiver.stopReceiver(handle); handle = 0; }
            }
            audioQueue.close();
            if (audioWorker != null) {
                audioWorker.interrupt();
                try {
                    audioWorker.join(5000);
                    if (audioWorker.isAlive() && failure == null) failure = new IOException("Audio output did not stop");
                } catch (InterruptedException error) {
                    if (failure == null) failure = error;
                    Thread.currentThread().interrupt();
                }
            }
            queue.close(); surface.release();
            if (multicast != null && multicast.isHeld()) multicast.release();
            port = 0;
            ready.countDown(); closed.countDown(); listener.stopped();
        }
    }

    // Only the synthetic self-test calls this. The lock prevents use-after-free on stop.
    void replay(byte[] annexB, long timeNs) {
        ByteBuffer packet = ByteBuffer.allocateDirect(annexB.length); packet.put(annexB);
        synchronized (nativeLock) {
            if (handle == 0 || stopping.get() || !NativeReceiver.replayReceiverVideo(handle, packet, annexB.length, timeNs)) {
                throw new IllegalStateException("Receiver stopped before replay");
            }
        }
    }

    void replayAudio(byte[] encoded, int codec, int spf, long timeNs, float volume, boolean flush) {
        ByteBuffer packet = ByteBuffer.allocateDirect(encoded.length); packet.put(encoded);
        synchronized (nativeLock) {
            if (handle == 0 || stopping.get() || !NativeReceiver.replayReceiverAudio(handle, packet, encoded.length,
                    codec, spf, timeNs, volume, flush)) throw new IllegalStateException("Audio receiver stopped before replay");
        }
    }
    double senderVolume() { return audioQueue.volumeDb(); }
    public boolean isClosed() { return closed.getCount()==0; }
    public int listeningPort() { return port; }
    public String localAddress() { return network == null ? null : network.address.getHostAddress(); }
}
