package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import io.buzincast.fireos3probe.LegacyReceiverSession;
import io.buzincast.fireos3probe.LegacyUrlPlayer;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.*;

/** Native UxPlay HTTP -> JNI -> service -> Media3, with synthetic local media. */
final class LegacyNativeUrlScenario {
    private interface Check {boolean ready() throws Exception;}
    private static void await(Check check) throws Exception {
        long end=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<end) {if(check.ready())return;SystemClock.sleep(20);}
        throw new AssertionError("Native URL did not settle at "+new Throwable().getStackTrace()[1]);
    }
    private static Object field(Object owner,String name) throws Exception {
        java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
    private static void ui(Instrumentation runner,Runnable action) {
        AtomicReference<Throwable> failure=new AtomicReference<>();runner.runOnMainSync(()-> {try {action.run();}catch(Throwable e) {failure.set(e);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private static Object read(Instrumentation runner,Object owner,String name) {
        AtomicReference<Object> value=new AtomicReference<>();ui(runner,()-> {try {value.set(field(owner,name));}catch(Exception e) {throw new AssertionError(e);}});return value.get();
    }
    private static LegacyUrlPlayer.State state(Instrumentation runner,LegacyReceiverService service) {
        AtomicReference<LegacyUrlPlayer.State> value=new AtomicReference<>();ui(runner,()->value.set(service.urlState()));return value.get();
    }
    private static double number(String xml,String key) {
        Matcher value=Pattern.compile("<key>"+key+"</key>\\s*<(?:real|integer)>([^<]+)</(?:real|integer)>").matcher(xml);
        if(!value.find())throw new AssertionError("Missing native playback field "+key+": "+xml);return Double.parseDouble(value.group(1));
    }
    private static boolean ready(String xml) {return Pattern.compile("<key>readyToPlay</key>\\s*(?:<true\\s*/>|<integer>1</integer>)").matcher(xml).find();}
    private static void idle(Instrumentation runner,LegacyReceiverService service) throws Exception {await(()->read(runner,service,"urlPlayer")==null && !(Boolean)read(runner,service,"urlRequested"));}
    static void check(Instrumentation runner,Activity activity) throws Exception {
        AtomicReference<LegacyReceiverService> owner=new AtomicReference<>();await(()-> {owner.set((LegacyReceiverService)read(runner,activity,"service"));return owner.get()!=null;});
        LegacyReceiverService service=owner.get();LegacyReceiverSession initial=(LegacyReceiverSession)read(runner,service,"session");int port=initial.listeningPort();
        try(Fixture fixture=new Fixture(runner)) {
            String base="http://127.0.0.1:"+fixture.server.getLocalPort();
            try(Wire wire=new Wire(port)) {
                String info=wire.request("GET","/server-info","").body;
                Matcher value=Pattern.compile("<key>features</key>\\s*<integer>([^<]+)</integer>").matcher(info);
                if(!value.find())throw new AssertionError("Missing native features");
                java.math.BigInteger features=new java.math.BigInteger(value.group(1));
                if(!features.testBit(0) || !features.testBit(4) || features.testBit(1))throw new AssertionError("Wrong native URL/photo feature advertisement");
                // Exercise RTP audio -> URL, not just an idle receiver. The
                // old audio generation must flush without cancelling the new URL.
                ByteArrayOutputStream fixtureBytes=new ByteArrayOutputStream();
                try(InputStream input=runner.getContext().getAssets().open("audio_samples.json")) {
                    byte[] chunk=new byte[4096];int count;while((count=input.read(chunk))!=-1)fixtureBytes.write(chunk,0,count);
                }
                org.json.JSONObject audio=new org.json.JSONArray(fixtureBytes.toString("UTF-8")).getJSONObject(0);
                byte[] packet=android.util.Base64.decode(audio.getJSONArray("packets").getString(0),android.util.Base64.DEFAULT);
                java.lang.reflect.Method replay=initial.getClass().getDeclaredMethod("replayAudio",byte[].class,int.class,int.class,long.class,float.class,boolean.class);replay.setAccessible(true);
                for(int i=0;i<12;i++)replay.invoke(initial,packet,audio.getInt("codec"),audio.getInt("samplesPerFrame"),System.nanoTime(),-144f,i==0);
                await(initial::audioActive);
                wire.play(base+"/sample.mp4",1);
                await(()-> {LegacyUrlPlayer.State current=state(runner,service);return current!=null && current.phase==LegacyUrlPlayer.Phase.READY && current.playing && current.positionMs>=1000;});
                info=wire.request("GET","/playback-info","").body;
                if(!ready(info) || number(info,"duration")<7.9 || number(info,"position")<1 || number(info,"rate")!=1)throw new AssertionError("Native snapshot was not prepared: "+info);
                wire.request("POST","/rate?value=0","");await(()->!state(runner,service).playing);
                wire.request("POST","/scrub?position=2","");await(()->Math.abs(state(runner,service).positionMs-2000)<100);
                await(()-> {String current=wire.request("GET","/playback-info","").body;return number(current,"rate")==0 && Math.abs(number(current,"position")-2)<=.1;});
                wire.request("POST","/rate?value=1.5","");await(()->state(runner,service).playing && state(runner,service).speed==1.5f);
                runner.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE);await(()->!state(runner,service).playing);
                await(()->number(wire.request("GET","/playback-info","").body,"rate")==0);
                wire.request("POST","/stop","");idle(runner,service);
                if(read(runner,service,"session")!=initial || initial.isClosed())throw new AssertionError("Native URL stop restarted the receiver");
                wire.play(base+"/stream.m3u8",0);await(()-> {LegacyUrlPlayer.State current=state(runner,service);return current!=null && current.playing && current.width==160 && current.durationMs>=7900;});
                wire.request("POST","/stop","");idle(runner,service);
                Response invalid=wire.request("POST","/play","Content-Location: file:///private.mp4\r\nStart-Position: 0\r\n");
                if(invalid.code!=400 || state(runner,service)!=null)throw new AssertionError("Malformed native source was not rejected");
                wire.play(base+"/silence.m4a",0);await(()->state(runner,service)!=null && state(runner,service).playing && state(runner,service).positionMs>100);
                wire.request("POST","/scrub?position=7.8","");await(()->read(runner,service,"urlPlayer")==null);
                if(read(runner,service,"urlFailure")!=null)throw new AssertionError("Native audio URL ended with a player error");
            }
            fcup(runner,service,fixture,port,base);
            // Closing the playing channel must not look like an intentional stop.
            Wire playing=new Wire(port);
            try {playing.play(base+"/sample.mp4",0);await(()->state(runner,service)!=null && state(runner,service).playing);}
            finally {playing.close();}
            SystemClock.sleep(350);if(state(runner,service)==null || !state(runner,service).playing)throw new AssertionError("Playing URL stopped on channel close");
            ui(runner,service::stopUrl);idle(runner,service);
            // A prepared, paused abandoned channel follows the production reset policy.
            Wire paused=new Wire(port);
            try {paused.play(base+"/sample.mp4",0);paused.request("POST","/rate?value=0","");await(()->!state(runner,service).playing);
                await(()->number(paused.request("GET","/playback-info","").body,"rate")==0);}
            finally {paused.close();}
            idle(runner,service);
            if(read(runner,service,"session")!=initial || initial.isClosed())throw new AssertionError("Channel cleanup restarted native transport");
            try(Wire restarting=new Wire(port)) {
                restarting.play(base+"/sample.mp4",0);
                await(()->state(runner,service)!=null && state(runner,service).playing);
                ui(runner,()->activity.startService(new Intent(activity,LegacyReceiverService.class).setAction(LegacyReceiverService.RESTART)));
                await(initial::isClosed);idle(runner,service);
            }
            AtomicReference<LegacyReceiverSession> replacement=new AtomicReference<>();
            await(()-> {replacement.set((LegacyReceiverSession)read(runner,service,"session"));return replacement.get()!=null && replacement.get()!=initial && replacement.get().listeningPort()>0;});
            LegacyReceiverSession restarted=replacement.get();final int heldPort=restarted.listeningPort();
            // Stop must release the HTTP /play wait even if the media body has not arrived.
            ExecutorService sender=Executors.newSingleThreadExecutor();
            try {
                Future<?> held=sender.submit(()-> {try(Wire wire=new Wire(heldPort)) {wire.play(base+"/hold.mp4",0);}
                    catch(IOException stopped) { /* A stopped server may close before writing the response. */ }
                    catch(Exception e) {throw new RuntimeException(e);}});
                if(!fixture.held.await(10,TimeUnit.SECONDS))throw new AssertionError("Media fixture did not enter the held request");
                ui(runner,()->activity.startService(new Intent(activity,LegacyReceiverService.class).setAction(LegacyReceiverService.STOP)));
                held.get(3,TimeUnit.SECONDS);fixture.release.countDown();
                await(restarted::isClosed);idle(runner,service);
            }finally {fixture.release.countDown();sender.shutdownNow();if(!sender.awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("Native sender retained a worker");}
            ui(runner,()->activity.startService(new Intent(activity,LegacyReceiverService.class).setAction(LegacyReceiverService.START)));
            await(()-> {LegacyReceiverSession current=(LegacyReceiverSession)read(runner,service,"session");return current!=null && current.listeningPort()>0 && current!=restarted;});
        }
    }
    private static void fcup(Instrumentation runner,LegacyReceiverService service,Fixture fixture,int port,String base) throws Exception {
        try(Wire reverse=new Wire(port);Wire command=new Wire(port)) {
            reverse.socket.getOutputStream().write(("POST /reverse HTTP/1.1\r\nX-Apple-Session-ID: "+reverse.id+
                "\r\nConnection: Upgrade\r\nUpgrade: PTTH/1.0\r\nX-Apple-Purpose: event\r\nContent-Length: 0\r\n\r\n").getBytes("US-ASCII"));
            if(reverse.receive().code!=101)throw new AssertionError("Native reverse channel was not upgraded");
            command.play(base+"/master.m3u8",1);
            String master="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=250000,RESOLUTION=160x90\n"+base+"/variant.m3u8\n";
            String media=new String(fixture.mediaPlaylist,"UTF-8").replace("\nhls-","\n"+base+"/hls-");
            for(String location:new String[]{base+"/master.m3u8",base+"/variant.m3u8"}) {
                Response event=reverse.receive();
                if(!event.start.equals("POST /event HTTP/1.1"))throw new AssertionError("Missing FCUP reverse event: "+event.start);
                Matcher url=Pattern.compile("<key>FCUP_Response_URL</key>\\s*<string>([^<]+)</string>").matcher(event.body);
                if(!url.find() || !url.group(1).equals(location))throw new AssertionError("Unexpected FCUP playlist request: "+event.body);
                int requestId=(int)number(event.body,"FCUP_Response_RequestID");
                reverse.socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes("US-ASCII"));
                byte[] playlist=(location.endsWith("/master.m3u8")?master:media).getBytes("UTF-8");
                if(command.request("POST","/action",Wire.actionBody(location,requestId,playlist),"application/x-apple-binary-plist").code!=200)
                    throw new AssertionError("Native FCUP playlist response was rejected");
            }
            await(()-> {LegacyUrlPlayer.State value=state(runner,service);return value!=null && value.playing && value.width==160 && value.durationMs>=7900 && value.positionMs>=1000;});
            AtomicReference<String> snapshot=new AtomicReference<>();
            try {await(()-> {String info=command.request("GET","/playback-info","").body;snapshot.set(info);
                return ready(info) && number(info,"duration")>=7.9 && number(info,"position")>=1;});}
            catch(AssertionError failure) {throw new AssertionError("FCUP snapshot did not settle: "+snapshot.get(),failure);}
            command.request("POST","/rate?value=0","");await(()->!state(runner,service).playing);
            command.request("POST","/scrub?position=3","");await(()->Math.abs(state(runner,service).positionMs-3000)<100);
            command.request("POST","/stop","");idle(runner,service);
        }
    }
    private static final class Response {
        final int code;final String start,body;
        Response(String start,String body) {this.start=start;this.code=start.startsWith("HTTP/")?Integer.parseInt(start.split(" ")[1]):0;this.body=body;}
    }
    private static final class Wire implements Closeable {
        final Socket socket;final String id=UUID.randomUUID().toString();
        Wire(int port) throws Exception {socket=new Socket("127.0.0.1",port);socket.setSoTimeout(15000);}
        void play(String location,float start) throws Exception {
            Response response=request("POST","/play",playBody(location,start),"application/x-apple-binary-plist");
            if(response.code!=200)throw new AssertionError("Native play returned "+response.code);
        }
        Response request(String method,String path,String body) throws Exception {
            return request(method,path,body.getBytes("UTF-8"),"text/parameters");
        }
        Response request(String method,String path,byte[] bytes,String type) throws Exception {
            socket.getOutputStream().write((method+" "+path+" HTTP/1.1\r\nX-Apple-Session-ID: "+id+"\r\nContent-Type: "+type+"\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes("UTF-8"));socket.getOutputStream().write(bytes);
            return receive();
        }
        Response receive() throws Exception {
            ByteArrayOutputStream head=new ByteArrayOutputStream();String header="";
            while(head.size()<16384 && !header.endsWith("\r\n\r\n")) {int value=socket.getInputStream().read();if(value<0)throw new EOFException("Native response closed early");head.write(value);header=head.toString("US-ASCII");}
            int length=0;for(String line:header.split("\r\n"))if(line.toLowerCase(Locale.US).startsWith("content-length:"))length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
            if(length<0 || length>262144)throw new AssertionError("Unbounded native response");byte[] data=new byte[length];int offset=0;
            while(offset<length) {int count=socket.getInputStream().read(data,offset,length-offset);if(count<0)throw new EOFException();offset+=count;}
            return new Response(header.split("\r\n")[0],new String(data,"UTF-8"));
        }
        private static byte[] actionBody(String location,int requestId,byte[] data) throws IOException {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeBytes("bplist00");
            int[] offsets=new int[13];offsets[0]=bytes.size();out.writeByte(0xd2);out.write(new byte[]{1,2,3,4});
            for(int i=1;i<=3;i++) {offsets[i]=bytes.size();plistString(out,new String[]{"type","params","unhandledURLResponse"}[i-1]);}
            offsets[4]=bytes.size();out.writeByte(0xd4);out.write(new byte[]{5,6,7,8,9,10,11,12});
            String[] keys={"FCUP_Response_URL","FCUP_Response_Data","FCUP_Response_RequestID","FCUP_Response_StatusCode"};
            for(int i=0;i<4;i++) {offsets[5+i]=bytes.size();plistString(out,keys[i]);}
            offsets[9]=bytes.size();plistString(out,location);
            offsets[10]=bytes.size();out.writeByte(0x4f);out.writeByte(0x12);out.writeInt(data.length);out.write(data);
            offsets[11]=bytes.size();out.writeByte(0x12);out.writeInt(requestId);
            offsets[12]=bytes.size();out.writeByte(0x11);out.writeShort(200);
            int table=bytes.size();for(int offset:offsets)out.writeInt(offset);
            out.write(new byte[6]);out.writeByte(4);out.writeByte(1);out.writeLong(offsets.length);out.writeLong(0);out.writeLong(table);out.flush();return bytes.toByteArray();
        }
        private static void plistString(DataOutputStream out,String value) throws IOException {
            byte[] bytes=value.getBytes("US-ASCII");int length=bytes.length;
            if(length<15)out.writeByte(0x50|length);
            else {out.writeByte(0x5f);if(length<=255) {out.writeByte(0x10);out.writeByte(length);}else {out.writeByte(0x11);out.writeShort(length);}}
            out.write(bytes);
        }
        private static byte[] playBody(String location,float start) throws IOException {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeBytes("bplist00");
            int[] offsets=new int[7];offsets[0]=bytes.size();out.writeByte(0xd3);out.write(new byte[]{1,2,3,4,5,6});
            String[] strings={"uuid","Content-Location","Start-Position-Seconds",UUID.randomUUID().toString(),location};
            for(int i=0;i<strings.length;i++) {offsets[i+1]=bytes.size();plistString(out,strings[i]);}
            offsets[6]=bytes.size();out.writeByte(0x23);out.writeDouble(start);
            int table=bytes.size();for(int offset:offsets)out.writeInt(offset);
            out.write(new byte[6]);out.writeByte(4);out.writeByte(1);out.writeLong(7);out.writeLong(0);out.writeLong(table);out.flush();return bytes.toByteArray();
        }
        @Override public void close() throws IOException {socket.close();}
    }
    private static final class Fixture implements Closeable {
        final ServerSocket server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        final CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);final Thread worker;volatile boolean closed;
        final byte[] mediaPlaylist;
        Fixture(Instrumentation runner) throws Exception {
            Map<String,byte[]> assets=new HashMap<>();List<String> names=new ArrayList<>(Arrays.asList("sample.mp4","silence.m4a","stream.m3u8"));
            for(int i=0;i<4;i++)names.add(String.format(Locale.US,"hls-%02d.ts",i));
            for(String name:names)try(InputStream input=runner.getContext().getAssets().open("url_formats/"+name);ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                byte[] chunk=new byte[4096];int count;while((count=input.read(chunk))!=-1)bytes.write(chunk,0,count);assets.put("/"+name,bytes.toByteArray());
            }
            mediaPlaylist=assets.get("/stream.m3u8");
            assets.put("/hold.mp4",assets.get("/sample.mp4"));
            worker=new Thread(()-> {while(!closed)try(Socket peer=server.accept()) {
                peer.setSoTimeout(5000);ByteArrayOutputStream head=new ByteArrayOutputStream();String header="";
                while(head.size()<16384 && !header.endsWith("\r\n\r\n")) {int value=peer.getInputStream().read();if(value<0)break;head.write(value);header=head.toString("US-ASCII");}
                String[] first=header.split(" ");if(first.length<2)continue;byte[] data=assets.get(first[1]);
                if(first[1].equals("/hold.mp4")) {held.countDown();release.await(20,TimeUnit.SECONDS);}
                if(data==null) {peer.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes("US-ASCII"));continue;}
                Matcher range=Pattern.compile("(?im)^Range: bytes=(\\d+)-").matcher(header);int start=range.find()?Integer.parseInt(range.group(1)):0;
                if(start<0 || start>=data.length)start=0;
                String type=first[1].endsWith(".m3u8")?"application/vnd.apple.mpegurl":first[1].endsWith(".ts")?"video/mp2t":"video/mp4";
                String response=(start>0?"HTTP/1.1 206 Partial Content":"HTTP/1.1 200 OK")+"\r\nContent-Type: "+type+"\r\nContent-Length: "+(data.length-start)+"\r\nAccept-Ranges: bytes\r\nConnection: close\r\n";
                if(start>0)response+="Content-Range: bytes "+start+"-"+(data.length-1)+"/"+data.length+"\r\n";
                peer.getOutputStream().write((response+"\r\n").getBytes("US-ASCII"));peer.getOutputStream().write(data,start,data.length-start);
            }catch(Exception e) {if(closed)break;}},"native-url-fixture");worker.start();
        }
        public void close() throws IOException {closed=true;release.countDown();server.close();try {worker.join(6000);}catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IOException(e);}if(worker.isAlive())throw new IOException("Native fixture retained a worker");}
    }
}
