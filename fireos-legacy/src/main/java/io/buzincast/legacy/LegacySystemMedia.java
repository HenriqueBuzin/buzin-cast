package io.buzincast.legacy;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.os.Build;
import android.view.KeyEvent;
import io.buzincast.fireos3probe.LegacyDacp;
import io.buzincast.fireos3probe.LegacyMedia;
import io.buzincast.fireos3probe.LegacyTrack;

/** Platform client available before MediaSession/Media3, owned by the receiver service. */
@SuppressWarnings("deprecation")
final class LegacySystemMedia implements java.io.Closeable {
    interface Sink {void command(LegacyDacp.Command command);void gain(float gain);void failure(RuntimeException error);void changed();}
    private final Context context;
    private final AudioManager audio;
    private final ComponentName component;
    private final Sink sink;
    private PendingIntent buttons;
    private RemoteControlClient client;
    private AudioManager.OnAudioFocusChangeListener focus;
    private LegacyMedia.State media=LegacyMedia.inactive();
    private LegacyDacp.State controls=LegacyDacp.inactive();
    private LegacyTrack lastTrack;
    private Bitmap lastArtwork;
    private long lastDuration=-1;
    private int epoch,focusEpoch,lastState=-1;
    private boolean registered,buttonRegistered,closed,suppressed,focusRequested,mirroring,permanentLoss;
    LegacySystemMedia(Context context,Sink sink) {
        this.context=context;this.sink=sink;audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        component=new ComponentName(context,LegacyMediaButtonReceiver.class);
    }
    void update(LegacyMedia.State media,LegacyDacp.State controls,boolean eligible,boolean mirroring) {
        if(closed)return;this.media=media;this.controls=controls;this.mirroring=mirroring;
        if(!eligible || !mirroring && !media.active && !controls.ready) {deactivate();return;}
        try {
            if(mirroring)unregisterClient();else if(!registered)activate();
            ensureFocus();
            publish();
        } catch(RuntimeException unsupported) {deactivate();sink.failure(unsupported);}
    }
    private void activate() {
        final int owner=++epoch;
        int flags=PendingIntent.FLAG_CANCEL_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0);
        Intent event=new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(component).putExtra("media_epoch",owner);
        // API17's ComponentName registration reconstructs request code zero.
        buttons=PendingIntent.getBroadcast(context,0,event,flags);
        if(Build.VERSION.SDK_INT>=18)Api18.register(audio,buttons);else audio.registerMediaButtonEventReceiver(component);
        buttonRegistered=true;
        client=new RemoteControlClient(buttons);audio.registerRemoteControlClient(client);registered=true;
    }
    private void ensureFocus() {
        if(focus!=null)return;
        final int owner=++focusEpoch;
        focus=change-> {if(!closed && focus!=null && owner==focusEpoch)focusChanged(change);};
        requestFocus();
    }
    private boolean requestFocus() {
        if(focus==null)return false;
        focusRequested=audio.requestAudioFocus(focus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if(focusRequested)permanentLoss=false;
        suppressed=!focusRequested;sink.gain(focusRequested?1:0);return focusRequested;
    }
    private void focusChanged(int change) {
        if(change==AudioManager.AUDIOFOCUS_GAIN) {
            // Old audio services may return GAIN even after a permanent loss.
            // Only an explicit new request can release that local mute.
            focusRequested=!permanentLoss;suppressed=permanentLoss;sink.gain(permanentLoss?0:1);
        }
        else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {sink.gain(suppressed?0:.2f);}
        else if(change==AudioManager.AUDIOFOCUS_LOSS || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            permanentLoss|=change==AudioManager.AUDIOFOCUS_LOSS;
            focusRequested=false;suppressed=true;sink.gain(0);
            if(!mirroring && controls.ready)sink.command(LegacyDacp.Command.PAUSE);
        }
        publish();
        sink.changed();
    }
    boolean prepare(LegacyDacp.Command command) {
        if(!registered)return true;
        if(command==LegacyDacp.Command.PLAY || command==LegacyDacp.Command.RESUME) {
            try {return focusRequested && !suppressed || requestFocus();}
            catch(RuntimeException unavailable) {sink.failure(unavailable);return false;}
        }
        return true;
    }
    boolean suppressed() {return suppressed;}
    void key(KeyEvent event,int owner) {
        if(!registered || owner!=epoch || !controls.ready)return;
        LegacyDacp.Command command=LegacyMediaKeys.command(event.getKeyCode(),event.getAction()==KeyEvent.ACTION_DOWN,event.getRepeatCount(),!suppressed && controls.playing);
        if(command!=null)sink.command(command);
    }
    private void publish() {
        if(!registered)return;
        int actions=controls.ready?RemoteControlClient.FLAG_KEY_MEDIA_PLAY|RemoteControlClient.FLAG_KEY_MEDIA_PAUSE|
            RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE|RemoteControlClient.FLAG_KEY_MEDIA_NEXT|RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS|
            RemoteControlClient.FLAG_KEY_MEDIA_FAST_FORWARD|RemoteControlClient.FLAG_KEY_MEDIA_REWIND:0;
        client.setTransportControlFlags(actions);
        int state=!suppressed && (media.active?media.playing:controls.playing)?RemoteControlClient.PLAYSTATE_PLAYING:RemoteControlClient.PLAYSTATE_PAUSED;
        if(Build.VERSION.SDK_INT>=18)Api18.state(client,state,media.positionMs,state==RemoteControlClient.PLAYSTATE_PLAYING?1:0);
        else if(state!=lastState)client.setPlaybackState(state);
        lastState=state;
        if(lastTrack!=media.track || lastArtwork!=media.artwork || lastDuration!=media.durationMs) {
            RemoteControlClient.MetadataEditor editor=client.editMetadata(true);
            editor.putString(MediaMetadataRetriever.METADATA_KEY_TITLE,media.track.title.isEmpty()?context.getString(R.string.app_name):media.track.title);
            editor.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST,media.track.artist);
            editor.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM,media.track.album);
            editor.putString(MediaMetadataRetriever.METADATA_KEY_GENRE,media.track.genre);
            editor.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION,media.durationMs);
            if(media.artwork!=null) {
                // The platform may recycle its artwork. Never transfer the UI's bitmap.
                Bitmap owned=null;try {owned=media.artwork.copy(Bitmap.Config.ARGB_8888,false);}catch(OutOfMemoryError insufficient) { /* Metadata still works without a copied cover. */ }
                if(owned!=null)editor.putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK,owned);
            }
            editor.apply();lastTrack=media.track;lastArtwork=media.artwork;lastDuration=media.durationMs;
        }
    }
    private void cleanup(Runnable action) {try {action.run();}catch(RuntimeException error) {sink.failure(error);}}
    private void unregisterClient() {
        epoch++;registered=false;
        if(client!=null) {RemoteControlClient previous=client;client=null;cleanup(()->audio.unregisterRemoteControlClient(previous));}
        if(buttonRegistered) {buttonRegistered=false;cleanup(()-> {
            if(Build.VERSION.SDK_INT>=18)Api18.unregister(audio,buttons);else audio.unregisterMediaButtonEventReceiver(component);
        });}
        if(buttons!=null) {buttons.cancel();buttons=null;}
        lastTrack=null;lastArtwork=null;lastDuration=-1;lastState=-1;
    }
    private void deactivate() {
        unregisterClient();focusEpoch++;
        if(focus!=null) {AudioManager.OnAudioFocusChangeListener previous=focus;focus=null;cleanup(()->audio.abandonAudioFocus(previous));}
        focusRequested=suppressed=permanentLoss=false;sink.gain(1);
    }
    @Override public void close() {if(closed)return;closed=true;deactivate();media=LegacyMedia.inactive();controls=LegacyDacp.inactive();}
    @android.annotation.TargetApi(18) private static final class Api18 {
        static void state(RemoteControlClient client,int state,long position,float speed) {client.setPlaybackState(state,position,speed);}
        static void register(AudioManager audio,PendingIntent buttons) {audio.registerMediaButtonEventReceiver(buttons);}
        static void unregister(AudioManager audio,PendingIntent buttons) {audio.unregisterMediaButtonEventReceiver(buttons);}
    }
}
