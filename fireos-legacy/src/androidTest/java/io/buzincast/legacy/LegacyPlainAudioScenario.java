package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.media.AudioManager;
import android.os.SystemClock;
import android.util.Base64;
import io.buzincast.fireos3probe.LegacyReceiverSession;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real encoded audio/native decoder/AudioTrack, with no identity or metadata. */
final class LegacyPlainAudioScenario {
    private static Object field(Object object,String name) throws Exception {
        java.lang.reflect.Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private interface Check {boolean ready() throws Exception;}
    private static void await(Check check) throws Exception {
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until) {if(check.ready())return;SystemClock.sleep(20);}
        throw new AssertionError("Plain audio did not settle at "+new Throwable().getStackTrace()[1]);
    }
    private static void focusOwned(Instrumentation runner,Object service,boolean expected) {
        runner.runOnMainSync(()-> {try {
            if((field(field(service,"systemMedia"),"focus")!=null)!=expected)
                throw new AssertionError("Unexpected platform focus registration for plain audio");
        }catch(Exception error) {throw new AssertionError(error);}});
    }
    static void check(Instrumentation runner,Activity activity) throws Exception {
        Object service=field(activity,"service");LegacyReceiverSession session=(LegacyReceiverSession)field(service,"session");
        Method replay=session.getClass().getDeclaredMethod("replayAudio",byte[].class,int.class,int.class,long.class,float.class,boolean.class);
        replay.setAccessible(true);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream input=runner.getContext().getAssets().open("audio_samples.json")) {
            byte[] chunk=new byte[4096];int count;while((count=input.read(chunk))!=-1)bytes.write(chunk,0,count);
        }
        JSONObject fixture=new JSONArray(bytes.toString("UTF-8")).getJSONObject(0);
        byte[] packet=Base64.decode(fixture.getJSONArray("packets").getString(0),Base64.DEFAULT);
        int codec=fixture.getInt("codec"),spf=fixture.getInt("samplesPerFrame");
        await(()->!session.audioActive() && LegacySystemMediaScenario.gain(activity)==0);
        // Keep the synthetic tone muted at the sender volume independently of focus gain.
        long time=System.nanoTime();
        for(int i=0;i<12;i++)replay.invoke(session,packet,codec,spf,time+i*spf*1000000000L/44100,-144f,i==0);
        await(()->session.audioActive() && LegacySystemMediaScenario.gain(activity)==1);
        focusOwned(runner,service,true);
        if(session.media().active || session.controls().identified)throw new AssertionError("Fixture unexpectedly supplied metadata or controls");
        AtomicReference<Boolean> client=new AtomicReference<>();
        runner.runOnMainSync(()-> {try {client.set((Boolean)field(field(service,"systemMedia"),"registered"));}
            catch(Exception error) {throw new AssertionError(error);}});
        if(client.get())throw new AssertionError("Plain audio registered unusable music buttons");
        try {
            LegacySystemMediaScenario.requestFocus(runner,activity,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            await(()->Math.abs(LegacySystemMediaScenario.gain(activity)-.2f)<.01f);
            LegacySystemMediaScenario.releaseFocus(runner);await(()->LegacySystemMediaScenario.gain(activity)==1);
            LegacySystemMediaScenario.requestFocus(runner,activity,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            await(()->LegacySystemMediaScenario.gain(activity)==0);
        } finally {LegacySystemMediaScenario.releaseFocus(runner);}
        await(()->!session.audioActive() && LegacySystemMediaScenario.gain(activity)==0);
        focusOwned(runner,service,false);
        for(int i=0;i<12;i++)replay.invoke(session,packet,codec,spf,System.nanoTime(),-144f,i==0);
        await(()->session.audioActive() && LegacySystemMediaScenario.gain(activity)==1);
        try {
            LegacySystemMediaScenario.requestFocus(runner,activity,AudioManager.AUDIOFOCUS_GAIN);
            await(()->LegacySystemMediaScenario.gain(activity)==0);
        } finally {LegacySystemMediaScenario.releaseFocus(runner);}
        await(()->!session.audioActive());focusOwned(runner,service,false);
        for(int i=0;i<12;i++)replay.invoke(session,packet,codec,spf,System.nanoTime(),-144f,i==0);
        await(session::audioActive);
        if(LegacySystemMediaScenario.gain(activity)!=0)throw new AssertionError("Idle input cleared permanent focus loss");
        focusOwned(runner,service,false);
        runner.runOnMainSync(()->activity.startService(new Intent(activity,LegacyReceiverService.class).setAction(LegacyReceiverService.RESTART)));
        AtomicReference<LegacyReceiverSession> restarted=new AtomicReference<>();
        await(()-> {
            runner.runOnMainSync(()-> {try {restarted.set((LegacyReceiverSession)field(service,"session"));}catch(Exception error) {throw new AssertionError(error);}});
            LegacyReceiverSession next=restarted.get();return session.isClosed() && next!=null && next!=session && next.listeningPort()>0;
        });
        LegacyReceiverSession resumed=restarted.get();
        for(int i=0;i<12;i++)replay.invoke(resumed,packet,codec,spf,System.nanoTime(),-144f,i==0);
        await(()->resumed.audioActive() && LegacySystemMediaScenario.gain(activity)==1);
        await(()->!resumed.audioActive() && LegacySystemMediaScenario.gain(activity)==0);
        focusOwned(runner,service,false);
    }
}
