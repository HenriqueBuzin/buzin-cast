package io.buzincast.legacy;

import android.content.Context;
import io.buzincast.fireos3probe.LegacyReceiverSession;
import io.buzincast.media.MatroskaRecorder;
import io.buzincast.media.MirrorPacket;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.UUID;

/** API17 platform binding for the shared production Matroska writer. */
final class LegacyMirrorCapture implements LegacyReceiverSession.Capture {
    static final int MAX_BYTES=8*1024*1024,MAX_PACKETS=120;
    static final class State {
        final boolean armed,recording,finishing;final String message;
        State(boolean armed,boolean recording,boolean finishing,String message) {
            this.armed=armed;this.recording=recording;this.finishing=finishing;this.message=message;
        }
    }
    private static final class Entry {
        final MirrorPacket packet;final int bytes;
        Entry(MirrorPacket packet,int bytes) {this.packet=packet;this.bytes=bytes;}
    }
    private static final class StorageFull extends IOException {}
    private final Context context;
    private final Runnable changed;
    private final boolean enabled;
    private final ArrayDeque<Entry> queue=new ArrayDeque<>();
    private int bytes;
    private volatile State state=new State(false,false,false,"");
    private Thread worker;
    private boolean accepting,closed;
    private String terminal;
    LegacyMirrorCapture(Context context,Runnable changed,boolean enabled) {
        this.context=context.getApplicationContext();this.changed=changed;this.enabled=enabled;
    }
    File directory() {
        // App-specific external storage still needs a broad write permission
        // before API19. Use private internal storage on the original Stick.
        File root=android.os.Build.VERSION.SDK_INT>=19?context.getExternalFilesDir(null):null;
        return new File(root==null?context.getFilesDir():root,"recordings");
    }
    State state() {return state;}
    @Override public synchronized boolean active() {return accepting;}
    synchronized void start() {
        if(!enabled || closed || worker!=null)return;
        File directory=directory();
        if((!directory.isDirectory() && !directory.mkdirs()) || directory.getUsableSpace()<128L*1024*1024) {
            state=new State(false,false,false,context.getString(R.string.capture_need_space));changed.run();return;
        }
        queue.clear();bytes=0;terminal=null;accepting=true;
        state=new State(true,false,false,context.getString(R.string.capture_waiting_keyframe));
        File file=new File(directory,"Buzin-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8)+".mkv");
        worker=new Thread(()->write(file),"buzin-legacy-recorder");worker.start();changed.run();
    }
    private void write(File file) {
        MatroskaRecorder recorder=new MatroskaRecorder(file);String failure=null;long lastSpace=0;
        try {
            while(true) {
                Entry entry;
                synchronized(this) {
                    while(queue.isEmpty() && accepting)wait(250);
                    if(queue.isEmpty())break;
                    entry=queue.removeFirst();bytes-=entry.bytes;
                }
                recorder.write(entry.packet);
                if(recorder.getVideoFrames()>0 && !state.recording) {
                    synchronized(this) {state=new State(accepting,true,!accepting,context.getString(R.string.capture_recording_file,file.getName()));}
                    changed.run();
                }
                if(System.nanoTime()-lastSpace>3_000_000_000L) {
                    lastSpace=System.nanoTime();
                    if(file.getParentFile().getUsableSpace()<32L*1024*1024)throw new StorageFull();
                }
            }
        } catch(Exception error) {failure=context.getString(error instanceof StorageFull?R.string.capture_space_exhausted:R.string.capture_failed);}
        finally {
            try {recorder.close();}catch(Exception error) {failure=context.getString(R.string.capture_failed);}
            boolean saved=recorder.getVideoFrames()>0;
            if(!saved)file.delete();
            synchronized(this) {
                accepting=false;queue.clear();bytes=0;worker=null;
                String message=saved?context.getString(R.string.capture_saved_file,file.getName()):context.getString(R.string.capture_no_keyframe);
                state=new State(false,false,false,failure!=null?failure:terminal!=null?terminal:message);notifyAll();
            }
            changed.run();
        }
    }
    private synchronized boolean room(int size) {
        if(!accepting)return false;
        if(size<0 || size>MAX_BYTES-bytes || queue.size()>=MAX_PACKETS) {
            terminal=context.getString(R.string.capture_slow_storage);stop();return false;
        }
        return true;
    }
    private synchronized void offer(MirrorPacket packet,int size) {
        if(!room(size))return;queue.addLast(new Entry(packet,size));bytes+=size;notifyAll();
    }
    @Override public void video(byte[] annexB,long pts,int width,int height) {
        // The runtime owns a fresh prefixed buffer per invocation; no mutation follows.
        offer(new MirrorPacket.Video(annexB,pts,width,height,false),annexB.length);
    }
    @Override public synchronized void audio(short[] pcm,int offset,int count,long pts) {
        if(!room(count*2))return;
        byte[] data=new byte[count*2];for(int i=0;i<count;i++) {short value=pcm[offset+i];data[i*2]=(byte)value;data[i*2+1]=(byte)(value>>8);}
        offer(new MirrorPacket.Audio(data,pts,44100,2),data.length);
    }
    synchronized void stop() {
        accepting=false;
        if(worker!=null)state=new State(false,state.recording,true,context.getString(R.string.capture_finishing));
        notifyAll();changed.run();
    }
    @Override public void ended() {stop();}
    synchronized void close() {closed=true;stop();}
    synchronized boolean busy() {return worker!=null;}
    File[] files() {
        if(!enabled)return new File[0];
        File[] files=directory().listFiles(file->file.isFile() && file.getName().endsWith(".mkv"));
        if(files==null)return new File[0];
        Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));return files;
    }
    synchronized File file(String name) throws IOException {
        if(!enabled || busy() || name==null || !name.matches("Buzin-[A-Za-z0-9-]+\\.mkv"))throw new IOException("Recording is unavailable");
        File root=directory().getCanonicalFile(),file=new File(root,name).getCanonicalFile();
        if(!root.equals(file.getParentFile()) || !file.isFile())throw new IOException("Recording is unavailable");return file;
    }
    synchronized void delete(String name) throws IOException {if(!file(name).delete())throw new IOException("Could not delete recording");changed.run();}
}
