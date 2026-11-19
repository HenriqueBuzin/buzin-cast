package io.buzincast.legacy;

import io.buzincast.fireos3probe.LegacyPcmConverter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Actual API17 JNI/libswresample: signal, channel routing, chunk boundaries, drain and validation. */
final class LegacyPcmScenario {
    static ByteBuffer buffer(int bytes) {return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);}
    static void append(ByteArrayOutputStream output,ByteBuffer bytes) {
        if(bytes.remaining()>1024*1024 || output.size()+bytes.remaining()>4*1024*1024)throw new AssertionError("PCM test exceeded its output budget");
        byte[] copy=new byte[bytes.remaining()];bytes.get(copy);output.write(copy,0,copy.length);
    }
    static byte[] convert(ByteBuffer source,int rate,int channels,int mask,int encoding,int outputRate,int outputChannels,int chunk) throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();int frame=channels*(encoding==2?2:encoding==3?1:encoding==0x20000000?3:4);
        try(LegacyPcmConverter converter=new LegacyPcmConverter(rate,channels,mask,encoding,outputRate,outputChannels)) {
            while(source.hasRemaining()) {
                ByteBuffer input=source.duplicate();input.limit(input.position()+Math.min(source.remaining(),chunk*frame));
                append(output,converter.convert(input));source.position(input.position());
            }
            for(int i=0;i<100 && !converter.ended();i++)append(output,converter.finish());
            if(!converter.ended() || converter.finish().hasRemaining())throw new AssertionError("PCM filter did not drain");
            try {converter.convert(buffer(frame));throw new AssertionError("PCM accepted input after drain");}catch(IOException expected) { }
        }
        return output.toByteArray();
    }
    static double amplitude(byte[] pcm,int rate,int tone) {
        ByteBuffer data=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);double real=0,imag=0;int frames=pcm.length/2;
        for(int i=0;i<frames;i++) {double value=data.getShort()/32768.0,phase=2*Math.PI*tone*i/rate;real+=value*Math.cos(phase);imag+=value*Math.sin(phase);}
        return 2*Math.hypot(real,imag)/frames;
    }
    static void check() throws Exception {
        verifyLowRatePriming();
        ByteBuffer identity=buffer(4000);for(int i=0;i<2000;i++)identity.putShort((short)(i*7919));identity.flip();
        byte[] original=new byte[identity.remaining()];identity.duplicate().get(original);
        if(!Arrays.equals(original,convert(identity,44100,2,0,2,44100,2,17)))throw new AssertionError("Identity PCM changed across chunk boundaries");
        ByteBuffer unsigned=buffer(4);unsigned.put(new byte[]{0,(byte)128,(byte)255,(byte)128}).flip();
        ByteBuffer decoded=ByteBuffer.wrap(convert(unsigned,48000,1,0,3,48000,1,2)).order(ByteOrder.LITTLE_ENDIAN);
        if(decoded.getShort()!=-32768 || decoded.getShort()!=0 || decoded.getShort()!=32512 || decoded.getShort()!=0)throw new AssertionError("Unsigned 8-bit PCM conversion changed its values");
        ByteBuffer packed=buffer(12);packed.put(new byte[]{0,0,0,(byte)255,(byte)255,127,0,0,(byte)128,0,0,64}).flip();
        decoded=ByteBuffer.wrap(convert(packed,48000,1,0,0x20000000,48000,1,2)).order(ByteOrder.LITTLE_ENDIAN);
        if(decoded.getShort()!=0 || decoded.getShort()!=32767 || decoded.getShort()!=-32768 || decoded.getShort()!=16384)throw new AssertionError("Packed 24-bit PCM conversion changed its values");
        ByteBuffer wide=buffer(12);wide.putInt(Integer.MIN_VALUE).putInt(0).putInt(1073741824).flip();
        decoded=ByteBuffer.wrap(convert(wide,48000,1,0,0x30000000,48000,1,2)).order(ByteOrder.LITTLE_ENDIAN);
        if(decoded.getShort()!=-32768 || decoded.getShort()!=0 || decoded.getShort()!=16384)throw new AssertionError("Signed 32-bit PCM conversion changed its values");
        ByteBuffer fractional=buffer(4800*2);for(int i=0;i<4800;i++)fractional.putShort((short)(4000*Math.sin(2*Math.PI*1000*i/48000)));fractional.flip();
        byte[] whole=convert(fractional.duplicate(),48000,1,0,2,44100,1,4800),chunked=convert(fractional.duplicate(),48000,1,0,2,44100,1,17);
        if(Math.abs(whole.length/2-4410)>1 || amplitude(whole,44100,1000)<.08 || !Arrays.equals(whole,chunked))throw new AssertionError("Fractional resampling depends on packet boundaries");
        ByteBuffer high=buffer(96000*4);
        for(int i=0;i<96000;i++)high.putFloat((float)(.12*Math.sin(2*Math.PI*880*i/96000)+.12*Math.sin(2*Math.PI*30000*i/96000)));high.flip();
        byte[] down=convert(high,96000,1,0,4,48000,1,511);
        if(Math.abs(down.length/2-48000)>1 || amplitude(down,48000,880)<.08 || amplitude(down,48000,18000)>.002)
            throw new AssertionError("Native resampler changed duration/tone or aliased above-Nyquist audio");
        // FL, FR, center, LFE, back L/R, side L/R in the Android native mask order.
        for(int channel=0;channel<8;channel++) {
            ByteBuffer source=buffer(256*8*2);for(int i=0;i<256;i++)for(int c=0;c<8;c++)source.putShort((short)(c==channel?20000:0));source.flip();
            ByteBuffer result=ByteBuffer.wrap(convert(source,48000,8,0,2,48000,2,31)).order(ByteOrder.LITTLE_ENDIAN);
            int left=result.getShort(),right=result.getShort();
            if(channel==0 || channel==4 || channel==6) {if(left<500 || right!=0)throw new AssertionError("Left speaker routed incorrectly: "+channel);}
            else if(channel==1 || channel==5 || channel==7) {if(right<500 || left!=0)throw new AssertionError("Right speaker routed incorrectly: "+channel);}
            else if(left<500 || Math.abs(left-right)>1)throw new AssertionError("Center/LFE was dropped or unbalanced");
        }
        ByteBuffer correlated=buffer(256*8*2);for(int i=0;i<256*8;i++)correlated.putShort((short)20000);correlated.flip();
        ByteBuffer mixed=ByteBuffer.wrap(convert(correlated,48000,8,0,2,48000,2,256)).order(ByteOrder.LITTLE_ENDIAN);
        while(mixed.hasRemaining())if(Math.abs(mixed.getShort()-20000)>500)throw new AssertionError("Correlated downmix is not peak-normalized");
        try(LegacyPcmConverter converter=new LegacyPcmConverter(48000,3,0,2,48000,2,new int[]{0,2,1})) {
            ByteBuffer vorbis=buffer(6);vorbis.putShort((short)0).putShort((short)10000).putShort((short)0).flip();ByteBuffer result=converter.convert(vorbis);
            int left=result.getShort(),right=result.getShort();if(left<1000 || left!=right)throw new AssertionError("PCM channel permutation did not preserve center routing");
        }
        validation();
    }
    static void verifyLowRatePriming() throws Exception {
        String name=null;
        for(int i=0;i<android.media.MediaCodecList.getCodecCount() && name==null;i++) {
            android.media.MediaCodecInfo info=android.media.MediaCodecList.getCodecInfoAt(i);if(!info.isEncoder())continue;
            for(String type:info.getSupportedTypes())if(type.equalsIgnoreCase("audio/mp4a-latm")) {name=info.getName();break;}
        }
        if(name==null)throw new AssertionError("Missing AAC calibration encoder");
        Class<?> probe=Class.forName("io.buzincast.fireos3probe.AacEncoderPriming");java.lang.reflect.Method frames=probe.getDeclaredMethod("frames",String.class,int.class,int.class);frames.setAccessible(true);
        int first=(Integer)frames.invoke(null,name,8000,1),cached=(Integer)frames.invoke(null,name,8000,1);
        if(first<0 || first>8192 || first!=cached)throw new AssertionError("Low-rate mono AAC priming was not measured/cached consistently");
    }
    static void validation() throws Exception {
        try {new LegacyPcmConverter(0,2,0,2,48000,2);throw new AssertionError("Invalid PCM rate was accepted");}catch(IOException expected) { }
        try {new LegacyPcmConverter(48000,6,12,2,48000,2);throw new AssertionError("Inconsistent PCM channel mask was accepted");}catch(IOException expected) { }
        try {new LegacyPcmConverter(48000,3,0,2,48000,2,new int[]{0,0,2});throw new AssertionError("Duplicate PCM channel permutation was accepted");}catch(IOException expected) { }
        try(LegacyPcmConverter converter=new LegacyPcmConverter(48000,2,0,2,48000,2)) {
            java.lang.reflect.Field handle=converter.getClass().getDeclaredField("handle");handle.setAccessible(true);long nativeHandle=handle.getLong(converter);
            java.lang.reflect.Method convert=converter.getClass().getDeclaredMethod("convert",long.class,ByteBuffer.class,int.class,int.class,boolean.class);convert.setAccessible(true);
            ByteBuffer bytes=buffer(8);
            for(Object[] bad:new Object[][]{{0L,bytes,0,1,false},{nativeHandle,bytes,-1,1,false},{nativeHandle,bytes,0,-1,false},
                {nativeHandle,bytes,0,LegacyPcmConverter.MAX_INPUT_FRAMES+1,false},{nativeHandle,bytes,1024,1,false},
                {nativeHandle,ByteBuffer.allocate(8),0,1,false},{nativeHandle,bytes,0,1,true}})
                if(convert.invoke(null,bad)!=null)throw new AssertionError("Native PCM bounds accepted invalid input");
            bytes.putShort((short)1234).putShort((short)-2345).flip();ByteBuffer valid=converter.convert(bytes);
            if(valid.getShort()!=1234 || valid.getShort()!=-2345)throw new AssertionError("Rejected PCM input modified converter state");
            converter.close();converter.close();try {converter.finish();throw new AssertionError("Closed PCM converter was usable");}catch(IOException expected) { }
        }
        try(LegacyPcmConverter converter=new LegacyPcmConverter(48000,1,0,4,48000,1)) {
            ByteBuffer bad=buffer(4);bad.putFloat(Float.NaN).flip();try {converter.convert(bad);throw new AssertionError("Non-finite PCM accepted");}catch(IOException expected) { }
        }
    }
}
