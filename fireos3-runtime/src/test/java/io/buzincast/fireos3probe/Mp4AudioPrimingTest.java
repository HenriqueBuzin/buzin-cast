package io.buzincast.fireos3probe;

import org.junit.Test;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;
import static io.buzincast.fireos3probe.Mp4CompositionRepair.*;

public class Mp4AudioPrimingTest {
    private static Node box(String name,Node...children) {Node node=new Node(fourcc(name),8,null);node.children=new ArrayList<>(Arrays.asList(children));return node;}
    private static Node header(String type,int scale,int duration) throws IOException {
        byte[] body=new byte[40];ByteBuffer data=ByteBuffer.wrap(body);
        if(type.equals("tkhd"))data.putInt(20,duration);else {data.putInt(12,scale);data.putInt(16,duration);}return Node.leaf(type,body);
    }
    private static Node track(int scale,long[] durations) throws IOException {
        byte[] size=new byte[12];ByteBuffer.wrap(size).putInt(8,durations.length);byte[] offsets=new byte[16];ByteBuffer.wrap(offsets).putInt(4,1).putLong(8,16);
        long total=0;for(long value:durations)total+=value;
        return box("trak",header("tkhd",1000,(int)(total*1000/scale)),box("mdia",header("mdhd",scale,(int)total),box("minf",box("stbl",Node.leaf("stsz",size),table("stts",durations,false),Node.leaf("co64",offsets)))));
    }
    private static File movie(Node...tracks) throws Exception {
        File file=File.createTempFile("aac-edit-",".mp4");List<Node> children=new ArrayList<>();children.add(header("mvhd",1000,1000));children.addAll(Arrays.asList(tracks));
        try(OutputStream output=new FileOutputStream(file)) {output.write(Node.leaf("ftyp",new byte[0]).encode());output.write(Node.leaf("mdat",new byte[]{0,1,2,3,4,5,6,7}).encode());output.write(box("moov",children.toArray(new Node[0])).encode());}return file;
    }
    private static Node read(File file) throws Exception {
        byte[] data=Files.readAllBytes(file.toPath());ByteBuffer bytes=ByteBuffer.wrap(data);
        for(int at=0;at<data.length;) {int size=bytes.getInt(at);if(bytes.getInt(at+4)==fourcc("moov"))return parse(data,at,at+size,0);at+=size;}throw new AssertionError("No moov");
    }
    private static Mp4AudioPriming.Track audio(int rate,long frames,Mp4AudioPriming.Segment...segments) {return new Mp4AudioPriming.Track(0,rate,frames,Arrays.asList(segments));}
    private static Node audioTrack(Node movie) {return movie.children.get(movie.children.size()-1);}
    @Test public void trimsOnlyPresentationAndKeepsAacPrerollAndPayload() throws Exception {
        File file=movie(track(90000,new long[]{3000,3000,3000}),track(48000,new long[]{1024,1024,1024,1024}));
        try {
            Mp4AudioPriming.repair(file,Arrays.asList(new Mp4AudioPriming.Track(0,0,0,Collections.emptyList()),audio(48000,4096,new Mp4AudioPriming.Segment(0,4096,1024,2048,0))),0,-1);
            Node moov=read(file),audio=audioTrack(moov),edit=audio.child("edts").child("elst");ByteBuffer data=ByteBuffer.wrap(edit.data);
            assertEquals(0x01000000,data.getInt(8));assertEquals(1,data.getInt(12));assertEquals(42,data.getLong(16));assertEquals(1024,data.getLong(24));
            assertEquals(4096,duration(audio.child("mdia").child("mdhd"),false));assertEquals(42,duration(audio.child("tkhd"),true));assertEquals(100,duration(moov.child("mvhd"),false));
            assertArrayEquals(new byte[]{0,1,2,3,4,5,6,7},Arrays.copyOfRange(Files.readAllBytes(file.toPath()),16,24));
        }finally {file.delete();}
    }
    @Test public void separateEncoderSessionsAndPresentationGapsUseDistinctMediaRanges() throws Exception {
        File file=movie(track(48000,new long[]{1024,1024,1024,1024,1024,1024,1024,1024}));
        try {
            Mp4AudioPriming.repair(file,Collections.singletonList(audio(48000,8192,new Mp4AudioPriming.Segment(0,4096,1024,2048,0),new Mp4AudioPriming.Segment(4096,4096,1024,2048,1000000))),0,-1);
            Node audio=audioTrack(read(file)),edit=audio.child("edts").child("elst");ByteBuffer data=ByteBuffer.wrap(edit.data);
            assertEquals(3,data.getInt(12));assertEquals(1024,data.getLong(24));assertEquals(958,data.getLong(36));assertEquals(-1,data.getLong(44));assertEquals(5120,data.getLong(64));
            assertEquals(1042,duration(audio.child("tkhd"),true));
        }finally {file.delete();}
    }
    @Test public void fractionalAudioScaleAndDelayedVideoRetainTheirPresentationClock() throws Exception {
        File file=movie(track(90000,new long[]{48000,3000,3000}),track(48000,new long[]{1114,1115,1114}));
        try {
            Mp4AudioPriming.repair(file,Arrays.asList(new Mp4AudioPriming.Track(500000,0,0,Collections.emptyList()),audio(44100,3072,new Mp4AudioPriming.Segment(0,3072,100,2900,0))),0,-1);
            Node moov=read(file),video=moov.children.get(1),audio=moov.children.get(2);
            assertEquals(9000,duration(video.child("mdia").child("mdhd"),false));assertEquals(600,duration(video.child("tkhd"),true));
            assertEquals(3343,duration(audio.child("mdia").child("mdhd"),false));assertEquals(108,ByteBuffer.wrap(audio.child("edts").child("elst").data).getLong(24));
        }finally {file.delete();}
    }
    @Test public void rejectsMissingFlushBeforeChangingThePrivateFile() throws Exception {
        File file=movie(track(48000,new long[]{1024,1024}));
        try {byte[] before=Files.readAllBytes(file.toPath());assertThrows(IOException.class,()->Mp4AudioPriming.repair(file,Collections.singletonList(audio(48000,2048,new Mp4AudioPriming.Segment(0,2048,1024,2048,0))),0,-1));assertArrayEquals(before,Files.readAllBytes(file.toPath()));}
        finally {file.delete();}
    }
    @Test public void lowRatePrimingIsExpressedInSamplesRatherThanAFixedMillisecondOffset() throws Exception {
        File file=movie(track(48000,new long[]{1024,1024,1024,1024}));
        try {
            Mp4AudioPriming.repair(file,Collections.singletonList(audio(8000,4096,new Mp4AudioPriming.Segment(0,4096,1024,2048,0))),0,-1);
            Node audio=audioTrack(read(file));assertEquals(6144,ByteBuffer.wrap(audio.child("edts").child("elst").data).getLong(24));
            assertEquals(256,duration(audio.child("tkhd"),true));assertEquals(24576,duration(audio.child("mdia").child("mdhd"),false));
        }finally {file.delete();}
    }
    @Test public void measuresSignalDelayDespiteLossyGainAndRejectsUnrelatedAudio() throws Exception {
        short[] source=new short[4096],decoded=new short[5000];int random=19;
        for(int i=0;i<source.length;i++) {random=random*1664525+1013904223;source[i]=(short)(random>>>16);decoded[137+i]=(short)(source[i]*.7);}
        assertEquals(137,AacEncoderPriming.correlate(source,decoded,512));
        assertThrows(IOException.class,()->AacEncoderPriming.correlate(source,new short[5000],512));
        assertThrows(IOException.class,()->AacEncoderPriming.correlate(source,new short[100],512));
    }
}
