package io.buzincast.fireos3probe;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Real HTTP ranges/headers and platform playback of a synthetic MP4 fixture. */
final class UrlSelfTest {
    private interface Check {boolean ready();}
    private static void await(LegacyUrlPlayer player,Check check) throws Exception {
        await(player,-1,check);
    }
    private static void await(LegacyUrlPlayer player,long owner,Check check) throws Exception {
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until) {
            if(owner>=0 && player.state().generation>owner)throw new IOException("Media request was superseded");
            if(owner<0 || player.state().generation==owner) {
                if(check.ready())return;
                if(player.state().phase==LegacyUrlPlayer.Phase.FAILED)throw player.state().failure;
            }
            Thread.sleep(20);
        }
        throw new IOException("URL player did not settle at "+new Throwable().getStackTrace()[1]+"; phase="+player.state().phase);
    }
    static void run(Context context,Surface initial,SurfaceTexture texture,AtomicInteger frames) throws Exception {
        byte[] sample;
        try(InputStream input=context.getAssets().open("url_sample.mp4")) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int count;
            while((count=input.read(chunk))!=-1)bytes.write(chunk,0,count);sample=bytes.toByteArray();
        }
        AtomicInteger retired=new AtomicInteger();LegacyUrlPlayer player=new LegacyUrlPlayer(context,state->{});
        try(Fixture server=new Fixture(sample)) {
            player.surface(initial,retired::incrementAndGet);
            String location="http://127.0.0.1:"+server.socket.getLocalPort()+"/sample.mp4";
            LegacyUrlRequest request=new LegacyUrlRequest(location,1000,Collections.singletonMap("X-Legacy-Fixture","fixture"));
            long firstLoad=player.load(request,false);
            await(player,firstLoad,()->player.state().phase==LegacyUrlPlayer.Phase.READY && !player.state().buffering && player.state().positionMs>=900 && player.state().durationMs>=3900 && player.state().width==160 && player.state().height==90);
            if(player.state().playing)throw new IOException("Prepared URL started despite paused request");
            player.playing(false);player.playing(false);Thread.sleep(100);
            if(player.state().phase!=LegacyUrlPlayer.Phase.READY)throw new IOException("Pausing an unstarted source broke the player");
            int before=frames.get();player.playing(true);
            await(player,()->player.state().playing && player.state().positionMs>=200 && frames.get()>before);
            player.playing(false);await(player,()->!player.state().playing);
            long paused=player.state().positionMs;Thread.sleep(300);
            if(Math.abs(player.state().positionMs-paused)>60)throw new IOException("Paused URL position kept advancing");
            player.seek(0);await(player,()->player.state().positionMs<60);
            player.playing(true);await(player,()->player.state().playing && player.state().positionMs>=200);
            player.surface(null,null);await(player,()->!initial.isValid());
            Surface replacement=new Surface(texture);int updated=frames.get();player.surface(replacement,retired::incrementAndGet);
            await(player,()->frames.get()>updated);
            await(player,()->player.state().phase==LegacyUrlPlayer.Phase.ENDED);
            if(player.state().playing || retired.get()!=1)throw new IOException("Completion or Surface retirement was incorrect");
            int completedFrames=frames.get();player.playing(true);
            await(player,()->player.state().phase==LegacyUrlPlayer.Phase.READY && player.state().playing && frames.get()>completedFrames);
            long generation=player.state().generation;
            player.load(new LegacyUrlRequest(location+"/missing",0,request.headers),true);
            long until=SystemClock.elapsedRealtime()+10000;
            while(player.state().phase!=LegacyUrlPlayer.Phase.FAILED && SystemClock.elapsedRealtime()<until)Thread.sleep(20);
            if(player.state().phase!=LegacyUrlPlayer.Phase.FAILED)throw new IOException("HTTP failure was not reported");
            long recovered=player.load(request,false);await(player,recovered,()->player.state().phase==LegacyUrlPlayer.Phase.READY);
            if(player.state().generation<=generation || player.state().failure!=null)throw new IOException("URL error recovery reused stale state");
            player.load(new LegacyUrlRequest(location+"/missing",0,request.headers),true);long replacementLoad=player.load(request,false);
            await(player,replacementLoad,()->player.state().phase==LegacyUrlPlayer.Phase.READY);Thread.sleep(200);
            if(player.state().phase!=LegacyUrlPlayer.Phase.READY)throw new IOException("Superseded source changed the replacement player");
            player.stop();await(player,()->player.state().phase==LegacyUrlPlayer.Phase.IDLE);
            if(server.requests.get()<3)throw new IOException("Fixture did not receive the real media requests");
            player.close();if(!player.awaitClosed(5000))throw new IOException("URL player worker did not close");
            if(retired.get()!=2 || replacement.isValid() || player.state().failure!=null)throw new IOException("URL player retained its Surface");
        } finally {player.close();if(!player.awaitClosed(5000))throw new IOException("URL cleanup did not finish");}
    }
    private static final class Fixture implements java.io.Closeable {
        final ServerSocket socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        final AtomicInteger requests=new AtomicInteger();final Thread worker;
        volatile boolean closed;
        Fixture(byte[] sample) throws IOException {
            worker=new Thread(()-> {
                while(!closed)try(Socket client=socket.accept()) {
                    client.setSoTimeout(3000);ByteArrayOutputStream raw=new ByteArrayOutputStream();String header="";
                    while(raw.size()<8192 && !header.endsWith("\r\n\r\n")) {
                        int value=client.getInputStream().read();if(value<0)break;raw.write(value);header=raw.toString("US-ASCII");
                    }
                    if(header.isEmpty())continue;requests.incrementAndGet();
                    String[] first=header.split("\r\n")[0].split(" ");
                    boolean found=first.length>=2 && first[1].equals("/sample.mp4") && header.toLowerCase(Locale.US).contains("\r\nx-legacy-fixture: fixture\r\n");
                    int start=0,end=sample.length-1;boolean range=false;
                    Matcher bytes=Pattern.compile("(?im)^Range: bytes=(\\d+)-(\\d*)\\r?$").matcher(header);
                    if(bytes.find()) {range=true;start=Integer.parseInt(bytes.group(1));if(!bytes.group(2).isEmpty())end=Math.min(end,Integer.parseInt(bytes.group(2)));}
                    if(!found || start<0 || start>end) {
                        client.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));continue;
                    }
                    String response="HTTP/1.1 "+(range?"206 Partial Content":"200 OK")+"\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: "+(end-start+1)+"\r\n"+
                        (range?"Content-Range: bytes "+start+"-"+end+"/"+sample.length+"\r\n":"")+"Connection: close\r\n\r\n";
                    client.getOutputStream().write(response.getBytes("US-ASCII"));client.getOutputStream().write(sample,start,end-start+1);
                } catch(Exception ignored) {if(closed)break;}
            },"legacy-url-http-fixture");worker.start();
        }
        @Override public void close() throws IOException {
            closed=true;socket.close();try {worker.join(4000);}catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IOException(error);}
            if(worker.isAlive())throw new IOException("HTTP fixture did not close");
        }
    }
}
