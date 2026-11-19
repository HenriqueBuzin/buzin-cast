package io.buzincast.fireos3probe;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Measure the configured AAC component's signal priming instead of assuming a device-specific delay. */
final class AacEncoderPriming {
    private static final int SIGNAL=4096,MAX_DELAY=8192,MAX_PCM=32768;
    private static final LinkedHashMap<String,Integer> cache=new LinkedHashMap<>();
    static MediaFormat format(int rate,int channels) {
        MediaFormat format=MediaFormat.createAudioFormat("audio/mp4a-latm",rate,channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE,channels*64000);return format;
    }
    static synchronized int frames(String name,int rate,int channels) throws IOException {
        String key=name+":"+rate+":"+channels;Integer known=cache.get(key);if(known!=null)return known;
        int result=measure(name,rate,channels);
        if(cache.size()==8)cache.remove(cache.keySet().iterator().next());cache.put(key,result);
        android.util.Log.i("LegacyAacPriming","Measured "+result+" samples at "+rate+" Hz for "+name);return result;
    }
    private static final class Packet {
        final byte[] data;final long pts;final boolean end;
        Packet(byte[] data,long pts,boolean end) {this.data=data;this.pts=pts;this.end=end;}
    }
    private static final class Port implements java.io.Closeable {
        final MediaCodec codec;ByteBuffer[] input,output;boolean started;
        Port(String name,MediaFormat format,boolean encode) throws IOException {
            codec=encode?MediaCodec.createByCodecName(name):MediaCodec.createDecoderByType("audio/mp4a-latm");
            try {codec.configure(format,null,null,encode?MediaCodec.CONFIGURE_FLAG_ENCODE:0);codec.start();started=true;input=codec.getInputBuffers();output=codec.getOutputBuffers();}
            catch(RuntimeException error) {close();throw new IOException("AAC priming codec setup failed",error);}
        }
        public void close() {try {if(started)codec.stop();}catch(RuntimeException ignored) { }finally {try {codec.release();}catch(RuntimeException ignored) { }}}
    }
    private static byte[] bytes(ByteBuffer source,MediaCodec.BufferInfo info,int maximum) throws IOException {
        if(info.offset<0 || info.size<0 || info.size>maximum || info.offset>source.capacity()-info.size)throw new IOException("Invalid AAC priming codec buffer");
        ByteBuffer copy=source.duplicate();copy.position(info.offset);copy.limit(info.offset+info.size);byte[] bytes=new byte[info.size];copy.get(bytes);return bytes;
    }
    static void configuration(byte[] config) throws IOException {
        // AAC-LC, indexed sample rate, mono/stereo and 1024-frame GA blocks.
        if(config.length<2 || (config[0]>>3&31)!=2 || ((config[0]&7)<<1|(config[1]>>7&1))==15 || (config[1]&4)!=0)
            throw new IOException("Unsupported AAC priming configuration");
    }
    private static int measure(String name,int rate,int channels) throws IOException {
        short[] signal=new short[SIGNAL];int random=0x54a2b731,previous=0;
        for(int i=0;i<signal.length;i++) {random=random*1664525+1013904223;previous=(previous*3+(short)(random>>>16))/4;signal[i]=(short)previous;}
        ArrayList<Short> decoded=new ArrayList<>();ArrayDeque<Packet> packets=new ArrayDeque<>();
        Port decoder=null;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int fed=0;boolean encoderEnd=false,decoderEnd=false;
        long deadline=SystemClock.elapsedRealtime()+15000;
        try(Port encoder=new Port(name,format(rate,channels),true)) {
            while(!decoderEnd) {
                if(SystemClock.elapsedRealtime()>deadline)throw new IOException("AAC priming measurement timed out");boolean progress=false;
                if(!encoderEnd) {
                    int index=encoder.codec.dequeueInputBuffer(0);
                    if(index>=0) {
                        ByteBuffer target=encoder.input[index];target.clear();target.order(ByteOrder.LITTLE_ENDIAN);
                        int count=Math.min(target.remaining()/(channels*2),SIGNAL+MAX_DELAY-fed);if(count==0 && fed<SIGNAL+MAX_DELAY)throw new IOException("Empty AAC probe input capacity");
                        for(int frame=0;frame<count;frame++)for(int ch=0;ch<channels;ch++)target.putShort(fed+frame<SIGNAL?signal[fed+frame]:0);
                        boolean end=fed==SIGNAL+MAX_DELAY;encoder.codec.queueInputBuffer(index,0,count*channels*2,fed*1000000L/rate,end?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);
                        fed+=count;encoderEnd=end;progress=true;
                    }
                }
                if(packets.size()<8) {
                    int index=encoder.codec.dequeueOutputBuffer(info,0);
                    if(index==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {encoder.output=encoder.codec.getOutputBuffers();progress=true;}
                    else if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat output=encoder.codec.getOutputFormat();
                        if(decoder==null && output.containsKey("csd-0")) {
                            ByteBuffer data=output.getByteBuffer("csd-0").duplicate();if(data.remaining()>128)throw new IOException("Oversized AAC probe configuration");
                            byte[] config=new byte[data.remaining()];data.get(config);configuration(config);
                            MediaFormat input=MediaFormat.createAudioFormat("audio/mp4a-latm",rate,channels);input.setByteBuffer("csd-0",ByteBuffer.wrap(config));decoder=new Port(null,input,false);
                        }progress=true;
                    }else if(index>=0) {
                        try {
                            byte[] data=bytes(encoder.output[index],info,128*1024);
                            if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0) {
                                configuration(data);
                                if(decoder==null) {MediaFormat input=MediaFormat.createAudioFormat("audio/mp4a-latm",rate,channels);input.setByteBuffer("csd-0",ByteBuffer.wrap(data));decoder=new Port(null,input,false);}
                            }else if(data.length>0 || (info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)packets.addLast(new Packet(data,info.presentationTimeUs,(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0));
                        }finally {encoder.codec.releaseOutputBuffer(index,false);}progress=true;
                    }
                }
                if(decoder!=null) {
                    if(!packets.isEmpty()) {
                        int index=decoder.codec.dequeueInputBuffer(0);
                        if(index>=0) {Packet packet=packets.removeFirst();ByteBuffer target=decoder.input[index];target.clear();if(packet.data.length>target.remaining())throw new IOException("AAC probe packet exceeds decoder capacity");target.put(packet.data);decoder.codec.queueInputBuffer(index,0,packet.data.length,packet.pts,packet.end?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);progress=true;}
                    }
                    int index=decoder.codec.dequeueOutputBuffer(info,0);
                    if(index==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {decoder.output=decoder.codec.getOutputBuffers();progress=true;}
                    else if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat output=decoder.codec.getOutputFormat();
                        if(output.getInteger(MediaFormat.KEY_SAMPLE_RATE)!=rate || output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)!=channels || output.containsKey("pcm-encoding") && output.getInteger("pcm-encoding")!=2)throw new IOException("Unexpected AAC probe PCM format");progress=true;
                    }else if(index>=0) {
                        try {
                            byte[] data=bytes(decoder.output[index],info,256*1024);if(data.length%(channels*2)!=0 || decoded.size()+data.length/(channels*2)>MAX_PCM)throw new IOException("AAC probe PCM exceeded its budget");
                            ByteBuffer pcm=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);while(pcm.hasRemaining()) {decoded.add(pcm.getShort());pcm.position(pcm.position()+(channels-1)*2);}
                            decoderEnd=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                        }finally {decoder.codec.releaseOutputBuffer(index,false);}progress=true;
                    }
                }
                if(!progress)SystemClock.sleep(1);
            }
        }catch(RuntimeException error) {throw new IOException("AAC priming measurement failed",error);}
        finally {if(decoder!=null)decoder.close();}
        short[] pcm=new short[decoded.size()];for(int i=0;i<pcm.length;i++)pcm[i]=decoded.get(i);return correlate(signal,pcm,MAX_DELAY);
    }
    static int correlate(short[] signal,short[] decoded,int maximum) throws IOException {
        int start=256,count=2048;if(signal.length<start+count || decoded.length<start+count)throw new IOException("Insufficient AAC calibration samples");
        double sourceEnergy=0;for(int i=start;i<start+count;i++)sourceEnergy+=(double)signal[i]*signal[i];
        double[] energy=new double[decoded.length+1];for(int i=0;i<decoded.length;i++)energy[i+1]=energy[i]+(double)decoded[i]*decoded[i];
        double best=-1;int delay=-1;
        for(int at=0;at<=maximum && at+start+count<=decoded.length;at++) {
            double power=energy[at+start+count]-energy[at+start];if(power==0)continue;long product=0;
            for(int i=0;i<count;i++)product+=(long)signal[start+i]*decoded[at+start+i];
            double score=product/Math.sqrt(sourceEnergy*power);if(score>best) {best=score;delay=at;}
        }
        if(best<.8 || delay<0)throw new IOException("AAC signal priming could not be measured reliably");return delay;
    }
}
