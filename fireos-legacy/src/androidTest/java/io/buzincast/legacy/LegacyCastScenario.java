package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.View;
import io.buzincast.fireos3probe.LegacyNetwork;
import io.buzincast.fireos3probe.LegacyTlsIdentity;
import io.buzincast.fireos3probe.LegacyUrlPlayer;
import io.buzincast.media.CastEnvelope;
import java.io.*;
import java.net.*;
import java.security.cert.*;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import javax.net.ssl.*;
import kotlin.Unit;
import org.json.JSONObject;
import static io.buzincast.legacy.LegacyApprovalScenario.*;

/** Shared Cast V2 transport over real API17 TLS and Media3; synthetic sender only. */
final class LegacyCastScenario {
    private static final String RECEIVER="urn:x-cast:com.google.cast.receiver",MEDIA="urn:x-cast:com.google.cast.media",
        HEARTBEAT="urn:x-cast:com.google.cast.tp.heartbeat",AUTH="urn:x-cast:com.google.cast.tp.deviceauth";
    private static X509Certificate certificate(Activity activity) throws Exception {
        try(DataInputStream in=new DataInputStream(new FileInputStream(new File(activity.getFilesDir(),"cast-v2-identity.bin")))) {
            if(in.readInt()!=0x42435c01)throw new AssertionError("Invalid TLS identity format");
            int key=in.readInt();if(key<1 || key>16384 || in.skipBytes(key)!=key)throw new AssertionError("Invalid stored key size");
            int count=in.readInt();if(count<1 || count>16384)throw new AssertionError("Invalid stored certificate size");
            byte[] data=new byte[count];in.readFully(data);
            return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(data));
        }
    }
    private static SSLContext client(Activity activity) throws Exception {
        byte[] expected=certificate(activity).getEncoded();
        X509TrustManager trust=new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] chain,String auth) throws CertificateException {throw new CertificateException("Test client has no identity");}
            public void checkServerTrusted(X509Certificate[] chain,String auth) throws CertificateException {
                if(chain.length!=1 || !Arrays.equals(expected,chain[0].getEncoded()))throw new CertificateException("Unexpected experimental server identity");
                chain[0].checkValidity();
            }
        };
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{trust},new java.security.SecureRandom());return tls;
    }
    private static Object cast(Instrumentation runner,LegacyReceiverService service) {return read(runner,service,"castV2");}
    private static SSLServerSocket ready(Instrumentation runner,LegacyReceiverService service) throws Exception {
        AtomicReference<SSLServerSocket> listener=new AtomicReference<>();
        await(()-> {
            Object current=cast(runner,service);if(current==null)return false;
            listener.set((SSLServerSocket)read(runner,current,"listener"));
            return listener.get()!=null && listener.get().isBound() && !listener.get().isClosed();
        });return listener.get();
    }
    private static void enable(Instrumentation runner,Activity activity,LegacyReceiverService service,boolean enabled) throws Exception {
        ui(runner,()-> {
            View root=activity.findViewById(android.R.id.content);root.findViewWithTag("nav-settings").performClick();
            if(new LegacyPreferences(activity).castV2()!=enabled)root.findViewWithTag("castv2_enabled").performClick();
        });runner.waitForIdleSync();
        if(enabled)ready(runner,service);else await(()->cast(runner,service)==null && !(Boolean)read(runner,service,"castClosing") &&
            io.buzincast.fireos3probe.LegacyNativeTls.liveSockets()==0);
        ui(runner,()->activity.findViewById(android.R.id.content).findViewWithTag("nav-overview").performClick());
    }
    private static SSLSocket connect(Activity activity,SSLServerSocket server) throws Exception {
        SSLSocket socket=(SSLSocket)client(activity).getSocketFactory().createSocket(LegacyNetwork.select().address,server.getLocalPort());
        try {
            socket.setEnabledProtocols(new String[]{"TLSv1.2"});socket.setSoTimeout(6000);socket.startHandshake();
            if(!"TLSv1.2".equals(socket.getSession().getProtocol()))throw new AssertionError("TLS was downgraded");
            return socket;
        } catch(Exception error) {socket.close();throw error;}
    }
    private static void released(Instrumentation runner,LegacyReceiverService service) throws Exception {
        await(()->cast(runner,service)!=null && read(runner,cast(runner,service),"client")==null);SystemClock.sleep(550);
    }
    private static void send(SSLSocket socket,String destination,String namespace,JSONObject body) throws Exception {
        new CastEnvelope("legacy-test-sender",destination,namespace,body.toString(),null).writeTo(socket.getOutputStream());
    }
    private static JSONObject reply(SSLSocket socket,int request) throws Exception {
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until) {
            CastEnvelope frame=CastEnvelope.Companion.readFrom(socket.getInputStream(),()->Unit.INSTANCE);
            if(frame==null)throw new AssertionError("Cast channel closed before response");
            if(frame.getText()!=null) {
                JSONObject body=new JSONObject(frame.getText());if(body.optInt("requestId",-1)==request)return body;
            }
        }throw new AssertionError("Cast response missing");
    }
    private static JSONObject command(SSLSocket socket,String destination,String namespace,int id,String type) throws Exception {
        send(socket,destination,namespace,new JSONObject().put("type",type).put("requestId",id));return reply(socket,id);
    }
    private static LegacyUrlPlayer.State state(Instrumentation runner,LegacyReceiverService service) {
        AtomicReference<LegacyUrlPlayer.State> value=new AtomicReference<>();ui(runner,()->value.set(service.urlState()));return value.get();
    }
    static void check(Instrumentation runner,Activity activity) throws Exception {
        AtomicReference<LegacyReceiverService> owner=new AtomicReference<>();
        await(()-> {owner.set((LegacyReceiverService)read(runner,activity,"service"));return owner.get()!=null;});
        LegacyReceiverService service=owner.get();
        if(new LegacyPreferences(activity).castV2())throw new AssertionError("Cast V2 must default off");
        LegacyTlsIdentity.nativeContext(activity); // Instrumentation worker, never the main thread.
        byte[] initial=certificate(activity).getEncoded();
        byte[] sample;try(InputStream in=runner.getContext().getAssets().open("url_sample.mp4");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] block=new byte[4096];int count;while((count=in.read(block))!=-1)out.write(block,0,count);sample=out.toByteArray();
        }
        try(JmDNS observer=JmDNS.create(LegacyNetwork.select().address,"legacy-cast-test-"+UUID.randomUUID().toString().substring(0,8));Fixture fixture=new Fixture(sample)) {
            enable(runner,activity,service,true);SSLServerSocket server=ready(runner,service);
            ServiceInfo found=null;
            for(ServiceInfo info:observer.list("_googlecast._tcp.local.",10000))if(new LegacyPreferences(activity).name().equals(info.getPropertyString("fn")))found=info;
            if(found==null || found.getPort()!=server.getLocalPort() || !"Buzin Cast Experimental".equals(found.getPropertyString("md")))throw new AssertionError("Missing experimental Cast DNS-SD identity");
            try(SSLSocket socket=connect(activity,server)) {
                new CastEnvelope("legacy-test-sender","receiver-0",HEARTBEAT,"{\"type\":\"PING\"}",null).writeTo(socket.getOutputStream());
                CastEnvelope pong=CastEnvelope.Companion.readFrom(socket.getInputStream(),()->Unit.INSTANCE);
                if(pong==null || !HEARTBEAT.equals(pong.getNamespace()) || !"PONG".equals(new JSONObject(pong.getText()).getString("type")))throw new AssertionError("Cast heartbeat failed");
                new CastEnvelope("legacy-test-sender","receiver-0",AUTH,null,new byte[]{10,0}).writeTo(socket.getOutputStream());
                CastEnvelope auth=CastEnvelope.Companion.readFrom(socket.getInputStream(),()->Unit.INSTANCE);
                if(auth==null || !Arrays.equals(auth.getBinary(),new byte[]{26,2,8,0}))throw new AssertionError("Experimental receiver claimed device certification");
                if(!"RECEIVER_STATUS".equals(command(socket,"receiver-0",RECEIVER,1,"GET_STATUS").getString("type")))throw new AssertionError("Cast receiver status failed");
                send(socket,"receiver-0",RECEIVER,new JSONObject().put("type","LAUNCH").put("requestId",2).put("appId","unsupported"));
                if(!"LAUNCH_ERROR".equals(reply(socket,2).getString("type")))throw new AssertionError("Unsupported app was launched");
                send(socket,"receiver-0",RECEIVER,new JSONObject().put("type","LAUNCH").put("requestId",3).put("appId","CC1AD845"));
                String transport=reply(socket,3).getJSONObject("status").getJSONArray("applications").getJSONObject(0).getString("transportId");
                send(socket,transport,MEDIA,new JSONObject().put("type","LOAD").put("requestId",4).put("media",new JSONObject().put("contentId","file:///invalid").put("contentType","video/mp4")));
                if(!"INVALID_REQUEST".equals(reply(socket,4).getString("type")))throw new AssertionError("Local URL escaped network validation");
                send(socket,transport,MEDIA,new JSONObject().put("type","LOAD").put("requestId",5).put("autoplay",false).put("media",new JSONObject()
                    .put("contentId","http://127.0.0.1:"+fixture.server.getLocalPort()+"/video.mp4").put("contentType","video/mp4")));
                JSONObject loaded=reply(socket,5);int media=loaded.getJSONArray("status").getJSONObject(0).getInt("mediaSessionId");
                await(()->state(runner,service)!=null && state(runner,service).phase==LegacyUrlPlayer.Phase.READY && !state(runner,service).playing);
                for(String type:new String[]{"PLAY","PAUSE"}) {
                    int id=type.equals("PLAY")?6:7;send(socket,transport,MEDIA,new JSONObject().put("type",type).put("requestId",id).put("mediaSessionId",media));reply(socket,id);
                    boolean playing=type.equals("PLAY");await(()->state(runner,service).playing==playing);
                }
                send(socket,transport,MEDIA,new JSONObject().put("type","SEEK").put("requestId",8).put("mediaSessionId",media).put("currentTime",1));reply(socket,8);
                await(()->Math.abs(state(runner,service).positionMs-1000)<150);
                send(socket,transport,MEDIA,new JSONObject().put("type","SET_VOLUME").put("requestId",9).put("mediaSessionId",media).put("volume",new JSONObject().put("level",.4).put("muted",true)));reply(socket,9);
                if((Float)read(runner,service,"urlGain")!=0)throw new AssertionError("Cast mute was not applied");
                send(socket,transport,MEDIA,new JSONObject().put("type","STOP").put("requestId",10).put("mediaSessionId",media));
                JSONObject stopped=reply(socket,10);if(!"IDLE".equals(stopped.getJSONArray("status").getJSONObject(0).getString("playerState")))throw new AssertionError("Cast terminal state was stale");
                await(()->state(runner,service)==null);
            }
            released(runner,service);
            try(SSLSocket oversized=connect(activity,server)) {
                new DataOutputStream(oversized.getOutputStream()).writeInt(65537);
                try {if(oversized.getInputStream().read()!=-1)throw new AssertionError("Oversized Cast frame was accepted");}catch(SSLException expected) {}
            }
            released(runner,service);
            try(Socket stalled=new Socket(LegacyNetwork.select().address,server.getLocalPort())) {
                await(()->read(runner,cast(runner,service),"client")!=null);SystemClock.sleep(200);
                long start=SystemClock.elapsedRealtime();enable(runner,activity,service,false);
                if(SystemClock.elapsedRealtime()-start>3000)throw new AssertionError("Closing an incomplete TLS handshake blocked shutdown");
            }
            enable(runner,activity,service,true);server=ready(runner,service);
            configure(runner,service,true);SSLServerSocket protectedServer=ready(runner,service);
            FutureTask<SSLSocket> denied=background(()->connect(activity,protectedServer));pending(runner,activity,service,"Cast V2");answer(runner,activity,false);
            try {denied.get(8,TimeUnit.SECONDS).close();throw new AssertionError("Declined Cast controller completed TLS");}catch(java.util.concurrent.ExecutionException expected) {}
            configure(runner,service,true);released(runner,service);
            FutureTask<SSLSocket> allowed=background(()->connect(activity,protectedServer));pending(runner,activity,service,"Cast V2");answer(runner,activity,true);allowed.get(8,TimeUnit.SECONDS).close();
            configure(runner,service,false);enable(runner,activity,service,false);
            enable(runner,activity,service,true);
            if(!Arrays.equals(initial,certificate(activity).getEncoded()))throw new AssertionError("Valid TLS identity was regenerated on restart");
            try(SSLSocket persisted=connect(activity,ready(runner,service))) {if(!persisted.isConnected())throw new AssertionError("Persisted TLS key did not work");}
            enable(runner,activity,service,false);
            try(DataOutputStream corrupt=new DataOutputStream(activity.openFileOutput("cast-v2-identity.bin",0))) {corrupt.writeInt(0x42435c01);corrupt.writeInt(Integer.MAX_VALUE);}
            enable(runner,activity,service,true);
            if(Arrays.equals(initial,certificate(activity).getEncoded()))throw new AssertionError("Corrupt TLS identity was not replaced");
            try(SSLSocket repaired=connect(activity,ready(runner,service))) {if(!repaired.isConnected())throw new AssertionError("Repaired TLS identity did not work");}
            released(runner,service);
            try(Proxy proxy=new Proxy(ready(runner,service).getLocalPort())) {
                // Only the public certificate leaves the app for the host witness.
                LegacyPinScenario.write(activity,"castv2-public.b64",android.util.Base64.encodeToString(certificate(activity).getEncoded(),android.util.Base64.NO_WRAP));
                SSLServerSocket actual=ready(runner,service);
                LegacyPinScenario.write(activity,"castv2-parameters.json",new JSONObject()
                    .put("protocols",new org.json.JSONArray(Arrays.asList(actual.getEnabledProtocols())))
                    .put("enabled",new org.json.JSONArray(Arrays.asList(actual.getEnabledCipherSuites())))
                    .put("supported",new org.json.JSONArray(Arrays.asList(actual.getSupportedCipherSuites()))).toString());
                LegacyPinScenario.write(activity,"castv2-host-port",Integer.toString(proxy.server.getLocalPort()));
                LegacyPinScenario.write(activity,"castv2-host-request","tls");
                File ack=new File(activity.getFilesDir(),"castv2-host-ack");
                long until=SystemClock.elapsedRealtime()+60000;
                while(!ack.isDirectory() && SystemClock.elapsedRealtime()<until)SystemClock.sleep(20);
                if(!ack.isDirectory())throw new AssertionError("Independent TLS witness did not finish");
            }
        } finally {configure(runner,service,false);enable(runner,activity,service,false);}
    }
    /** Adb forwards to loopback; the experimental listener intentionally binds LAN. */
    private static final class Proxy implements Closeable {
        final ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
        final Thread worker;volatile Socket front,back;volatile Thread reverse;volatile boolean closed;
        Proxy(int port) throws IOException {
            worker=new Thread(()-> {
                try {
                    front=server.accept();front.setSoTimeout(15000);
                    back=new Socket(LegacyNetwork.select().address,port);back.setSoTimeout(15000);
                    reverse=new Thread(()->copy(back,front),"legacy-cast-host-reply");reverse.start();copy(front,back);
                } catch(Exception ignored) { }
            },"legacy-cast-host-proxy");worker.start();
        }
        void copy(Socket source,Socket destination) {
            try {
                byte[] bytes=new byte[4096];int count,total=0;
                while(!closed && (count=source.getInputStream().read(bytes))!=-1) {
                    if((total+=count)>4*1024*1024)throw new IOException("Test proxy exceeded budget");
                    destination.getOutputStream().write(bytes,0,count);
                }
            } catch(IOException ignored) { }
            finally {try {source.close();destination.close();}catch(IOException ignored) {}}
        }
        public void close() throws IOException {
            closed=true;server.close();if(front!=null)front.close();if(back!=null)back.close();
            try {worker.join(3000);if(reverse!=null)reverse.join(3000);}catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IOException(error);}
            if(worker.isAlive() || reverse!=null && reverse.isAlive())throw new IOException("Cast proxy retained a worker");
        }
    }
    private static final class Fixture implements Closeable {
        final ServerSocket server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));final Thread worker;volatile boolean closed;
        Fixture(byte[] sample) throws IOException {
            worker=new Thread(()-> {while(!closed)try(Socket peer=server.accept()) {
                peer.setSoTimeout(3000);ByteArrayOutputStream bytes=new ByteArrayOutputStream();String head="";
                while(bytes.size()<8192 && !head.endsWith("\r\n\r\n")) {int value=peer.getInputStream().read();if(value<0)break;bytes.write(value);head=bytes.toString("US-ASCII");}
                peer.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: "+sample.length+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));peer.getOutputStream().write(sample);
            }catch(Exception error) {if(closed)break;}},"legacy-cast-fixture");worker.start();
        }
        public void close() throws IOException {closed=true;server.close();try {worker.join(5000);}catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IOException(error);}if(worker.isAlive())throw new IOException("Cast fixture retained a thread");}
    }
}
