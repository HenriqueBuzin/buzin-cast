package io.buzincast.legacy;

import android.content.Context;
import io.buzincast.fireos3probe.LegacyUrlDownload;
import io.buzincast.fireos3probe.LegacyUrlRequest;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

/** Country gate and private storage binding for encoded URL downloads. */
final class LegacyDownloads {
    private final Context context;private final boolean enabled;
    private volatile LegacyUrlDownload engine;
    private final Runnable changed;
    private volatile boolean starting,cancelRequested,closed;
    LegacyDownloads(Context context,Runnable changed,boolean enabled) {
        this.context=context.getApplicationContext();this.enabled=enabled;this.changed=changed;
    }
    LegacyUrlDownload.State state() {return engine==null?null:engine.state();}
    boolean busy() {return starting || state()!=null && state().busy();}
    boolean active() {return busy() && !cancelRequested;}
    synchronized void start(LegacyUrlRequest request) {
        if(!enabled || closed || busy())return;
        if(request==null || request.localRecording)throw new IllegalArgumentException("Expected network media");
        starting=true;cancelRequested=false;
        if(engine==null)engine=new LegacyUrlDownload(context,state-> {starting=false;changed.run();});
        try {engine.start(request,new File(directory(),"Buzin-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8)+".mp4"));}
        catch(RuntimeException failure) {starting=false;throw failure;}
    }
    void cancel() {cancelRequested=true;if(engine!=null)engine.cancel();}
    synchronized void close() {closed=true;cancelRequested=true;if(engine!=null)engine.close();}
    File directory() {return new File(context.getFilesDir(),"downloads");}
    String message() {
        LegacyUrlDownload.State value=state();if(value==null)return "";
        if(starting)return context.getString(R.string.cd_download)+" · …";
        switch(value.phase) {
            case SAVED:return context.getString(R.string.download_saved,value.file.getName());
            case FAILED:return context.getString(R.string.download_failed);
            case CANCELLED:return context.getString(R.string.download_cancelled);
            case LOADING:case WRITING:return context.getString(R.string.cd_download)+" · "+(value.progress<0?"…":value.progress+"%");
            default:return "";
        }
    }
    File[] files() {
        if(!enabled)return new File[0];
        File[] files=directory().listFiles(file->file.isFile() && file.getName().endsWith(".mp4"));
        if(files==null)return new File[0];Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));return files;
    }
    File file(String name) throws IOException {
        if(!enabled || busy() || name==null || !name.matches("Buzin-[A-Za-z0-9-]+\\.mp4"))throw new IOException("Download is unavailable");
        File root=directory().getCanonicalFile(),file=new File(root,name).getCanonicalFile();
        if(!root.equals(file.getParentFile()) || !file.isFile())throw new IOException("Download is unavailable");return file;
    }
    void delete(String name) throws IOException {if(!file(name).delete())throw new IOException("Could not delete download");}
}
