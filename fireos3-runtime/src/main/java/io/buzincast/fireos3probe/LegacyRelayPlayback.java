package io.buzincast.fireos3probe;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.view.Surface;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Owned API17 AVC/PCM output for authenticated Buzin relay packets. */
public final class LegacyRelayPlayback implements java.io.Closeable {
    public interface Listener {void changed();}
    private static final int AUDIO_BYTES=256*1024,AUDIO_PACKETS=64;
    private static final class Pcm {
        final byte[] data;final long pts;
        Pcm(byte[] data,long pts) {this.data=data;this.pts=pts;}
    }
    private final Listener listener;
    private final AtomicBoolean closing=new AtomicBoolean();
    private final AtomicInteger retired=new AtomicInteger();
    private final AudioPlaybackClock clock=new AudioPlaybackClock();
    private final VideoFrameQueue queue=new VideoFrameQueue();
    private final ArrayDeque<Pcm> audio=new ArrayDeque<>();
    private int audioBytes;
    private boolean activated;
    private byte[] priming;
    private long primingPts;
    private final LegacyAvcPlayer video;
    private final Thread videoWorker,audioWorker;
    public volatile long decodedFrames,writtenFrames,playedFrames;
    public volatile int width,height;
    public volatile boolean audioActive,closed;
    public volatile float focusGain;
    public volatile float appliedGain;
    public volatile Exception failure;
    public LegacyRelayPlayback(Listener listener) {
        this.listener=listener;
        video=new LegacyAvcPlayer(null,queue,new LegacyAvcPlayer.Listener() {
            @Override public void decodedFrame() {if(decodedFrames++==0)listener.changed();}
            @Override public void resized(int w,int h) {width=w;height=h;listener.changed();}
        },clock);
        videoWorker=new Thread(()-> {
            try {video.run(closing::get);}
            catch(Exception error) {if(!closing.get())fail(error);}
            finally {finishWorker();}
        },"buzin-legacy-relay-video");
        audioWorker=new Thread(()-> {
            try {playAudio();}
            catch(Exception error) {if(!closing.get())fail(error);}
            finally {finishWorker();}
        },"buzin-legacy-relay-pcm");
        videoWorker.start();audioWorker.start();
    }
    /** Surface is owned on success, including a null request's retirement callback. */
    public synchronized void surface(Surface surface,Runnable retired) {
        if(closing.get()) {if(surface!=null)surface.release();if(retired!=null)retired.run();return;}
        video.setSurface(surface,retired);
        if(surface!=null && !activated) {activated=true;if(priming!=null) {queue.onVideoData(priming,primingPts,false);priming=null;}}
    }
    public void detach(Runnable retired) {video.detachSurface(retired);}
    private static void timestamp(long pts) {
        if(pts<0 || pts>Long.MAX_VALUE-1_000_000_000L)throw new IllegalArgumentException("Invalid relay timestamp");
    }
    /** Packet arrays are owned by the caller and must not be mutated after handoff. */
    public synchronized void video(byte[] bytes,long pts,int width,int height,boolean hevc) {
        timestamp(pts);
        if(hevc || width<1 || height<1 || width>1920 || height>1920 || (long)width*height>1920L*1080 || bytes==null || bytes.length<5 || bytes.length>VideoFrameQueue.MAX_BYTES)
            throw new IllegalArgumentException("Unsupported relay video");
        if(closing.get())return;
        if(!activated) {if(priming==null) {priming=bytes;primingPts=pts;}return;}
        queue.onVideoData(bytes,pts,false);
    }
    public synchronized void audio(byte[] bytes,long pts,int rate,int channels) {
        timestamp(pts);
        if(rate!=44100 || channels!=2 || bytes==null || bytes.length==0 || bytes.length>128*1024 || bytes.length%4!=0)
            throw new IllegalArgumentException("Unsupported relay PCM");
        if(closing.get())return;
        if(audio.size()>=AUDIO_PACKETS || bytes.length>AUDIO_BYTES-audioBytes)throw new IllegalStateException("Relay PCM queue exceeded its budget");
        audio.addLast(new Pcm(bytes,pts));audioBytes+=bytes.length;notifyAll();
    }
    public synchronized void gain(float value) {focusGain=Float.isNaN(value)?0:Math.max(0,Math.min(1,value));notifyAll();}
    private synchronized Pcm take(float lastGain) throws InterruptedException {
        while(audio.isEmpty() && !closing.get() && lastGain==focusGain)wait(200);
        Pcm next=audio.pollFirst();if(next!=null)audioBytes-=next.data.length;return next;
    }
    private void playAudio() throws Exception {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        AudioTrack track=null;float lastGain=-1;
        try {
            while(!closing.get()) {
                float currentGain=focusGain;
                if(track!=null && currentGain!=lastGain) {track.setStereoVolume(currentGain,currentGain);appliedGain=currentGain;}
                lastGain=currentGain;
                Pcm pcm=take(lastGain);if(pcm==null)continue;
                if(track==null) {
                    int minimum=AudioTrack.getMinBufferSize(44100,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
                    if(minimum<=0)throw new IOException("Relay PCM output unsupported");
                    track=new AudioTrack(AudioManager.STREAM_MUSIC,44100,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,17640),AudioTrack.MODE_STREAM);
                    if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("Relay PCM output init failed");
                    track.setStereoVolume(0,0);appliedGain=0;lastGain=-1;track.play();audioActive=true;listener.changed();
                }
                for(int offset=0;offset<pcm.data.length && !closing.get();) {
                    float gain=Float.isNaN(focusGain)?0:Math.max(0,Math.min(1,focusGain));
                    if(gain!=lastGain) {track.setStereoVolume(gain,gain);lastGain=gain;appliedGain=gain;}
                    int count=track.write(pcm.data,offset,Math.min(4096*4,pcm.data.length-offset));
                    if(count<=0 || count%4!=0)throw new IOException("Relay PCM write failed");
                    clock.submit(writtenFrames,count/4,pcm.pts+(offset/4L)*1_000_000_000L/44100);
                    writtenFrames+=count/4;offset+=count;
                    playedFrames=clock.updateHead(track.getPlaybackHeadPosition(),System.nanoTime());
                }
            }
        } finally {
            try {if(track!=null) {try {if(track.getPlayState()==AudioTrack.PLAYSTATE_PLAYING)track.stop();}finally {track.release();}}}
            finally {audioActive=false;clock.reset();listener.changed();}
        }
    }
    private void finishWorker() {
        if(retired.incrementAndGet()==2) {closed=true;listener.changed();}
    }
    private synchronized void fail(Exception error) {if(failure==null) {failure=error;android.util.Log.e("LegacyRelayPlayback","Relay output failed",error);}close();listener.changed();}
    @Override public synchronized void close() {
        if(!closing.compareAndSet(false,true))return;
        queue.close();priming=null;audio.clear();audioBytes=0;notifyAll();videoWorker.interrupt();audioWorker.interrupt();
    }
}
