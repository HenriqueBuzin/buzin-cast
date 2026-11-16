package io.buzincast.fireos3probe;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioPlaybackClockTest {
    private static final long NOW=1000000000L, PTS=1700000000000000000L;
    @Test public void recentAudioSurvivesAShortHoleButNotFlushOrExpiry() {
        AudioPlaybackClock clock=new AudioPlaybackClock();assertFalse(clock.recentlyActive(NOW));
        clock.submit(0,441,PTS);clock.updateHead(0,NOW);assertEquals(PTS,clock.positionNs(NOW));
        clock.updateHead(441,NOW+10000000L);
        assertEquals(AudioPlaybackClock.UNKNOWN,clock.positionNs(NOW+10000000L));
        assertTrue(clock.recentlyActive(NOW+20000000L));
        assertFalse(clock.recentlyActive(NOW+300000000L));
        clock.reset();assertFalse(clock.recentlyActive(NOW+20000000L));
    }
    @Test public void repeatedCoarseCounterDoesNotRewindOrKeepAStallFresh() {
        AudioPlaybackClock clock=new AudioPlaybackClock();clock.submit(0,4096,PTS);clock.updateHead(0,NOW);
        long predicted=clock.positionNs(NOW+50000000L);
        clock.updateHead(0,NOW+50000000L);
        assertEquals(predicted,clock.positionNs(NOW+50000000L));
        clock.updateHead(0,NOW+300000000L);
        assertEquals(AudioPlaybackClock.UNKNOWN,clock.positionNs(NOW+300000000L));
    }
    @Test public void predictionStaysOnKnownPcmUntilHardwareCrossesItsEnd() {
        AudioPlaybackClock clock=new AudioPlaybackClock();clock.submit(0,441,PTS);clock.updateHead(0,NOW);
        assertEquals(PTS+440*1000000000L/44100,clock.positionNs(NOW+20000000L));
        clock.updateHead(441,NOW+20000000L);
        assertEquals(AudioPlaybackClock.UNKNOWN,clock.positionNs(NOW+20000000L));
    }
    @Test public void waitsUntilTimestampedAudioReachesPlaybackHead() {
        AudioPlaybackClock clock=new AudioPlaybackClock();
        clock.submit(4410,441,PTS); clock.updateHead(0,NOW);
        assertEquals(100000000L,clock.delayNs(PTS,NOW));
        assertEquals(50000000L,clock.delayNs(PTS,NOW+50000000L));
        clock.updateHead(4410,NOW+100000000L);
        assertEquals(PTS,clock.positionNs(NOW+100000000L));
    }
    @Test public void mapsDiscontinuitiesWithoutInventingTimestamps() {
        AudioPlaybackClock clock=new AudioPlaybackClock();
        clock.submit(0,441,PTS); clock.submit(882,441,PTS+2000000000L);
        clock.updateHead(200,NOW);
        assertTrue(clock.delayNs(PTS,NOW)<0);
        clock.updateHead(500,NOW+10000000L);
        assertEquals(PTS+2000000000L-382*1000000000L/44100,clock.positionNs(NOW+10000000L));
        clock.updateHead(882,NOW+20000000L);
        assertEquals(PTS+2000000000L,clock.positionNs(NOW+20000000L));
    }
    @Test public void silenceStaleHeadAndUnknownVideoDoNotFreezeVideo() {
        AudioPlaybackClock clock=new AudioPlaybackClock();
        clock.updateHead(0,NOW); assertEquals(AudioPlaybackClock.UNKNOWN,clock.positionNs(NOW));
        clock.submit(0,441,PTS);
        assertEquals(AudioPlaybackClock.UNKNOWN,clock.delayNs(0,NOW));
        assertEquals(AudioPlaybackClock.UNKNOWN,clock.positionNs(NOW+300000000L));
        clock.updateHead(441,NOW+10000000L);
        assertEquals(AudioPlaybackClock.UNKNOWN,clock.positionNs(NOW+10000000L));
    }
    @Test public void playbackCounterWrapsAfterManyHours() {
        AudioPlaybackClock clock=new AudioPlaybackClock();
        long first=0xfffffff0L;
        clock.submit(first,441,PTS); clock.updateHead(first,NOW);
        assertEquals((1L<<32)+16,clock.updateHead(16,NOW+1000000L));
        assertEquals(PTS+32*1000000000L/44100,clock.positionNs(NOW+1000000L));
    }
    @Test public void unexpectedCounterResetInvalidatesOldMapping() {
        AudioPlaybackClock clock=new AudioPlaybackClock();
        clock.submit(0,441,PTS); clock.updateHead(100,NOW); clock.updateHead(0,NOW+1000000L);
        assertEquals(AudioPlaybackClock.UNKNOWN,clock.positionNs(NOW+1000000L));
        clock.reset(); clock.submit(0,441,PTS+1000000000L); clock.updateHead(0,NOW+2000000L);
        assertEquals(PTS+1000000000L,clock.positionNs(NOW+2000000L));
    }
    @Test public void rejectsInvalidOrOverlappingMetadataAndResetsOnFlush() {
        AudioPlaybackClock clock=new AudioPlaybackClock();
        clock.submit(0,441,PTS); clock.submit(100,441,PTS+1000000000L);
        clock.submit(441,0,PTS); clock.submit(441,441,0); clock.submit(Long.MAX_VALUE,441,PTS);
        clock.updateHead(200,NOW);
        assertEquals(PTS+200*1000000000L/44100,clock.positionNs(NOW));
        clock.reset(); assertEquals(AudioPlaybackClock.UNKNOWN,clock.positionNs(NOW));
    }
}
