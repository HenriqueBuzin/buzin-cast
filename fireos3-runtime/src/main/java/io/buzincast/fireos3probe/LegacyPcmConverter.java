package io.buzincast.fireos3probe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Serialized, bounded libswresample owner; PCM is interleaved in Android speaker-mask order. */
public final class LegacyPcmConverter implements java.io.Closeable {
    public static final int MAX_INPUT_FRAMES=16384;
    private long handle;
    private final int bytesPerFrame;
    private boolean draining,ended;
    public LegacyPcmConverter(int inputRate,int channels,int channelMask,int encoding,int outputRate,int outputChannels) throws IOException {
        this(inputRate,channels,channelMask,encoding,outputRate,outputChannels,null);
    }
    public LegacyPcmConverter(int inputRate,int channels,int channelMask,int encoding,int outputRate,int outputChannels,int[] channelOrder) throws IOException {
        if(inputRate<1000 || inputRate>384000 || outputRate<1000 || outputRate>384000 || channels<1 || channels>8 || outputChannels<1 || outputChannels>2)
            throw new IOException("Unsupported PCM conversion layout");
        int bytes=encoding==2?2:encoding==3?1:encoding==0x20000000?3:encoding==4 || encoding==0x30000000?4:0;
        if(bytes==0)throw new IOException("Unsupported PCM encoding");bytesPerFrame=bytes*channels;
        System.loadLibrary("fireos3_probe");handle=create(inputRate,channels,channelMask,encoding,outputRate,outputChannels,channelOrder);
        if(handle==0)throw new IOException("PCM converter initialization failed");
    }
    public synchronized ByteBuffer convert(ByteBuffer input) throws IOException {
        if(handle==0 || draining || input==null || !input.isDirect() || input.remaining()%bytesPerFrame!=0 || input.remaining()/bytesPerFrame>MAX_INPUT_FRAMES)
            throw new IOException("Invalid PCM converter input");
        byte[] bytes=convert(handle,input,input.position(),input.remaining()/bytesPerFrame,false);
        if(bytes==null)throw new IOException("PCM conversion failed");input.position(input.limit());
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
    public synchronized ByteBuffer finish() throws IOException {
        if(handle==0)throw new IOException("PCM converter is closed");draining=true;
        if(ended)return ByteBuffer.allocate(0);
        byte[] bytes=convert(handle,null,0,0,true);if(bytes==null)throw new IOException("PCM converter drain failed");
        ended=bytes.length==0;return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
    public synchronized boolean ended() {return ended;}
    @Override public synchronized void close() {if(handle!=0) {destroy(handle);handle=0;}ended=true;}
    private static native long create(int inputRate,int channels,int mask,int encoding,int outputRate,int outputChannels,int[] order);
    private static native byte[] convert(long handle,ByteBuffer input,int offset,int frames,boolean end);
    private static native void destroy(long handle);
}
