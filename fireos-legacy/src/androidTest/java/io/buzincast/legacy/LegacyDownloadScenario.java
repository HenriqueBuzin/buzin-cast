package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import io.buzincast.fireos3probe.LegacyUrlDownload;
import io.buzincast.fireos3probe.LegacyUrlRequest;
import io.buzincast.fireos3probe.LegacyUrlPlayer;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.*;

/** Synthetic HTTP media -> API17 MP4 export -> local playback and library. */
final class LegacyDownloadScenario {
    interface Check {boolean ready() throws Exception;}
    static void await(Check check) throws Exception {
        long end=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<end) {if(check.ready())return;SystemClock.sleep(20);}
        throw new AssertionError("Download did not settle at "+new Throwable().getStackTrace()[1]);
    }
    static Object field(Object owner,String name) throws Exception {java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    static void ui(Instrumentation runner,Runnable action) {
        AtomicReference<Throwable> error=new AtomicReference<>();runner.runOnMainSync(()-> {try {action.run();}catch(Throwable e) {error.set(e);}});
        if(error.get()!=null)throw new AssertionError(error.get());
    }
    static void click(Activity activity,String tag) {LegacyRecordingScenario.click(activity,tag);}
    static LegacyUrlPlayer.State player(Instrumentation runner,LegacyReceiverService service) {
        AtomicReference<LegacyUrlPlayer.State> state=new AtomicReference<>();ui(runner,()->state.set(service.urlState()));return state.get();
    }
    static void terminal(LegacyDownloads downloads,LegacyUrlDownload.Phase phase,LegacyUrlDownload.State previous) throws Exception {
        await(()-> {
            LegacyUrlDownload.State value=downloads.state();
            if(value==previous || downloads.busy())return false;
            if(value.phase!=phase)throw new AssertionError("Download ended as "+value.phase+": "+value.failure);
            return true;
        });
    }
    static void check(Instrumentation runner,Activity activity) throws Exception {
        if(BuildConfig.VIDEO_DOWNLOAD_ENABLED)LegacyPcmScenario.check();
        LegacyReceiverService service=(LegacyReceiverService)field(activity,"service");LegacyDownloads downloads=service.downloads();
        try(Fixture fixture=new Fixture(runner)) {
            Map<String,String> headers=Collections.singletonMap("X-Download-Fixture","active");
            String base="http://127.0.0.1:"+fixture.server.getLocalPort();
            String[] names={"movie.mp4","movie.m3u8","movie.mpd","two-periods.mpd","bframes.mp4","repaired-bframes.mp4","vp8-vorbis.webm","avc-vorbis.mkv","vp8-aac.mkv","avc-vorbis-96k.mkv","avc-vorbis-51.mkv"};
            for(int index=0;index<names.length;index++) {
                String name=names[index];LegacyUrlRequest request=new LegacyUrlRequest(base+"/"+name,2000,headers);
                android.util.Log.i("LegacyDownloadScenario","Checking "+name);
                // The API17 x86 OMX decoder only supports AVC Baseline. Export
                // must still preserve Main-profile B frames without decoding them.
                if(index<4) {
                    ui(runner,()->service.loadUrl(request));
                    await(()-> {LegacyUrlPlayer.State value=player(runner,service);return value!=null && value.playing && value.width==160;});
                }
                if(!BuildConfig.VIDEO_DOWNLOAD_ENABLED) {
                    if(activity.findViewById(android.R.id.content).findViewWithTag("url-download")!=null)throw new AssertionError("Restricted edition exposed download");
                    ui(runner,service::download);downloads.start(request);
                    if(downloads.busy() || downloads.state()!=null || downloads.files().length!=0)throw new AssertionError("Restricted download gate failed");
                    ui(runner,service::stopUrl);return;
                }
                LegacyUrlDownload.State previous=downloads.state();
                if(index>=4)downloads.start(request);else ui(runner,()->click(activity,"url-download"));
                terminal(downloads,LegacyUrlDownload.Phase.SAVED,previous);
                File saved=downloads.state().file;
                try(InputStream input=new FileInputStream(saved);ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                    LegacyRecordingScenario.copy(input,bytes);
                    if(index==4)fixture.assets.put("/repaired-bframes.mp4",bytes.toByteArray());
                    try(OutputStream out=new FileOutputStream(new File(activity.getFilesDir(),"legacy-download-"+index+".b64"))) {
                        out.write(android.util.Base64.encode(bytes.toByteArray(),android.util.Base64.NO_WRAP));
                    }
                }
                // Stagefright on Android 4.2 omits tracks using signed CTTS.
                // Media3 parses this standard metadata on the round trip above;
                // FFmpeg independently validates and decodes both B-frame exports.
                if(index<4 || index>=6)verify(saved,index==3?15900:7800);
                android.net.Uri uri=androidx.core.content.FileProvider.getUriForFile(activity,activity.getPackageName()+".recordings",downloads.file(saved.getName()));
                try(InputStream input=activity.getContentResolver().openInputStream(uri)) {
                    byte[] prefix=new byte[8];if(input.read(prefix)!=8 || !"ftyp".equals(new String(prefix,4,4,"US-ASCII")))throw new AssertionError("Export URI did not open an MP4");
                }
                if(index==4 || index==5)continue; // Independently decode B-frame artifacts on the host.
                ui(runner,()-> {try {service.playDownload(saved.getName());}catch(IOException e) {throw new AssertionError(e);}});
                await(()-> {LegacyUrlPlayer.State value=player(runner,service);return value!=null && value.playing && value.width==160 && value.durationMs>=(name.equals("two-periods.mpd")?15900:7900);});
                AtomicReference<Boolean> allowed=new AtomicReference<>();ui(runner,()->allowed.set(service.canDownload()));
                if(allowed.get())throw new AssertionError("Local files were offered for network download");
            }
            int complete=downloads.files().length;
            LegacyUrlDownload.State previous=downloads.state();
            downloads.start(new LegacyUrlRequest(base+"/bad.m3u8",0,headers));terminal(downloads,LegacyUrlDownload.Phase.FAILED,previous);
            if(downloads.files().length!=complete || parts(downloads)!=0)throw new AssertionError("Failed export left a visible/partial file");
            previous=downloads.state();downloads.start(new LegacyUrlRequest(base+"/slow.mp4",0,headers));
            if(!fixture.held.await(10,TimeUnit.SECONDS))throw new AssertionError("Slow source was not opened");
            ui(runner,()-> {activity.onBackPressed();click(activity,"nav-tools");click(activity,"download-cancel");});
            terminal(downloads,LegacyUrlDownload.Phase.CANCELLED,previous);fixture.release.countDown();
            if(parts(downloads)!=0 || downloads.files().length!=complete)throw new AssertionError("Cancelled export left files");
            try {downloads.file("../outside.mp4");throw new AssertionError("Download traversal was accepted");}catch(IOException expected) { }
            File first=downloads.files()[0];
            ui(runner,()-> {click(activity,"download-refresh");click(activity,"download-delete:"+first.getName());click(activity,"download-delete-confirm");});
            if(downloads.files().length!=complete-1)throw new AssertionError("Confirmed download deletion failed");
            preserveExisting(runner,activity,new LegacyUrlRequest(base+"/movie.mp4",0,headers));
            conversionCancellation(activity,new LegacyUrlRequest(base+"/vp8-vorbis.webm",0,headers));
        }
    }
    static int parts(LegacyDownloads downloads) {String[] parts=downloads.directory().list((dir,name)->name.endsWith(".part"));return parts==null?0:parts.length;}
    static void verify(File file,long minimumMs) throws Exception {
        MediaExtractor extractor=new MediaExtractor();
        try {
            extractor.setDataSource(file.getPath());boolean video=false,audio=false;long duration=0;
            for(int i=0;i<extractor.getTrackCount();i++) {
                MediaFormat format=extractor.getTrackFormat(i);String mime=format.getString(MediaFormat.KEY_MIME);
                video|="video/avc".equals(mime);audio|="audio/mp4a-latm".equals(mime);
                if(format.containsKey(MediaFormat.KEY_DURATION))duration=Math.max(duration,format.getLong(MediaFormat.KEY_DURATION)/1000);
            }
            if(!video || !audio || duration<minimumMs)throw new AssertionError("Exported MP4 tracks/duration invalid: video="+video+", audio="+audio+", duration="+duration);
        }finally {extractor.release();}
    }
    static void preserveExisting(Instrumentation runner,Activity activity,LegacyUrlRequest request) throws Exception {
        AtomicReference<LegacyUrlDownload.State> state=new AtomicReference<>();LegacyUrlDownload engine=new LegacyUrlDownload(activity,state::set);
        File existing=new File(activity.getCacheDir(),"existing-download.mp4"),part=new File(activity.getCacheDir(),"existing-part.mp4.part");
        byte[] marker="existing".getBytes("US-ASCII");
        try {
            try(OutputStream output=new FileOutputStream(existing)) {output.write(marker);}
            engine.start(request,existing);await(()->state.get()!=null && state.get().phase==LegacyUrlDownload.Phase.FAILED);
            if(existing.length()!=marker.length)throw new AssertionError("Existing destination was overwritten");
            try(OutputStream output=new FileOutputStream(part)) {output.write(marker);}
            LegacyUrlDownload.State previous=state.get();engine.start(request,new File(activity.getCacheDir(),"existing-part.mp4"));
            await(()->state.get()!=previous && state.get().phase==LegacyUrlDownload.Phase.FAILED);
            if(part.length()!=marker.length)throw new AssertionError("Existing partial file was removed");
        }finally {engine.close();existing.delete();part.delete();}
        Thread worker=(Thread)field(engine,"thread");await(()->!worker.isAlive());
    }
    static void conversionCancellation(Activity activity,LegacyUrlRequest request) throws Exception {
        AtomicReference<LegacyUrlDownload.State> state=new AtomicReference<>();LegacyUrlDownload engine=new LegacyUrlDownload(activity,state::set);
        android.os.Handler worker=(android.os.Handler)field(engine,"worker");
        List<android.media.MediaCodec> codecs=new ArrayList<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        File output=new File(activity.getCacheDir(),"cancel-conversion.mp4"),part=new File(output.getPath()+".part");
        Runnable probe=new Runnable() {public void run() {
            try {
                Object job=field(engine,"job");
                if(job!=null && ((Number)field(job,"samples")).longValue()>0) {
                    for(Object binding:(List<?>)field(job,"bindings")) {
                        Object converter=field(binding,"transcoder");if(converter==null)continue;
                        codecs.add((android.media.MediaCodec)field(converter,"decoder"));codecs.add((android.media.MediaCodec)field(converter,"encoder"));
                    }
                    if(codecs.size()!=4)throw new AssertionError("Cancellation did not reach both active conversion tracks");
                    engine.cancel();return;
                }
                worker.postDelayed(this,10);
            }catch(Throwable error) {failure.set(error);}
        }};
        try {
            output.delete();part.delete();engine.start(request,output);worker.post(probe);
            await(()-> {if(failure.get()!=null)throw new AssertionError("Conversion cancellation probe failed",failure.get());return state.get()!=null && state.get().phase==LegacyUrlDownload.Phase.CANCELLED;});
            if(output.exists() || part.exists())throw new AssertionError("Cancelled conversion left an output file");
            for(android.media.MediaCodec codec:codecs) {
                try {codec.getInputBuffers();throw new AssertionError("Cancelled conversion retained a usable codec");}catch(IllegalStateException released) { }
            }
            LegacyUrlDownload.State previous=state.get();engine.start(request,output);
            await(()-> {LegacyUrlDownload.State current=state.get();if(current==previous || current.busy())return false;if(current.phase!=LegacyUrlDownload.Phase.SAVED)throw new AssertionError("Conversion did not restart: "+current.phase+" "+current.failure);return true;});
            verify(output,7800);
        }finally {worker.removeCallbacks(probe);engine.close();}
        await(()->!((Thread)field(engine,"thread")).isAlive());output.delete();part.delete();
    }
    static final class Fixture implements Closeable {
        final ServerSocket server=new ServerSocket(0,12,InetAddress.getByName("127.0.0.1"));
        final ExecutorService clients=Executors.newFixedThreadPool(4);final Thread acceptor;volatile boolean closed;
        final CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
        final ConcurrentMap<String,byte[]> assets=new ConcurrentHashMap<>();
        Fixture(Instrumentation runner) throws Exception {
            for(String name:runner.getContext().getAssets().list("download_formats"))try(InputStream input=runner.getContext().getAssets().open("download_formats/"+name);ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                LegacyRecordingScenario.copy(input,bytes);assets.put("/"+name,bytes.toByteArray());
            }
            assets.put("/bad.m3u8","#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nfile:///private/forbidden.ts\n#EXT-X-ENDLIST\n".getBytes("UTF-8"));
            assets.put("/slow.mp4",assets.get("/movie.mp4"));
            acceptor=new Thread(()-> {while(!closed)try {Socket peer=server.accept();clients.execute(()->serve(peer,assets));}catch(IOException e) {if(closed)break;}},"download-fixture");acceptor.start();
        }
        void serve(Socket peer,Map<String,byte[]> assets) {
            try(Socket socket=peer) {
                socket.setSoTimeout(5000);ByteArrayOutputStream bytes=new ByteArrayOutputStream();String header="";
                while(bytes.size()<16384 && !header.endsWith("\r\n\r\n")) {int value=socket.getInputStream().read();if(value<0)return;bytes.write(value);header=bytes.toString("US-ASCII");}
                String[] first=header.split(" ");if(first.length<2)return;byte[] data=assets.get(first[1]);
                if(!header.toLowerCase(Locale.US).contains("x-download-fixture: active")) {socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes("US-ASCII"));return;}
                if(data==null) {socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes("US-ASCII"));return;}
                Matcher range=Pattern.compile("(?im)^Range: bytes=(\\d+)-").matcher(header);int start=range.find()?Integer.parseInt(range.group(1)):0;
                if(start>=data.length)start=0;String type=first[1].endsWith(".m3u8")?"application/vnd.apple.mpegurl":first[1].endsWith(".mpd")?"application/dash+xml":first[1].endsWith(".ts")?"video/mp2t":"video/mp4";
                String response=(start>0?"HTTP/1.1 206 Partial Content":"HTTP/1.1 200 OK")+"\r\nContent-Type: "+type+"\r\nContent-Length: "+(data.length-start)+"\r\nConnection: close\r\nAccept-Ranges: bytes\r\n";
                if(start>0)response+="Content-Range: bytes "+start+"-"+(data.length-1)+"/"+data.length+"\r\n";
                socket.getOutputStream().write((response+"\r\n").getBytes("US-ASCII"));
                if(first[1].equals("/slow.mp4")) {socket.getOutputStream().write(data,start,Math.min(64,data.length-start));held.countDown();release.await(15,TimeUnit.SECONDS);return;}
                socket.getOutputStream().write(data,start,data.length-start);
            }catch(Exception ignored) { }
        }
        public void close() throws IOException {
            closed=true;release.countDown();server.close();clients.shutdownNow();
            try {acceptor.join(2000);if(!clients.awaitTermination(7,TimeUnit.SECONDS))throw new IOException("Fixture retained HTTP clients");}
            catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IOException(e);}
        }
    }
}
