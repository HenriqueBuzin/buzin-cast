package io.buzincast.fireos3probe;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Adds signed composition offsets missing from Media3 1.2's MP4 writer.
 * Payload offsets stay unchanged: the old moov becomes free and the replacement
 * is appended to the private temporary file before publication. */
final class Mp4CompositionRepair {
    static final int MAX_METADATA=8*1024*1024;
    static boolean reordered(long[] pts) {if(pts==null)return false;for(int i=1;i<pts.length;i++)if(pts[i]<pts[i-1])return true;return false;}
    static void repair(File file,List<long[]> presentation,long minimumPtsUs) throws IOException {
        boolean needed=false;for(long[] pts:presentation)needed|=reordered(pts);if(!needed)return;
        try(RandomAccessFile io=new RandomAccessFile(file,"rw")) {
            long offset=0,moovAt=-1;byte[] original=null;
            while(offset<io.length()) {
                io.seek(offset);long size=io.readInt()&0xffffffffL;int type=io.readInt();int header=8;
                if(size==1) {size=io.readLong();header=16;}else if(size==0)size=io.length()-offset;
                if(size<header || size>io.length()-offset)throw new IOException("Invalid top-level MP4 box");
                if(type==fourcc("moov")) {
                    if(original!=null || size>MAX_METADATA)throw new IOException("Unsupported MP4 metadata size/layout");
                    original=new byte[(int)size];io.seek(offset);io.readFully(original);moovAt=offset;
                }
                offset+=size;
            }
            if(original==null)throw new IOException("Missing MP4 metadata");
            Node moov=parse(original,0,original.length,0);Node mvhd=moov.child("mvhd");
            long movieScale=timescale(mvhd),movieDuration=0;int trackIndex=0;
            for(Node trak:moov.children)if(trak.type==fourcc("trak")) {
                if(trackIndex>=presentation.size())throw new IOException("Unexpected MP4 track count");
                long[] pts=presentation.get(trackIndex++);Node tkhd=trak.child("tkhd");
                if(reordered(pts)) {
                    Node mdia=trak.child("mdia"),mdhd=mdia.child("mdhd"),stbl=mdia.child("minf").child("stbl");
                    long scale=timescale(mdhd);Node stsz=stbl.child("stsz");
                    if(uint(stsz.data,stsz.header+8)!=pts.length)throw new IOException("MP4 sample count differs from export timestamps");
                    long[] sorted=pts.clone();Arrays.sort(sorted);long[] duration=new long[pts.length],composition=new long[pts.length];
                    long first=sorted[0],total=0;
                    for(int i=0;i<pts.length;i++) {
                        long dts=ticks(sorted[i]-first,scale);
                        composition[i]=ticks(pts[i]-minimumPtsUs,scale)-dts;
                        if(composition[i]<Integer.MIN_VALUE || composition[i]>Integer.MAX_VALUE)throw new IOException("MP4 composition offset exceeds its range");
                        duration[i]=i+1<pts.length?ticks(sorted[i+1]-first,scale)-dts:i>0?duration[i-1]:1;
                        if(duration[i]<=0 || duration[i]>0xffffffffL)throw new IOException("Invalid MP4 decoding duration");total+=duration[i];
                    }
                    stbl.replace("stts",table("stts",duration,false));stbl.replace("ctts",table("ctts",composition,true));
                    setDuration(mdhd,total,false);
                    long displayEndUs=sorted[sorted.length-1]-minimumPtsUs+duration[duration.length-1]*1000000L/scale;
                    setDuration(tkhd,ticks(displayEndUs,movieScale),true);
                }
                movieDuration=Math.max(movieDuration,duration(tkhd,true));
            }
            if(trackIndex!=presentation.size())throw new IOException("Missing MP4 tracks");
            setDuration(mvhd,movieDuration,false);byte[] replacement=moov.encode();
            if(replacement.length>MAX_METADATA)throw new IOException("MP4 metadata exceeded its budget");
            io.seek(io.length());io.write(replacement);
            io.seek(moovAt+4);io.writeInt(fourcc("free"));io.getFD().sync();
        }
    }
    static long ticks(long us,long scale) throws IOException {
        if(scale<=0 || us>Long.MAX_VALUE/scale || us<Long.MIN_VALUE/scale)throw new IOException("MP4 timestamp overflow");
        return us*scale/1000000L;
    }
    static Node table(String type,long[] values,boolean signed) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(signed?0x01000000:0);int groups=0;
        for(int i=0;i<values.length;i++)if(i==0 || values[i]!=values[i-1])groups++;
        out.writeInt(groups);
        for(int at=0;at<values.length;) {int end=at+1;while(end<values.length && values[end]==values[at])end++;
            out.writeInt(end-at);out.writeInt((int)values[at]);at=end;}
        return Node.leaf(type,bytes.toByteArray());
    }
    private static long timescale(Node node) throws IOException {return uint(node.data,node.header+(version(node)==0?12:20));}
    private static long duration(Node node,boolean track) throws IOException {
        int version=version(node),at=node.header+(track?(version==0?20:28):(version==0?16:24));
        if(version==0)return uint(node.data,at);
        bounds(node.data,at,8);long value=ByteBuffer.wrap(node.data).getLong(at);if(value<0)throw new IOException("Invalid MP4 duration");return value;
    }
    private static void setDuration(Node node,long value,boolean track) throws IOException {
        int version=version(node),at=node.header+(track?(version==0?20:28):(version==0?16:24));
        if(value<0 || version==0 && value>0xffffffffL)throw new IOException("MP4 duration exceeds header version");
        bounds(node.data,at,version==0?4:8);if(version==0)ByteBuffer.wrap(node.data).putInt(at,(int)value);else ByteBuffer.wrap(node.data).putLong(at,value);
    }
    private static int version(Node node) throws IOException {bounds(node.data,node.header,4);int v=node.data[node.header]&255;if(v>1)throw new IOException("Unsupported MP4 header version");return v;}
    private static long uint(byte[] data,int at) throws IOException {bounds(data,at,4);return ByteBuffer.wrap(data).getInt(at)&0xffffffffL;}
    private static void bounds(byte[] data,int at,int size) throws IOException {if(at<0 || size<0 || at>data.length-size)throw new IOException("Truncated MP4 metadata");}
    private static int fourcc(String value) {return value.charAt(0)<<24|value.charAt(1)<<16|value.charAt(2)<<8|value.charAt(3);}
    private static Node parse(byte[] data,int start,int end,int depth) throws IOException {
        if(depth>8 || end-start<8)throw new IOException("Invalid MP4 metadata nesting");
        long size=uint(data,start);int type=(int)uint(data,start+4),header=8;
        if(size==1) {bounds(data,start,16);size=ByteBuffer.wrap(data).getLong(start+8);header=16;}
        if(size!=end-start || size<header)throw new IOException("Invalid MP4 metadata box size");
        boolean container=type==fourcc("moov") || type==fourcc("trak") || type==fourcc("mdia") || type==fourcc("minf") || type==fourcc("stbl");
        Node node=new Node(type,header,container?null:Arrays.copyOfRange(data,start,end));
        if(container) {
            node.children=new ArrayList<>();int at=start+header;
            while(at<end) {
                long child=uint(data,at);if(child==1) {bounds(data,at,16);child=ByteBuffer.wrap(data).getLong(at+8);}
                if(child<8 || child>end-at)throw new IOException("Invalid MP4 child box size");
                node.children.add(parse(data,at,at+(int)child,depth+1));at+=(int)child;
            }
        }
        return node;
    }
    static final class Node {
        final int type,header;final byte[] data;List<Node> children;
        Node(int type,int header,byte[] data) {this.type=type;this.header=header;this.data=data;}
        static Node leaf(String type,byte[] body) throws IOException {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.writeInt(body.length+8);out.writeInt(fourcc(type));out.write(body);return new Node(fourcc(type),8,bytes.toByteArray());
        }
        Node child(String name) throws IOException {for(Node child:children)if(child.type==fourcc(name))return child;throw new IOException("Missing MP4 metadata box "+name);}
        void replace(String name,Node value) {int type=fourcc(name);for(int i=0;i<children.size();i++)if(children.get(i).type==type) {children.set(i,value);return;}children.add(value);}
        byte[] encode() throws IOException {
            if(children==null)return data;
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeLong(0);
            for(Node child:children) {out.write(child.encode());if(bytes.size()>MAX_METADATA)throw new IOException("MP4 metadata exceeded its budget");}
            byte[] result=bytes.toByteArray();ByteBuffer.wrap(result).putInt(result.length).putInt(type);return result;
        }
    }
}
