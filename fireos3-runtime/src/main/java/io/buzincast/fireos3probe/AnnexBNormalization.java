package io.buzincast.fireos3probe;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Canonical four-byte Annex-B prefixes for the MP4 muxer, including TS CSD/samples. */
final class AnnexBNormalization {
    private static int prefix(ByteBuffer input,int at) {
        if(at+3<=input.limit() && input.get(at)==0 && input.get(at+1)==0) {
            if(input.get(at+2)==1)return 3;
            if(at+4<=input.limit() && input.get(at+2)==0 && input.get(at+3)==1)return 4;
        }
        return 0;
    }
    private static int find(ByteBuffer input,int at) {for(int i=at;i<input.limit()-2;i++)if(prefix(input,i)>0)return i;return -1;}
    static ByteBuffer normalize(ByteBuffer input,int maximum) throws IOException {
        int first=find(input,input.position());
        if(first<0)throw new IOException("Missing Annex-B prefix");
        for(int i=input.position();i<first;i++)if(input.get(i)!=0)throw new IOException("Unexpected data before Annex-B prefix");
        long size=0;
        for(int at=first;at>=0;) {
            int start=at+prefix(input,at),next=find(input,start),end=next<0?input.limit():next;
            if(end<=start)throw new IOException("Empty Annex-B unit");
            size+=4L+end-start;if(size>maximum)throw new IOException("Annex-B data exceeded its budget");at=next;
        }
        ByteBuffer output=ByteBuffer.allocateDirect((int)size);
        for(int at=first;at>=0;) {
            int start=at+prefix(input,at),next=find(input,start),end=next<0?input.limit():next;
            output.putInt(1);ByteBuffer bytes=input.duplicate();bytes.position(start);bytes.limit(end);output.put(bytes);at=next;
        }
        output.flip();return output;
    }
    static byte[] normalize(byte[] input,int maximum) throws IOException {
        ByteBuffer data=normalize(ByteBuffer.wrap(input),maximum);byte[] output=new byte[data.remaining()];data.get(output);return output;
    }
}
