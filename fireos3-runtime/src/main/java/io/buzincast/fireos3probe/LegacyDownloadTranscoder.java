package io.buzincast.fireos3probe;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/** Synchronous API16 codec buffers, driven by the download worker, without a Surface or audio output. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class LegacyDownloadTranscoder implements java.io.Closeable {
    private static final int LIMIT=8*1024*1024;
    static final class Sample {
        final ByteBuffer bytes;final long ptsUs;final boolean key;final Format format;
        Sample(ByteBuffer bytes,long pts,boolean key,Format format) {this.bytes=bytes;ptsUs=pts;this.key=key;this.format=format;}
    }
    private final boolean video;
    private final Format input;
    private final int codedWidth,codedHeight;
    private MediaCodec decoder,encoder;
    private ByteBuffer[] decoderInputs,decoderOutputs,encoderInputs,encoderOutputs;
    private final MediaCodec.BufferInfo decoded=new MediaCodec.BufferInfo(),encoded=new MediaCodec.BufferInfo();
    private MediaFormat decodedFormat;
    private Format outputFormat;
    private final ByteArrayOutputStream configuration=new ByteArrayOutputStream();
    private boolean inputEnded,decoderEnded,encoderEnded,decoderStarted,encoderStarted,closed;
    private int pending=-1,pendingOffset;

    static boolean available(Format format) {
        boolean video=MimeTypes.isVideo(format.sampleMimeType);
        if(!video && !MimeTypes.isAudio(format.sampleMimeType))return false;
        if(video && (format.width<=0 || format.height<=0 || (format.width&1)!=0 || (format.height&1)!=0 ||
            format.width>1920 || format.height>1920 || (long)format.width*format.height>1920L*1080))return false;
        if(!video && (format.sampleRate<8000 || format.sampleRate>48000 || format.channelCount<1 || format.channelCount>2))return false;
        return codec(format.sampleMimeType,false,false)!=null && codec(video?"video/avc":"audio/mp4a-latm",true,video)!=null;
    }
    private static String codec(String mime,boolean encode,boolean planar) {
        if(mime==null)return null;
        for(int i=0;i<MediaCodecList.getCodecCount();i++) {
            MediaCodecInfo info=MediaCodecList.getCodecInfoAt(i);if(info.isEncoder()!=encode)continue;
            for(String type:info.getSupportedTypes())if(type.equalsIgnoreCase(mime))try {
                if(!planar)return info.getName();
                for(int color:info.getCapabilitiesForType(type).colorFormats)
                    if(color==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)return info.getName();
            }catch(RuntimeException unavailable) { /* Try another installed component. */ }
        }
        return null;
    }
    private static int avcLevel(String name,int width,int height,int fps,int bitrate) throws IOException {
        int[][] limits={{1,99,1485,64000},{2,99,1485,128000},{4,396,3000,192000},{8,396,6000,384000},{16,396,11880,768000},
            {32,396,11880,2000000},{64,792,19800,4000000},{128,1620,20250,4000000},{256,1620,40500,10000000},
            {512,3600,108000,14000000},{1024,5120,216000,20000000},{2048,8192,245760,20000000},{4096,8192,245760,50000000}};
        long blocks=(long)(width/16)*(height/16);int maximum=0;
        for(int i=0;i<MediaCodecList.getCodecCount();i++) {
            MediaCodecInfo info=MediaCodecList.getCodecInfoAt(i);if(!name.equals(info.getName()))continue;
            for(MediaCodecInfo.CodecProfileLevel pair:info.getCapabilitiesForType("video/avc").profileLevels)
                if(pair.profile==MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)maximum=Math.max(maximum,pair.level);
        }
        for(int[] level:limits)if(level[0]<=maximum && blocks<=level[1] && blocks*fps<=level[2] && bitrate<=level[3])return level[0];
        throw new IOException("No AVC baseline level fits this download");
    }
    LegacyDownloadTranscoder(Format input) throws IOException {
        this.input=input;video=MimeTypes.isVideo(input.sampleMimeType);
        // JB's SoftAVC encoder starts with a QCIF buffer and asserts when it is
        // configured smaller; it also requires macroblock-aligned dimensions.
        // Keep the source pixels and express the padding as standard SPS cropping.
        codedWidth=video?Math.max(176,(input.width+15)&~15):0;
        codedHeight=video?Math.max(144,(input.height+15)&~15):0;
        try {
            String decodeName=codec(input.sampleMimeType,false,false),encodeName=codec(video?"video/avc":"audio/mp4a-latm",true,video);
            if(!available(input) || decodeName==null || encodeName==null)throw new IOException("No compatible download codec");
            MediaFormat source=video?MediaFormat.createVideoFormat(input.sampleMimeType,input.width,input.height):
                MediaFormat.createAudioFormat(input.sampleMimeType,input.sampleRate,input.channelCount);
            source.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,LIMIT);
            if(video)source.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
            for(int i=0;i<input.initializationData.size();i++)source.setByteBuffer("csd-"+i,ByteBuffer.wrap(input.initializationData.get(i)));
            decoder=MediaCodec.createByCodecName(decodeName);decoder.configure(source,null,null,0);decoder.start();decoderStarted=true;
            decoderInputs=decoder.getInputBuffers();decoderOutputs=decoder.getOutputBuffers();
            MediaFormat target;
            if(video) {
                target=MediaFormat.createVideoFormat("video/avc",codedWidth,codedHeight);
                target.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
                int fps=input.frameRate>0?(int)Math.min(60,Math.ceil(input.frameRate)):30;
                int bitrate=(int)Math.min(8000000,Math.max(256000,(long)input.width*input.height*4));
                target.setInteger("profile",MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                target.setInteger("level",avcLevel(encodeName,codedWidth,codedHeight,fps,bitrate));
                target.setInteger(MediaFormat.KEY_FRAME_RATE,fps);
                target.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2);
                target.setInteger(MediaFormat.KEY_BIT_RATE,bitrate);
            } else {
                target=MediaFormat.createAudioFormat("audio/mp4a-latm",input.sampleRate,input.channelCount);
                target.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                target.setInteger(MediaFormat.KEY_BIT_RATE,input.channelCount*64000);
            }
            encoder=MediaCodec.createByCodecName(encodeName);encoder.configure(target,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start();encoderStarted=true;
            encoderInputs=encoder.getInputBuffers();encoderOutputs=encoder.getOutputBuffers();
        }catch(Exception error) {android.util.Log.e("LegacyTranscode","Codec initialization failed",error);close();if(error instanceof IOException)throw (IOException)error;throw new IOException("Download codec initialization failed",error);}
    }
    boolean offer(ByteBuffer bytes,long ptsUs,boolean key) throws IOException {
        if(closed || inputEnded)throw new IOException("Download codec input is closed");
        int index=decoder.dequeueInputBuffer(0);if(index<0)return false;
        ByteBuffer target=decoderInputs[index];target.clear();
        if(bytes.remaining()>LIMIT || bytes.remaining()>target.remaining())throw new IOException("Download codec sample exceeds input capacity");
        int count=bytes.remaining();target.put(bytes.duplicate());decoder.queueInputBuffer(index,0,count,ptsUs,key?MediaCodec.BUFFER_FLAG_SYNC_FRAME:0);return true;
    }
    void endInput() {
        if(inputEnded || closed)return;int index=decoder.dequeueInputBuffer(0);if(index<0)return;
        decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true;
    }
    boolean ended() {return encoderEnded;}
    Sample poll() throws IOException {
        if(closed)return null;
        for(int attempts=0;attempts<12;attempts++) {
            int index=encoderEnded?MediaCodec.INFO_TRY_AGAIN_LATER:encoder.dequeueOutputBuffer(encoded,0);
            if(index==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)encoderOutputs=encoder.getOutputBuffers();
            else if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {Format announced=exportFormat(encoder.getOutputFormat());if(announced!=null)outputFormat=announced;}
            else if(index>=0) {
                try {
                    if((encoded.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)encoderEnded=true;
                    if(encoded.size>0 && (encoded.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0) {
                        if(encoded.size>128*1024-configuration.size())throw new IOException("Download codec configuration exceeded its budget");
                        ByteBuffer source=encoderOutputs[index].duplicate();source.position(encoded.offset);source.limit(encoded.offset+encoded.size);
                        byte[] bytes=new byte[source.remaining()];source.get(bytes);
                        if(video)bytes=AvcEncoderCrop.annexB(bytes,input.width,input.height,codedWidth,codedHeight);
                        if(bytes.length>128*1024-configuration.size())throw new IOException("Download codec configuration exceeded its budget");
                        configuration.write(bytes,0,bytes.length);
                        ArrayList<byte[]> csd=new ArrayList<>();
                        if(video) {
                            bytes=AvcEncoderCrop.annexB(configuration.toByteArray(),input.width,input.height,codedWidth,codedHeight);
                            AvcConfig avc=AvcConfig.fromAnnexB(bytes);
                            if(avc!=null) {csd.add(avc.spsWithStartCode());csd.add(avc.ppsWithStartCode());}
                        }else csd.add(configuration.toByteArray());
                        if(!csd.isEmpty())outputFormat=format(csd);
                    }
                    if(encoded.size>0 && (encoded.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                        if(outputFormat==null || encoded.size>LIMIT)throw new IOException("Invalid encoded download sample: video="+video+", format="+(outputFormat!=null)+", bytes="+encoded.size);
                        ByteBuffer source=encoderOutputs[index].duplicate();source.position(encoded.offset);source.limit(encoded.offset+encoded.size);
                        ByteBuffer copy;
                        if(video) {byte[] bytes=new byte[source.remaining()];source.get(bytes);bytes=AvcEncoderCrop.annexB(bytes,input.width,input.height,codedWidth,codedHeight);copy=ByteBuffer.allocateDirect(bytes.length);copy.put(bytes).flip();}
                        else {copy=ByteBuffer.allocateDirect(encoded.size);copy.put(source).flip();}
                        return new Sample(copy,encoded.presentationTimeUs,(encoded.flags&MediaCodec.BUFFER_FLAG_SYNC_FRAME)!=0,outputFormat);
                    }
                }finally {encoder.releaseOutputBuffer(index,false);}
            }
            if(pending<0 && !decoderEnded) {
                int next=decoder.dequeueOutputBuffer(decoded,0);
                if(next==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)decoderOutputs=decoder.getOutputBuffers();
                else if(next==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)validateDecoded(decoder.getOutputFormat());
                else if(next>=0) {pending=next;pendingOffset=0;}
            }
            if(pending>=0) {
                int next=encoder.dequeueInputBuffer(0);
                if(next>=0) {
                    ByteBuffer target=encoderInputs[next];target.clear();
                    int count=0;
                    if(decoded.size>0) {
                        ByteBuffer source=decoderOutputs[pending].duplicate();source.position(decoded.offset+pendingOffset);source.limit(decoded.offset+decoded.size);
                        if(video)count=copyVideo(source,target);
                        else {int frame=input.channelCount*2;if(decoded.size%frame!=0)throw new IOException("Invalid decoded PCM capacity");count=Math.min(source.remaining(),target.remaining()/frame*frame);if(count==0)throw new IOException("Empty PCM encoder capacity");source.limit(source.position()+count);target.put(source);}
                    }
                    long pts=decoded.presentationTimeUs+(video?0:pendingOffset/(input.channelCount*2L)*1000000/input.sampleRate);
                    pendingOffset+=video?decoded.size:count;boolean complete=pendingOffset==decoded.size;
                    boolean eos=complete && (decoded.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    encoder.queueInputBuffer(next,0,count,pts,eos?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);
                    if(complete) {decoder.releaseOutputBuffer(pending,false);pending=-1;}if(eos)decoderEnded=true;
                }
            }
        }
        return null;
    }
    private void validateDecoded(MediaFormat format) throws IOException {
        if(video) {
            int color=format.getInteger(MediaFormat.KEY_COLOR_FORMAT);
            if(color!=MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar && color!=MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                throw new IOException("Download decoder produced an unsupported YUV layout");
        } else if(format.getInteger(MediaFormat.KEY_SAMPLE_RATE)!=input.sampleRate || format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)!=input.channelCount || integer(format,"pcm-encoding",2)!=2)
            throw new IOException("Download decoder changed the PCM layout");
        decodedFormat=format;
    }
    private static int integer(MediaFormat format,String name,int fallback) {return format.containsKey(name)?format.getInteger(name):fallback;}
    private int copyVideo(ByteBuffer source,ByteBuffer target) throws IOException {
        if(decodedFormat==null)throw new IOException("Download video preceded its output format");
        int w=input.width,h=input.height,stride=integer(decodedFormat,"stride",w),slice=integer(decodedFormat,"slice-height",h);
        if(stride==0)stride=w;if(slice==0)slice=h;
        int left=integer(decodedFormat,"crop-left",0),top=integer(decodedFormat,"crop-top",0);
        int right=integer(decodedFormat,"crop-right",w-1),bottom=integer(decodedFormat,"crop-bottom",h-1);
        long ySize=(long)stride*slice,required=ySize+ySize/2;
        if(stride<w || slice<h || stride>4096 || slice>4096 || (stride&1)!=0 || (slice&1)!=0 || left<0 || top<0 ||
            (left&1)!=0 || (top&1)!=0 || right-left+1!=w || bottom-top+1!=h || left+w>stride || top+h>slice || required>source.remaining() ||
            (long)codedWidth*codedHeight*3/2>target.remaining())throw new IOException("Download decoder YUV bounds are invalid");
        int base=source.position(),targetBase=target.position(),codedLuma=codedWidth*codedHeight;
        for(int i=0;i<codedLuma;i++)target.put((byte)16);
        for(int i=0;i<codedLuma/2;i++)target.put((byte)128);
        for(int row=0;row<h;row++)for(int col=0;col<w;col++)target.put(targetBase+row*codedWidth+col,source.get(base+(top+row)*stride+left+col));
        int chroma=base+(int)ySize;
        boolean planar=decodedFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
        for(int plane=0;plane<2;plane++)for(int row=0;row<h/2;row++)for(int col=0;col<w/2;col++) {
            int offset=planar?plane*(int)ySize/4+(top/2+row)*(stride/2)+left/2+col:(top/2+row)*stride+left+col*2+plane;
            target.put(targetBase+codedLuma+plane*codedLuma/4+row*(codedWidth/2)+col,source.get(chroma+offset));
        }
        return codedLuma*3/2;
    }
    private Format exportFormat(MediaFormat format) throws IOException {
        ArrayList<byte[]> csd=new ArrayList<>();
        for(int i=0;format.containsKey("csd-"+i);i++) {
            ByteBuffer source=format.getByteBuffer("csd-"+i).duplicate();if(source.remaining()>128*1024)throw new IOException("Download encoder configuration exceeded its budget");
            byte[] bytes=new byte[source.remaining()];source.get(bytes);if(video)bytes=AvcEncoderCrop.annexB(bytes,input.width,input.height,codedWidth,codedHeight);csd.add(bytes);
        }
        return csd.isEmpty()?null:format(csd);
    }
    private Format format(ArrayList<byte[]> csd) {
        Format.Builder builder=new Format.Builder().setSampleMimeType(video?"video/avc":"audio/mp4a-latm").setInitializationData(csd).setLanguage(IsoMediaLanguage.normalize(input.language));
        if(video)builder.setWidth(input.width).setHeight(input.height).setRotationDegrees(input.rotationDegrees).setFrameRate(input.frameRate);
        else builder.setSampleRate(input.sampleRate).setChannelCount(input.channelCount);
        return builder.build();
    }
    @Override public void close() {
        if(closed)return;closed=true;
        try {release(decoder,decoderStarted);}finally {try {release(encoder,encoderStarted);}finally {decoder=encoder=null;}}
    }
    private static void release(MediaCodec codec,boolean started) {
        if(codec==null)return;try {if(started)codec.stop();}finally {codec.release();}
    }
}
