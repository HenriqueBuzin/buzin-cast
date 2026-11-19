package io.buzincast.fireos3probe;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import io.buzincast.fireos3probe.Mp4CompositionRepair.Node;
import static io.buzincast.fireos3probe.Mp4CompositionRepair.*;

/** Standard edit lists retain AAC preroll in the media while presenting only the source PCM. */
final class Mp4AudioPriming {
    static final class Segment {
        final long firstFrame,availableFrames,delayFrames,contentFrames,startUs;
        Segment(long first,long available,long delay,long content,long start) {firstFrame=first;availableFrames=available;delayFrames=delay;contentFrames=content;startUs=start;}
    }
    static final class Track {
        final long firstPtsUs,encodedFrames;final int rate;final List<Segment> segments;
        Track(long first,int rate,long encoded,List<Segment> segments) {firstPtsUs=first;this.rate=rate;encodedFrames=encoded;this.segments=segments;}
    }
    private static Node optional(Node node,String name) {for(Node child:node.children)if(child.type==fourcc(name))return child;return null;}
    static void repair(File file,List<Track> tracks,long originUs,long windowDurationUs) throws IOException {
        boolean needed=false;long muxOrigin=Long.MAX_VALUE;
        for(Track track:tracks) {needed|=!track.segments.isEmpty();muxOrigin=Math.min(muxOrigin,track.firstPtsUs);}if(!needed)return;
        try(RandomAccessFile io=new RandomAccessFile(file,"rw")) {
            long offset=0,moovAt=-1;byte[] original=null;
            while(offset<io.length()) {
                io.seek(offset);long size=io.readInt()&0xffffffffL;int type=io.readInt(),header=8;
                if(size==1) {size=io.readLong();header=16;}else if(size==0)size=io.length()-offset;
                if(size<header || size>io.length()-offset)throw new IOException("Invalid MP4 top-level box");
                if(type==fourcc("moov")) {if(original!=null || size>MAX_METADATA)throw new IOException("Unsupported MP4 metadata");original=new byte[(int)size];io.seek(offset);io.readFully(original);moovAt=offset;}
                offset+=size;
            }
            if(original==null)throw new IOException("Missing MP4 metadata");Node moov=parse(original,0,original.length,0),mvhd=moov.child("mvhd");long movieScale=timescale(mvhd),movieDuration=0;int index=0;
            for(Node trak:moov.children)if(trak.type==fourcc("trak")) {
                if(index>=tracks.size())throw new IOException("Unexpected MP4 track count");Track info=tracks.get(index++);
                Node tkhd=trak.child("tkhd"),mdhd=trak.child("mdia").child("mdhd"),stbl=trak.child("mdia").child("minf").child("stbl");long scale=timescale(mdhd);
                if(!info.segments.isEmpty()) {
                    if(optional(trak,"edts")!=null || info.rate<1000 || info.rate>384000 || info.encodedFrames<=0 || info.encodedFrames%1024!=0)
                        throw new IOException("Invalid AAC presentation metadata");
                    long samples=uint(stbl.child("stsz").data,stbl.child("stsz").header+8);
                    if(samples!=info.encodedFrames/1024 || samples>1000000)throw new IOException("AAC sample count differs from presentation metadata");
                    stbl.replace("stts",aacTable((int)samples,info.rate,scale));setDuration(mdhd,info.encodedFrames*scale/info.rate,false);
                    List<long[]> edits=new ArrayList<>();long cursor=0,previousFrameEnd=0;
                    for(int s=0;s<info.segments.size();s++) {
                        Segment segment=info.segments.get(s);
                        if(segment.firstFrame!=previousFrameEnd || segment.availableFrames<1 || segment.delayFrames<0 || segment.delayFrames>8192 || segment.contentFrames<=0 ||
                            segment.contentFrames>segment.availableFrames-segment.delayFrames || segment.firstFrame>info.encodedFrames-segment.availableFrames)
                            throw new IOException("AAC encoder did not flush the source PCM");
                        previousFrameEnd=segment.firstFrame+segment.availableFrames;
                        long start=Math.max(originUs,segment.startUs),end=segment.startUs+segment.contentFrames*1000000L/info.rate;
                        if(s+1<info.segments.size())end=Math.min(end,info.segments.get(s+1).startUs);
                        if(windowDurationUs>0)end=Math.min(end,windowDurationUs);
                        if(end<=start)continue;
                        long at=ticks(start-originUs,movieScale),length=ticks(end-start,movieScale);
                        if(at<cursor)throw new IOException("Overlapping AAC presentation segments");
                        if(at>cursor)edits.add(new long[]{at-cursor,-1});
                        long media=(segment.firstFrame+segment.delayFrames)*scale/info.rate+ticks(start-segment.startUs,scale);
                        if(length>0)edits.add(new long[]{length,media});cursor=at+length;
                    }
                    if(previousFrameEnd!=info.encodedFrames || edits.isEmpty())throw new IOException("Incomplete AAC presentation segments");
                    trak.replace("edts",editList(edits));setDuration(tkhd,cursor,true);
                }else if(optional(stbl,"ctts")==null) {
                    // Media3 1.2 stretches the first sample for a delayed track.
                    // Restore its media duration and express that delay as an empty edit.
                    long stretch=ticks(info.firstPtsUs-muxOrigin,scale);
                    if(stretch>0) {Node table=trimFirst(stbl.child("stts"),stretch);stbl.replace("stts",table);setDuration(mdhd,tableDuration(table),false);}
                    long gap=ticks(info.firstPtsUs-originUs,movieScale);
                    if(gap>0 || stretch>0) {
                        if(optional(trak,"edts")!=null)throw new IOException("Existing MP4 edits require composition");
                        long length=ticks(duration(mdhd,false)*1000000L/scale,movieScale);List<long[]> edits=new ArrayList<>();
                        if(gap>0)edits.add(new long[]{gap,-1});edits.add(new long[]{length,0});trak.replace("edts",editList(edits));setDuration(tkhd,gap+length,true);
                    }
                }
                movieDuration=Math.max(movieDuration,duration(tkhd,true));
            }
            if(index!=tracks.size())throw new IOException("Missing MP4 track");setDuration(mvhd,movieDuration,false);byte[] replacement=moov.encode();if(replacement.length>MAX_METADATA)throw new IOException("MP4 presentation metadata exceeded its budget");
            io.seek(io.length());io.write(replacement);io.seek(moovAt+4);io.writeInt(fourcc("free"));
        }
    }
    private static Node aacTable(int samples,int rate,long scale) throws IOException {
        ByteArrayOutputStream body=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(body);out.writeInt(0);out.writeInt(0);
        long previous=0,last=-1,run=0;int entries=0;
        for(int i=0;i<samples;i++) {
            long next=(i+1L)*1024*scale/rate,delta=next-previous;previous=next;
            if(delta<=0 || delta>0xffffffffL)throw new IOException("Invalid AAC sample duration");
            if(delta!=last && run>0) {out.writeInt((int)run);out.writeInt((int)last);entries++;run=0;}
            last=delta;run++;
        }
        if(run>0) {out.writeInt((int)run);out.writeInt((int)last);entries++;}
        byte[] data=body.toByteArray();if(data.length>MAX_METADATA)throw new IOException("AAC timing table exceeded its budget");ByteBuffer.wrap(data).putInt(4,entries);return Node.leaf("stts",data);
    }
    private static Node trimFirst(Node table,long amount) throws IOException {
        long entries=uint(table.data,table.header+4);if(entries<1 || table.data.length!=table.header+8+entries*8)throw new IOException("Invalid MP4 time table");
        long count=uint(table.data,table.header+8),delta=uint(table.data,table.header+12);if(count<1 || delta<=amount)throw new IOException("Invalid first MP4 sample duration");
        ByteArrayOutputStream body=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(body);out.writeInt(0);out.writeInt((int)entries+(count>1?1:0));out.writeInt(1);out.writeInt((int)(delta-amount));
        if(count>1) {out.writeInt((int)(count-1));out.writeInt((int)delta);}out.write(table.data,table.header+16,table.data.length-table.header-16);return Node.leaf("stts",body.toByteArray());
    }
    private static long tableDuration(Node table) throws IOException {
        long entries=uint(table.data,table.header+4),total=0;
        if(entries<1 || table.data.length!=table.header+8+entries*8)throw new IOException("Invalid MP4 timing table");
        for(int i=0;i<entries;i++) {long count=uint(table.data,table.header+8+i*8),delta=uint(table.data,table.header+12+i*8);if(count<1 || delta<1 || count>Long.MAX_VALUE/delta || total>Long.MAX_VALUE-count*delta)throw new IOException("MP4 timing overflow");total+=count*delta;}return total;
    }
    private static Node editList(List<long[]> entries) throws IOException {
        if(entries.size()>MAX_METADATA/20)throw new IOException("Too many AAC edits");ByteArrayOutputStream body=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(body);out.writeInt(0x01000000);out.writeInt(entries.size());
        for(long[] entry:entries) {if(entry[0]<1 || entry[1]<-1)throw new IOException("Invalid MP4 edit");out.writeLong(entry[0]);out.writeLong(entry[1]);out.writeShort(1);out.writeShort(0);}
        Node edits=new Node(fourcc("edts"),8,null);edits.children=new ArrayList<>();edits.children.add(Node.leaf("elst",body.toByteArray()));return edits;
    }
}
