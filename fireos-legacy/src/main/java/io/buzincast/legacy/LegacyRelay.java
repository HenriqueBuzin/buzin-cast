package io.buzincast.legacy;

import android.content.Context;
import android.view.Surface;
import io.buzincast.fireos3probe.LegacyReceiverSession;
import io.buzincast.fireos3probe.LegacyRelayPlayback;
import io.buzincast.media.MirrorPacket;
import io.github.jqssun.airplay.media.MirrorRelay;
import kotlin.Unit;
import java.util.function.BooleanSupplier;

/** Shared transport, bounded output and native capture tap; UI changes are posted by the owner. */
final class LegacyRelay implements LegacyReceiverSession.Capture {
    private final MirrorRelay transport;
    private final Runnable changed;
    private final BooleanSupplier available;
    private volatile LegacyRelayPlayback playback;
    private volatile boolean closed;
    private volatile boolean outputFailure;
    private final Context context;
    private final java.util.concurrent.atomic.AtomicBoolean requested=new java.util.concurrent.atomic.AtomicBoolean();
    private static final MirrorRelay.State IDLE=new MirrorRelay.State();
    LegacyRelay(Context context,Runnable changed,BooleanSupplier available) {
        this.changed=changed;this.available=available;
        this.context=context.getApplicationContext();
        transport=new MirrorRelay(context,()-> {transportChanged();return Unit.INSTANCE;},packet-> {incoming(packet);return Unit.INSTANCE;},
            ()->{closePlayback();changed.run();return Unit.INSTANCE;},()->!closed && available.getAsBoolean());
    }
    MirrorRelay.State state() {return requested.get()?transport.getState().getValue():IDLE;}
    String message() {
        MirrorRelay.State value=state();String message=value.getMessage();
        if(!outputFailure && requested.get() && transport.sessionActive() && !value.getListening() && !value.getReceiving() && !value.getSending())return context.getString(R.string.relay_connecting);
        if(!outputFailure && (value.getListening() || value.getReceiving() || value.getSending() || message.isEmpty() ||
            message.equals(context.getString(R.string.relay_connecting)) || message.equals(context.getString(R.string.relay_ended)) ||
            message.equals(context.getString(R.string.relay_new_code)) || message.equals(context.getString(R.string.relay_slow_network))))return message;
        return context.getString(R.string.relay_send_failed,context.getString(R.string.error_label));
    }
    LegacyRelayPlayback playback() {return playback;}
    boolean engaged() {LegacyRelayPlayback p=playback;return requested.get() && transport.sessionActive() || p!=null && !p.closed;}
    boolean receiving() {LegacyRelayPlayback p=playback;return state().getReceiving() || p!=null && !p.closed;}
    boolean retiring() {LegacyRelayPlayback p=playback;return p!=null && !p.closed && !state().getReceiving();}
    private synchronized void transportChanged() {
        if(!closed && transport.sessionActive() && state().getReceiving() && playback==null && available.getAsBoolean()) {
            playback=new LegacyRelayPlayback(changed::run);
        }
        changed.run();
    }
    private synchronized void incoming(MirrorPacket packet) {
        LegacyRelayPlayback p=playback;
        if(closed || !state().getReceiving() || p==null || p.closed || p.failure!=null || !available.getAsBoolean())throw new IllegalStateException("Relay session unavailable");
        if(packet instanceof MirrorPacket.Video) {
            MirrorPacket.Video frame=(MirrorPacket.Video)packet;
            p.video(frame.getData(),frame.getPtsNs(),frame.getWidth(),frame.getHeight(),frame.getHevc());
        } else if(packet instanceof MirrorPacket.Audio) {
            MirrorPacket.Audio audio=(MirrorPacket.Audio)packet;p.audio(audio.getData(),audio.getPtsNs(),audio.getSampleRate(),audio.getChannels());
        }
    }
    synchronized void surface(Surface surface,Runnable retired) {
        LegacyRelayPlayback p=playback;
        if(p==null || !state().getReceiving()) {if(surface!=null)surface.release();if(retired!=null)retired.run();return;}
        p.surface(surface,retired);
    }
    private synchronized void closePlayback() {if(playback!=null)playback.close();}
    synchronized boolean reap() {
        if(playback!=null && playback.closed) {playback=null;return true;}return false;
    }
    void listen() {if(closed || receiving())throw new IllegalStateException("Relay output is retiring");stop();reap();outputFailure=false;requested.set(true);transport.listen();}
    void connect(String host,int port,String code) {if(closed || receiving())throw new IllegalStateException("Relay output is busy");outputFailure=false;requested.set(true);transport.connect(host,port,code);}
    void outputFailed() {outputFailure=true;stop();}
    void stop() {if(requested.getAndSet(false))transport.stop();closePlayback();}
    void close() {closed=true;stop();}
    @Override public boolean active() {return !closed && state().getSending();}
    @Override public void video(byte[] bytes,long pts,int width,int height) {if(active())transport.offer(new MirrorPacket.Video(bytes,pts,width,height,false));}
    @Override public void audio(short[] pcm,int offset,int count,long pts) {
        if(!active())return;
        if(count<0 || count>65536 || offset<0 || offset>pcm.length-count)throw new IllegalArgumentException("Invalid relay capture PCM");
        byte[] bytes=new byte[count*2];for(int i=0;i<count;i++) {short sample=pcm[offset+i];bytes[i*2]=(byte)sample;bytes[i*2+1]=(byte)(sample>>8);}
        if(bytes.length>0)transport.offer(new MirrorPacket.Audio(bytes,pts,44100,2));
    }
    @Override public void ended() {if(active())transport.offer(MirrorPacket.End.INSTANCE);}
}
