package io.buzincast.fireos3probe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Hide only the padded right/bottom pixels required by old AVC encoders; never stretch the image. */
final class AvcEncoderCrop {
    static byte[] annexB(byte[] input,int width,int height,int codedWidth,int codedHeight) throws IOException {
        if(input.length==0 || input.length>8*1024*1024)throw new IOException("Invalid encoder packet budget");
        ByteArrayOutputStream out=new ByteArrayOutputStream();int start=start(input,0);
        // JB's PV/SoftAVC encoder emits single raw NAL units for video frames,
        // while its configuration buffer already includes Annex-B prefixes.
        if(start<0 && (input[0]&0x80)==0 && (input[0]&31)>0 && (input[0]&31)<=12) {
            byte[] prefixed=new byte[input.length+4];prefixed[3]=1;System.arraycopy(input,0,prefixed,4,input.length);input=prefixed;start=0;
        }
        if(start!=0)throw new IOException("Expected Annex-B encoder output");
        while(start>=0) {
            int body=start+(input[start+2]==1?3:4),next=start(input,body),end=next<0?input.length:next;
            byte[] nal=Arrays.copyOfRange(input,body,end);
            if(nal.length==0)throw new IOException("Empty encoder NAL");
            if((nal[0]&31)==7)nal=sps(nal,width,height,codedWidth,codedHeight);
            out.write(new byte[]{0,0,0,1},0,4);out.write(nal,0,nal.length);start=next;
        }
        return out.toByteArray();
    }
    private static int start(byte[] data,int offset) {
        for(int i=offset;i+2<data.length;i++)if(data[i]==0 && data[i+1]==0 && (data[i+2]==1 || i+3<data.length && data[i+2]==0 && data[i+3]==1))return i;
        return -1;
    }
    private static byte[] sps(byte[] nal,int width,int height,int codedWidth,int codedHeight) throws IOException {
        if(nal.length>4096 || width<2 || height<2 || (width&1)!=0 || (height&1)!=0 || width>codedWidth || height>codedHeight)throw new IOException("Invalid encoder crop bounds");
        ByteArrayOutputStream raw=new ByteArrayOutputStream();
        for(int i=0;i<nal.length;i++)if(!(i>=2 && nal[i]==3 && nal[i-1]==0 && nal[i-2]==0))raw.write(nal[i]);
        Bits bits=new Bits(raw.toByteArray());bits.read(8);int profile=bits.read(8);
        if(profile!=66 && profile!=77 && profile!=88)throw new IOException("Expected a baseline/main AVC encoder");
        bits.read(8);bits.read(8);bits.ue();bits.ue();int order=bits.ue();
        if(order==0)bits.ue();
        else if(order==1) {bits.read(1);bits.ue();bits.ue();int cycle=bits.ue();if(cycle>255)throw new IOException("Invalid encoder order cycle");for(int i=0;i<cycle;i++)bits.ue();}
        else if(order!=2)throw new IOException("Invalid encoder order type");
        bits.ue();bits.read(1);int columns=bits.ue(),rows=bits.ue(),frameOnly=bits.read(1);
        if(frameOnly!=1 || columns+1!=codedWidth/16 || rows+1!=codedHeight/16)throw new IOException("Unexpected encoder coded dimensions");
        bits.read(1);int prefix=bits.position;
        if(bits.read(1)!=0) {bits.ue();bits.ue();bits.ue();bits.ue();}
        int suffix=bits.position,last=bits.data.length*8-1;
        while(last>=suffix && ((bits.data[last/8]>>(7-last%8))&1)==0)last--;
        if(last<suffix)throw new IOException("Missing encoder SPS trailing bits");
        Writer result=new Writer();for(int i=0;i<prefix;i++)result.bit((bits.data[i/8]>>(7-i%8))&1);
        result.bit(1);result.ue(0);result.ue((codedWidth-width)/2);result.ue(0);result.ue((codedHeight-height)/2);
        for(int i=suffix;i<=last;i++)result.bit((bits.data[i/8]>>(7-i%8))&1);
        byte[] rbsp=result.bytes();ByteArrayOutputStream escaped=new ByteArrayOutputStream();int zeros=0;
        for(byte value:rbsp) {if(zeros>=2 && (value&255)<=3) {escaped.write(3);zeros=0;}escaped.write(value);zeros=value==0?zeros+1:0;}
        return escaped.toByteArray();
    }
    private static final class Bits {
        final byte[] data;int position;
        Bits(byte[] data) {this.data=data;}
        int read(int count) throws IOException {
            if(position+count>data.length*8)throw new IOException("Truncated encoder SPS");int value=0;
            while(count-->0) {value=(value<<1)|((data[position/8]>>(7-position%8))&1);position++;}return value;
        }
        int ue() throws IOException {int zeros=0;while(read(1)==0)if(++zeros>24)throw new IOException("Invalid encoder Golomb value");return (1<<zeros)-1+read(zeros);}
    }
    private static final class Writer {
        final ByteArrayOutputStream output=new ByteArrayOutputStream();int value,count;
        void bit(int bit) {value=(value<<1)|bit;if(++count==8) {output.write(value);value=count=0;}}
        void ue(int code) {int value=code+1,bits=32-Integer.numberOfLeadingZeros(value);for(int i=1;i<bits;i++)bit(0);for(int i=bits-1;i>=0;i--)bit((value>>i)&1);}
        byte[] bytes() {if(count>0)output.write(value<<(8-count));return output.toByteArray();}
    }
}
