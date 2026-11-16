package io.buzincast.fireos3probe;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class LegacyTrackTest {
    private static byte[] item(String tag,byte[] value) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeBytes(tag);out.writeInt(value.length);out.write(value);return bytes.toByteArray();
    }
    private static byte[] join(byte[]...items) throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] item:items)out.write(item);return out.toByteArray();}
    @Test public void nestedUtf8TrackAndUnsignedDurationMatchSenderFields() throws Exception {
        byte[] payload=join(item("minm","Canção · שיר".getBytes("UTF-8")),item("asar","Artist".getBytes("UTF-8")),
            item("asal","Album".getBytes("UTF-8")),item("asgn","Genre".getBytes("UTF-8")),item("astm",new byte[]{-1,-1,-1,-1}));
        LegacyTrack track=LegacyTrack.parse(item("mcon",item("mlit",payload)));
        assertEquals("Canção · שיר",track.title);assertEquals("Artist",track.artist);assertEquals("Album",track.album);
        assertEquals("Genre",track.genre);assertEquals(0xffffffffL,track.durationMs);
    }
    @Test public void truncatedOverflowingOrDeepPacketsAreRejectedAtomically() throws Exception {
        byte[] good=item("minm",new byte[]{65});byte[] malformed=join(good,new byte[]{1});
        assertThrows(IOException.class,()->LegacyTrack.parse(malformed));
        byte[] invalid=item("minm",new byte[0]);invalid[4]=(byte)0xff;
        final byte[] negative=invalid;assertThrows(IOException.class,()->LegacyTrack.parse(negative));
        assertThrows(IOException.class,()->LegacyTrack.parse(item("astm",new byte[]{-1,-1,-1,-1,-1,-1,-1,-1})));
        byte[] deep=good;for(int i=0;i<10;i++)deep=item("mlit",deep);
        final byte[] nested=deep;assertThrows(IOException.class,()->LegacyTrack.parse(nested));
    }
    @Test public void unknownTagsAreSkippedAndRecordTextAndPayloadBudgetsAreEnforced() throws Exception {
        assertEquals("A",LegacyTrack.parse(join(item("junk",new byte[]{0,1,2}),item("minm",new byte[]{65}))).title);
        assertThrows(IOException.class,()->LegacyTrack.parse(new byte[LegacyTrack.MAX_BYTES+1]));
        assertThrows(IOException.class,()->LegacyTrack.parse(item("minm",new byte[8193])));
        ByteArrayOutputStream many=new ByteArrayOutputStream();for(int i=0;i<257;i++)many.write(item("junk",new byte[0]));
        assertThrows(IOException.class,()->LegacyTrack.parse(many.toByteArray()));
    }
    @Test public void timelineHandlesRolloverPauseResumeClampingAndDegenerateReports() {
        LegacyTrackTimeline clock=new LegacyTrackTimeline();long start=0xffff0000L;
        assertTrue(clock.progress(start,(start+44100)&0xffffffffL,(start+441000)&0xffffffffL,100));
        assertEquals(1000,clock.position(100));assertEquals(2000,clock.position(1100));assertEquals(10000,clock.duration());
        clock.playing(false,1100);assertEquals(2000,clock.position(5100));
        assertFalse(clock.progress(0,0,0,5100));assertEquals(10000,clock.duration());
        clock.playing(true,5100);assertEquals(3000,clock.position(6100));assertEquals(10000,clock.position(100000));
        clock.clear(100000);assertEquals(0,clock.duration());assertEquals(0,clock.position(100001));
        assertFalse(clock.progress(-1,1,2,1));assertFalse(clock.progress(0x100000000L,1,2,1));
        clock.progress(44100,44000,88200,1);assertEquals(0,clock.position(1));
        clock.progress(44100,88300,88200,1);assertEquals(1000,clock.position(1));
    }
}
