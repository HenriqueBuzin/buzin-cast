package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import io.buzincast.fireos3probe.LegacyNetwork;
import io.buzincast.fireos3probe.LegacyReceiverSession;
import io.buzincast.fireos3probe.LegacyUrlPlayer;
import io.buzincast.media.FCastFrame;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import kotlin.Unit;
import org.json.JSONObject;

/** The shared production FCast v2 transport, through real TCP/DNS-SD and platform playback. */
final class LegacyFcastScenario {
    private static Object field(Object object,String name) throws Exception {
        java.lang.reflect.Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private interface Check {boolean ready() throws Exception;}
    private static void await(Check check) throws Exception {
        long until=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<until) {if(check.ready())return;SystemClock.sleep(20);}
        throw new AssertionError("FCast did not settle at "+new Throwable().getStackTrace()[1]);
    }
    private static void ui(Instrumentation runner,Runnable action) {
        AtomicReference<Throwable> failure=new AtomicReference<>();runner.runOnMainSync(()-> {try {action.run();}catch(Throwable error) {failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private static Object read(Instrumentation runner,Object object,String name) {
        AtomicReference<Object> result=new AtomicReference<>();ui(runner,()-> {try {result.set(field(object,name));}catch(Exception error) {throw new AssertionError(error);}});return result.get();
    }
    private static LegacyUrlPlayer.State state(Instrumentation runner,LegacyReceiverService service) {
        AtomicReference<LegacyUrlPlayer.State> result=new AtomicReference<>();ui(runner,()->result.set(service.urlState()));return result.get();
    }
    private static void frame(Socket socket,int opcode,String body) throws Exception {new FCastFrame(opcode,body).writeTo(socket.getOutputStream());}
    private static FCastFrame receive(Socket socket,int opcode) throws Exception {
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until) {
            FCastFrame frame=FCastFrame.Companion.readFrom(socket.getInputStream(),()->Unit.INSTANCE);
            if(frame==null)throw new AssertionError("FCast disconnected before opcode "+opcode);
            if(frame.getOpcode()==opcode)return frame;
        }
        throw new AssertionError("FCast did not send opcode "+opcode);
    }
    private static Socket connect(InetAddress address) throws Exception {Socket socket=new Socket(address,46899);socket.setSoTimeout(10000);return socket;}
    private interface JsonCheck {boolean matches(JSONObject value) throws Exception;}
    private static JSONObject receiveMatching(Socket socket,int opcode,JsonCheck check) throws Exception {
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until) {
            JSONObject body=new JSONObject(receive(socket,opcode).getBody());if(check.matches(body))return body;
        }
        throw new AssertionError("FCast reported unexpected state for opcode "+opcode);
    }
    private static void configure(Instrumentation runner,Activity activity,boolean enabled) {
        ui(runner,()-> {
            new LegacyPreferences(activity).bool("fcast_enabled",enabled);
            activity.startService(new Intent(activity,LegacyReceiverService.class).setAction(LegacyReceiverService.CONFIGURE));
        });
    }
    private static void disabled(Instrumentation runner,LegacyReceiverService service) throws Exception {
        await(()->read(runner,service,"fcast")==null && !(Boolean)read(runner,service,"fcastClosing"));
    }
    static void check(Instrumentation runner,Activity activity) throws Exception {
        AtomicReference<LegacyReceiverService> owner=new AtomicReference<>();
        await(()-> {owner.set((LegacyReceiverService)read(runner,activity,"service"));return owner.get()!=null;});
        LegacyReceiverService service=owner.get();
        if(new LegacyPreferences(activity).fcast())throw new AssertionError("FCast must start disabled");
        InetAddress address=LegacyNetwork.select().address;
        byte[] sample;
        try(InputStream input=runner.getContext().getAssets().open("url_sample.mp4");ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] chunk=new byte[4096];int count;while((count=input.read(chunk))!=-1)bytes.write(chunk,0,count);sample=bytes.toByteArray();
        }
        try(JmDNS observer=JmDNS.create(address,"legacy-fcast-test-"+UUID.randomUUID().toString().substring(0,8));LegacyUrlScenario.Fixture fixture=new LegacyUrlScenario.Fixture(sample)) {
            // Enable through the actual settings callback used by touch/remote.
            ui(runner,()-> {
                View root=activity.findViewById(android.R.id.content);root.findViewWithTag("nav-settings").performClick();
                View toggle=root.findViewWithTag("fcast_enabled");if(toggle==null)throw new AssertionError("Missing translated FCast setting");toggle.performClick();
            });
            await(()->read(runner,service,"fcast")!=null);
            String name=new LegacyPreferences(activity).name();
            // A sender browses PTR records before resolving the discovered instance.
            // Resolving a name before its asynchronous registration can miss the
            // initial announcement without ever starting a service-type browse.
            ServiceInfo[] visible=observer.list("_fcast._tcp.local.",10000);
            ServiceInfo discovered=null;
            for(ServiceInfo info:visible)if(name.equals(info.getName())) {discovered=info;break;}
            if(discovered==null || discovered.getPort()!=46899 || !"2".equals(discovered.getPropertyString("v")) || !"Buzin Cast".equals(discovered.getPropertyString("appName")))
                throw new AssertionError("FCast discovery: expected name="+name+", result="+description(discovered)+", visible="+descriptions(visible));
            Object advertisement=field(read(runner,service,"fcast"),"discovery");
            try(Socket client=connect(address)) {
                if(new JSONObject(receive(client,11).getBody()).getInt("version")!=2)throw new AssertionError("Wrong FCast version");
                frame(client,12,"");receive(client,13);
                frame(client,1,new JSONObject().put("url","file:///private.mp4").put("container","video/mp4").toString());
                receive(client,9);
                if(state(runner,service)!=null)throw new AssertionError("Invalid source disturbed playback");
                JSONObject request=new JSONObject().put("url","http://127.0.0.1:"+fixture.socket.getLocalPort()+"/sample.mp4")
                    .put("container","video/mp4").put("time",0).put("speed",1).put("headers",new JSONObject().put("X-Legacy-Fixture","fixture"));
                LegacyReceiverSession nativeReceiver=(LegacyReceiverSession)read(runner,service,"session");
                try(Socket idleNative=new Socket(address,nativeReceiver.listeningPort())) {
                    idleNative.getOutputStream().write("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".getBytes("US-ASCII"));
                    await(()->nativeReceiver.connectionCount()>0);
                    frame(client,1,request.toString());receive(client,9);
                    if(state(runner,service)!=null || read(runner,service,"session")!=nativeReceiver)throw new AssertionError("FCast interrupted an idle native peer");
                }
                await(()->nativeReceiver.connectionCount()==0);
                frame(client,1,request.toString());
                await(()->state(runner,service)!=null && state(runner,service).phase==LegacyUrlPlayer.Phase.READY && state(runner,service).playing);
                frame(client,2,"");await(()->!state(runner,service).playing);
                if(read(runner,service,"session")!=null)throw new AssertionError("FCast shared the native output");
                frame(client,5,"{\"time\":1}");await(()->Math.abs(state(runner,service).positionMs-1000)<100);
                frame(client,8,"{\"volume\":0.35}");
                receiveMatching(client,7,body->Math.abs(body.getDouble("volume")-.35)<.001);
                JSONObject update=receiveMatching(client,6,body->body.getInt("state")==2);
                if(update.getDouble("duration")<3)throw new AssertionError("FCast did not report media duration");
                frame(client,3,"");await(()->state(runner,service).playing);
                frame(client,2,"");await(()->!state(runner,service).playing);
                frame(client,4,"");
                await(()-> {LegacyReceiverSession session=(LegacyReceiverSession)read(runner,service,"session");return session!=null && session.listeningPort()>0;});
                request.put("speed",1.25);frame(client,1,request.toString());receive(client,9);
                await(()-> {LegacyReceiverSession session=(LegacyReceiverSession)read(runner,service,"session");return state(runner,service)==null && session!=null && session.listeningPort()>0;});
                // Non-unit speed must fail explicitly, never silently play at the wrong rate.
                configure(runner,activity,false);disabled(runner,service);
                if(field(advertisement,"multicast")!=null || !((ExecutorService)field(advertisement,"worker")).isTerminated())throw new AssertionError("FCast discovery retained workers or multicast lock");
            }
            configure(runner,activity,true);await(()->read(runner,service,"fcast")!=null);
            // Incomplete frames time out without retiring the listening service.
            try(Socket partial=connect(address)) {
                receive(partial,11);partial.getOutputStream().write(new byte[]{2});partial.getOutputStream().flush();
                long deadline=SystemClock.elapsedRealtime()+9000;boolean ended=false;
                while(SystemClock.elapsedRealtime()<deadline) {
                    FCastFrame next=FCastFrame.Companion.readFrom(partial.getInputStream(),()->Unit.INSTANCE);
                    if(next==null) {ended=true;break;}
                }
                if(!ended)throw new AssertionError("Partial FCast frame remained open");
            }
            await(()-> {Object receiver=read(runner,service,"fcast");return receiver!=null && field(receiver,"client")==null;});
            try(Socket client=connect(address)) {receive(client,11);frame(client,12,"");receive(client,13);}
        }finally {configure(runner,activity,false);disabled(runner,service);}
    }
    private static String description(ServiceInfo info) {return info==null?"missing":info.getName()+":"+info.getPort()+",v="+info.getPropertyString("v")+",appName="+info.getPropertyString("appName");}
    private static String descriptions(ServiceInfo[] infos) {StringBuilder value=new StringBuilder();for(ServiceInfo info:infos)value.append("[").append(description(info)).append("]");return value.toString();}
}
