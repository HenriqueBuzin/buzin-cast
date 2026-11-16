package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/** A LAN sender fixture in the test APK; no product-side testing commands. */
final class LegacyControlScenario {
    static Activity run(Instrumentation runner,boolean metadata,boolean systemMedia) throws Exception {
        if(Build.VERSION.SDK_INT!=17 || !(Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu")))
            throw new AssertionError("Controls checks require the isolated API17 emulator");
        Activity activity=runner.startActivitySync(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(runner.getTargetContext(),"io.buzincast.legacy.LegacyActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        Inet4Address address=null;
        for(NetworkInterface nic:Collections.list(NetworkInterface.getNetworkInterfaces()))
            if(nic.isUp() && !nic.isLoopback())for(java.net.InetAddress candidate:Collections.list(nic.getInetAddresses()))
                if(candidate instanceof Inet4Address && candidate.isSiteLocalAddress())address=(Inet4Address)candidate;
        if(address==null)throw new AssertionError("Emulator local interface unavailable");
        await(runner,()->find(activity,"receiver-status") instanceof TextView &&
            ((TextView)find(activity,"receiver-status")).getText().toString().equals(activity.getString(activity.getResources().getIdentifier("buzin_ready","string",activity.getPackageName()))));
        if(findOnUi(runner,activity,"sender-0").isShown())throw new AssertionError("Controls visible without a resolved sender");
        try(Peer first=new Peer(address,"17017");Peer second=new Peer(address,"27017")) {
            first.identify();
            try {await(runner,()->find(activity,"sender-0").isShown());}
            catch(AssertionError error) {throw new AssertionError(error.getMessage()+"; "+controlDiagnostic(runner,activity),error);}
            if(metadata)LegacyMetadataScenario.check(runner,activity);
            if(systemMedia)systemChecks(runner,activity,first);
            int base=first.size();
            touch(runner,activity,"sender-1");first.expect(base,"pause");await(runner,()->text(activity,"sender-1").equals("▶"));
            touch(runner,activity,"sender-1");first.expect(base+1,"play");await(runner,()->text(activity,"sender-1").equals("Ⅱ"));
            touch(runner,activity,"sender-0");first.expect(base+2,"previtem");
            touch(runner,activity,"sender-2");first.expect(base+3,"nextitem");
            touch(runner,activity,"sender-3");first.expect(base+4,"volumedown");
            touch(runner,activity,"sender-4");first.expect(base+5,"volumeup");
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE);first.expect(base+6,"pause");await(runner,()->text(activity,"sender-1").equals("▶"));
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY);first.expect(base+7,"play");await(runner,()->text(activity,"sender-1").equals("Ⅱ"));
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_NEXT);first.expect(base+8,"nextitem");
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PREVIOUS);first.expect(base+9,"previtem");
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);first.expect(base+10,"beginff");first.expect(base+11,"playresume");
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_REWIND);first.expect(base+12,"beginrew");first.expect(base+13,"playresume");
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);first.expect(base+14,"pause");await(runner,()->text(activity,"sender-1").equals("▶"));
            // Failure cannot make the UI claim that playback resumed.
            first.code=500;touch(runner,activity,"sender-1");first.expect(base+15,"play");SystemClock.sleep(200);
            if(!textOnUi(runner,activity,"sender-1").equals("▶"))throw new AssertionError("Failed command changed the playback state");
            second.identify();await(runner,()->find(activity,"sender-0").isShown() && text(activity,"sender-1").equals("Ⅱ"));
            if(metadata)LegacyMetadataScenario.cleared(runner,activity);
            touch(runner,activity,"sender-2");second.expect(0,"nextitem");
            if(first.size()!=base+16)throw new AssertionError("Replacement command reached the old sender");
            touch(runner,activity,"receiver-toggle");
            await(runner,()->!find(activity,"sender-0").isShown());
            if(systemMedia)LegacySystemMediaScenario.stopped(runner,activity);
        }
        return activity;
    }
    private static void systemChecks(Instrumentation runner,Activity activity,Peer peer) throws Exception {
        LegacySystemMediaScenario.metadata(runner,activity);
        int focusIndex=peer.size();
        try {
            LegacySystemMediaScenario.requestFocus(runner,activity,android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            await(runner,()->Math.abs(LegacySystemMediaScenario.gain(activity)-.2f)<.01f);
            LegacySystemMediaScenario.releaseFocus(runner);await(runner,()->LegacySystemMediaScenario.gain(activity)==1);
            if(peer.size()!=focusIndex)throw new AssertionError("Ducking should not pause the sender");
            for(int hint:new int[]{android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,android.media.AudioManager.AUDIOFOCUS_GAIN}) {
                LegacySystemMediaScenario.requestFocus(runner,activity,hint);peer.expect(focusIndex++,"pause");
                await(runner,()->LegacySystemMediaScenario.gain(activity)==0 && text(activity,"sender-1").equals("▶"));
                LegacySystemMediaScenario.releaseFocus(runner);
                if(hint==android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)await(runner,()->LegacySystemMediaScenario.gain(activity)==1);
                touch(runner,activity,"sender-1");peer.expect(focusIndex++,"play");
                await(runner,()->LegacySystemMediaScenario.gain(activity)==1 && text(activity,"sender-1").equals("Ⅱ"));
            }
        } finally {LegacySystemMediaScenario.releaseFocus(runner);}
        ui(runner,()->activity.moveTaskToBack(true));
        await(runner,()->!activity.hasWindowFocus());
        LegacySystemMediaScenario.host(activity,"state");
        String[] stages={"pause","play","next","previous","toggle","fast_forward","rewind","play_final"};
        String[][] paths={{"pause"},{"play"},{"nextitem"},{"previtem"},{"pause"},{"beginff","playresume"},{"beginrew","playresume"},{"play"}};
        int index=peer.size();
        for(int i=0;i<stages.length;i++) {
            LegacySystemMediaScenario.host(activity,stages[i]);
            for(String path:paths[i])peer.expect(index++,path);
        }
        int mirrorBase=peer.size();LegacyMirrorScenario.check(runner,activity);
        if(peer.size()!=mirrorBase)throw new AssertionError("Mirror audio focus dispatched a music control to the sender");
        if(LegacySystemMediaScenario.gain(activity)!=0)throw new AssertionError("Mode switching resumed sound after permanent audio focus loss");
        LegacySystemMediaScenario.host(activity,"recover_audio");peer.expect(mirrorBase,"play");
        await(runner,()->LegacySystemMediaScenario.gain(activity)==1);
        LegacySystemMediaScenario.host(activity,"background_done");
        try {await(runner,()->activity.hasWindowFocus() && text(activity,"sender-1").equals("Ⅱ"));}
        catch(AssertionError error) {throw new AssertionError(error.getMessage()+"; focus="+activity.hasWindowFocus()+
            "; destroyed="+activity.isDestroyed()+"; playing="+textOnUi(runner,activity,"sender-1")+"; "+controlDiagnostic(runner,activity),error);}
    }
    private interface Check {boolean done();}
    private static String controlDiagnostic(Instrumentation runner,Activity activity) {
        AtomicReference<String> result=new AtomicReference<>("No control diagnostic");
        ui(runner,()-> {
            try {
                java.lang.reflect.Field binding=activity.getClass().getDeclaredField("service");binding.setAccessible(true);
                Object service=binding.get(activity);java.lang.reflect.Field sessionField=service.getClass().getDeclaredField("session");sessionField.setAccessible(true);
                Object session=sessionField.get(service);Object state=session.getClass().getMethod("controls").invoke(session);
                StringBuilder text=new StringBuilder();
                for(String name:new String[]{"ready","identified","resolving","failure"}) {
                    Object value=state.getClass().getField(name).get(state);text.append(name).append('=').append(value).append(';');
                }
                result.set(text.toString());
            } catch(Exception error) {result.set("Control diagnostic failed: "+error);}
        });return result.get();
    }
    private static void await(Instrumentation runner,Check condition) throws Exception {
        long until=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<until) {
            AtomicReference<Boolean> result=new AtomicReference<>(false);
            ui(runner,()->result.set(condition.done()));if(result.get())return;SystemClock.sleep(20);
        }
        throw new AssertionError("Controls did not reach their expected UI state at "+new Throwable().getStackTrace()[1]);
    }
    private static View find(Activity activity,String tag) {
        View view=activity.findViewById(android.R.id.content).findViewWithTag(tag);
        if(view==null)throw new AssertionError("Missing view "+tag);return view;
    }
    private static String text(Activity activity,String tag) {return ((TextView)find(activity,tag)).getText().toString();}
    private static View findOnUi(Instrumentation runner,Activity activity,String tag) {
        AtomicReference<View> view=new AtomicReference<>();ui(runner,()->view.set(find(activity,tag)));return view.get();
    }
    private static String textOnUi(Instrumentation runner,Activity activity,String tag) {
        AtomicReference<String> value=new AtomicReference<>();ui(runner,()->value.set(text(activity,tag)));return value.get();
    }
    private static void ui(Instrumentation runner,Runnable action) {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        runner.runOnMainSync(()->{try {action.run();}catch(Throwable error) {failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private static void touch(Instrumentation runner,Activity activity,String tag) throws Exception {
        int[] point=new int[2];ui(runner,()-> {
            View view=find(activity,tag);view.getLocationOnScreen(point);point[0]+=view.getWidth()/2;point[1]+=view.getHeight()/2;
        });
        long down=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
            MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,point[0],point[1],0);
            try {runner.sendPointerSync(event);}finally {event.recycle();}
        }
        runner.waitForIdleSync();ui(runner,()-> {if(!find(activity,tag).hasFocus())throw new AssertionError("Touch did not focus "+tag);});
    }
    private static final class Peer implements java.io.Closeable {
        final String id="Buzin"+Long.toHexString(System.nanoTime()),token;
        final ServerSocket server;
        final JmDNS dns;
        final Thread worker;
        final List<String> requests=Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        volatile int code=204;
        Peer(Inet4Address address,String token) throws Exception {
            this.token=token;server=new ServerSocket(0,8,address);
            worker=new Thread(()-> {
                while(!server.isClosed())try(Socket socket=server.accept()) {
                    socket.setSoTimeout(3000);ByteArrayOutputStream bytes=new ByteArrayOutputStream();String request;
                    do {int value=socket.getInputStream().read();if(value<0)throw new java.io.IOException("Truncated request");
                        bytes.write(value);request=bytes.toString("US-ASCII");
                    } while(!request.endsWith("\r\n\r\n") && bytes.size()<8192);
                    if(!request.contains("\r\nActive-Remote: "+token+"\r\n"))throw new AssertionError("Wrong sender token");
                    requests.add(request.split(" ")[1]);
                    socket.getOutputStream().write(("HTTP/1.1 "+code+" Result\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                } catch(Throwable error) {if(!server.isClosed())failure.set(error);}
            },"test-sender-http");worker.start();
            dns=JmDNS.create(address,"test-sender-"+id);
            dns.registerService(ServiceInfo.create("_dacp._tcp.local.","iTunes_Ctrl_"+id,server.getLocalPort(),""));
        }
        void identify() throws Exception {
            try(Socket connection=new Socket("127.0.0.1",7000)) {
                connection.setSoTimeout(3000);
                connection.getOutputStream().write(("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nDACP-ID: "+id+"\r\nActive-Remote: "+token+"\r\n\r\n").getBytes("US-ASCII"));
                ByteArrayOutputStream result=new ByteArrayOutputStream();
                do {int value=connection.getInputStream().read();if(value<0)throw new AssertionError("RTSP closed");result.write(value);}
                while(!result.toString("US-ASCII").endsWith("\r\n\r\n") && result.size()<8192);
                if(!result.toString("US-ASCII").startsWith("RTSP/1.0 200 OK"))throw new AssertionError("Native identity request failed");
            }
        }
        int size() {return requests.size();}
        void expect(int index,String command) throws Exception {
            long until=SystemClock.elapsedRealtime()+10000;
            while(requests.size()<=index && failure.get()==null && SystemClock.elapsedRealtime()<until)SystemClock.sleep(10);
            if(failure.get()!=null)throw new AssertionError(failure.get());
            if(requests.size()<=index || !requests.get(index).equals("/ctrl-int/1/"+command))
                throw new AssertionError("Expected sender command "+index+": "+command+"; got "+requests);
        }
        @Override public void close() throws java.io.IOException {
            try {dns.close();}finally {server.close();try {worker.join(4000);}catch(InterruptedException error) {Thread.currentThread().interrupt();}}
            if(worker.isAlive())throw new java.io.IOException("Sender fixture worker did not stop");
        }
    }
}
