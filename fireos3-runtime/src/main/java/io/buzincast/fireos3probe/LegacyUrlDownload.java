package io.buzincast.fireos3probe;

import android.content.Context;
import android.media.MediaCodec.BufferInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.muxer.Mp4Muxer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** API17 encoded-sample export. No MediaMuxer, decoder, surface or audio output. */
@androidx.annotation.OptIn(markerClass=UnstableApi.class)
public final class LegacyUrlDownload implements java.io.Closeable {
    public enum Phase {IDLE,LOADING,WRITING,SAVED,CANCELLED,FAILED,CLOSED}
    public static final class State {
        public final Phase phase;public final int progress;
        public final long positionUs,bytes;public final File file;public final Exception failure;
        State(Phase phase,int progress,long position,long bytes,File file,Exception failure) {
            this.phase=phase;this.progress=progress;this.positionUs=position;this.bytes=bytes;this.file=file;this.failure=failure;
        }
        public boolean busy() {return phase==Phase.LOADING || phase==Phase.WRITING;}
    }
    public interface Listener {void changed(State state);}
    private static final int SAMPLE_LIMIT=8*1024*1024;
    private final Context context;private final Listener listener;
    private final HandlerThread thread=new HandlerThread("buzin-legacy-download");
    private final Handler worker;private final AtomicBoolean closed=new AtomicBoolean();
    private volatile State state=new State(Phase.IDLE,-1,0,0,null,null);
    private Job job;
    public LegacyUrlDownload(Context context,Listener listener) {
        this.context=context.getApplicationContext();this.listener=listener;thread.start();worker=new Handler(thread.getLooper());
    }
    public State state() {return state;}
    public void start(LegacyUrlRequest request,File destination) {
        if(request==null || request.localRecording || destination==null)throw new IllegalArgumentException("Expected network media and an output file");
        if(closed.get())throw new IllegalStateException("Downloader is closed");
        worker.post(()-> {
            if(closed.get() || job!=null)return;
            Job next=new Job(request.withPosition(0),destination);job=next;
            next.publish(Phase.LOADING,null);
            try {next.begin();}catch(Exception error) {next.finish(Phase.FAILED,error);}
        });
    }
    public void cancel() {worker.post(()-> {if(job!=null)job.finish(Phase.CANCELLED,null);});}
    @Override public void close() {
        if(closed.compareAndSet(false,true))worker.post(()-> {
            if(job!=null)job.finish(Phase.CANCELLED,null);
            state=new State(Phase.CLOSED,-1,0,0,null,null);listener.changed(state);thread.quit();
        });
    }
    private final class Job implements MediaSource.MediaSourceCaller,MediaPeriod.Callback {
        final LegacyUrlRequest request;final File destination,temporary;
        MediaSource source;MediaPeriod period;Timeline timeline;Mp4Muxer muxer;
        FileOutputStream output;boolean prepared,finished,ownsTemporary;
        int periodIndex;long durationUs=C.TIME_UNSET,periodOffsetUs,positionUs,bytes,samples,lastProgressMs,lastDataMs,minimumPtsUs=Long.MAX_VALUE;
        final DefaultAllocator allocator=new DefaultAllocator(true,C.DEFAULT_BUFFER_SEGMENT_SIZE);
        final List<Track> tracks=new ArrayList<>();
        final List<Binding> bindings=new ArrayList<>();
        final Runnable pump=()-> {
            if(finished)return;
            try {tick();}catch(Exception error) {finish(Phase.FAILED,error);}
        };
        Job(LegacyUrlRequest request,File destination) {
            this.request=request;this.destination=destination;
            temporary=new File(destination.getParentFile(),destination.getName()+".part");
        }
        void begin() throws IOException {
            File parent=destination.getParentFile();
            if(parent==null || (!parent.isDirectory() && !parent.mkdirs()) || parent.getUsableSpace()<128L*1024*1024 || destination.exists() || !temporary.createNewFile())
                throw new IOException("Output is unavailable");
            ownsTemporary=true;
            output=new FileOutputStream(temporary);
            muxer=new Mp4Muxer.Builder(output).setLastFrameDurationBehavior(Mp4Muxer.LAST_FRAME_DURATION_BEHAVIOR_DUPLICATE_PREV_DURATION).build();
            DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent("BuzinCast")
                .setConnectTimeoutMs(10000).setReadTimeoutMs(15000).setAllowCrossProtocolRedirects(false).setDefaultRequestProperties(request.headers);
            ResolvingDataSource.Factory network=new ResolvingDataSource.Factory(http,spec-> {
                String scheme=spec.uri.getScheme();
                if((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) || spec.uri.getHost()==null ||
                    spec.uri.getUserInfo()!=null || spec.uri.toString().length()>8192)throw new ProtocolException("Expected HTTP(S) media resource");
                return spec;
            });
            MediaItem.Builder item=new MediaItem.Builder().setUri(request.location);
            if(request.mime!=null)item.setMimeType(request.mime);
            source=new DefaultMediaSourceFactory(network).setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(2)).createMediaSource(item.build());
            lastDataMs=SystemClock.elapsedRealtime();source.prepareSource(this,null,PlayerId.UNSET);schedule(0);
        }
        @Override public void onSourceInfoRefreshed(MediaSource source,Timeline timeline) {
            if(finished)return;
            try {
                if(timeline.isEmpty())return;
                Timeline.Window window=timeline.getWindow(0,new Timeline.Window());
                if(window.isLive() || window.isDynamic)throw new IOException("Live export is unavailable");
                if(timeline.getWindowCount()!=1)throw new IOException("Expected one media item");
                this.timeline=timeline;durationUs=window.durationUs;
                if(period==null)openPeriod();
            }catch(Exception error) {finish(Phase.FAILED,error);}
        }
        void openPeriod() throws IOException {
            if(periodIndex>=timeline.getPeriodCount()) {finish(Phase.SAVED,null);return;}
            Timeline.Period info=timeline.getPeriod(periodIndex,new Timeline.Period());
            periodOffsetUs=info.getPositionInWindowUs();
            if(periodOffsetUs==C.TIME_UNSET)throw new IOException("Unknown period timing");
            prepared=false;bindings.clear();
            period=source.createPeriod(new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(periodIndex)),allocator,0);
            period.prepare(this,0);schedule(0);
        }
        @Override public void onPrepared(MediaPeriod mediaPeriod) {
            if(finished || mediaPeriod!=period)return;
            try {
                TrackGroupArray groups=period.getTrackGroups();List<ExoTrackSelection> selections=new ArrayList<>();
                for(int kind:new int[]{C.TRACK_TYPE_VIDEO,C.TRACK_TYPE_AUDIO}) {
                    TrackGroup bestGroup=null;int bestIndex=0;long score=Long.MIN_VALUE;boolean present=false;
                    for(int group=0;group<groups.length;group++) {
                        TrackGroup candidate=groups.get(group);if(candidate.type!=kind)continue;present=true;
                        for(int index=0;index<candidate.length;index++) {
                            Format format=candidate.getFormat(index);if(!supported(format) || format.drmInitData!=null)continue;
                            long rank=kind==C.TRACK_TYPE_VIDEO?(long)Math.max(0,format.width)*Math.max(0,format.height)*1000000L+Math.max(0,format.bitrate):Math.max(0,format.bitrate);
                            if(rank>score) {score=rank;bestGroup=candidate;bestIndex=index;}
                        }
                    }
                    if(bestGroup==null) {if(present)throw new IOException("Media requires transcoding or is protected");continue;}
                    selections.add(new FixedTrackSelection(bestGroup,bestIndex));
                }
                if(selections.isEmpty())throw new IOException("No supported media tracks");
                if(periodIndex>0 && selections.size()!=tracks.size())throw new IOException("Media track layout changed");
                if(periodIndex==0)while(tracks.size()<selections.size())tracks.add(null);
                ExoTrackSelection[] selected=selections.toArray(new ExoTrackSelection[0]);SampleStream[] streams=new SampleStream[selected.length];
                for(ExoTrackSelection selection:selected)selection.enable();
                period.selectTracks(selected,new boolean[selected.length],streams,new boolean[selected.length],0);
                for(int index=0;index<streams.length;index++) {
                    if(streams[index]==null)throw new IOException("Missing selected media stream");
                    bindings.add(new Binding(streams[index],selected[index],index));
                }
                prepared=true;lastDataMs=SystemClock.elapsedRealtime();publish(Phase.WRITING,null);schedule(0);
            }catch(Exception error) {finish(Phase.FAILED,error);}
        }
        @Override public void onContinueLoadingRequested(MediaPeriod mediaPeriod) {if(!finished && period==mediaPeriod)schedule(0);}
        void schedule(long delay) {worker.removeCallbacks(pump);if(!finished)worker.postDelayed(pump,delay);}
        void tick() throws IOException {
            source.maybeThrowSourceInfoRefreshError();
            if(period==null) {if(SystemClock.elapsedRealtime()-lastDataMs>45000)throw new IOException("Media preparation timed out");schedule(20);return;}
            if(!prepared) {period.maybeThrowPrepareError();continueLoading();schedule(20);return;}
            long end=SystemClock.elapsedRealtime()+15;boolean blocked=false;
            while(SystemClock.elapsedRealtime()<end && !blocked) {
                boolean allEnded=true,allReady=true;Binding next=null;
                for(Binding binding:bindings) {
                    binding.read();allEnded&=binding.ended;allReady&=binding.ended || binding.sample!=null;
                    if(binding.sample!=null && (next==null || binding.ptsUs<next.ptsUs))next=binding;
                }
                if(allEnded) {nextPeriod();return;}
                if(!allReady || next==null) {blocked=true;break;}
                BufferInfo info=new BufferInfo();long pts=periodOffsetUs+next.ptsUs;
                info.set(0,next.sample.remaining(),pts,next.key?android.media.MediaCodec.BUFFER_FLAG_SYNC_FRAME:0);
                muxer.writeSampleData(tracks.get(next.index).token,next.sample,info);
                tracks.get(next.index).remember(pts);minimumPtsUs=Math.min(minimumPtsUs,pts);
                bytes+=info.size;samples++;positionUs=Math.max(positionUs,pts);next.sample=null;lastDataMs=SystemClock.elapsedRealtime();
            }
            long position=positionInPeriod();period.discardBuffer(position,false);continueLoading();
            if(allocator.getTotalBytesAllocated()>32*1024*1024)throw new IOException("Media buffering exceeded its budget");
            long now=SystemClock.elapsedRealtime();
            if(now-lastDataMs>45000)throw new IOException("Media loading timed out");
            if(now-lastProgressMs>=250) {
                lastProgressMs=now;if(temporary.getParentFile().getUsableSpace()<32L*1024*1024)throw new IOException("Storage is nearly full");
                publish(Phase.WRITING,null);
            }
            schedule(blocked?10:0);
        }
        long positionInPeriod() {return Math.max(0,positionUs-periodOffsetUs);}
        void continueLoading() {
            period.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(positionInPeriod()).setPlaybackSpeed(1).build());
        }
        void nextPeriod() throws IOException {
            for(Binding binding:bindings)binding.selection.disable();
            source.releasePeriod(period);period=null;bindings.clear();periodIndex++;openPeriod();
        }
        void publish(Phase phase,Exception failure) {
            int progress=durationUs>0?(int)Math.min(99,positionUs*100/durationUs):-1;
            if(phase==Phase.SAVED)progress=100;
            state=new State(phase,progress,positionUs,bytes,phase==Phase.SAVED?destination:null,failure);listener.changed(state);
        }
        void finish(Phase phase,Exception failure) {
            if(finished)return;finished=true;worker.removeCallbacks(pump);
            try {
                for(Binding binding:bindings)binding.selection.disable();
                if(period!=null)source.releasePeriod(period);
                if(source!=null)source.releaseSource(this);
            }catch(Exception error) {if(failure==null)failure=error;phase=Phase.FAILED;}
            try {if(muxer!=null)muxer.close();}catch(Exception error) {if(failure==null)failure=error;phase=Phase.FAILED;}
            try {if(output!=null)output.close();}catch(IOException error) {if(failure==null)failure=error;phase=Phase.FAILED;}
            if(phase==Phase.SAVED)try {
                List<long[]> timing=new ArrayList<>();for(Track track:tracks)if(track!=null)timing.add(track.timestamps());
                Mp4CompositionRepair.repair(temporary,timing,minimumPtsUs);
            }catch(IOException error) {failure=error;phase=Phase.FAILED;}
            if(phase==Phase.SAVED) {
                if(samples==0 || !temporary.isFile() || destination.exists() || !temporary.renameTo(destination)) {failure=new IOException("Could not publish media");phase=Phase.FAILED;}
            }
            if(phase!=Phase.SAVED && ownsTemporary)temporary.delete();
            job=null;publish(phase,failure);
        }
        final class Binding {
            final SampleStream stream;final ExoTrackSelection selection;final int index;
            final DecoderInputBuffer buffer=new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
            final FormatHolder holder=new FormatHolder();
            boolean ended,key;long ptsUs;ByteBuffer sample;Format format;
            Binding(SampleStream stream,ExoTrackSelection selection,int index) {this.stream=stream;this.selection=selection;this.index=index;}
            void read() throws IOException {
                if(ended || sample!=null)return;stream.maybeThrowError();
                for(int attempt=0;attempt<4;attempt++) {
                    buffer.clear();holder.clear();int result=stream.readData(holder,buffer,0);
                    if(result==C.RESULT_FORMAT_READ) {
                        format=holder.format;
                        if(format==null || !supported(format) || format.drmInitData!=null)throw new IOException("Unsupported or protected media format");
                        format=format.buildUpon().setLanguage(IsoMediaLanguage.normalize(format.language)).build();
                        if(nalVideo(format)) {
                            List<byte[]> data=new ArrayList<>();
                            for(byte[] item:format.initializationData)data.add(AnnexBNormalization.normalize(item,128*1024));
                            format=format.buildUpon().setInitializationData(data).build();
                        }
                    } else if(result==C.RESULT_BUFFER_READ) {
                        if(buffer.isEndOfStream()) {ended=true;return;}
                        if(buffer.isEncrypted())throw new IOException("Encrypted sample export is unavailable");
                        if(buffer.isDecodeOnly())continue;
                        if(buffer.data==null || buffer.data.position()==0)continue;
                        if(format==null)throw new IOException("Media sample preceded its format");
                        if(tracks.get(index)==null) {
                            tracks.set(index,new Track(format,muxer.addTrack(index,format)));
                            if(MimeTypes.isVideo(format.sampleMimeType))muxer.setOrientation(Math.max(0,format.rotationDegrees));
                        } else if(!compatible(tracks.get(index).format,format))throw new IOException("Media format changed during export");
                        buffer.flip();int size=buffer.data.remaining();if(size>SAMPLE_LIMIT)throw new IOException("Media sample exceeded its budget");
                        if(nalVideo(format))sample=AnnexBNormalization.normalize(buffer.data,SAMPLE_LIMIT);
                        else {sample=ByteBuffer.allocateDirect(size);sample.put(buffer.data);sample.flip();}
                        ptsUs=buffer.timeUs;key=buffer.isKeyFrame();return;
                    } else return;
                }
            }
        }
    }
    private static final class Track {
        final Format format;final Mp4Muxer.TrackToken token;
        private long[] pts;private int count;
        Track(Format format,Mp4Muxer.TrackToken token) {
            this.format=format;this.token=token;
            boolean baseline=MimeTypes.VIDEO_H264.equals(format.sampleMimeType) && !format.initializationData.isEmpty() &&
                format.initializationData.get(0).length>5 && (format.initializationData.get(0)[5]&255)==66;
            if(MimeTypes.isVideo(format.sampleMimeType) && !baseline)pts=new long[1024];
        }
        void remember(long value) throws IOException {
            if(pts==null)return;
            if(count==1000000)throw new IOException("Composition timestamp metadata exceeded its budget");
            if(count==pts.length)pts=Arrays.copyOf(pts,Math.min(1000000,pts.length*2));pts[count++]=value;
        }
        long[] timestamps() {return pts==null?null:Arrays.copyOf(pts,count);}
    }
    private static boolean supported(Format format) {
        return Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES.contains(format.sampleMimeType) || Mp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES.contains(format.sampleMimeType);
    }
    private static boolean nalVideo(Format format) {return MimeTypes.VIDEO_H264.equals(format.sampleMimeType) || MimeTypes.VIDEO_H265.equals(format.sampleMimeType);}
    private static boolean compatible(Format a,Format b) {
        if(!a.sampleMimeType.equals(b.sampleMimeType) || a.width!=b.width || a.height!=b.height || a.rotationDegrees!=b.rotationDegrees ||
            a.sampleRate!=b.sampleRate || a.channelCount!=b.channelCount || a.initializationData.size()!=b.initializationData.size())return false;
        for(int i=0;i<a.initializationData.size();i++)if(!Arrays.equals(a.initializationData.get(i),b.initializationData.get(i)))return false;
        return true;
    }
}
