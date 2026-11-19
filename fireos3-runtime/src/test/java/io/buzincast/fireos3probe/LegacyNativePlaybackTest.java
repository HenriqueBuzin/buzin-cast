package io.buzincast.fireos3probe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyNativePlaybackTest {
    private static final class Target implements LegacyNativePlayback.Listener {
        final List<String> commands=new ArrayList<>();
        @Override public void play(long token,LegacyUrlRequest request) {commands.add("play:"+token+":"+request.startMs);}
        @Override public void seek(long token,float seconds) {commands.add("seek:"+token+":"+seconds);}
        @Override public void rate(long token,float rate) {commands.add("rate:"+token+":"+rate);}
        @Override public void stop(long token) {commands.add("stop:"+token);}
    }
    @Test public void staleReportsCannotReplaceOrResurrectMedia() {
        LegacyNativePlayback media=new LegacyNativePlayback();Target target=new Target();media.listener(target);
        media.play("http://example.test/a.mp4",1.25f);long first=media.state().token;
        assertFalse(media.state().ready);assertTrue(media.update(first,2,8,1,true,false));
        media.play("http://example.test/b.mp4",0);long second=media.state().token;
        assertNotEquals(first,second);assertFalse(media.update(first,7,8,1,true,false));assertFalse(media.state().ready);
        media.finish(first);assertEquals(second,media.state().token);assertEquals(0,media.state().duration,0);
        media.finish(second);assertFalse(media.update(second,1,8,1,true,false));assertEquals(-1,media.state().duration,0);
        media.close();media.play("http://example.test/c.mp4",0);assertEquals(2,target.commands.size());
    }
    @Test public void controlsCarryTheCurrentTokenAndIgnoreUnsupportedNumbers() {
        LegacyNativePlayback media=new LegacyNativePlayback();Target target=new Target();media.listener(target);
        media.seek(1);media.rate(1);assertTrue(target.commands.isEmpty());
        media.play("http://example.test/a.mp4",0);media.seek(2.5f);media.rate(-1);media.rate(4);
        media.seek(Float.NaN);media.seek(-1);media.seek(Float.MAX_VALUE);media.rate(Float.POSITIVE_INFINITY);media.rate(.125f);media.rate(4.1f);
        assertEquals(java.util.Arrays.asList("play:1:0","seek:1:2.5","rate:1:0.0","rate:1:4.0"),target.commands);
        media.stop();media.stop();assertEquals("stop:1",target.commands.get(4));assertEquals(5,target.commands.size());
    }
    @Test public void unsupportedFrontendRespondsStoppedInsteadOfWaitingForAPlayer() throws Exception {
        LegacyNativePlayback media=new LegacyNativePlayback();media.play("http://example.test/a.mp4",0);
        assertFalse(media.enabled());assertTrue(media.state().ready);assertEquals(-1,media.state().duration,0);
        media.awaitReady(1);
    }
    @Test public void updateCloseAndReplacementReleaseAWaitingNetworkWorker() throws Exception {
        for(int mode=0;mode<3;mode++) {
            LegacyNativePlayback media=new LegacyNativePlayback();media.listener(new Target());media.play("http://example.test/a.mp4",0);
            CountDownLatch entered=new CountDownLatch(1);
            FutureTask<Void> task=new FutureTask<>(()-> {entered.countDown();media.awaitReady(10000);return null;});
            Thread worker=new Thread(task,"native-ready-test");worker.start();assertTrue(entered.await(2,TimeUnit.SECONDS));
            long deadline=System.nanoTime()+2000000000L;
            while(worker.getState()!=Thread.State.TIMED_WAITING && System.nanoTime()<deadline)Thread.sleep(1);
            assertEquals(Thread.State.TIMED_WAITING,worker.getState());
            if(mode==0)media.update(1,0,8,0,true,false);
            else if(mode==1)media.close();else media.play("http://example.test/b.mp4",0);
            task.get(2,TimeUnit.SECONDS);worker.join(2000);assertFalse(worker.isAlive());
            if(mode==2)assertFalse(media.state().ready);
        }
    }
    @Test public void invalidPlayLeavesTheExistingSessionUntouched() {
        LegacyNativePlayback media=new LegacyNativePlayback();media.listener(new Target());media.play("http://example.test/a.mp4",0);
        for(float time:new float[]{-0.0001f,Float.NaN,Float.POSITIVE_INFINITY,Float.MAX_VALUE}) {
            try {media.play("http://example.test/b.mp4",time);fail();}catch(IllegalArgumentException expected) { }
        }
        try {media.play("file:///private",0);fail();}catch(IllegalArgumentException expected) { }
        assertEquals(1,media.state().token);
    }
    @Test public void abandonedConnectionStopsPausedMediaButNotBufferingMedia() {
        VideoFrameQueue queue=new VideoFrameQueue();Target target=new Target();queue.url.listener(target);
        queue.url.play("http://example.test/a.mp4",0);queue.url.update(1,0,8,0,true,true);
        queue.onVideoUrlReset(true);assertEquals(0,queue.url.state().position,0);assertEquals(8,queue.url.state().duration,0);
        queue.url.update(1,0,8,0,true,false);queue.onVideoUrlReset(true);assertEquals(-1,queue.url.state().duration,0);
        queue.url.play("http://example.test/b.mp4",0);queue.onVideoUrlReset(false);assertEquals(-1,queue.url.state().duration,0);
    }
}
