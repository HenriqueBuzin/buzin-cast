package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.View;
import android.widget.EditText;
import io.buzincast.fireos3probe.*;
import io.buzincast.media.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static io.buzincast.legacy.LegacyApprovalScenario.*;

/** Real Tools UI, service arbitration, MediaCodec Surface, PCM hardware head and native capture. */
final class LegacyRelayAppScenario {
    static void click(Activity activity,String tag) {LegacyRecordingScenario.click(activity,tag);}
    static LegacyReceiverSession nativeSession(Instrumentation runner,LegacyReceiverService service) {return (LegacyReceiverSession)read(runner,service,"session");}
    static LegacyRelayPlayback playback(Instrumentation runner,LegacyReceiverService service) {AtomicReference<LegacyRelayPlayback> p=new AtomicReference<>();ui(runner,()->p.set(service.relay().playback()));return p.get();}
    static boolean available(Instrumentation runner,LegacyReceiverService service) {AtomicBoolean b=new AtomicBoolean();ui(runner,()->b.set(service.canListenRelay()));return b.get();}
    static void leases(Instrumentation runner,Activity activity,int expected) {
        TextureTarget target=(TextureTarget)read(runner,activity,"textureTarget");
        if(target==null || ((Number)read(runner,target,"leases")).intValue()!=expected)throw new AssertionError("Display lease count is not "+expected);
    }
    static Activity launch(Instrumentation runner,Activity previous) throws Exception {
        AtomicReference<Activity> current=new AtomicReference<>(previous);Instrumentation.ActivityMonitor monitor=runner.addMonitor(LegacyActivity.class.getName(),null,false);
        try {
            previous.getApplicationContext().startActivity(Intent.makeMainActivity(new android.content.ComponentName(previous,LegacyActivity.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            await(()-> {Activity shown=monitor.getLastActivity();if(shown!=null)current.set(shown);return (Boolean)read(runner,current.get(),"foreground");});
            ((LegacyGestureChecks)runner).activeActivity(current.get());return current.get();
        } finally {runner.removeMonitor(monitor);}
    }
    static final class Peer implements Closeable {
        final Socket socket;final SecureMirrorChannel channel;
        Peer(Instrumentation runner,Activity activity,LegacyReceiverService service) throws Exception {
            ui(runner,()-> {if(activity.findViewById(android.R.id.content).findViewWithTag("relay-listen")==null)click(activity,"nav-tools");click(activity,"relay-listen");});
            AtomicReference<io.github.jqssun.airplay.media.MirrorRelay.State> state=new AtomicReference<>();
            await(()-> {ui(runner,()->state.set(service.relay().state()));return state.get().getListening();});
            socket=new Socket("127.0.0.1",state.get().getPort());socket.setSoTimeout(10000);
            DataInputStream input=new DataInputStream(socket.getInputStream());OutputStream output=socket.getOutputStream();
            output.write(SecureMirrorChannel.Companion.getMAGIC());output.flush();byte[] salt=new byte[16];input.readFully(salt);
            channel=new SecureMirrorChannel(input,output,SecureMirrorChannel.Companion.deriveKey(state.get().getCode(),salt),LegacyMirrorCipher.INSTANCE);
            channel.send("hello".getBytes("US-ASCII"));if(!java.util.Arrays.equals(channel.receive(),"ready".getBytes("US-ASCII")))throw new AssertionError("Service relay handshake");
            await(()->playback(runner,service)!=null);
        }
        void video(byte[] frame,long pts) {channel.send(MirrorPacketCodec.INSTANCE.encode(new MirrorPacket.Video(frame,pts,160,90,false)));}
        void pcm(long pts) {byte[] data=new byte[441*4];for(int i=0;i<441;i++) {short v=(short)(Math.sin(i*.1)*1000);data[i*4]=(byte)v;data[i*4+1]=(byte)(v>>8);data[i*4+2]=(byte)v;data[i*4+3]=(byte)(v>>8);}channel.send(MirrorPacketCodec.INSTANCE.encode(new MirrorPacket.Audio(data,pts,44100,2)));}
        void stream(byte[] frame,int count,long base) throws Exception {for(int i=0;i<count;i++) {video(frame,base+i*30_000_000L);for(int n=0;n<3;n++)pcm(base+i*30_000_000L+n*10_000_000L);SystemClock.sleep(30);}}
        @Override public void close() throws IOException {socket.close();}
    }
    static void check(Instrumentation runner,Activity initial) throws Exception {
        AtomicReference<Activity> current=new AtomicReference<>(initial);
        AtomicReference<LegacyReceiverService> owner=new AtomicReference<>();await(()-> {owner.set((LegacyReceiverService)read(runner,current.get(),"service"));return owner.get()!=null;});
        LegacyReceiverService service=owner.get();await(()->available(runner,service));byte[] frame=LegacyRecordingScenario.frame(runner);
        LegacyReceiverSession nativeOwner=nativeSession(runner,service);ui(runner,()->click(current.get(),"nav-tools"));
        AtomicReference<LegacyRelayPlayback> observed=new AtomicReference<>();
        try {
            try(Peer peer=new Peer(runner,current.get(),service)) {
                LegacyRelayPlayback p=playback(runner,service);observed.set(p);peer.stream(frame,45,1_000_000_000L);
                await(()-> {if(p.failure!=null)throw new AssertionError("Relay playback failure",p.failure);return p.decodedFrames>10 && p.playedFrames>4410;});
                if(p.width!=160 || p.height!=90 || !p.audioActive || p.focusGain!=1)throw new AssertionError("Relay format/audio focus");
                Object decoder=read(runner,p,"video");long delay=((Number)read(runner,decoder,"lastRenderDelayNs")).longValue();
                if(delay==Long.MIN_VALUE || delay< -150_000_000L || delay>8_000_000L)throw new AssertionError("Relay did not render against the PCM hardware clock: "+delay);
                android.media.AudioManager manager=(android.media.AudioManager)current.get().getSystemService(android.content.Context.AUDIO_SERVICE);
                android.media.AudioManager.OnAudioFocusChangeListener interruption=change->{};
                try {
                    if(manager.requestAudioFocus(interruption,android.media.AudioManager.STREAM_MUSIC,android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)!=android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new AssertionError("Focus fixture not granted");
                    await(()->p.focusGain==0 && p.appliedGain==0);
                } finally {manager.abandonAudioFocus(interruption);}
                await(()->p.focusGain==1 && p.appliedGain==1);
                leases(runner,current.get(),1);
                AtomicBoolean image=new AtomicBoolean();ui(runner,()-> {
                    try {Bitmap bitmap=((TextureView)LegacyRecordingScenario.field(current.get(),"texture")).getBitmap(160,90);
                        if(bitmap!=null) {int first=bitmap.getPixel(0,0);for(int y=0;y<90;y+=5)for(int x=0;x<160;x+=5)if(bitmap.getPixel(x,y)!=first)image.set(true);bitmap.recycle();}}
                    catch(Exception error) {throw new AssertionError(error);}
                });if(!image.get())throw new AssertionError("Relay Surface did not contain decoded image");
                long decoded=p.decodedFrames;ui(runner,()->current.get().moveTaskToBack(true));await(()->!(Boolean)read(runner,current.get(),"foreground"));
                peer.stream(frame,10,2_350_000_000L);if(!p.audioActive || p.failure!=null)throw new AssertionError("Relay background audio");
                current.set(launch(runner,current.get()));
                await(()-> {Object target=read(runner,current.get(),"textureTarget");return target!=null && read(runner,service,"relayBoundTarget")==target;});
                peer.stream(frame,15,2_650_000_000L);await(()->p.decodedFrames>decoded);
                peer.channel.send(MirrorPacketCodec.INSTANCE.encode(MirrorPacket.End.INSTANCE));await(()->p.closed && playback(runner,service)==null);leases(runner,current.get(),1);
                if(nativeSession(runner,service)!=nativeOwner || nativeOwner.isClosed())throw new AssertionError("Relay replaced native receiver");
            }
            await(()->available(runner,service));
            byte[] sample;try(InputStream input=runner.getContext().getAssets().open("url_sample.mp4");ByteArrayOutputStream output=new ByteArrayOutputStream()) {LegacyRecordingScenario.copy(input,output);sample=output.toByteArray();}
            try(LegacyUrlScenario.Fixture fixture=new LegacyUrlScenario.Fixture(sample);Peer peer=new Peer(runner,current.get(),service)) {
                LegacyRelayPlayback p=playback(runner,service);peer.stream(frame,6,3_500_000_000L);await(()->p.decodedFrames>0);
                LegacyUrlRequest request=new LegacyUrlRequest("http://127.0.0.1:"+fixture.socket.getLocalPort()+"/sample.mp4",0,java.util.Collections.singletonMap("X-Legacy-Fixture","fixture"));
                ui(runner,()->service.loadUrl(request));
                await(()-> {LegacyUrlPlayer.State state=LegacyDownloadScenario.player(runner,service);if(state!=null && state.failure!=null)throw new AssertionError(state.failure);return p.closed && state!=null && state.phase==LegacyUrlPlayer.Phase.READY && state.width==160;});
                leases(runner,current.get(),1);ui(runner,service::stopUrl);
                await(()->read(runner,service,"urlPlayer")==null && !(Boolean)read(runner,service,"urlRequested"));leases(runner,current.get(),1);
            }
            await(()->available(runner,service));
            try(Peer peer=new Peer(runner,current.get(),service)) {
                LegacyRelayPlayback p=playback(runner,service);peer.stream(frame,8,3_200_000_000L);await(()->p.decodedFrames>0);
                ui(runner,()-> {current.get().onBackPressed();click(current.get(),"nav-tools");click(current.get(),"relay-stop");});
                await(()->p.closed && playback(runner,service)==null);leases(runner,current.get(),1);
            }
            await(()->available(runner,service));
            try(Peer peer=new Peer(runner,current.get(),service)) {
                LegacyRelayPlayback p=playback(runner,service);peer.stream(frame,12,4_000_000_000L);
                java.lang.reflect.Method replay=nativeOwner.getClass().getDeclaredMethod("replay",byte[].class,long.class);replay.setAccessible(true);
                replay.invoke(nativeOwner,frame,5_000_000_000L);await(()->p.closed && playback(runner,service)==null);
                for(int i=0;i<12;i++) {replay.invoke(nativeOwner,frame,5_100_000_000L+i*33_333_333L);SystemClock.sleep(33);}
                Object player=read(runner,nativeOwner,"videoPlayer");await(()->((Number)read(runner,player,"outputFrames")).intValue()>0);leases(runner,current.get(),1);
            }
            ui(runner,()->LegacyReceiverService.dispatch(service,LegacyReceiverService.RESTART));
            await(()->nativeSession(runner,service)!=null && nativeSession(runner,service)!=nativeOwner && available(runner,service));
            sender(runner,current.get(),service,frame);
            LegacyReceiverSession sentOwner=nativeSession(runner,service);
            ui(runner,()-> {service.stopRelay();LegacyReceiverService.dispatch(service,LegacyReceiverService.RESTART);current.get().onBackPressed();click(current.get(),"nav-tools");});
            await(()->nativeSession(runner,service)!=null && nativeSession(runner,service)!=sentOwner && available(runner,service));
            // A malformed authenticated format ends reception without crashing main or leaking output.
            await(()->available(runner,service));
            try(Peer peer=new Peer(runner,current.get(),service)) {
                LegacyRelayPlayback p=playback(runner,service);
                peer.channel.send(MirrorPacketCodec.INSTANCE.encode(new MirrorPacket.Video(frame,1,160,90,true)));
                await(()->p.closed && playback(runner,service)==null);leases(runner,current.get(),1);
            }
            await(()->available(runner,service));
            try(Peer peer=new Peer(runner,current.get(),service)) {
                LegacyRelayPlayback p=playback(runner,service);peer.stream(frame,6,8_000_000_000L);await(()->p.decodedFrames>0);
                ui(runner,()->LegacyReceiverService.dispatch(service,LegacyReceiverService.STOP));
                await(()->p.closed && nativeSession(runner,service)==null && playback(runner,service)==null);
                if(read(runner,service,"wakeLock")!=null)throw new AssertionError("Receiver stop leaked a wake lock while bound");leases(runner,current.get(),0);
            }
            await(()->available(runner,service));
            try(Peer peer=new Peer(runner,current.get(),service)) {
                LegacyRelayPlayback p=playback(runner,service);peer.stream(frame,8,9_000_000_000L);await(()->p.decodedFrames>0 && p.playedFrames>0);
                if(nativeSession(runner,service)!=null || (Boolean)read(runner,service,"desired"))throw new AssertionError("Standalone relay unexpectedly started the native server");
                Activity abandoned=current.get();ui(runner,()-> {if(initial!=abandoned)initial.finish();abandoned.finish();});
                await(()-> {AtomicBoolean gone=new AtomicBoolean();ui(runner,()->gone.set(abandoned.isDestroyed()));return gone.get() && read(runner,service,"client")==null;});
                peer.stream(frame,10,9_240_000_000L);
                if((Boolean)read(runner,service,"destroyed") || !p.audioActive)throw new AssertionError("Standalone relay ended when its Activity was destroyed");
                current.set(launch(runner,abandoned));await(()-> {Object target=read(runner,current.get(),"textureTarget");return target!=null && read(runner,service,"relayBoundTarget")==target;});
                long decoded=p.decodedFrames;peer.stream(frame,10,9_540_000_000L);await(()->p.decodedFrames>decoded);
                leases(runner,current.get(),1);peer.channel.send(MirrorPacketCodec.INSTANCE.encode(MirrorPacket.End.INSTANCE));
                await(()->p.closed && playback(runner,service)==null);leases(runner,current.get(),0);
                if(read(runner,service,"wakeLock")!=null)throw new AssertionError("Standalone relay retained its wake lock");
            }
            sender(runner,current.get(),service,frame);
            ui(runner,()->LegacyReceiverService.dispatch(service,LegacyReceiverService.STOP));await(()->nativeSession(runner,service)==null);
            LegacyNetwork currentNetwork=LegacyNetwork.select();java.lang.reflect.Method change=service.getClass().getDeclaredMethod("networkChanged",String.class,LegacyNetwork.class);change.setAccessible(true);
            boolean recovery=new LegacyPreferences(current.get()).networkRecovery();
            try {
                ui(runner,()-> {new LegacyPreferences(current.get()).bool("network_recovery",false);try {change.invoke(service,"relay-fixture-network",currentNetwork);}catch(Exception error) {throw new AssertionError(error);}});
                if(read(runner,service,"failure")!=null || nativeSession(runner,service)!=null)throw new AssertionError("Network update reported a native-server failure while native reception was off");
            } finally {ui(runner,()-> {new LegacyPreferences(current.get()).bool("network_recovery",recovery);try {change.invoke(service,"relay-fixture-restored",currentNetwork);}catch(Exception error) {throw new AssertionError(error);}});}
            ui(runner,()->LegacyReceiverService.dispatch(service,LegacyReceiverService.START));await(()->nativeSession(runner,service)!=null && available(runner,service));
        } finally {
            org.json.JSONObject diagnostic=new org.json.JSONObject();LegacyRelayPlayback p=observed.get();
            if(p!=null)diagnostic.put("decoded",p.decodedFrames).put("written",p.writtenFrames).put("played",p.playedFrames).put("failure",String.valueOf(p.failure)).put("closed",p.closed);
            ui(runner,()-> {try {
                diagnostic.put("transport",service.relay().state().getMessage()).put("relay_available",LegacyRecordingScenario.field(service,"relayAvailable"));
                LegacyReceiverSession nativeCurrent=(LegacyReceiverSession)LegacyRecordingScenario.field(service,"session");
                if(nativeCurrent!=null)diagnostic.put("native_connections",nativeCurrent.connectionCount()).put("native_mirroring",nativeCurrent.mirroring()).put("native_audio",nativeCurrent.audioActive());
            }catch(Exception error) {throw new AssertionError(error);}});
            LegacyPinScenario.write(current.get(),"legacy-relay-app.json",diagnostic.toString(2));
            ui(runner,service::stopRelay);await(()->playback(runner,service)==null);
        }
        await(()-> {for(Thread t:Thread.getAllStackTraces().keySet())if(t.isAlive() && (t.getName().equals("buzin-legacy-relay-video") || t.getName().equals("buzin-legacy-relay-pcm")))return false;return true;});
        ui(runner,()-> {current.get().onBackPressed();click(current.get(),"nav-tools");current.get().findViewById(android.R.id.content).findViewWithTag("relay-listen").requestFocus();});
        runner.waitForIdleSync();LegacyApprovalScenario.capture(runner,current.get());
        if(!new File(current.get().getFilesDir(),"legacy-approval.b64").renameTo(new File(current.get().getFilesDir(),"legacy-relay-tools.b64")))throw new AssertionError("Tools capture failed");
    }
    static void sender(Instrumentation runner,Activity activity,LegacyReceiverService service,byte[] frame) throws Exception {
        AtomicInteger videos=new AtomicInteger(),audios=new AtomicInteger();AtomicReference<Throwable> failed=new AtomicReference<>();
        boolean independent=nativeSession(runner,service)==null;
        CountDownLatch waiting=new CountDownLatch(1),allowSalt=new CountDownLatch(independent?1:0);
        try(ServerSocket server=new ServerSocket(0,4,LegacyNetwork.select().address)) {
            server.setSoTimeout(10000);
            Thread receiver=new Thread(()-> {
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(15000);DataInputStream input=new DataInputStream(socket.getInputStream());byte[] magic=new byte[8];input.readFully(magic);
                    if(!java.util.Arrays.equals(magic,SecureMirrorChannel.Companion.getMAGIC()))throw new AssertionError("Relay capture magic");
                    waiting.countDown();if(!allowSalt.await(10,TimeUnit.SECONDS))throw new AssertionError("Relay locale fixture did not resume");
                    byte[] salt=new byte[16];new java.security.SecureRandom().nextBytes(salt);socket.getOutputStream().write(salt);socket.getOutputStream().flush();
                    SecureMirrorChannel channel=new SecureMirrorChannel(input,socket.getOutputStream(),SecureMirrorChannel.Companion.deriveKey("ABCDEFGHJKLM",salt),LegacyMirrorCipher.INSTANCE);
                    if(!java.util.Arrays.equals(channel.receive(),"hello".getBytes("US-ASCII")))throw new AssertionError("Relay capture auth");channel.send("ready".getBytes("US-ASCII"));
                    while(true) {byte[] bytes=channel.receive();if(bytes.length==0)continue;MirrorPacket p=MirrorPacketCodec.INSTANCE.decode(bytes);if(p==MirrorPacket.End.INSTANCE)break;
                        if(p instanceof MirrorPacket.Video) {MirrorPacket.Video v=(MirrorPacket.Video)p;if(v.getWidth()!=160 || v.getHeight()!=90 || v.getData().length<frame.length)throw new AssertionError("Native relay video shape");videos.incrementAndGet();}
                        else if(p instanceof MirrorPacket.Audio) {MirrorPacket.Audio a=(MirrorPacket.Audio)p;if(a.getData().length==0 || a.getSampleRate()!=44100 || a.getChannels()!=2)throw new AssertionError("Native relay PCM shape");audios.incrementAndGet();}
                    }
                }catch(Throwable error) {failed.set(error);}
            },"relay-app-test-receiver");receiver.start();
            try {
                String address=LegacyNetwork.select().address.getHostAddress();
                boolean hideVideo=(Boolean)read(runner,activity,"video");
                ui(runner,()-> {if(hideVideo)activity.onBackPressed();click(activity,"nav-tools");click(activity,"relay-connect");});
                await(()-> {AtomicBoolean laidOut=new AtomicBoolean();ui(runner,()-> {View host=activity.findViewById(android.R.id.content).findViewWithTag("relay-host");laidOut.set(host!=null && host.isShown() && host.getWidth()>0 && host.hasFocus());});return laidOut.get();});
                LegacyApprovalScenario.capture(runner,activity);
                if(!new File(activity.getFilesDir(),"legacy-approval.b64").renameTo(new File(activity.getFilesDir(),"legacy-relay-connect.b64")))throw new AssertionError("Relay editor capture failed");
                ui(runner,()-> {
                    ((EditText)activity.findViewById(android.R.id.content).findViewWithTag("relay-host")).setText(address);
                    ((EditText)activity.findViewById(android.R.id.content).findViewWithTag("relay-port")).setText(Integer.toString(server.getLocalPort()));
                    ((EditText)activity.findViewById(android.R.id.content).findViewWithTag("relay-code")).setText("ABCDEFGHJKLM");click(activity,"relay-submit");});
                if(independent) {
                    if(!waiting.await(10,TimeUnit.SECONDS))throw new AssertionError("Relay connect did not reach the test receiver");
                    android.content.res.Resources resources=activity.getApplicationContext().getResources();
                    android.content.res.Configuration before=new android.content.res.Configuration(resources.getConfiguration()),changed=new android.content.res.Configuration(before);changed.locale=new java.util.Locale("de");
                    java.lang.reflect.Method reconcile=service.getClass().getDeclaredMethod("reconcile");reconcile.setAccessible(true);
                    try {
                        ui(runner,()-> {resources.updateConfiguration(changed,resources.getDisplayMetrics());try {reconcile.invoke(service);}catch(Exception error) {throw new AssertionError(error);}});
                        AtomicBoolean engaged=new AtomicBoolean();ui(runner,()->engaged.set(service.relay().engaged()));
                        if(!engaged.get() || nativeSession(runner,service)!=null)throw new AssertionError("Locale change ended the independent pending connection");
                    } finally {ui(runner,()-> {resources.updateConfiguration(before,resources.getDisplayMetrics());try {reconcile.invoke(service);}catch(Exception error) {throw new AssertionError(error);}});allowSalt.countDown();}
                }
                try {await(()-> {AtomicBoolean sending=new AtomicBoolean();ui(runner,()->sending.set(service.relay().active()));return sending.get();});}
                catch(AssertionError error) {throw new AssertionError("Relay sender handshake: "+service.relay().state()+", peer="+failed.get(),error);}
                if(nativeSession(runner,service)==null) {ui(runner,()->LegacyReceiverService.dispatch(service,LegacyReceiverService.START));await(()->nativeSession(runner,service)!=null && available(runner,service));}
                LegacyReceiverSession session=nativeSession(runner,service);
                java.lang.reflect.Method video=session.getClass().getDeclaredMethod("replay",byte[].class,long.class);video.setAccessible(true);
                java.lang.reflect.Method audio=session.getClass().getDeclaredMethod("replayAudio",byte[].class,int.class,int.class,long.class,float.class,boolean.class,boolean.class);audio.setAccessible(true);
                byte[] encoded;try(InputStream in=runner.getContext().getAssets().open("audio_samples.json");ByteArrayOutputStream out=new ByteArrayOutputStream()) {LegacyRecordingScenario.copy(in,out);encoded=android.util.Base64.decode(new org.json.JSONArray(out.toString("UTF-8")).getJSONObject(0).getJSONArray("packets").getString(0),android.util.Base64.DEFAULT);}
                long pts=System.currentTimeMillis()*1_000_000L,audioPts=pts;for(int i=0;i<30;i++) {video.invoke(session,frame,pts+i*33_333_333L);for(int n=0;n<4;n++) {audio.invoke(session,encoded,2,i==0 && n==0?352:0,audioPts,-12f,i==0 && n==0,false);audioPts+=352L*1_000_000_000L/44100;}SystemClock.sleep(33);}
                await(()->videos.get()>10 && audios.get()>10);
                service.relay().ended();receiver.join(15000);if(receiver.isAlive())throw new AssertionError("Native relay sender did not end");if(failed.get()!=null)throw new AssertionError("Native relay capture peer failed",failed.get());
            } finally {allowSalt.countDown();ui(runner,service::stopRelay);server.close();receiver.join(16000);if(receiver.isAlive())throw new AssertionError("Relay fixture leaked a thread");}
        }
    }
}
