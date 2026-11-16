package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic packets enter the real native callbacks; no unauthenticated RTP shortcut. */
final class LegacyMetadataScenario {
    private static final String TITLE="Canção de teste · שיר",ARTIST="BuzinCast fixture",ALBUM="Native metadata";
    static void check(Instrumentation runner,Activity activity) throws Exception {
        Bitmap art=Bitmap.createBitmap(2048,1024,Bitmap.Config.ARGB_8888);
        Canvas painter=new Canvas(art);Paint ink=new Paint();ink.setColor(Color.rgb(124,244,205));painter.drawRect(0,0,1024,1024,ink);
        ink.setColor(Color.rgb(17,60,90));painter.drawRect(1024,0,2048,1024,ink);
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();art.compress(Bitmap.CompressFormat.PNG,100,encoded);art.recycle();
        replay(activity,encoded.toByteArray(),2,0,0,0);
        byte[] metadata=item("mlit",join(item("minm",TITLE.getBytes("UTF-8")),item("asar",ARTIST.getBytes("UTF-8")),
            item("asal",ALBUM.getBytes("UTF-8")),item("asgn","Demo".getBytes("UTF-8")),item("astm",new byte[]{0,2,-26,48})));
        replay(activity,metadata,1,0,0,0);
        long start=0xffff0000L;
        replay(activity,new byte[0],3,start,(start+30*44100)&0xffffffffL,(start+190*44100)&0xffffffffL);
        await(runner,()->text(activity,"track-title").equals(TITLE) && text(activity,"track-artist").equals(ARTIST) &&
            text(activity,"track-album").equals(ALBUM) && ((ImageView)find(activity,"track-artwork")).getDrawable()!=null);
        ui(runner,()-> {
            Bitmap image=((BitmapDrawable)((ImageView)find(activity,"track-artwork")).getDrawable()).getBitmap();
            if(image.isRecycled() || image.getWidth()>512 || image.getHeight()>512 || image.getWidth()!=image.getHeight()*2)
                throw new AssertionError("Cover was stretched or decoded beyond its budget");
            if(image.getPixel(10,10)!=Color.rgb(124,244,205) || image.getPixel(image.getWidth()-10,10)!=Color.rgb(17,60,90))
                throw new AssertionError("Decoded cover pixels differ from the native packet");
        });
        long before=number(activity,"positionMs");SystemClock.sleep(650);long after=number(activity,"positionMs");
        if(before<30000 || after-before<500 || after>35000 || number(activity,"durationMs")!=190000)
            throw new AssertionError("Native progress did not advance across unsigned rollover");
        replay(activity,new byte[0],4,0,0,0);await(runner,()->text(activity,"sender-1").equals("▶"));
        long until=SystemClock.elapsedRealtime()+5000;while(bool(activity,"playing") && SystemClock.elapsedRealtime()<until)SystemClock.sleep(10);
        if(bool(activity,"playing"))throw new AssertionError("Native teardown did not pause metadata");
        before=number(activity,"positionMs");SystemClock.sleep(650);if(number(activity,"positionMs")!=before)throw new AssertionError("Paused progress kept advancing");
        ui(runner,()-> {
            find(activity,"sender-1").requestFocus();
            View decor=activity.getWindow().getDecorView();Bitmap picture=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);
            try(FileOutputStream output=activity.openFileOutput("legacy-metadata.b64",0)) {
                ByteArrayOutputStream png=new ByteArrayOutputStream();decor.draw(new Canvas(picture));picture.compress(Bitmap.CompressFormat.PNG,100,png);
                // API17 adbd has no exec-out transport. ASCII avoids PTY newline
                // conversion while moving the exact PNG bytes through run-as.
                output.write(android.util.Base64.encode(png.toByteArray(),android.util.Base64.NO_WRAP));
            } catch(Exception error) {throw new AssertionError(error);}finally {picture.recycle();}
        });
        replay(activity,join(item("minm","Broken".getBytes("UTF-8")),new byte[]{1}),1,0,0,0);
        await(runner,()->failure(activity)!=null && text(activity,"track-title").equals(TITLE));
        Object previous=failure(activity);replay(activity,new byte[]{1,2,3},2,0,0,0);
        await(runner,()->failure(activity)!=null && failure(activity)!=previous && ((ImageView)find(activity,"track-artwork")).getDrawable()!=null);
        rejected(activity,new byte[64*1024+1],1);rejected(activity,new byte[4*1024*1024+1],2);
        replay(activity,new byte[0],3,start,(start+33*44100)&0xffffffffL,(start+190*44100)&0xffffffffL);
        await(runner,()->text(activity,"sender-1").equals("Ⅱ"));
    }
    static void cleared(Instrumentation runner,Activity activity) throws Exception {
        try {await(runner,()->!find(activity,"now-playing").isShown() && ((ImageView)find(activity,"track-artwork")).getDrawable()==null);}
        catch(AssertionError error) {throw new AssertionError(error.getMessage()+"; active="+bool(activity,"active")+"; artwork="+value(activity,"artwork"),error);}
        if(number(activity,"durationMs")!=0 || number(activity,"positionMs")!=0)throw new AssertionError("Old sender progress survived replacement");
    }
    private static Object session(Activity activity) throws Exception {
        java.lang.reflect.Field serviceField=activity.getClass().getDeclaredField("service");serviceField.setAccessible(true);Object service=serviceField.get(activity);
        java.lang.reflect.Field field=service.getClass().getDeclaredField("session");field.setAccessible(true);return field.get(service);
    }
    private static Object state(Activity activity) {
        try {Object session=session(activity);return session.getClass().getMethod("media").invoke(session);}
        catch(Exception error) {throw new AssertionError(error);}
    }
    private static Object value(Activity activity,String field) {try {Object state=state(activity);return state.getClass().getField(field).get(state);}catch(Exception error) {throw new AssertionError(error);}}
    private static long number(Activity activity,String field) {return (Long)value(activity,field);}
    private static boolean bool(Activity activity,String field) {return (Boolean)value(activity,field);}
    private static Object failure(Activity activity) {return value(activity,"failure");}
    private static void replay(Activity activity,byte[] data,int kind,long start,long current,long end) throws Exception {
        Object session=session(activity);Method replay=session.getClass().getDeclaredMethod("replayMedia",byte[].class,int.class,long.class,long.class,long.class);
        replay.setAccessible(true);replay.invoke(session,data,kind,start,current,end);
    }
    private static void rejected(Activity activity,byte[] data,int kind) throws Exception {
        try {replay(activity,data,kind,0,0,0);throw new AssertionError("Oversized native metadata was accepted");}
        catch(InvocationTargetException error) {if(!(error.getCause() instanceof IllegalStateException))throw error;}
    }
    private interface Check {boolean ready();}
    private static void await(Instrumentation runner,Check check) throws Exception {
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until) {AtomicReference<Boolean> done=new AtomicReference<>(false);ui(runner,()->done.set(check.ready()));if(done.get())return;SystemClock.sleep(20);}
        throw new AssertionError("Metadata UI did not settle at "+new Throwable().getStackTrace()[1]);
    }
    private static View find(Activity activity,String tag) {View view=activity.findViewById(android.R.id.content).findViewWithTag(tag);if(view==null)throw new AssertionError("Missing "+tag);return view;}
    private static String text(Activity activity,String tag) {return ((TextView)find(activity,tag)).getText().toString();}
    private static void ui(Instrumentation runner,Runnable action) {
        AtomicReference<Throwable> failure=new AtomicReference<>();runner.runOnMainSync(()-> {try {action.run();}catch(Throwable error) {failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private static byte[] item(String tag,byte[] data) throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeBytes(tag);out.writeInt(data.length);out.write(data);return bytes.toByteArray();}
    private static byte[] join(byte[]... data) throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] bytes:data)out.write(bytes);return out.toByteArray();}
}
