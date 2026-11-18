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
        default void controlsChanged() {}
        default void mediaChanged() {}
        default void streamChanged() {}
    }
    private final Context context;
    private final String identity;
    private final Listener listener;
    private final Object nativeLock = new Object();
    private final AudioPacketQueue audioQueue = new AudioPacketQueue();
    private final VideoFrameQueue queue;
    private final ReceiverOptions options;
    private LegacyNetwork requestedNetwork;
    private volatile LegacyDacp controls;
    private final LegacyMedia media;
    private final AtomicBoolean videoActive = new AtomicBoolean();
    private final AtomicBoolean mirrorActive = new AtomicBoolean();
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
    public LegacyReceiverSession(Context context,Surface surface,String identity,Listener listener,ReceiverOptions options,LegacyNetwork network) {
        this(context,surface,identity,listener,false,options);requestedNetwork=network;
    }
    LegacyReceiverSession(Context context, Surface surface, String identity, Listener listener, boolean muted, ReceiverOptions options) {
        this.context = context.getApplicationContext();
        this.identity = identity; this.listener = listener; this.options=options;
        media=new LegacyMedia(listener::mediaChanged);
        queue = new VideoFrameQueue(audioQueue, () -> { videoActive.set(false); listener.videoStopped(); },active-> {
            mirrorActive.set(active);media.mirroring(active);listener.streamChanged();
        });
        queue.connections(listener::streamChanged);
        queue.media(new VideoFrameQueue.Media() {
            @Override public void metadata(byte[] bytes) {media.metadata(bytes);}
            @Override public void artwork(byte[] bytes) {media.artwork(bytes);}
            @Override public void progress(long start,long current,long end) {
                if(!mirrorActive.get() && LegacyTrackTimeline.validProgress(start,current,end)) {LegacyDacp target=controls;if(target!=null)target.senderPlaying(true);}
                media.progress(start,current,end);
            }
        });
        audioPlayer = new LegacyAudioPlayer(audioQueue, muted, playbackClock,options,listener::streamChanged);
        videoPlayer = new LegacyAvcPlayer(surface, queue, new LegacyAvcPlayer.Listener() {
            @Override public void decodedFrame() {
                decoded.countDown();
                if (videoActive.compareAndSet(false,true)) listener.videoStarted();
            }
            @Override public void resized(int width,int height) { listener.videoSize(width,height); }
        }, playbackClock,options!=null && options.lowLatency);
        worker = new Thread(this::run, "fireos3-live-receiver");
    }

    public void start() { worker.start(); }
    /** Transfers ownership. Release the TextureView's SurfaceTexture in whenReleased,
     * never before the decoder has retired its old Surface. Null detaches video only. */
    public void setVideoSurface(Surface surface,Runnable whenReleased) {
        videoPlayer.setSurface(surface,whenReleased);
    }
    public void detachVideoOutput(Runnable detached) {
        if(detached==null)throw new IllegalArgumentException("Missing output handoff callback");
        videoPlayer.detachSurface(detached);
    }
    public void stop() {
        if (!stopping.compareAndSet(false, true)) return;
        queue.close(); audioQueue.close(); worker.interrupt();
        Thread audio = audioWorker;
        if (audio != null) audio.interrupt();
    }

    private void run() {
        LegacyDiscovery discovery = new LegacyDiscovery();
        CountDownLatch discoveryReady=new CountDownLatch(1);
        WifiManager.MulticastLock multicast = null;
        try {
            System.loadLibrary("fireos3_probe");
            network = LegacyNetwork.select(requestedNetwork);
            if(requestedNetwork!=null && !requestedNetwork.equals(network))throw new java.net.SocketException("Selected local network changed before startup");
            controls=new LegacyDacp(service-> {
                try {if(!discoveryReady.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Discovery is not ready");}
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new IOException("Discovery cancelled",interrupted);}
                return discovery.resolveControl(service,network);
            },()-> {LegacyDacp target=controls;if(target!=null)media.playing(target.state().playing);listener.controlsChanged();});
            queue.controls(new VideoFrameQueue.Controls() {
                @Override public void identity(String id,String token) {media.sender(id,token);LegacyDacp target=controls;if(target!=null)target.update(id,token);}
                @Override public void stopped() {LegacyDacp target=controls;if(target!=null)target.senderStopped();}
            });
            WifiManager wifi = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicast = wifi.createMulticastLock("buzin-api17-probe");
                multicast.setReferenceCounted(false); multicast.acquire();
            }
            String[] video, audio;
            synchronized (nativeLock) {
                handle = NativeReceiver.startNetworkReceiver(new File(context.getFilesDir(), "receiver.key").getAbsolutePath(),
                        queue, identity, network.address.getHostAddress(), network.prefix,
                        options == null ? null : options.name, options == null ? 0 : options.port,
                        options == null ? 1280 : options.width,options == null ? 720 : options.height,options == null ? 30 : options.fps);
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
            discoveryReady.countDown();
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
            discoveryReady.countDown();
            LegacyDacp previous=controls;controls=null;if(previous!=null)previous.close();
            media.close();
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
            queue.close();videoPlayer.closeSurface();
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
        replayAudio(encoded,codec,spf,timeNs,volume,flush,true);
    }
    void replayAudio(byte[] encoded,int codec,int spf,long timeNs,float volume,boolean flush,boolean nativeThread) {
        ByteBuffer packet = ByteBuffer.allocateDirect(encoded.length); packet.put(encoded);
        synchronized (nativeLock) {
            if (handle == 0 || stopping.get() || !NativeReceiver.replayReceiverAudio(handle, packet, encoded.length,
                    codec, spf, timeNs, volume, flush,nativeThread)) throw new IllegalStateException("Audio receiver stopped before replay");
        }
    }
    double senderVolume() { return audioQueue.volumeDb(); }
    // Synthetic metadata fixture, parallel to the separate probe's A/V fixtures.
    void replayMedia(byte[] bytes,int kind,long start,long current,long end) {
        ByteBuffer input=ByteBuffer.allocateDirect(bytes.length);input.put(bytes);
        synchronized(nativeLock) {
            if(handle==0 || stopping.get() || !NativeReceiver.replayReceiverMedia(handle,input,bytes.length,kind,start,current,end))
                throw new IllegalStateException("Metadata fixture rejected");
        }
    }
    public boolean isClosed() { return closed.getCount()==0; }
    public int listeningPort() { return port; }
    public int connectionCount() {return queue.connectionCount();}
    public String localAddress() { return network == null ? null : network.address.getHostAddress(); }
    public LegacyDacp.State controls() {LegacyDacp current=controls;return current==null?LegacyDacp.inactive():current.state();}
    public LegacyMedia.State media() {return media.state();}
    public boolean mirroring() {return mirrorActive.get();}
    public boolean audioActive() {return audioPlayer.outputActive;}
    public void audioFocusGain(float gain) {audioPlayer.focusGain=Math.max(0,Math.min(1,gain));}
    public void control(LegacyDacp.Command command,LegacyDacp.Completion completion) {
        LegacyDacp current=controls;
        if(current==null) {if(completion!=null)completion.complete(new IOException("Receiver is stopped"));}
        else current.command(command,completion);
    }
}
