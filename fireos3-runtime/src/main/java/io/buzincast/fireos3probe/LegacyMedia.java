package io.buzincast.fireos3probe;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Iterator;

/** Coalesced metadata/artwork work, independently owned by one native session. */
public final class LegacyMedia implements java.io.Closeable {
    static final int MAX_ART_BYTES=4*1024*1024;
    private static final int METADATA=1,ART=2,PROGRESS=3,PLAYING=4;
    public static final class State {
        public final LegacyTrack track;
        public final Bitmap artwork;
        public final long positionMs,durationMs;
        public final boolean active,playing;
        public final Exception failure;
        private State(LegacyTrack track,Bitmap artwork,long position,long duration,boolean playing,Exception failure) {
            this.track=track;this.artwork=artwork;positionMs=position;durationMs=duration;this.playing=playing;this.failure=failure;
            active=artwork!=null || duration>0 || !track.title.isEmpty() || !track.artist.isEmpty() || !track.album.isEmpty();
        }
    }
    public static State inactive() {return new State(LegacyTrack.empty(),null,0,0,false,null);}
    private static final class Event {
        final int type;final byte[] bytes;final long a,b,c,generation,at;
        Event(int type,byte[] bytes,long a,long b,long c,long generation) {
            this.type=type;this.bytes=bytes;this.a=a;this.b=b;this.c=c;this.generation=generation;at=SystemClock.elapsedRealtime();
        }
    }
    private final Runnable changed;
    private final ArrayDeque<Event> pending=new ArrayDeque<>();
    private final LegacyTrackTimeline timeline=new LegacyTrackTimeline();
    private LegacyTrack track=LegacyTrack.empty();
    private Bitmap artwork;
    private Exception failure;
    private Thread worker;
    private boolean closed,mirroring;
    private long generation;
    private String senderId="",senderToken="";
    LegacyMedia(Runnable changed) {this.changed=changed;}
    public synchronized State state() {
        return new State(track,artwork,timeline.position(SystemClock.elapsedRealtime()),timeline.duration(),timeline.playing(),failure);
    }
    void sender(String id,String token) {
        synchronized(this) {
            if(closed || senderId.equals(id) && senderToken.equals(token))return;
            senderId=id==null?"":id;senderToken=token==null?"":token;
            generation++;pending.clear();track=LegacyTrack.empty();artwork=null;failure=null;timeline.clear(SystemClock.elapsedRealtime());
        }
        changed.run();
    }
    void metadata(byte[] bytes) {if(bytes!=null && bytes.length<=LegacyTrack.MAX_BYTES)offer(METADATA,bytes,0,0,0);}
    void artwork(byte[] bytes) {if(bytes!=null && bytes.length>0 && bytes.length<=MAX_ART_BYTES)offer(ART,bytes,0,0,0);}
    void progress(long start,long current,long end) {offer(PROGRESS,null,start,current,end);}
    void playing(boolean playing) {offer(PLAYING,null,playing?1:0,0,0);}
    void mirroring(boolean active) {
        synchronized(this) {
            if(closed || mirroring==active)return;
            mirroring=active;generation++;pending.clear();track=LegacyTrack.empty();artwork=null;failure=null;
            timeline.clear(SystemClock.elapsedRealtime());
        }
        changed.run();
    }
    private synchronized void offer(int type,byte[] bytes,long a,long b,long c) {
        if(closed || mirroring)return;
        // Only the newest pending packet of each kind matters. At most one
        // encoded cover is queued and one is being decoded, never an album's worth.
        for(Iterator<Event> events=pending.iterator();events.hasNext();)if(events.next().type==type)events.remove();
        pending.addLast(new Event(type,bytes,a,b,c,generation));
        if(worker==null) {worker=new Thread(this::run,"buzin-legacy-metadata");worker.setDaemon(true);worker.start();}
        notifyAll();
    }
    private void run() {
        while(true) {
            Event event;
            synchronized(this) {
                while(!closed && pending.isEmpty())try {wait();}catch(InterruptedException stopping) {return;}
                if(closed)return;event=pending.removeFirst();
            }
            Bitmap decoded=null;LegacyTrack parsed=null;Exception error=null;
            try {
                if(event.type==METADATA)parsed=LegacyTrack.parse(event.bytes);
                else if(event.type==ART)decoded=decode(event.bytes);
            } catch(IOException | RuntimeException invalid) {error=invalid;}
            catch(OutOfMemoryError insufficient) {error=new IOException("Artwork could not fit in memory");}
            synchronized(this) {
                if(closed || event.generation!=generation) {if(decoded!=null)decoded.recycle();continue;}
                if(error!=null)failure=error;
                else {
                    failure=null;
                    if(parsed!=null) {track=parsed;timeline.duration(parsed.durationMs,event.at);}
                    if(decoded!=null)artwork=decoded;
                    if(event.type==PROGRESS)timeline.progress(event.a,event.b,event.c,event.at);
                    if(event.type==PLAYING)timeline.playing(event.a!=0,event.at);
                }
            }
            changed.run();
        }
    }
    private static Bitmap decode(byte[] bytes) throws IOException {
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        if(bounds.outWidth<1 || bounds.outHeight<1 || (long)bounds.outWidth*bounds.outHeight>64L*1024*1024)
            throw new IOException("Invalid or excessive artwork dimensions");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
        while(bounds.outWidth/options.inSampleSize>512 || bounds.outHeight/options.inSampleSize>512)options.inSampleSize*=2;
        options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(bitmap==null)throw new IOException("Invalid artwork");return bitmap;
    }
    @Override public void close() {
        Thread previous;
        synchronized(this) {
            if(closed)return;closed=true;generation++;pending.clear();senderId=senderToken="";
            track=LegacyTrack.empty();artwork=null;timeline.clear(SystemClock.elapsedRealtime());failure=null;
            previous=worker;notifyAll();
        }
        if(previous!=null)previous.interrupt();
    }
}
