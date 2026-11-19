package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import io.buzincast.media.*;
import io.buzincast.fireos3probe.LegacyRelayCrypto;
import io.github.jqssun.airplay.media.MirrorRelay;
import kotlin.Unit;
import org.json.JSONObject;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual shared relay transport plus an independent host AES-GCM peer. No rendering yet. */
final class LegacyRelayScenario {
    static final byte[] VIDEO={0,0,0,1,0x65,11,22},AUDIO={1,2,3,4};
    interface Check {boolean ready() throws Exception;}
    static void await(Check check) throws Exception {
        long until=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<until) {if(check.ready())return;SystemClock.sleep(20);}
        throw new AssertionError("Relay did not settle at "+new Throwable().getStackTrace()[1]);
    }
    static MirrorPacket.Video video() {return new MirrorPacket.Video(VIDEO,100000000L,160,90,false);}
    static MirrorPacket.Audio audio() {return new MirrorPacket.Audio(AUDIO,100000010L,44100,2);}
    static void request(Activity activity,String stage,MirrorRelay relay) throws Exception {
        JSONObject value=new JSONObject().put("stage",stage).put("port",relay.getState().getValue().getPort()).put("code",relay.getState().getValue().getCode());
        LegacyPinScenario.write(activity,"relay-host-request",value.toString());
    }
    static File ack(Activity activity,String stage) {return new File(activity.getFilesDir(),"relay-host-ack-"+stage);}
    static void host(Activity activity,String stage,MirrorRelay relay) throws Exception {request(activity,stage,relay);await(()->ack(activity,stage).isDirectory());}
    static void noThreads() throws Exception {
        await(()-> {
            for(Thread thread:Thread.getAllStackTraces().keySet())if(thread.isAlive() && (thread.getName().equals("buzin-relay-listen") || thread.getName().equals("buzin-relay-send")))return false;
            return true;
        });
    }
    static void crypto() throws Exception {
        byte[] key=new byte[32],nonce=new byte[12],aad=new byte[8];
        byte[] tag=LegacyRelayCrypto.crypt(true,key,nonce,aad,new byte[0]);
        if(tag.length!=16 || LegacyRelayCrypto.crypt(false,key,nonce,aad,tag).length!=0)throw new AssertionError("Empty GCM keepalive");
        tag[0]^=1;try {LegacyRelayCrypto.crypt(false,key,nonce,aad,tag);throw new AssertionError("Corrupt tag accepted");}catch(SecurityException expected) {}
        for(byte[][] invalid:new byte[][][]{ {new byte[31],nonce,aad,new byte[0]}, {key,new byte[11],aad,new byte[0]}, {key,nonce,new byte[7],new byte[0]}, {key,nonce,aad,new byte[4*1024*1024+33]} }) {
            try {LegacyRelayCrypto.crypt(true,invalid[0],invalid[1],invalid[2],invalid[3]);throw new AssertionError("Invalid GCM bounds accepted");}catch(IllegalArgumentException expected) {}
        }
        try {LegacyRelayCrypto.crypt(false,key,nonce,aad,new byte[15]);throw new AssertionError("Short GCM tag accepted");}catch(IllegalArgumentException expected) {}
        try {LegacyRelayCrypto.crypt(true,null,nonce,aad,new byte[0]);throw new AssertionError("Null key accepted");}catch(IllegalArgumentException expected) {}
        byte[] limit=new byte[4*1024*1024+32];limit[limit.length-1]=37;
        byte[] sealed=LegacyRelayCrypto.crypt(true,key,nonce,aad,limit);
        if(!Arrays.equals(limit,LegacyRelayCrypto.crypt(false,key,nonce,aad,sealed)))throw new AssertionError("GCM maximum valid payload failed");
        LegacyRelayQueue queue=new LegacyRelayQueue(120);MirrorPacket.Video large=new MirrorPacket.Video(new byte[4*1024*1024],0,160,90,false);
        if(!queue.offer(large) || !queue.offer(large) || queue.offer(MirrorPacket.End.INSTANCE))throw new AssertionError("Encoded byte budget not enforced");
        if(queue.poll(0,TimeUnit.MILLISECONDS)!=large || !queue.offer(MirrorPacket.End.INSTANCE))throw new AssertionError("Queue did not release its byte budget");
        queue.clear();if(queue.poll(1,TimeUnit.MILLISECONDS)!=null)throw new AssertionError("Queue did not clear");
    }
    static void check(Instrumentation runner,Activity activity) throws Exception {
        crypto();
        ArrayBlockingQueue<MirrorPacket> packets=new ArrayBlockingQueue<>(16);AtomicInteger ended=new AtomicInteger();AtomicBoolean available=new AtomicBoolean(true);
        MirrorRelay relay=new MirrorRelay(activity,()->Unit.INSTANCE,packet->{if(!packets.offer(packet))throw new AssertionError("Unexpected relay packets");return Unit.INSTANCE;},()->{ended.incrementAndGet();return Unit.INSTANCE;},available::get);
        try {
            relay.listen();host(activity,"wrong_code",relay);
            if(!relay.getState().getValue().getListening() || !packets.isEmpty() || ended.get()!=0)throw new AssertionError("Wrong code affected receiver admission");
            host(activity,"receive",relay);await(()->ended.get()==1);
            MirrorPacket first=packets.poll(1,TimeUnit.SECONDS),second=packets.poll(1,TimeUnit.SECONDS);
            if(!(first instanceof MirrorPacket.Video) || !(second instanceof MirrorPacket.Audio))throw new AssertionError("Missing independent host packets");
            MirrorPacket.Video video=(MirrorPacket.Video)first;MirrorPacket.Audio audio=(MirrorPacket.Audio)second;
            if(!Arrays.equals(video.getData(),VIDEO) || video.getPtsNs()!=100000000L || video.getWidth()!=160 || video.getHeight()!=90 || video.getHevc() ||
                !Arrays.equals(audio.getData(),AUDIO) || audio.getPtsNs()!=100000010L || audio.getSampleRate()!=44100 || audio.getChannels()!=2)throw new AssertionError("Relay changed packet content");
            noThreads();
            for(String stage:new String[]{"bad_tag","sequence","oversized","truncated","invalid_packet"}) {
                int previous=ended.get();relay.listen();host(activity,stage,relay);
                await(()->ended.get()==previous+1);if(!packets.isEmpty())throw new AssertionError("Invalid authenticated packet was delivered: "+stage);noThreads();
            }
            available.set(false);relay.listen();host(activity,"busy",relay);
            if(!relay.getState().getValue().getListening())throw new AssertionError("Busy rejection unexpectedly retired listener");relay.stop();noThreads();available.set(true);
            relay.listen();host(activity,"stalled_open",relay);relay.stop();host(activity,"stalled_closed",relay);noThreads();
            request(activity,"sender",relay);await(()->ack(activity,"sender").isDirectory());
            int port=0;for(String name:activity.getFilesDir().list())if(name.matches("relay-host-port-[0-9]{1,5}"))port=Integer.parseInt(name.substring("relay-host-port-".length()));
            if(port<1 || port>65535)throw new AssertionError("Missing host listener port");
            relay.connect("10.0.2.2",port,"ABCDEFGHJKLM");await(()->relay.getState().getValue().getSending());
            relay.offer(video());relay.offer(audio());relay.offer(MirrorPacket.End.INSTANCE);
            await(()->new File(activity.getFilesDir(),"relay-host-sent").isDirectory());noThreads();
            if(relay.getState().getValue().getSending())throw new AssertionError("Sender stayed active after End");
            host(activity,"done",relay);
        } finally {relay.stop();noThreads();}
    }
}
