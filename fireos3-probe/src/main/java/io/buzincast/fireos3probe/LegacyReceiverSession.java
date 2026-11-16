package io.buzincast.fireos3probe;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns native receiver, publisher, decoder and Surface on a background worker. */
final class LegacyReceiverSession {
    interface Listener {
        void ready(String name);
        void failed(Exception error);
        void stopped();
    }
    private final Context context;
    private final Surface surface;
    private final String identity;
    private final Listener listener;
    private final Object nativeLock = new Object();
    private final VideoFrameQueue queue = new VideoFrameQueue();
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
        this.context = context.getApplicationContext(); this.surface = surface;
        this.identity = identity; this.listener = listener;
        worker = new Thread(this::run, "fireos3-live-receiver");
    }

    void start() { worker.start(); }
    void stop() { stopping.set(true); queue.close(); worker.interrupt(); }

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
                handle = ProbeActivity.startNetworkReceiver(new File(context.getFilesDir(), "receiver.key").getAbsolutePath(),
                        queue, identity, network.address.getHostAddress(), network.prefix);
                if (handle == 0) throw new IOException("Native video receiver could not start");
                port = ProbeActivity.receiverPort(handle);
                video = ProbeActivity.receiverTxt(handle, true);
                audio = ProbeActivity.receiverTxt(handle, false);
            }
            if (stopping.get()) return;
            discovery.start(network.address, identity, port, video, audio);
            if (stopping.get()) return;
            ready.countDown(); listener.ready(discovery.name());
            LegacyAvcPlayer player = new LegacyAvcPlayer(surface, queue, decoded::countDown);
            player.run(stopping::get);
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
                if (handle != 0) { ProbeActivity.stopReceiver(handle); handle = 0; }
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
            if (handle == 0 || stopping.get() || !ProbeActivity.replayReceiverVideo(handle, packet, annexB.length, timeNs)) {
                throw new IllegalStateException("Receiver stopped before replay");
            }
        }
    }
}
