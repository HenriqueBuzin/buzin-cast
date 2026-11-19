package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import io.buzincast.fireos3probe.LegacyReceiverSession;
import io.buzincast.fireos3probe.LegacyNetwork;
import io.github.jqssun.airplay.media.ConnectionApproval;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Actual admission paths, remote-focus UI and bounded cancellation on API17. */
final class LegacyApprovalScenario {
    interface Check {boolean ready() throws Exception;}
    static void await(Check check) throws Exception {
        long until=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<until) {if(check.ready())return;SystemClock.sleep(20);}
        throw new AssertionError("Approval did not settle at "+new Throwable().getStackTrace()[1]);
    }
    static void ui(Instrumentation runner,Runnable action) {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        runner.runOnMainSync(()-> {try {action.run();}catch(Throwable error) {failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    static Object read(Instrumentation runner,Object object,String name) {
        AtomicReference<Object> result=new AtomicReference<>();
        ui(runner,()-> {try {java.lang.reflect.Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);result.set(f.get(object));}catch(Exception error) {throw new AssertionError(error);}});
        return result.get();
    }
    static <T> FutureTask<T> background(Callable<T> task) {
        FutureTask<T> result=new FutureTask<>(task);new Thread(result,"legacy-approval-client").start();return result;
    }
    static String request(int port,boolean soap) throws Exception {
        String body=soap?"<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:GetTransportInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID></u:GetTransportInfo></s:Body></s:Envelope>":"";
        String head=soap?"POST /ctl/avt HTTP/1.1\r\nHost: localhost\r\nSOAPACTION: \"urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\"\r\nContent-Type: text/xml\r\n":"OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n";
        try(Socket socket=new Socket(LegacyNetwork.select().address,port)) {
            socket.setSoTimeout(35000);
            socket.getOutputStream().write((head+"Content-Length: "+body.getBytes("UTF-8").length+"\r\nConnection: close\r\n\r\n"+body).getBytes("UTF-8"));
            ByteArrayOutputStream reply=new ByteArrayOutputStream();int value;
            while((value=socket.getInputStream().read())>=0) {
                reply.write(value);if(reply.size()>65536)throw new AssertionError("Unbounded admission response");
                if(!soap && reply.toString("US-ASCII").endsWith("\r\n\r\n"))break;
            }
            return reply.toString("UTF-8");
        }
    }
    static ConnectionApproval.Request pending(Instrumentation runner,Activity activity,LegacyReceiverService service,String protocol) throws Exception {
        await(()->service.approvalRequest()!=null && protocol.equals(service.approvalRequest().getProtocol())
            && read(runner,activity,"approvalModal")!=null);
        ConnectionApproval.Request request=service.approvalRequest();
        ui(runner,()-> {
            View decline=activity.findViewById(android.R.id.content).findViewWithTag("approval-decline");
            if(decline==null || !decline.isFocused())throw new AssertionError("Approval must focus Decline");
        });
        return request;
    }
    static void answer(Instrumentation runner,Activity activity,boolean allow) {
        ui(runner,()-> {
            View button=activity.findViewById(android.R.id.content).findViewWithTag(allow?"approval-allow":"approval-decline");
            if(button==null || !button.performClick())throw new AssertionError("Missing approval action");
        });
    }
    static void capture(Instrumentation runner,Activity activity) {
        runner.waitForIdleSync();
        ui(runner,()-> {
            View root=activity.getWindow().getDecorView();
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
            try {
                root.draw(new android.graphics.Canvas(bitmap));ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes);
                try(java.io.FileOutputStream out=activity.openFileOutput("legacy-approval.b64",0)) {
                    out.write(android.util.Base64.encode(bytes.toByteArray(),android.util.Base64.NO_WRAP));
                } catch(java.io.IOException error) {throw new AssertionError(error);}
            } finally {bitmap.recycle();}
        });
    }
    static void configure(Instrumentation runner,LegacyReceiverService service,boolean enabled) throws Exception {
        ui(runner,()-> {
            new LegacyPreferences(service).bool("approve_connections",enabled);
            LegacyReceiverService.dispatch(service,LegacyReceiverService.CONFIGURE);
        });
        runner.waitForIdleSync();
        if((Boolean)read(runner,service,"desired"))await(()->!(Boolean)read(runner,service,"stopping") && !(Boolean)read(runner,service,"busy"));
    }
    static void expectCode(String reply,int code) {
        if(!reply.startsWith("RTSP/1.0 "+code) && !reply.startsWith("HTTP/1.1 "+code))throw new AssertionError("Unexpected admission result: "+reply);
    }
    static int receiverPort(Instrumentation runner,LegacyReceiverService service) throws Exception {
        await(()-> {LegacyReceiverSession session=(LegacyReceiverSession)read(runner,service,"session");return session!=null && session.listeningPort()>0;});
        return ((LegacyReceiverSession)read(runner,service,"session")).listeningPort();
    }
    static void check(Instrumentation runner,Activity activity) throws Exception {
        AtomicReference<LegacyReceiverService> owner=new AtomicReference<>();
        await(()-> {owner.set((LegacyReceiverService)read(runner,activity,"service"));return owner.get()!=null;});
        LegacyReceiverService service=owner.get();
        int nativePort=receiverPort(runner,service);
        expectCode(request(nativePort,false),200); // Default remains opt-out.
        ui(runner,()-> {
            LegacyPreferences prefs=new LegacyPreferences(service);prefs.bool("fcast_enabled",true);prefs.bool("dlna_enabled",true);
        });
        configure(runner,service,true);
        try {
            FutureTask<String> denied=background(()->request(nativePort,false));
            ConnectionApproval.Request old=pending(runner,activity,service,"AirPlay");capture(runner,activity);answer(runner,activity,false);
            expectCode(denied.get(5,TimeUnit.SECONDS),403);
            ui(runner,()->service.answerApproval(old.getId(),true));
            expectCode(request(nativePort,false),403); // Stale answer cannot override denial/cooldown.
            if(service.approvalRequest()!=null)throw new AssertionError("Denied peer immediately reprompted");
            configure(runner,service,true);
            FutureTask<String> accepted=background(()->request(nativePort,false));
            pending(runner,activity,service,"AirPlay");answer(runner,activity,true);expectCode(accepted.get(5,TimeUnit.SECONDS),200);
            expectCode(request(nativePort,false),200);
            if(service.approvalRequest()!=null)throw new AssertionError("Temporary grant was not reused");

            await(()->read(runner,service,"fcast")!=null);
            try(Socket fcast=new Socket(LegacyNetwork.select().address,46899)) {
                fcast.setSoTimeout(5000);pending(runner,activity,service,"FCast"); // AirPlay grant must not carry across protocols.
                ui(runner,activity::onBackPressed);
                if(fcast.getInputStream().read()!=-1)throw new AssertionError("Back did not reject FCast");
            }
            configure(runner,service,true);
            await(()->read(runner,read(runner,service,"fcast"),"client")==null);
            try(Socket fcast=new Socket(LegacyNetwork.select().address,46899)) {
                fcast.setSoTimeout(5000);pending(runner,activity,service,"FCast");answer(runner,activity,true);
                byte[] prefix=new byte[5];new java.io.DataInputStream(fcast.getInputStream()).readFully(prefix);
                if(prefix[4]!=11)throw new AssertionError("Approved FCast did not send its version");
            }
            await(()->read(runner,service,"dlna")!=null && read(runner,read(runner,service,"dlna"),"server")!=null);
            int dlnaPort=((io.leancast.app.ControlServer)read(runner,read(runner,service,"dlna"),"server")).getBoundPort();
            FutureTask<String> soapDenied=background(()->request(dlnaPort,true));
            pending(runner,activity,service,"DLNA");answer(runner,activity,false);expectCode(soapDenied.get(5,TimeUnit.SECONDS),403);
            configure(runner,service,true);
            FutureTask<String> soapAccepted=background(()->request(dlnaPort,true));
            pending(runner,activity,service,"DLNA");answer(runner,activity,true);expectCode(soapAccepted.get(5,TimeUnit.SECONDS),200);

            configure(runner,service,true);
            long started=SystemClock.elapsedRealtime();FutureTask<String> timeout=background(()->request(nativePort,false));
            pending(runner,activity,service,"AirPlay");expectCode(timeout.get(32,TimeUnit.SECONDS),403);
            if(SystemClock.elapsedRealtime()-started<24000)throw new AssertionError("Approval did not wait its documented timeout");
            await(()->read(runner,activity,"approvalModal")==null);

            configure(runner,service,true);
            FutureTask<String> stopped=background(()->request(nativePort,false));pending(runner,activity,service,"AirPlay");
            ui(runner,()->LegacyReceiverService.dispatch(service,LegacyReceiverService.STOP));
            expectCode(stopped.get(5,TimeUnit.SECONDS),403);
            await(()->read(runner,service,"session")==null && service.approvalRequest()==null && read(runner,activity,"approvalModal")==null);
            ui(runner,()->LegacyReceiverService.dispatch(service,LegacyReceiverService.START));
            int restartedPort=receiverPort(runner,service);
            ui(runner,()->activity.moveTaskToBack(true));await(()->!(Boolean)read(runner,activity,"foreground"));
            FutureTask<String> background=background(()->request(restartedPort,false));
            pending(runner,activity,service,"AirPlay");await(()->(Boolean)read(runner,activity,"foreground"));
            answer(runner,activity,false);expectCode(background.get(5,TimeUnit.SECONDS),403);
            configure(runner,service,false);expectCode(request(restartedPort,false),200);
            await(()->read(runner,service,"fcast")!=null && !(Boolean)read(runner,service,"fcastClosing"));
            try(Socket previous=new Socket(LegacyNetwork.select().address,46899)) {
                previous.setSoTimeout(5000);byte[] prefix=new byte[5];new java.io.DataInputStream(previous.getInputStream()).readFully(prefix);
                if(prefix[4]!=11)throw new AssertionError("Disabled approval unexpectedly blocked FCast");
                configure(runner,service,true); // Existing unauthenticated clients cannot survive enabling approval.
                int drained=0;while(previous.getInputStream().read()!=-1)if(++drained>65536)throw new AssertionError("Retired FCast still streaming");
            }
        } finally {
            configure(runner,service,false);
            ui(runner,()-> {LegacyPreferences prefs=new LegacyPreferences(service);prefs.bool("fcast_enabled",false);prefs.bool("dlna_enabled",false);LegacyReceiverService.dispatch(service,LegacyReceiverService.CONFIGURE);});
        }
    }
}
