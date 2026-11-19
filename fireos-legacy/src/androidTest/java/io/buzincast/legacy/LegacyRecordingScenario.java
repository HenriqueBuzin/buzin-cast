package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.view.View;
import io.buzincast.fireos3probe.LegacyReceiverSession;
import io.buzincast.fireos3probe.LegacyUrlPlayer;
import java.io.*;
import java.nio.ByteBuffer;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic native H.264/ALAC -> shared MKV writer -> local Media3 playback. */
final class LegacyRecordingScenario {
    interface Check {boolean ready() throws Exception;}
    static void await(Check check) throws Exception {
        long end=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<end) {if(check.ready())return;SystemClock.sleep(20);}
        throw new AssertionError("Recording did not settle at "+new Throwable().getStackTrace()[1]);
    }
    static Object field(Object owner,String name) throws Exception {java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    static void ui(Instrumentation runner,Runnable action) {
        AtomicReference<Throwable> error=new AtomicReference<>();runner.runOnMainSync(()-> {try {action.run();}catch(Throwable failure) {error.set(failure);}});
        if(error.get()!=null)throw new AssertionError(error.get());
    }
    static void click(Activity activity,String tag) {
        View control=activity.findViewById(android.R.id.content).findViewWithTag(tag);
        if(control==null || !control.isEnabled())throw new AssertionError("Missing recording control "+tag);control.performClick();
    }
    static void check(Instrumentation runner,Activity activity) throws Exception {
        LegacyReceiverService service=(LegacyReceiverService)field(activity,"service");
        LegacyMirrorCapture capture=service.capture();
        ui(runner,()->click(activity,"nav-tools"));
        if(!BuildConfig.RECORDING_ENABLED) {
            if(activity.findViewById(android.R.id.content).findViewWithTag("capture-toggle")!=null)throw new AssertionError("Restricted edition exposed recording");
            capture.start();if(capture.active() || capture.busy() || capture.files().length!=0)throw new AssertionError("Restricted recording was not disabled");return;
        }
        ui(runner,()->click(activity,"capture-toggle"));
        if(!capture.active())throw new AssertionError("Recording did not arm");
        LegacyReceiverSession session=(LegacyReceiverSession)field(service,"session");
        Method video=session.getClass().getDeclaredMethod("replay",byte[].class,long.class);video.setAccessible(true);
        Method audio=session.getClass().getDeclaredMethod("replayAudio",byte[].class,int.class,int.class,long.class,float.class,boolean.class,boolean.class);audio.setAccessible(true);
        byte[] frame=frame(runner),packet;
        try(InputStream input=runner.getContext().getAssets().open("audio_samples.json");ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            copy(input,bytes);org.json.JSONObject fixture=new org.json.JSONArray(bytes.toString("UTF-8")).getJSONObject(0);
            packet=android.util.Base64.decode(fixture.getJSONArray("packets").getString(0),android.util.Base64.DEFAULT);
        }
        long base=System.currentTimeMillis()*1000000L,audioPts=base,step=352L*1000000000L/44100;
        for(int index=0;index<60;index++) {
            video.invoke(session,frame,base+index*33333333L);
            for(int n=0;n<4;n++) {audio.invoke(session,packet,2,index==0 && n==0?352:0,audioPts,-12f,index==0 && n==0,false);audioPts+=step;}
            SystemClock.sleep(33);
        }
        await(()->capture.state().recording);
        ui(runner,()-> {activity.onBackPressed();click(activity,"nav-tools");click(activity,"capture-toggle");});
        await(()->!capture.busy());
        File[] files=capture.files();if(files.length!=1 || files[0].length()<1024)throw new AssertionError("Native recording was not saved");
        String name=files[0].getName();
        // Retain only this synthetic artifact, including on a later assertion failure.
        try(InputStream input=new FileInputStream(files[0]);ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            copy(input,bytes);try(OutputStream output=new FileOutputStream(new File(activity.getFilesDir(),"legacy-recording.b64"))) {
                output.write(android.util.Base64.encode(bytes.toByteArray(),android.util.Base64.NO_WRAP));
            }
        }
        // Verify the actual container; a filename or a nonempty file is insufficient.
        MediaExtractor extractor=new MediaExtractor();
        try {
            extractor.setDataSource(files[0].getPath());boolean avc=false;StringBuilder tracks=new StringBuilder();
            for(int i=0;i<extractor.getTrackCount();i++) {
                String mime=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                tracks.append(mime).append(';');
                avc|="video/avc".equals(mime);
            }
            // Android 4.2's platform MKV extractor omits PCM. The app uses
            // Media3's Matroska extractor; verify that selected audio below.
            if(!avc)throw new AssertionError("API17 extractor tracks: "+tracks);
        }finally {extractor.release();}
        android.net.Uri uri=androidx.core.content.FileProvider.getUriForFile(activity,activity.getPackageName()+".recordings",capture.file(name));
        try(InputStream input=activity.getContentResolver().openInputStream(uri)) {
            if(input.read()!=0x1a || input.read()!=0x45 || input.read()!=0xdf || input.read()!=0xa3)throw new AssertionError("Export URI did not open the saved Matroska file");
        }
        try {capture.file("../"+name);throw new AssertionError("Recording traversal was accepted");}catch(IOException expected) { }
        ui(runner,()->click(activity,"capture-toggle"));
        try {capture.delete(name);throw new AssertionError("Deleted a recording while capturing");}catch(IOException expected) { }
        ui(runner,()->click(activity,"capture-toggle"));await(()->!capture.busy());
        if(capture.files().length!=1)throw new AssertionError("Empty capture left a file");
        capture.start();capture.video(new byte[LegacyMirrorCapture.MAX_BYTES+1],base,160,90);
        await(()->!capture.busy());
        if(capture.active() || capture.files().length!=1 || !capture.state().message.equals(activity.getString(R.string.capture_slow_storage)))
            throw new AssertionError("Recording budget failure did not close cleanly");
        ui(runner,()->click(activity,"capture-refresh"));
        ui(runner,()->click(activity,"capture-play:"+name));
        AtomicReference<LegacyUrlPlayer.State> playing=new AtomicReference<>();
        await(()-> {ui(runner,()->playing.set(service.urlState()));LegacyUrlPlayer.State value=playing.get();
            return value!=null && value.playing && value.width==160 && value.durationMs>=1900;});
        await(()-> {
            AtomicReference<io.buzincast.fireos3probe.LegacyUrlPlayer> current=new AtomicReference<>();
            ui(runner,()-> {try {current.set((io.buzincast.fireos3probe.LegacyUrlPlayer)field(service,"urlPlayer"));}catch(Exception e) {throw new AssertionError(e);}});
            if(current.get()==null)return false;
            AtomicReference<Object> format=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
            java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
            ((android.os.Handler)field(current.get(),"worker")).post(()-> {
                try {
                    Object player=field(current.get(),"player");
                    if(player!=null) {Method method=player.getClass().getMethod("getAudioFormat");method.setAccessible(true);format.set(method.invoke(player));}
                }catch(Throwable failure) {error.set(failure);}finally {done.countDown();}
            });
            if(!done.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Local audio worker did not reply");
            if(error.get()!=null)throw new AssertionError(error.get());
            return format.get()!=null && "audio/raw".equals(field(format.get(),"sampleMimeType")) &&
                ((Number)field(format.get(),"sampleRate")).intValue()==44100 && ((Number)field(format.get(),"channelCount")).intValue()==2;
        });
        ui(runner,service::stopUrl);await(()-> {ui(runner,()->playing.set(service.urlState()));return playing.get()==null;});
        ui(runner,()-> {activity.onBackPressed();click(activity,"nav-tools");click(activity,"capture-delete:"+name);click(activity,"capture-delete-confirm");});
        if(capture.files().length!=0)throw new AssertionError("Confirmed delete did not remove the recording");
        capture.start();
        ui(runner,()->activity.startService(new android.content.Intent(activity,LegacyReceiverService.class).setAction(LegacyReceiverService.STOP)));
        await(()->!capture.busy() && session.isClosed());
        if(capture.active() || capture.files().length!=0)throw new AssertionError("Receiver shutdown retained an armed capture");
    }
    static void copy(InputStream input,OutputStream output) throws IOException {byte[] bytes=new byte[4096];int count;while((count=input.read(bytes))!=-1)output.write(bytes,0,count);}
    static byte[] frame(Instrumentation runner) throws Exception {
        MediaExtractor extractor=new MediaExtractor();
        try {
            try(android.content.res.AssetFileDescriptor asset=runner.getContext().getAssets().openFd("url_formats/sample.mp4")) {
                extractor.setDataSource(asset.getFileDescriptor(),asset.getStartOffset(),asset.getLength());
            }
            int track=-1;for(int i=0;i<extractor.getTrackCount();i++)if("video/avc".equals(extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)))track=i;
            if(track<0)throw new AssertionError("Fixture has no AVC track");extractor.selectTrack(track);MediaFormat format=extractor.getTrackFormat(track);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            for(String key:new String[]{"csd-0","csd-1"}) {ByteBuffer data=format.getByteBuffer(key).duplicate();byte[] bytes=new byte[data.remaining()];data.get(bytes);out.write(bytes);}
            ByteBuffer data=ByteBuffer.allocate(256*1024);int count=extractor.readSampleData(data,0);if(count<=0)throw new AssertionError("Fixture has no IDR");
            byte[] bytes=new byte[count];data.position(0);data.get(bytes);
            if(bytes[0]==0 && bytes[1]==0 && (bytes[2]==1 || bytes[2]==0 && bytes[3]==1))out.write(bytes);
            else for(int at=0;at<bytes.length;) {
                int size=ByteBuffer.wrap(bytes,at,4).getInt();at+=4;if(size<=0 || size>bytes.length-at)throw new AssertionError("Invalid fixture NAL");
                out.write(new byte[]{0,0,0,1});out.write(bytes,at,size);at+=size;
            }
            return out.toByteArray();
        }finally {extractor.release();}
    }
}
