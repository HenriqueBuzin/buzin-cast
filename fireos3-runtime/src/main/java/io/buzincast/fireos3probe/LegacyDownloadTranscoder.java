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
    private final int audioRate,audioChannels;
    private int primingFrames;
    private MediaCodec decoder,encoder;
    private String decoderName;
    private ByteBuffer[] decoderInputs,decoderOutputs,encoderInputs,encoderOutputs;
    private final MediaCodec.BufferInfo decoded=new MediaCodec.BufferInfo(),encoded=new MediaCodec.BufferInfo();
    private MediaFormat decodedFormat;
    private Format outputFormat;
    private final ByteArrayOutputStream configuration=new ByteArrayOutputStream();
    private boolean inputEnded,decoderEnded,encoderEnded,decoderStarted,encoderStarted,closed;
    private int pending=-1,pendingOffset;
    private LegacyPcmConverter pcm;
    private ByteBuffer pcmOutput=ByteBuffer.allocate(0);
    private int inputPcmRate,inputPcmFrameBytes,inputPcmChannels,inputPcmEncoding,inputPcmMask;
    private long firstAudioUs=Long.MIN_VALUE,inputAudioBaseUs,inputAudioFrames,outputAudioFrames;
    private boolean audioEosQueued;
    private boolean pendingAligned,overlapPending;
    private long contentAudioFrames=-1;
    private int paddingAudioFrames;
    int audioPrimingFrames() {return primingFrames;}
    long audioContentFrames() {return contentAudioFrames;}
    long audioStartUs() {return firstAudioUs==Long.MIN_VALUE?0:firstAudioUs;}

    static boolean available(Format format) {
        boolean video=MimeTypes.isVideo(format.sampleMimeType);
        if(!video && !MimeTypes.isAudio(format.sampleMimeType))return false;
        if(video && (format.width<=0 || format.height<=0 || (format.width&1)!=0 || (format.height&1)!=0 ||
            format.width>1920 || format.height>1920 || (long)format.width*format.height>1920L*1080))return false;
        if(!video && (format.sampleRate<1000 || format.sampleRate>384000 || format.channelCount<1 || format.channelCount>8))return false;
        return codec(format.sampleMimeType,false,false)!=null && codec(video?"video/avc":"audio/mp4a-latm",true,video)!=null;
    }
    private static int aacRate(int rate) {
        int[] rates={8000,11025,12000,16000,22050,24000,32000,44100,48000};int closest=rates[0];
        for(int candidate:rates)if(Math.abs(candidate-rate)<Math.abs(closest-rate))closest=candidate;return closest;
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
        audioRate=video?0:aacRate(input.sampleRate);audioChannels=video?0:Math.min(input.channelCount,2);
        // JB's SoftAVC encoder starts with a QCIF buffer and asserts when it is
        // configured smaller; it also requires macroblock-aligned dimensions.
        // Keep the source pixels and express the padding as standard SPS cropping.
        codedWidth=video?Math.max(176,(input.width+15)&~15):0;
        codedHeight=video?Math.max(144,(input.height+15)&~15):0;
        try {
            String decodeName=codec(input.sampleMimeType,false,false),encodeName=codec(video?"video/avc":"audio/mp4a-latm",true,video);
            decoderName=decodeName;
            if(!available(input) || decodeName==null || encodeName==null)throw new IOException("No compatible download codec");
            if(!video)primingFrames=AacEncoderPriming.frames(encodeName,audioRate,audioChannels);
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
                target=AacEncoderPriming.format(audioRate,audioChannels);
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
            if(!video)feedAudioEncoder();
            if(pending<0 && !decoderEnded && (video || !pcmOutput.hasRemaining())) {
                int next=decoder.dequeueOutputBuffer(decoded,0);
                if(next==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)decoderOutputs=decoder.getOutputBuffers();
                else if(next==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)validateDecoded(decoder.getOutputFormat());
                else if(next>=0) {pending=next;pendingOffset=0;pendingAligned=false;}
            }
            if(pending>=0) {
                if(!video) {convertPcm();continue;}
                int next=encoder.dequeueInputBuffer(0);
                if(next>=0) {
                    ByteBuffer target=encoderInputs[next];target.clear();
                    int count=0;
                    if(decoded.size>0) {
                        ByteBuffer source=decoderOutputs[pending].duplicate();source.position(decoded.offset);source.limit(decoded.offset+decoded.size);
                        count=copyVideo(source,target);
                    }
                    boolean eos=(decoded.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    encoder.queueInputBuffer(next,0,count,decoded.presentationTimeUs,eos?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);
                    decoder.releaseOutputBuffer(pending,false);pending=-1;if(eos)decoderEnded=true;
                }
            }
            if(!video && decoderEnded && !pcmOutput.hasRemaining()) {
                if(pcm==null)validateDecoded(decoder.getOutputFormat());
                if(!pcm.ended())pcmOutput=pcm.finish();
                feedAudioEncoder();
            }
        }
        return null;
    }
    private void validateDecoded(MediaFormat format) throws IOException {
        if(video) {
            int color=format.getInteger(MediaFormat.KEY_COLOR_FORMAT);
            if(color!=MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar && color!=MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                throw new IOException("Download decoder produced an unsupported YUV layout");
        } else {
            int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),encoding=integer(format,"pcm-encoding",2),mask=integer(format,"channel-mask",0);
            if(pcm!=null) {
                if(rate!=inputPcmRate || channels!=inputPcmChannels || encoding!=inputPcmEncoding || mask!=inputPcmMask)throw new IOException("Download decoder changed its PCM layout");
            }else {
                pcm=new LegacyPcmConverter(rate,channels,mask,encoding,audioRate,audioChannels,pcmChannelOrder(channels,mask));
                inputPcmRate=rate;inputPcmChannels=channels;inputPcmEncoding=encoding;inputPcmMask=mask;
                inputPcmFrameBytes=channels*(encoding==2?2:encoding==3?1:encoding==0x20000000?3:4);
            }
        }
        decodedFormat=format;
    }
    private int[] pcmChannelOrder(int channels,int mask) {
        // AOSP's pre-Oreo SoftVorbis/Tremolo hands out Vorbis-order PCM,
        // without a channel mask. Other components retain their declared order.
        if(android.os.Build.VERSION.SDK_INT>25 || mask!=0 || !"OMX.google.vorbis.decoder".equals(decoderName) || !MimeTypes.AUDIO_VORBIS.equals(input.sampleMimeType))return null;
        switch(channels) {
            case 3:return new int[]{0,2,1};case 5:return new int[]{0,2,1,3,4};
            case 6:return new int[]{0,2,1,5,3,4};case 7:return new int[]{0,2,1,6,3,4,5};
            case 8:return new int[]{0,2,1,7,5,6,3,4};default:return null;
        }
    }
    private void convertPcm() throws IOException {
        if(pcmOutput.hasRemaining())return;
        if(pcm==null)validateDecoded(decoder.getOutputFormat());
        if(decoded.size%inputPcmFrameBytes!=0)throw new IOException("Invalid decoded PCM frame alignment");
        if(decoded.size>pendingOffset) {
            if(firstAudioUs==Long.MIN_VALUE)firstAudioUs=inputAudioBaseUs=decoded.presentationTimeUs;
            if(!pendingAligned) {
                long expected=inputAudioBaseUs+inputAudioFrames*1000000/inputPcmRate,delta=decoded.presentationTimeUs-expected;
                if(Math.abs(delta)>2000 || overlapPending || pcm.ended()) {
                    if(!pcm.ended()) {pcmOutput=pcm.finish();return;}
                    long target=Math.round((decoded.presentationTimeUs-firstAudioUs)*(double)audioRate/1000000);
                    if(target>1000000L*1024)throw new IOException("PCM timeline exceeds the MP4 metadata budget");
                    if(target>outputAudioFrames) {
                        int frames=(int)Math.min(4096,target-outputAudioFrames);pcmOutput=ByteBuffer.allocate(frames*audioChannels*2);return;
                    }
                    long currentUs=firstAudioUs+outputAudioFrames*1000000/audioRate;
                    long skip=Math.max(0,(long)Math.ceil((currentUs-decoded.presentationTimeUs)*(double)inputPcmRate/1000000));
                    int frames=decoded.size/inputPcmFrameBytes;pendingOffset=(int)Math.min(frames,skip)*inputPcmFrameBytes;overlapPending=skip>=frames;
                    pcm.close();pcm=new LegacyPcmConverter(inputPcmRate,inputPcmChannels,inputPcmMask,inputPcmEncoding,audioRate,audioChannels,pcmChannelOrder(inputPcmChannels,inputPcmMask));
                    inputAudioBaseUs=decoded.presentationTimeUs+(pendingOffset/inputPcmFrameBytes)*1000000L/inputPcmRate;inputAudioFrames=0;
                }
                pendingAligned=true;
            }
            if(pendingOffset<decoded.size) {
                ByteBuffer source=decoderOutputs[pending].duplicate();source.position(decoded.offset+pendingOffset);
                int count=Math.min(decoded.size-pendingOffset,LegacyPcmConverter.MAX_INPUT_FRAMES*inputPcmFrameBytes);
                source.limit(source.position()+count);pcmOutput=pcm.convert(source);pendingOffset+=count;inputAudioFrames+=count/inputPcmFrameBytes;
            }
        }
        if(pendingOffset==decoded.size) {
            boolean eos=(decoded.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
            decoder.releaseOutputBuffer(pending,false);pending=-1;if(eos)decoderEnded=true;
        }
    }
    private void feedAudioEncoder() throws IOException {
        boolean drained=decoderEnded && pcm!=null && pcm.ended() && !pcmOutput.hasRemaining();
        if(drained && contentAudioFrames<0) {contentAudioFrames=outputAudioFrames;paddingAudioFrames=primingFrames;}
        boolean eos=drained && paddingAudioFrames==0;
        if(drained && paddingAudioFrames>0) {
            int frames=Math.min(4096,paddingAudioFrames);pcmOutput=ByteBuffer.allocate(frames*audioChannels*2);paddingAudioFrames-=frames;eos=false;
        }
        if(audioEosQueued || !pcmOutput.hasRemaining() && !eos)return;
        int next=encoder.dequeueInputBuffer(0);if(next<0)return;
        ByteBuffer target=encoderInputs[next];target.clear();int frame=audioChannels*2;
        int count=Math.min(pcmOutput.remaining(),target.remaining()/frame*frame);
        if(count==0 && !eos)throw new IOException("Empty AAC encoder capacity");
        ByteBuffer source=pcmOutput.duplicate();source.limit(source.position()+count);target.put(source);pcmOutput.position(pcmOutput.position()+count);
        long pts=(firstAudioUs==Long.MIN_VALUE?0:firstAudioUs)+outputAudioFrames*1000000/audioRate;
        encoder.queueInputBuffer(next,0,count,pts,eos?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);outputAudioFrames+=count/frame;if(eos)audioEosQueued=true;
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
        if(video)builder.setWidth(input.width).setHeight(input.height).setRotationDegrees(input.rotationDegrees).setFrameRate(input.frameRate).setColorInfo(LegacyExportColor.complete(input.colorInfo));
        else builder.setSampleRate(audioRate).setChannelCount(audioChannels);
        return builder.build();
    }
    @Override public void close() {
        if(closed)return;closed=true;
        try {release(decoder,decoderStarted);}finally {try {release(encoder,encoderStarted);}finally {if(pcm!=null)pcm.close();decoder=encoder=null;}}
    }
    private static void release(MediaCodec codec,boolean started) {
        if(codec==null)return;try {if(started)codec.stop();}finally {codec.release();}
    }
}
