package io.buzincast.fireos3probe;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Synthetic HLS, DASH, silent AAC, real playback clocks and HTTP-only child resources. */
final class UrlFormatsSelfTest {
    private interface Check {boolean ready();}
    private static void await(LegacyUrlPlayer player,long owner,Check check) throws Exception {
        long until=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<until) {
            LegacyUrlPlayer.State state=player.state();
            if(state.generation==owner) {
                if(check.ready())return;
                if(state.phase==LegacyUrlPlayer.Phase.FAILED)throw state.failure;
            }
            Thread.sleep(20);
        }
        throw new IOException("URL format check did not settle at "+new Throwable().getStackTrace()[1]+"; phase="+player.state().phase);
    }
    private static byte[] asset(Context context,String name) throws IOException {
        try(InputStream input=context.getAssets().open("url_formats/"+name);ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] chunk=new byte[4096];int count;while((count=input.read(chunk))!=-1)bytes.write(chunk,0,count);return bytes.toByteArray();
        }
    }
    static void run(Context context,SurfaceTexture texture,AtomicInteger frames) throws Exception {
        Map<String,byte[]> resources=new HashMap<>();JSONObject manifest=new JSONObject(new String(asset(context,"fixtures.json"),"UTF-8")).getJSONObject("sha256");
        for(java.util.Iterator<String> keys=manifest.keys();keys.hasNext();) {
            String name=keys.next();byte[] data=asset(context,name);
            StringBuilder hash=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(data))hash.append(String.format(Locale.US,"%02x",value & 255));
            if(!manifest.getString(name).equals(hash.toString()))throw new IOException("Synthetic URL fixture checksum mismatch");
            resources.put("/"+name,data);
        }
        resources.put("/opaque-hls",resources.get("/stream.m3u8"));resources.put("/opaque-dash",resources.get("/stream.mpd"));
        resources.put("/opaque-mp4",resources.get("/sample.mp4"));
        File local=new File(context.getFilesDir(),"url-child-fixture.ts");
        try(FileOutputStream output=new FileOutputStream(local)) {output.write(resources.get("/hls-00.ts"));}
        resources.put("/local-child.m3u8",("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\n"+Uri.fromFile(local)+"\n#EXT-X-ENDLIST\n").getBytes("UTF-8"));
        LegacyUrlPlayer player=new LegacyUrlPlayer(context,state->{});Surface surface=new Surface(texture);AtomicInteger retired=new AtomicInteger();
        try(Fixture server=new Fixture(resources)) {
            player.surface(surface,retired::incrementAndGet);
            Map<String,String> headers=Collections.singletonMap("X-Legacy-Fixture","fixture");
            String base="http://127.0.0.1:"+server.socket.getLocalPort();
            String[] paths={"/opaque-mp4","/opaque-hls","/opaque-dash"},types={"video/mp4; codecs=avc1","application/x-mpegURL","application/dash+xml"};
            for(int i=0;i<paths.length;i++) {
                long owner=player.load(new LegacyUrlRequest(base+paths[i],0,headers,types[i]),false);
                await(player,owner,()->player.state().phase==LegacyUrlPlayer.Phase.READY && player.state().durationMs>=7900 && player.state().width==160);
                if(player.state().playing)throw new IOException("Adaptive media ignored paused preparation");
                player.seek(3000);int before=frames.get();player.playing(true);
                await(player,owner,()->player.state().playing && player.state().positionMs>=3100 && frames.get()>before);
                player.playing(false);await(player,owner,()->!player.state().playing);
            }
            for(String name:new String[]{"/opaque-hls","/hls-00.ts","/opaque-dash","/dash-init.mp4","/dash-1.m4s"})
                if(server.count(name)==0)throw new IOException("Adaptive player did not request "+name);
            long audio=player.load(new LegacyUrlRequest(base+"/silence.m4a",0,headers,"audio/mp4"),false);
            await(player,audio,()->player.state().phase==LegacyUrlPlayer.Phase.READY && player.state().durationMs>=7900);
            if(player.state().width!=0 || player.state().height!=0)throw new IOException("Audio retained the previous video dimensions");
            double slow=clockRate(player,audio,.5f),fast=clockRate(player,audio,2);
            if(slow>.9 || slow<.25 || fast<1.5 || fast>3 || fast<slow*2.5)throw new IOException("Audio clock did not follow playback speed: "+slow+" / "+fast);
            android.util.Log.i("BuzinLegacyUrl","Silent AAC clock ratios: "+slow+" / "+fast);
            long rejected=player.load(new LegacyUrlRequest(base+"/local-child.m3u8",0,headers,"application/x-mpegURL"),true);
            long until=SystemClock.elapsedRealtime()+15000;
            while(SystemClock.elapsedRealtime()<until) {
                LegacyUrlPlayer.State state=player.state();
                if(state.generation==rejected) {
                    if(state.phase==LegacyUrlPlayer.Phase.READY)throw new IOException("HTTP manifest accessed a local media file");
                    if(state.phase==LegacyUrlPlayer.Phase.FAILED)break;
                }
                Thread.sleep(20);
            }
            if(player.state().generation!=rejected || player.state().phase!=LegacyUrlPlayer.Phase.FAILED)throw new IOException("Local child URI was not rejected");
            if(server.invalidHeaders.get()!=0)throw new IOException("A media request lost its custom HTTP header");
        }finally {
            player.close();if(!player.awaitClosed(5000))throw new IOException("Adaptive player worker did not close");
            if(surface.isValid() || retired.get()!=1)throw new IOException("Adaptive player retained its Surface");
            if(!local.delete() && local.exists())throw new IOException("Could not remove local child fixture");
        }
    }
    private static double clockRate(LegacyUrlPlayer player,long owner,float speed) throws Exception {
        player.seek(500);player.speed(speed);player.playing(true);
        await(player,owner,()->player.state().playing && player.state().speed==speed && player.state().positionMs>=600);
        Thread.sleep(300);long from=player.state().positionMs,start=SystemClock.elapsedRealtime();
        Thread.sleep(1000);player.playing(false);await(player,owner,()->!player.state().playing);
        return (player.state().positionMs-from)/(double)(SystemClock.elapsedRealtime()-start);
    }
    private static final class Fixture implements java.io.Closeable {
        final ServerSocket socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        final AtomicInteger invalidHeaders=new AtomicInteger();
        final Map<String,AtomicInteger> requests=new ConcurrentHashMap<>();
        final Thread worker;volatile boolean closed;
        Fixture(Map<String,byte[]> resources) throws IOException {
            worker=new Thread(()-> {
                while(!closed)try(Socket client=socket.accept()) {
                    client.setSoTimeout(3000);ByteArrayOutputStream raw=new ByteArrayOutputStream();String header="";
                    while(raw.size()<8192 && !header.endsWith("\r\n\r\n")) {int value=client.getInputStream().read();if(value<0)break;raw.write(value);header=raw.toString("US-ASCII");}
                    if(header.isEmpty())continue;
                    String[] first=header.split("\r\n")[0].split(" ");String path=first.length>1?first[1]:"";
                    requests.putIfAbsent(path,new AtomicInteger());requests.get(path).incrementAndGet();
                    byte[] data=resources.get(path);
                    boolean authorized=header.toLowerCase(Locale.US).contains("\r\nx-legacy-fixture: fixture\r\n");
                    if(!authorized)invalidHeaders.incrementAndGet();
                    if(data==null || !authorized) {client.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));continue;}
                    int start=0,end=data.length-1;boolean range=false;
                    Matcher bytes=Pattern.compile("(?im)^Range: bytes=(\\d+)-(\\d*)\\r?$").matcher(header);
                    if(bytes.find()) {range=true;start=Integer.parseInt(bytes.group(1));if(!bytes.group(2).isEmpty())end=Math.min(end,Integer.parseInt(bytes.group(2)));}
                    if(start<0 || start>end) {client.getOutputStream().write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));continue;}
                    String type=path.equals("/opaque-hls") || path.endsWith(".m3u8")?"application/x-mpegURL":
                        path.equals("/opaque-dash") || path.endsWith(".mpd")?"application/dash+xml":path.endsWith(".ts")?"video/mp2t":path.endsWith(".m4a")?"audio/mp4":"video/mp4";
                    String response="HTTP/1.1 "+(range?"206 Partial Content":"200 OK")+"\r\nContent-Type: "+type+"\r\nAccept-Ranges: bytes\r\nContent-Length: "+(end-start+1)+"\r\n"+
                        (range?"Content-Range: bytes "+start+"-"+end+"/"+data.length+"\r\n":"")+"Connection: close\r\n\r\n";
                    client.getOutputStream().write(response.getBytes("US-ASCII"));client.getOutputStream().write(data,start,end-start+1);
                }catch(Exception error) {if(closed)break;}
            },"legacy-adaptive-http-fixture");worker.start();
        }
        int count(String path) {AtomicInteger value=requests.get(path);return value==null?0:value.get();}
        @Override public void close() throws IOException {
            closed=true;socket.close();try {worker.join(4000);}catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IOException(error);}
            if(worker.isAlive())throw new IOException("Adaptive HTTP fixture did not close");
        }
    }
}
