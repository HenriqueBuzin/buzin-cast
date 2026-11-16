package io.buzincast.fireos3probe;

import java.io.IOException;
import java.nio.charset.Charset;

/** Immutable DMAP fields, parsed atomically within the old device's memory budget. */
public final class LegacyTrack {
    static final int MAX_BYTES=64*1024;
    public final String title,artist,album,genre;
    public final long durationMs;
    private LegacyTrack(String title,String artist,String album,String genre,long duration) {
        this.title=title;this.artist=artist;this.album=album;this.genre=genre;durationMs=duration;
    }
    public static LegacyTrack empty() {return new LegacyTrack("","","","",0);}
    static LegacyTrack parse(byte[] data) throws IOException {
        if(data==null || data.length>MAX_BYTES)throw new IOException("Metadata exceeds its budget");
        Builder result=new Builder();read(data,0,data.length,0,result);
        return new LegacyTrack(result.title,result.artist,result.album,result.genre,result.duration);
    }
    private static final class Builder {String title="",artist="",album="",genre="";long duration;int count;}
    private static void read(byte[] bytes,int start,int end,int depth,Builder result) throws IOException {
        if(depth>8)throw new IOException("Metadata nesting exceeds its budget");
        for(int at=start;at<end;) {
            if(end-at<8 || ++result.count>256)throw new IOException("Malformed or excessive metadata records");
            String tag=new String(bytes,at,4,Charset.forName("US-ASCII"));
            int size=((bytes[at+4]&255)<<24)|((bytes[at+5]&255)<<16)|((bytes[at+6]&255)<<8)|(bytes[at+7]&255);
            at+=8;if(size<0 || size>end-at)throw new IOException("Truncated metadata record");
            if(tag.equals("mlit") || tag.equals("mcon") || tag.equals("mlcl"))read(bytes,at,at+size,depth+1,result);
            else if(tag.equals("astm")) {
                if(size<1 || size>8)throw new IOException("Invalid metadata duration");
                long value=0;
                for(int i=0;i<size;i++) {
                    int next=bytes[at+i]&255;
                    if(value>(Long.MAX_VALUE-next)/256)throw new IOException("Metadata duration overflow");
                    value=value*256+next;
                }
                result.duration=value;
            } else if(tag.equals("minm") || tag.equals("asar") || tag.equals("asal") || tag.equals("asgn")) {
                if(size>8192)throw new IOException("Metadata text exceeds its budget");
                String value=new String(bytes,at,size,Charset.forName("UTF-8"));
                if(tag.equals("minm"))result.title=value;
                else if(tag.equals("asar"))result.artist=value;
                else if(tag.equals("asal"))result.album=value;
                else result.genre=value;
            }
            at+=size;
        }
    }
}
