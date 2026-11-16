package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Base64;
import io.buzincast.fireos3probe.LegacyMedia;
import io.buzincast.fireos3probe.LegacyReceiverSession;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

/** Native encoded-packet callbacks and real RTSP teardown, without a display. */
final class LegacyMirrorScenario {
    private static Object field(Object object,String name) throws Exception {
        java.lang.reflect.Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private interface Check {boolean ready() throws Exception;}
    private static void await(Check check) throws Exception {
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until) {if(check.ready())return;SystemClock.sleep(20);}
        throw new AssertionError("Mirror transition did not settle at "+new Throwable().getStackTrace()[1]);
    }
    private static boolean registered(Instrumentation runner,Object service) {
        AtomicReference<Boolean> value=new AtomicReference<>();
        runner.runOnMainSync(()-> {try {value.set((Boolean)field(field(service,"systemMedia"),"registered"));}
            catch(Exception error) {throw new AssertionError(error);}});return value.get();
    }
    static void check(Instrumentation runner,Activity activity) throws Exception {
        Object service=field(activity,"service");LegacyReceiverSession session=(LegacyReceiverSession)field(service,"session");
        String address=session.localAddress();int port=session.listeningPort();
        Method video=session.getClass().getDeclaredMethod("replay",byte[].class,long.class);video.setAccessible(true);
        Method media=session.getClass().getDeclaredMethod("replayMedia",byte[].class,int.class,long.class,long.class,long.class);media.setAccessible(true);
        byte[] track=new byte[]{'m','i','n','m',0,0,0,4,'T','e','s','t'};
        Bitmap bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff7cf4cd);
        ByteArrayOutputStream art=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,art);bitmap.recycle();
        for(int cycle=0;cycle<3;cycle++) {
            // A headless stream must enter mirror mode before any decoder output.
            // No SPS is supplied: this scenario does not claim H.264 decoding.
            video.invoke(session,new byte[]{0,0,0,1,0x65,(byte)0x88},0L);
            await(()->session.mirroring() && !registered(runner,service));
            media.invoke(session,track,1,0L,0L,0L);
            media.invoke(session,art.toByteArray(),2,0L,0L,0L);
            media.invoke(session,new byte[0],3,0L,44100L,4410000L);
            LegacyMedia.State hidden=session.media();
            if(hidden.active || hidden.artwork!=null || hidden.durationMs!=0 || hidden.positionMs!=0 || hidden.playing || hidden.failure!=null)
                throw new AssertionError("Music metadata survived or reappeared inside a mirror stream");
            teardownMirror(port);
            await(()->!session.mirroring() && registered(runner,service));
            if(session.media().active)throw new AssertionError("Old music metadata returned after mirror teardown");
            media.invoke(session,track,1,0L,0L,0L);media.invoke(session,art.toByteArray(),2,0L,0L,0L);
            media.invoke(session,new byte[0],3,0L,44100L,4410000L);
            await(()->session.media().active && session.media().artwork!=null && session.media().durationMs==100000);
            if(field(service,"session")!=session || session.listeningPort()!=port || !address.equals(session.localAddress()))
                throw new AssertionError("Mode switching restarted the native receiver");
        }
    }
    private static void teardownMirror(int port) throws Exception {
        // Binary plist {streams:[{type:110}]}, the native mirror TEARDOWN path.
        byte[] body=Base64.decode("YnBsaXN0MDDRAQJXc3RyZWFtc6ED0QQFVHR5cGUQbggLExUYHQAAAAAAAAEBAAAAAAAAAAYAAAAAAAAAAAAAAAAAAAAf",Base64.DEFAULT);
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(("TEARDOWN * RTSP/1.0\r\nCSeq: 2\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes("US-ASCII"));
            socket.getOutputStream().write(body);
            ByteArrayOutputStream response=new ByteArrayOutputStream();
            while(response.size()<8192 && !response.toString("US-ASCII").endsWith("\r\n\r\n")) {
                int value=socket.getInputStream().read();if(value<0)break;response.write(value);
            }
            if(!response.toString("US-ASCII").startsWith("RTSP/1.0 200 OK"))throw new AssertionError("Native mirror TEARDOWN failed");
        }
    }
}
