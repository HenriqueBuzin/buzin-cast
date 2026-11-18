package io.buzincast.fireos3probe;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import java.util.concurrent.CountDownLatch;
import java.net.ProtocolException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** API17 URL backend. All player access and owned Surface changes share one worker. */
@androidx.annotation.OptIn(markerClass=UnstableApi.class)
public final class LegacyUrlPlayer implements java.io.Closeable {
    public enum Phase {IDLE,LOADING,READY,ENDED,FAILED,CLOSED}
    public static final class State {
        public final Phase phase;
        public final long generation,positionMs,durationMs;
        public final boolean playing,buffering;
        public final float speed;
        public final int width,height;
        public final Exception failure;
        State(Phase phase,long generation,long positionMs,long durationMs,boolean playing,boolean buffering,float speed,int width,int height,Exception failure) {
            this.phase=phase;this.generation=generation;this.positionMs=positionMs;this.durationMs=durationMs;
            this.playing=playing;this.buffering=buffering;this.speed=speed;this.width=width;this.height=height;this.failure=failure;
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
    private ExoPlayer player;
    private long generation;
    private boolean buffering;
    private float gain=1,focusGain,speed=1;
    private int width,height;
    private Phase phase=Phase.IDLE;
    private volatile State state=new State(Phase.IDLE,0,0,0,false,false,1,0,0,null);
    private final Runnable tick=new Runnable() {
        @Override public void run() {if(closing.get())return;if(player!=null) {publish(null);worker.postDelayed(this,250);}}
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
            releasePlayer();generation=owner;buffering=true;speed=1;width=height=0;
            phase=Phase.LOADING;publish(null);
            // HTTP-only sources also apply to manifest/segment URIs. Never route
            // a sender-provided child URI through file/content/asset data sources.
            DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent("BuzinCast")
                .setConnectTimeoutMs(10000).setReadTimeoutMs(15000).setAllowCrossProtocolRedirects(false)
                .setDefaultRequestProperties(request.headers);
            ResolvingDataSource.Factory network=new ResolvingDataSource.Factory(http,spec-> {
                String scheme=spec.uri.getScheme();
                if((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) || spec.uri.getHost()==null ||
                    spec.uri.getUserInfo()!=null || spec.uri.toString().length()>8192)
                    throw new ProtocolException("Expected HTTP(S) media resource without embedded credentials");
                return spec;
            });
            DefaultMediaSourceFactory sources=new DefaultMediaSourceFactory(network)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(2));
            ExoPlayer next=new ExoPlayer.Builder(context,new DefaultRenderersFactory(context).setEnableDecoderFallback(true))
                .setLooper(thread.getLooper()).setMediaSourceFactory(sources).build();
            player=next;next.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),false);
            next.setVolume(gain*focusGain);
            next.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int playbackState) {
                    if(!current(next))return;
                    buffering=playbackState==Player.STATE_BUFFERING;
                    if(playbackState==Player.STATE_READY) {phase=Phase.READY;}
                    else if(playbackState==Player.STATE_ENDED) {phase=Phase.ENDED;worker.removeCallbacks(tick);}
                    publish(null);
                }
                @Override public void onIsPlayingChanged(boolean playing) {if(current(next))publish(null);}
                @Override public void onVideoSizeChanged(VideoSize size) {if(current(next)) {width=size.width;height=size.height;publish(null);}}
                @Override public void onPlayerError(PlaybackException error) {if(current(next))fail(error);}
            });
            if(target!=null)next.setVideoSurface(target.value);
            MediaItem.Builder item=new MediaItem.Builder().setUri(request.location);
            if(request.mime!=null)item.setMimeType(request.mime);
            next.setMediaItem(item.build(),request.startMs);next.setPlayWhenReady(play);next.prepare();
            worker.removeCallbacks(tick);worker.post(tick);
        });
        return owner;
    }
    private boolean current(ExoPlayer candidate) {return !closing.get() && player==candidate && generation==commands.get();}
    public void playing(boolean playing) {post(()-> {
        if(player==null)return;
        if(playing && phase==Phase.ENDED) {player.seekTo(0);phase=Phase.READY;}
        player.setPlayWhenReady(playing);worker.removeCallbacks(tick);worker.post(tick);publish(null);
    });}
    public void seek(long positionMs) {
        if(positionMs<0 || positionMs>LegacyUrlRequest.MAX_POSITION_MS)throw new IllegalArgumentException("Unsupported media position");
        post(()-> {if(player!=null) {
            long duration=player.getDuration();player.seekTo(duration>0?Math.min(positionMs,duration):positionMs);
            if(phase==Phase.ENDED)phase=Phase.READY;
            worker.removeCallbacks(tick);worker.post(tick);publish(null);
        }});
    }
    public void speed(float speed) {
        if(Float.isNaN(speed) || Float.isInfinite(speed) || speed<.25f || speed>4)throw new IllegalArgumentException("Invalid playback speed");
        post(()-> {this.speed=speed;if(player!=null)player.setPlaybackSpeed(speed);publish(null);});
    }
    public void gain(float gain) {validateGain(gain);post(()-> {this.gain=gain;applyGain();});}
    public void focusGain(float gain) {validateGain(gain);post(()-> {focusGain=gain;applyGain();});}
    private static void validateGain(float gain) {if(Float.isNaN(gain) || Float.isInfinite(gain) || gain<0 || gain>1)throw new IllegalArgumentException("Invalid media gain");}
    private void applyGain() {if(player!=null)player.setVolume(gain*focusGain);}
    /** Takes ownership; retire the view's texture only from whenReleased. */
    public void surface(Surface surface,Runnable whenReleased) {
        targets.request(surface,surface==null?null:()-> {try {surface.release();}finally {if(whenReleased!=null)whenReleased.run();}});
        if(closing.get())return;
        post(()-> {
            if(!targets.changed())return;
            if(player!=null) {
                player.clearVideoSurface();
                if(player.getPlayerError()!=null) {fail(player.getPlayerError());return;}
            }
            target=targets.activate();if(player!=null && target!=null)player.setVideoSurface(target.value);
        });
    }
    public void stop() {long owner=commands.incrementAndGet();post(()-> {
        if(owner!=commands.get())return;generation=owner;releasePlayer();buffering=false;speed=1;width=height=0;phase=Phase.IDLE;publish(null);
    });}
    private void publish(Exception error) {
        long position=0,duration=0;boolean playing=false;
        try {if(player!=null) {
            position=Math.max(0,player.getCurrentPosition());duration=Math.max(0,player.getDuration());playing=player.isPlaying();
            speed=player.getPlaybackParameters().speed;
        }}catch(RuntimeException invalid) {fail(invalid);return;}
        state=new State(phase,generation,position,duration,playing,buffering,speed,width,height,error);listener.changed(state);
    }
    private void fail(Exception error) {
        try {releasePlayer();}catch(RuntimeException release) {error.addSuppressed(release);}
        buffering=false;phase=Phase.FAILED;publish(error);
    }
    private void releasePlayer() {
        worker.removeCallbacks(tick);ExoPlayer previous=player;player=null;
        if(previous!=null)previous.release();
    }
    @Override public void close() {
        if(!closing.compareAndSet(false,true))return;
        worker.post(()-> {try {
            generation=commands.incrementAndGet();Exception failure=null;
            try {releasePlayer();}catch(RuntimeException error) {failure=error;}
            try {targets.close();}catch(RuntimeException error) {if(failure==null)failure=error;else failure.addSuppressed(error);}
            target=null;buffering=false;speed=1;width=height=0;
            phase=Phase.CLOSED;publish(failure);
        }finally {closed.countDown();thread.quit();}});
    }
    public boolean awaitClosed(long timeoutMs) throws InterruptedException {return closed.await(timeoutMs,TimeUnit.MILLISECONDS);}
}
