package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.media.AudioManager;
import android.media.RemoteControlClient;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.ImageView;
import android.graphics.drawable.BitmapDrawable;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;

/** Inspects the real platform RCC and coordinates shell-origin media key events. */
final class LegacySystemMediaScenario {
    private static Bitmap originalArtwork;
    private static AudioManager audio;
    private static AudioManager.OnAudioFocusChangeListener competingFocus;
    private static Object field(Object object,String name) throws Exception {
        java.lang.reflect.Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static Object system(Activity activity) throws Exception {return field(field(activity,"service"),"systemMedia");}
    static void metadata(Instrumentation runner,Activity activity) {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        runner.runOnMainSync(()-> {
            try {
                Object bridge=system(activity);if(!(Boolean)field(bridge,"registered"))throw new AssertionError("Platform client was not registered");
                RemoteControlClient client=(RemoteControlClient)field(bridge,"client");
                Bundle data=(Bundle)field(client,"mMetadata");
                if(!"Canção de teste · שיר".equals(data.getString(Integer.toString(MediaMetadataRetriever.METADATA_KEY_TITLE))) ||
                    !"BuzinCast fixture".equals(data.getString(Integer.toString(MediaMetadataRetriever.METADATA_KEY_ARTIST))) ||
                    data.getLong(Integer.toString(MediaMetadataRetriever.METADATA_KEY_DURATION))!=190000)
                    throw new AssertionError("Platform metadata differs from the native packets");
                Bitmap cover=(Bitmap)field(client,"mArtwork");
                ImageView view=activity.findViewById(android.R.id.content).findViewWithTag("track-artwork");
                originalArtwork=((BitmapDrawable)view.getDrawable()).getBitmap();
                if(cover==null || cover==originalArtwork || cover.isRecycled() || originalArtwork.isRecycled())
                    throw new AssertionError("The system must own a separate live artwork bitmap");
            } catch(Throwable error) {failure.set(error);}
        });
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    static void host(Activity activity,String stage) throws Exception {
        File ack=new File(activity.getFilesDir(),"system-media-ack-"+stage);
        try(FileOutputStream out=activity.openFileOutput("system-media-request",0)) {out.write(stage.getBytes("US-ASCII"));}
        long until=SystemClock.elapsedRealtime()+30000;
        while(!ack.exists() && SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);
        if(!ack.exists())throw new AssertionError("Host did not complete the system media stage: "+stage);
    }
    static void requestFocus(Instrumentation runner,Activity activity,int hint) {
        runner.runOnMainSync(()-> {
            audio=(AudioManager)activity.getSystemService(Activity.AUDIO_SERVICE);competingFocus=change->{};
            if(audio.requestAudioFocus(competingFocus,AudioManager.STREAM_MUSIC,hint)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                throw new AssertionError("Competing audio focus request failed");
        });
    }
    static void releaseFocus(Instrumentation runner) {
        runner.runOnMainSync(()-> {if(audio!=null && competingFocus!=null)audio.abandonAudioFocus(competingFocus);competingFocus=null;});
    }
    static float gain(Activity activity) {
        try {Object service=field(activity,"service");Object session=field(service,"session");return (Float)field(field(session,"audioPlayer"),"focusGain");}
        catch(Exception error) {throw new AssertionError(error);}
    }
    static void stopped(Instrumentation runner,Activity activity) throws Exception {
        AtomicReference<Boolean> registered=new AtomicReference<>(true);
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until) {
            runner.runOnMainSync(()-> {try {registered.set((Boolean)field(system(activity),"registered"));}catch(Exception error) {throw new AssertionError(error);}});
            if(!registered.get())break;SystemClock.sleep(20);
        }
        if(registered.get())throw new AssertionError("System client survived receiver stop");
        if(originalArtwork!=null && originalArtwork.isRecycled())throw new AssertionError("Platform cleanup recycled the UI's original cover");
        originalArtwork=null;host(activity,"stopped");
    }
}
