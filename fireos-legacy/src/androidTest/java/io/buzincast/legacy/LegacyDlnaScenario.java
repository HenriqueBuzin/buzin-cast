package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.widget.ImageView;
import io.buzincast.fireos3probe.LegacyNetwork;
import io.buzincast.fireos3probe.LegacyUrlPlayer;
import io.github.jqssun.airplay.media.PhotoActivity;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real SOAP socket commands and photo UI on the API17 platform, using synthetic media only. */
final class LegacyDlnaScenario {
    private interface Check {boolean ready() throws Exception;}
    private static void await(Check check) throws Exception {
        long end=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<end) {if(check.ready())return;SystemClock.sleep(20);}
        throw new AssertionError("DLNA did not settle at "+new Throwable().getStackTrace()[1]);
    }
    private static Object field(Object owner,String name) throws Exception {
        java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
    private static void ui(Instrumentation runner,Runnable action) {
        AtomicReference<Throwable> failure=new AtomicReference<>();runner.runOnMainSync(()-> {try {action.run();}catch(Throwable e) {failure.set(e);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private static Object read(Instrumentation runner,Object owner,String name) {
        AtomicReference<Object> result=new AtomicReference<>();ui(runner,()-> {try {result.set(field(owner,name));}catch(Exception e) {throw new AssertionError(e);}});return result.get();
    }
    private static LegacyUrlPlayer.State state(Instrumentation runner,LegacyReceiverService service) {
        AtomicReference<LegacyUrlPlayer.State> result=new AtomicReference<>();ui(runner,()->result.set(service.urlState()));return result.get();
    }
    private static void configure(Instrumentation runner,Activity activity,boolean enabled) {
        ui(runner,()-> {new LegacyPreferences(activity).bool("dlna_enabled",enabled);
            activity.startService(new Intent(activity,LegacyReceiverService.class).setAction(LegacyReceiverService.CONFIGURE));});
    }
    private static String request(int port,String method,String path,String headers,String body) throws Exception {
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(10000);byte[] bytes=body.getBytes("UTF-8");
            socket.getOutputStream().write((method+" "+path+" HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n"+headers+"\r\n").getBytes("UTF-8"));
            socket.getOutputStream().write(bytes);ByteArrayOutputStream response=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int count;
            while((count=socket.getInputStream().read(chunk))!=-1) {response.write(chunk,0,count);if(response.size()>262144)throw new AssertionError("Unbounded response");}
            return response.toString("UTF-8");
        }
    }
    private static String soap(int port,String action,String arguments) throws Exception {
        String type="urn:schemas-upnp-org:service:AVTransport:1";
        String result=request(port,"POST","/ctl/avt","Content-Type: text/xml; charset=utf-8\r\nSOAPACTION: \""+type+"#"+action+"\"\r\n",
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:"+action+" xmlns:u=\""+type+"\"><InstanceID>0</InstanceID>"+arguments+"</u:"+action+"></s:Body></s:Envelope>");
        if(!result.startsWith("HTTP/1.1 200"))throw new AssertionError("SOAP "+action+": "+result);return result;
    }
    static void check(Instrumentation runner,Activity activity) throws Exception {
        AtomicReference<LegacyReceiverService> owner=new AtomicReference<>();
        await(()-> {owner.set((LegacyReceiverService)read(runner,activity,"service"));return owner.get()!=null;});
        LegacyReceiverService service=owner.get();
        if(new LegacyPreferences(activity).dlna())throw new AssertionError("DLNA must default off");
        byte[] video;try(InputStream input=runner.getContext().getAssets().open("url_sample.mp4");ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] chunk=new byte[4096];int count;while((count=input.read(chunk))!=-1)bytes.write(chunk,0,count);video=bytes.toByteArray();
        }
        Bitmap source=Bitmap.createBitmap(64,32,Bitmap.Config.ARGB_8888);source.eraseColor(0xff31bf76);
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();source.compress(Bitmap.CompressFormat.PNG,100,encoded);source.recycle();
        try(Fixture fixture=new Fixture(video,encoded.toByteArray())) {
            ui(runner,()-> {android.view.View root=activity.findViewById(android.R.id.content);root.findViewWithTag("nav-settings").performClick();root.findViewWithTag("dlna_enabled").performClick();});
            await(()->read(runner,service,"dlna")!=null);
            Object receiver=read(runner,service,"dlna");Object server=field(receiver,"server");
            int port=((io.leancast.app.ControlServer)server).getBoundPort();
            String description=request(port,"GET","/desc.xml","","");
            if(!description.contains("MediaRenderer") || !description.contains("/ctl/avt"))throw new AssertionError("Missing renderer description: "+description);
            InetAddress address=LegacyNetwork.select().address;
            try(DatagramSocket search=new DatagramSocket(new InetSocketAddress(address,0))) {
                search.setSoTimeout(5000);
                byte[] query="M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: upnp:rootdevice\r\n\r\n".getBytes("US-ASCII");
                search.send(new DatagramPacket(query,query.length,address,1900));byte[] raw=new byte[4096];DatagramPacket reply=new DatagramPacket(raw,raw.length);search.receive(reply);
                String discovery=new String(raw,0,reply.getLength(),"US-ASCII");if(!discovery.contains(":"+port+"/desc.xml"))throw new AssertionError("Wrong SSDP location");
            }
            String base="http://127.0.0.1:"+fixture.socket.getLocalPort();
            // Validation can reject after the SOAP worker has queued main-thread
            // playback. It must report an error instead of killing the process.
            StringBuilder oversized=new StringBuilder(base+"/");while(oversized.length()<8300)oversized.append('x');
            soap(port,"SetAVTransportURI","<CurrentURI>"+oversized+"</CurrentURI><CurrentURIMetaData/>");
            await(()->soap(port,"GetTransportInfo","").contains("STOPPED"));
            if(state(runner,service)!=null)throw new AssertionError("Rejected source disturbed native output");
            soap(port,"SetAVTransportURI","<CurrentURI>"+base+"/video.mp4</CurrentURI><CurrentURIMetaData/>");
            await(()->state(runner,service)!=null && state(runner,service).playing && state(runner,service).phase==LegacyUrlPlayer.Phase.READY);
            await(()->soap(port,"GetTransportInfo","").contains("<CurrentTransportState>PLAYING</CurrentTransportState>"));
            soap(port,"Pause","");await(()->!state(runner,service).playing);
            soap(port,"Seek","<Unit>REL_TIME</Unit><Target>0:00:01</Target>");await(()->Math.abs(state(runner,service).positionMs-1000)<100);
            if(!soap(port,"GetPositionInfo","").contains("0:00:01"))throw new AssertionError("Position was not exposed to sender");
            soap(port,"Play","<Speed>1</Speed>");await(()->state(runner,service).playing);
            soap(port,"Stop","");await(()->state(runner,service)==null && read(runner,service,"session")!=null);
            Instrumentation.ActivityMonitor monitor=runner.addMonitor(PhotoActivity.class.getName(),null,false);
            try {
                soap(port,"SetAVTransportURI","<CurrentURI>"+base+"/photo.png</CurrentURI><CurrentURIMetaData/>");
                Activity photo=runner.waitForMonitorWithTimeout(monitor,10000);if(photo==null)throw new AssertionError("DLNA photo did not open");
                await(()->read(runner,photo,"bitmap")!=null);
                ui(runner,()-> {try {
                    Bitmap image=(Bitmap)field(photo,"bitmap");if(image.getWidth()!=64 || image.getPixel(0,0)!=0xff31bf76)throw new AssertionError("Wrong photo pixels");
                    android.view.View root=photo.findViewById(android.R.id.content);
                    for(int i=0;i<6;i++)if(root.findViewWithTag("photo-control-"+i)==null)throw new AssertionError("Missing photo control");
                    root.findViewWithTag("photo-control-3").performClick();root.findViewWithTag("photo-control-4").performClick();
                    ImageView view=(ImageView)field(photo,"image");if(view.getRotation()!=90 || view.getScaleType()!=ImageView.ScaleType.CENTER_CROP)throw new AssertionError("Photo controls failed");
                }catch(Exception e) {throw new AssertionError(e);}});
                if(read(runner,service,"dlna")==null)throw new AssertionError("Photo stopped the receiver");
                soap(port,"Stop","");await(()->photo.isFinishing());
            }finally {runner.removeMonitor(monitor);ui(runner,()->PhotoActivity.Companion.closePhoto());}
            // Invalid images fail without retaining temporary files.
            int before=activity.getCacheDir().list().length;
            try {LegacyPhotoLoader.load(activity,base+"/invalid.png");throw new AssertionError("Invalid photo accepted");}catch(PhotoActivity.PhotoLoadException expected) { }
            if(activity.getCacheDir().list().length!=before)throw new AssertionError("Photo loader leaked cache files");
            configure(runner,activity,false);await(()->read(runner,service,"dlna")==null && !(Boolean)read(runner,service,"dlnaClosing"));
            try(Socket released=new Socket("127.0.0.1",port)) {throw new AssertionError("DLNA listener retained after disable");}catch(java.net.ConnectException expected) { }
            configure(runner,activity,true);await(()->read(runner,service,"dlna")!=null);
        }finally {configure(runner,activity,false);await(()->read(runner,service,"dlna")==null && !(Boolean)read(runner,service,"dlnaClosing"));}
    }
    private static final class Fixture implements java.io.Closeable {
        final ServerSocket socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));final Thread worker;volatile boolean closed;
        Fixture(byte[] video,byte[] photo) throws Exception {
            worker=new Thread(()-> {while(!closed)try(Socket peer=socket.accept()) {
                peer.setSoTimeout(5000);ByteArrayOutputStream head=new ByteArrayOutputStream();String text="";
                while(head.size()<8192 && !text.endsWith("\r\n\r\n")) {int value=peer.getInputStream().read();if(value<0)break;head.write(value);text=head.toString("US-ASCII");}
                byte[] bytes=text.startsWith("GET /video.mp4 ")?video:text.startsWith("GET /photo.png ")?photo:new byte[]{1,2,3};
                peer.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));peer.getOutputStream().write(bytes);
            }catch(Exception error) {if(closed)break;}},"legacy-dlna-fixture");worker.start();
        }
        public void close() throws java.io.IOException {closed=true;socket.close();try {worker.join(6000);}catch(InterruptedException e) {Thread.currentThread().interrupt();throw new java.io.IOException(e);}if(worker.isAlive())throw new java.io.IOException("Fixture retained worker");}
    }
}
