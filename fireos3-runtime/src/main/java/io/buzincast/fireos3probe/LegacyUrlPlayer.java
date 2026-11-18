package io.buzincast.fireos3probe;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** API17 platform URL backend. Network, player and Surface operations share one worker. */
public final class LegacyUrlPlayer implements java.io.Closeable {
    public enum Phase {IDLE,LOADING,READY,ENDED,FAILED,CLOSED}
    public static final class State {
        public final Phase phase;
        public final long generation,positionMs,durationMs;
        public final boolean playing,buffering;
        public final int width,height;
        public final Exception failure;
        State(Phase phase,long generation,long positionMs,long durationMs,boolean playing,boolean buffering,int width,int height,Exception failure) {
            this.phase=phase;this.generation=generation;this.positionMs=positionMs;this.durationMs=durationMs;
            this.playing=playing;this.buffering=buffering;this.width=width;this.height=height;this.failure=failure;
        }
    }
    public interface Listener {void changed(State state);}
    private final Context context;
    private final Listener listener;
    private final HandlerThread thread=new HandlerThread("buzin-legacy-url-player");
    private final Handler worker;
    private final AtomicBoolean closing=new AtomicBoolean();
    private final AtomicLong commands=new AtomicLong();
    private final CountDownLatch closed=new CountDownLatch(1);
    private final OwnedTargetSlot<Surface> targets=new OwnedTargetSlot<>();
    private OwnedTargetSlot.Target<Surface> target;
    private MediaPlayer player;
    private long generation,pendingSeek=-1;
    private boolean wantedPlaying,prepared,buffering,seeking;
    private float gain=1,focusGain;
    private int width,height;
    private Phase phase=Phase.IDLE;
    private volatile State state=new State(Phase.IDLE,0,0,0,false,false,0,0,null);
    private final Runnable tick=new Runnable() {
        @Override public void run() {if(closing.get())return;if(prepared)publish(null);if(player!=null)worker.postDelayed(this,250);}
    };
    public LegacyUrlPlayer(Context context,Listener listener) {
        if(listener==null)throw new IllegalArgumentException("Missing player listener");
        this.context=context.getApplicationContext();this.listener=listener;thread.start();worker=new Handler(thread.getLooper());
    }
    public State state() {return state;}
    private void post(Runnable action) {
        if(closing.get())throw new IllegalStateException("URL player is closed");
        worker.post(()-> {if(closing.get())return;try {action.run();}catch(RuntimeException error) {fail(error);}});
    }
    public long load(LegacyUrlRequest request,boolean play) {
        if(request==null)throw new IllegalArgumentException("Missing media request");
        long owner=commands.incrementAndGet();
        post(()-> {
            if(owner!=commands.get())return;
            releasePlayer();generation=owner;prepared=seeking=false;wantedPlaying=play;buffering=true;width=height=0;pendingSeek=request.startMs;
            phase=Phase.LOADING;publish(null);
            MediaPlayer next=new MediaPlayer();player=next;
            next.setAudioStreamType(AudioManager.STREAM_MUSIC);next.setVolume(0,0);
            next.setOnPreparedListener(p-> {
                if(!current(p))return;
                try {
                    prepared=true;buffering=false;phase=Phase.READY;applyGain();
                    seeking=pendingSeek>0;
                    if(seeking)p.seekTo(seekPosition(pendingSeek,p.getDuration()));
                    pendingSeek=-1;if(wantedPlaying && !seeking)p.start();publish(null);
                } catch(RuntimeException error) {fail(error);}
            });
            next.setOnCompletionListener(p-> {if(current(p) && !seeking) {worker.removeCallbacks(tick);phase=Phase.ENDED;wantedPlaying=false;buffering=false;publish(null);}});
            next.setOnErrorListener((p,what,extra)-> {if(current(p))fail(new IOException("Platform URL playback failed: "+what+"/"+extra));return true;});
            next.setOnVideoSizeChangedListener((p,w,h)-> {if(current(p)) {width=w;height=h;publish(null);}});
            next.setOnSeekCompleteListener(p-> {if(current(p)) {seeking=false;try {if(wantedPlaying)p.start();publish(null);}catch(RuntimeException error) {fail(error);}}});
            next.setOnInfoListener((p,what,extra)-> {
                if(current(p) && (what==MediaPlayer.MEDIA_INFO_BUFFERING_START || what==MediaPlayer.MEDIA_INFO_BUFFERING_END)) {
                    buffering=what==MediaPlayer.MEDIA_INFO_BUFFERING_START;publish(null);
                }return false;
            });
            try {
                if(target!=null)next.setSurface(target.value);
                next.setDataSource(context,Uri.parse(request.location),request.headers);next.prepareAsync();
                worker.removeCallbacks(tick);worker.post(tick);
            } catch(IOException | RuntimeException error) {fail(error);}
        });
        return owner;
    }
    private boolean current(MediaPlayer candidate) {return !closing.get() && player==candidate && generation==commands.get();}
    public void playing(boolean playing) {post(()-> {
        wantedPlaying=playing;if(!prepared)return;
        if(playing) {if(!seeking)player.start();phase=Phase.READY;worker.removeCallbacks(tick);worker.post(tick);}
        else if(player.isPlaying())player.pause();
        publish(null);
    });}
    public void seek(long positionMs) {
        if(positionMs<0 || positionMs>Integer.MAX_VALUE)throw new IllegalArgumentException("Unsupported media position");
        post(()-> {pendingSeek=positionMs;if(prepared) {seeking=true;phase=Phase.READY;player.seekTo(seekPosition(positionMs,player.getDuration()));pendingSeek=-1;publish(null);}});
    }
    private static int seekPosition(long requested,int duration) {return (int)(duration>0?Math.min(requested,duration):requested);}
    public void gain(float gain) {validateGain(gain);post(()-> {this.gain=gain;applyGain();});}
    public void focusGain(float gain) {validateGain(gain);post(()-> {focusGain=gain;applyGain();});}
    private static void validateGain(float gain) {if(Float.isNaN(gain) || Float.isInfinite(gain) || gain<0 || gain>1)throw new IllegalArgumentException("Invalid media gain");}
    private void applyGain() {if(player!=null)player.setVolume(gain*focusGain,gain*focusGain);}
    /** Takes ownership; retire the view's texture only from whenReleased. */
    public void surface(Surface surface,Runnable whenReleased) {
        targets.request(surface,surface==null?null:()-> {try {surface.release();}finally {if(whenReleased!=null)whenReleased.run();}});
        if(closing.get())return;
        post(()-> {
            if(!targets.changed())return;
            if(player!=null)player.setSurface(null);
            target=targets.activate();if(player!=null && target!=null)player.setSurface(target.value);
        });
    }
    public void stop() {long owner=commands.incrementAndGet();post(()-> {if(owner!=commands.get())return;generation=owner;releasePlayer();prepared=buffering=seeking=wantedPlaying=false;width=height=0;pendingSeek=-1;phase=Phase.IDLE;publish(null);});}
    private void publish(Exception error) {
        long position=0,duration=0;boolean playing=false;
        try {if(prepared && player!=null) {position=player.getCurrentPosition();duration=Math.max(0,player.getDuration());playing=player.isPlaying();}}
        catch(RuntimeException invalid) {fail(invalid);return;}
        state=new State(phase,generation,position,duration,playing,buffering || seeking,width,height,error);listener.changed(state);
    }
    private void fail(Exception error) {
        try {releasePlayer();}catch(RuntimeException release) {error.addSuppressed(release);}
        prepared=buffering=seeking=wantedPlaying=false;phase=Phase.FAILED;publish(error);
    }
    private void releasePlayer() {
        worker.removeCallbacks(tick);MediaPlayer previous=player;player=null;
        if(previous!=null)previous.release();
    }
    @Override public void close() {
        if(!closing.compareAndSet(false,true))return;
        worker.post(()-> {try {
            generation=commands.incrementAndGet();Exception failure=null;
            try {releasePlayer();}catch(RuntimeException error) {failure=error;}
            try {targets.close();}catch(RuntimeException error) {if(failure==null)failure=error;else failure.addSuppressed(error);}
            target=null;prepared=buffering=seeking=wantedPlaying=false;width=height=0;
            phase=Phase.CLOSED;publish(failure);
        }finally {closed.countDown();thread.quit();}});
    }
    public boolean awaitClosed(long timeoutMs) throws InterruptedException {return closed.await(timeoutMs,TimeUnit.MILLISECONDS);}
}
