package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.TextureView;
import io.buzincast.fireos3probe.LegacyDacp;
import io.buzincast.fireos3probe.LegacyReceiverSession;
import io.buzincast.fireos3probe.LegacyUrlPlayer;
import io.buzincast.fireos3probe.LegacyUrlRequest;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

/** Real service arbitration, TextureView, platform media focus and UI controls on API17. */
final class LegacyUrlScenario {
    private static Object field(Object object,String name) throws Exception {
        java.lang.reflect.Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private interface Check {boolean ready() throws Exception;}
    private static void await(Check check) throws Exception {
        long until=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<until) {if(check.ready())return;SystemClock.sleep(20);}
        throw new AssertionError("URL service did not settle at "+new Throwable().getStackTrace()[1]);
    }
    private static void ui(Instrumentation runner,Runnable action) {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        runner.runOnMainSync(()-> {try {action.run();}catch(Throwable error) {failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private static LegacyReceiverService service(Instrumentation runner,Activity activity) throws Exception {
        AtomicReference<LegacyReceiverService> service=new AtomicReference<>();
        await(()-> {ui(runner,()-> {try {service.set((LegacyReceiverService)field(activity,"service"));}catch(Exception error) {throw new AssertionError(error);}});return service.get()!=null;});
        return service.get();
    }
    private static LegacyUrlPlayer.State state(Instrumentation runner,LegacyReceiverService service) {
        AtomicReference<LegacyUrlPlayer.State> result=new AtomicReference<>();ui(runner,()->result.set(service.urlState()));return result.get();
    }
    private static LegacyReceiverSession nativeSession(Instrumentation runner,LegacyReceiverService service) {
        AtomicReference<LegacyReceiverSession> result=new AtomicReference<>();
        ui(runner,()-> {try {result.set((LegacyReceiverSession)field(service,"session"));}catch(Exception error) {throw new AssertionError(error);}});return result.get();
    }
    static void check(Instrumentation runner,Activity activity) throws Exception {
        LegacyReceiverService service=service(runner,activity);
        await(()->nativeSession(runner,service)!=null && nativeSession(runner,service).listeningPort()>0);
        LegacyReceiverSession initial=nativeSession(runner,service);
        byte[] sample;
        try(InputStream input=runner.getContext().getAssets().open("url_sample.mp4");ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] chunk=new byte[4096];int count;while((count=input.read(chunk))!=-1)bytes.write(chunk,0,count);sample=bytes.toByteArray();
        }
        try(Fixture fixture=new Fixture(sample)) {
            String location="http://127.0.0.1:"+fixture.socket.getLocalPort();
            LegacyUrlRequest request=new LegacyUrlRequest(location+"/sample.mp4",0,Collections.singletonMap("X-Legacy-Fixture","fixture"));
            ui(runner,()->service.loadUrl(request));
            await(()-> {LegacyUrlPlayer.State state=state(runner,service);return state!=null && state.phase==LegacyUrlPlayer.Phase.READY && state.width==160 && state.playing;});
            if(!initial.isClosed() || nativeSession(runner,service)!=null)throw new AssertionError("Native output overlapped the URL player");
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE);
            await(()->state(runner,service)!=null && !state(runner,service).playing);
            ui(runner,()-> {
                View controls=activity.findViewById(android.R.id.content).findViewWithTag("url-controls");
                if(controls==null || controls.getVisibility()!=View.VISIBLE)throw new AssertionError("URL controls are missing");
                View toggle=controls.findViewWithTag("url-toggle");
                if(!toggle.isEnabled())throw new AssertionError("URL play control is disabled");
                // Exercise the same click callback used by touch, then pause with a real media key.
                toggle.performClick();
            });
            await(()->state(runner,service).playing && state(runner,service).positionMs>=200);
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE);
            await(()->!state(runner,service).playing);
            long paused=state(runner,service).positionMs;SystemClock.sleep(350);
            if(Math.abs(state(runner,service).positionMs-paused)>60)throw new AssertionError("Service pause kept advancing");
            ui(runner,()-> {try {
                Bitmap frame=((TextureView)field(activity,"texture")).getBitmap();
                if(frame==null)throw new AssertionError("URL output has no TextureView frame");
                int colored=0;
                for(int y=0;y<frame.getHeight();y+=8)for(int x=0;x<frame.getWidth();x+=8) {
                    int pixel=frame.getPixel(x,y);if((pixel>>16 & 255)>80 || (pixel>>8 & 255)>80 || (pixel & 255)>80)colored++;
                }
                frame.recycle();if(colored<50)throw new AssertionError("URL output stayed blank");
            }catch(Exception error) {throw new AssertionError(error);}});
            // URL playback participates in the same platform focus policy as native music.
            ui(runner,()->service.control(LegacyDacp.Command.PLAY,null));await(()->state(runner,service).playing);
            try {
                LegacySystemMediaScenario.requestFocus(runner,activity,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                await(()->!state(runner,service).playing);
                ui(runner,()-> {if(!new LegacyReceiverService.State(service).audioSuppressed)throw new AssertionError("URL ignored transient focus loss");});
            }finally {LegacySystemMediaScenario.releaseFocus(runner);}
            ui(runner,()->service.control(LegacyDacp.Command.PLAY,null));await(()->state(runner,service).playing);
            try {
                LegacySystemMediaScenario.requestFocus(runner,activity,AudioManager.AUDIOFOCUS_GAIN);
                await(()->!state(runner,service).playing);
            }finally {LegacySystemMediaScenario.releaseFocus(runner);}
            ui(runner,()-> {
                service.control(LegacyDacp.Command.RESUME,null);
                if(!new LegacyReceiverService.State(service).audioSuppressed)throw new AssertionError("URL seek release cleared permanent focus loss without Play");
            });
            ui(runner,()->service.control(LegacyDacp.Command.PLAY,null));await(()->state(runner,service).playing);
            ui(runner,()-> {if(new LegacyReceiverService.State(service).audioSuppressed)throw new AssertionError("URL Play did not recover focus");service.control(LegacyDacp.Command.PAUSE,null);});
            await(()->!state(runner,service).playing);
            ui(runner,()->service.control(LegacyDacp.Command.REWIND,null));
            await(()->state(runner,service).positionMs<60);
            AtomicReference<LegacyUrlPlayer> player=new AtomicReference<>();
            ui(runner,()-> {try {player.set((LegacyUrlPlayer)field(service,"urlPlayer"));}catch(Exception error) {throw new AssertionError(error);}activity.onBackPressed();});
            await(()->nativeSession(runner,service)!=null && nativeSession(runner,service).listeningPort()>0);
            if(!player.get().awaitClosed(1000) || state(runner,service)!=null)throw new AssertionError("URL player retained ownership after Back");
            if(nativeSession(runner,service)==initial)throw new AssertionError("Native receiver was not recreated");
            // An invalid HTTP source must return to the receiver without leaking its URL into UI metadata.
            ui(runner,()->service.loadUrl(new LegacyUrlRequest(location+"/missing",0,request.headers)));
            await(()-> {AtomicReference<Boolean> failed=new AtomicReference<>(false);ui(runner,()->failed.set(new LegacyReceiverService.State(service).urlError));return failed.get();});
            await(()->nativeSession(runner,service)!=null && nativeSession(runner,service).listeningPort()>0);
            if(state(runner,service)!=null)throw new AssertionError("Failed URL retained its session");
            ui(runner,()->service.loadUrl(request));
            await(()->state(runner,service)!=null && state(runner,service).phase==LegacyUrlPlayer.Phase.READY);
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_STOP);
            await(()->nativeSession(runner,service)!=null && nativeSession(runner,service).listeningPort()>0);
            ui(runner,()-> {if(new LegacyReceiverService.State(service).urlError)throw new AssertionError("Previous URL failure survived a successful replacement");});
        }
    }
    static final class Fixture implements java.io.Closeable {
        final ServerSocket socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        final Thread worker;volatile boolean closed;
        Fixture(byte[] sample) throws Exception {
            worker=new Thread(()-> {
                while(!closed)try(Socket client=socket.accept()) {
                    client.setSoTimeout(3000);ByteArrayOutputStream raw=new ByteArrayOutputStream();String header="";
                    while(raw.size()<8192 && !header.endsWith("\r\n\r\n")) {int value=client.getInputStream().read();if(value<0)break;raw.write(value);header=raw.toString("US-ASCII");}
                    if(header.isEmpty())continue;
                    boolean found=header.startsWith("GET /sample.mp4 ") && header.toLowerCase(java.util.Locale.US).contains("\r\nx-legacy-fixture: fixture\r\n");
                    String response=found?"HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: "+sample.length+"\r\nConnection: close\r\n\r\n":"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                    client.getOutputStream().write(response.getBytes("US-ASCII"));if(found)client.getOutputStream().write(sample);
                }catch(Exception error) {if(closed)break;}
            },"legacy-url-service-fixture");worker.start();
        }
        @Override public void close() throws java.io.IOException {
            closed=true;socket.close();try {worker.join(4000);}catch(InterruptedException error) {Thread.currentThread().interrupt();throw new java.io.IOException(error);}
            if(worker.isAlive())throw new java.io.IOException("HTTP fixture did not close");
        }
    }
}
