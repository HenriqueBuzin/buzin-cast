package io.buzincast.legacy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import io.buzincast.fireos3probe.LegacyReceiverSession;

/** Main-thread owner of the receiver. Native/codec work stays in runtime workers. */
public final class LegacyReceiverService extends Service {
    static final String OPEN="open",START="start",STOP="stop",RESTART="restart",CONFIGURE="configure",BOOT="boot";
    private static final int NOTIFICATION=17001;
    private static final String CHANNEL="buzin-legacy-receiver";
    interface Observer { void changed(State state); }
    static final class State {
        final boolean desired,running,busy,video;
        final String name,address;
        final int port,width,height;
        final Exception failure;
        final List<String> events;
        State(LegacyReceiverService service) {
            desired=service.desired;running=service.running;busy=service.busy;video=service.video;
            name=service.name;failure=service.failure;width=service.width;height=service.height;
            address=service.session==null?null:service.session.localAddress();
            port=service.session==null?0:service.session.listeningPort();
            events=new ArrayList<>(service.events);
        }
    }
    final class LocalBinder extends Binder { LegacyReceiverService service() {return LegacyReceiverService.this;} }
    private final LocalBinder binder=new LocalBinder();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> events=new ArrayDeque<>();
    private LegacyPreferences prefs;
    private LegacyReceiverSession session;
    private Object client;
    private Observer observer;
    private TextureTarget target;
    private PowerManager.WakeLock wakeLock;
    private boolean desired,running,busy,video,visible,opened,bootSession,stopping,destroyed,promoted;
    private int generation,width,height;
    private String name;
    private Exception failure;

    static void dispatch(Context context,String action) {
        Intent intent=new Intent(context,LegacyReceiverService.class).setAction(action);
        if(Build.VERSION.SDK_INT>=26)Api26.start(context,intent);else context.startService(intent);
    }
    @Override public void onCreate() {
        super.onCreate();prefs=new LegacyPreferences(this);name=prefs.name();
    }
    @Override public IBinder onBind(Intent intent) {return binder;}
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        // Fulfil startForegroundService's deadline before doing network work.
        promote();String action=intent==null?null:intent.getAction();
        if(action==null) {if(!opened) {desired=prefs.serviceRequested();bootSession=prefs.bootAutoStart();opened=true;}}
        else if(OPEN.equals(action)) {if(!opened) {desired=prefs.autoStart();opened=true;}}
        else if(START.equals(action)) {desired=true;opened=true;failure=null;}
        else if(STOP.equals(action)) {desired=false;opened=true;bootSession=false;failure=null;}
        else if(BOOT.equals(action)) {desired=prefs.bootAutoStart();bootSession=desired;opened=true;}
        else if(RESTART.equals(action)) {name=prefs.name();if(session!=null)stopNative();}
        prefs.serviceRequested(desired);reconcile();
        return desired && (prefs.background() || bootSession)?START_STICKY:START_NOT_STICKY;
    }
    void attach(Object owner,Observer listener) {
        if(client!=owner) {target=null;bindSurface();}
        client=owner;observer=listener;visible=true;bootSession=false;reconcile();
    }
    void showSurface(Object owner,TextureTarget next) {
        if(client!=owner || target==next)return;
        target=next;bindSurface();
    }
    void detach(Object owner) {
        if(client!=owner)return;
        client=null;observer=null;visible=false;target=null;bindSurface();reconcile();
    }
    private void bindSurface() {
        if(session==null)return;
        if(target==null)session.setVideoSurface(null,null);
        else {TextureTarget owned=target;session.setVideoSurface(owned.acquire(),owned::retired);}
    }
    private void reconcile() {
        if(destroyed)return;
        boolean wanted=desired && (visible || prefs.background() || bootSession);
        if(wanted) {promote();if(session==null)startNative();}
        else if(session!=null)stopNative();
        else {demote();stopSelf();}
        publish();
    }
    @android.annotation.SuppressLint("WakelockTimeout")
    private void startNative() {
        busy=true;stopping=false;failure=null;name=prefs.name();final int owner=++generation;
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
        wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"buzincast:legacy-receiver");
        wakeLock.setReferenceCounted(false);wakeLock.acquire();
        session=new LegacyReceiverSession(this,null,prefs.identity(),new LegacyReceiverSession.Listener() {
            private void post(Runnable action) {main.post(()-> {if(!destroyed && owner==generation)action.run();});}
            @Override public void ready(String receiverName) {post(()-> {
                if(stopping)return;running=true;busy=false;name=receiverName;record(getString(R.string.diag_receiver_running));publish();
            });}
            @Override public void failed(Exception error) {post(()-> {
                desired=false;prefs.serviceRequested(false);failure=error;record(error.getClass().getSimpleName()+": "+error.getMessage());publish();
            });}
            @Override public void stopped() {post(()-> {
                session=null;running=busy=video=stopping=false;width=height=0;releaseWakeLock();
                record(getString(R.string.server_stopped));reconcile();
            });}
            @Override public void videoStarted() {post(()-> {video=true;publish();});}
            @Override public void videoStopped() {post(()-> {video=false;publish();});}
            @Override public void videoSize(int w,int h) {post(()-> {width=w;height=h;publish();});}
        },prefs.options());
        bindSurface();session.start();
    }
    private void stopNative() {if(!stopping) {stopping=busy=true;session.stop();}}
    private void record(String text) {if(events.size()==100)events.removeFirst();events.addLast(text);}
    private void publish() {if(observer!=null)observer.changed(new State(this));}
    private void releaseWakeLock() {if(wakeLock!=null) {if(wakeLock.isHeld())wakeLock.release();wakeLock=null;}}
    private void promote() {
        if(promoted)return;
        Intent open=new Intent(this,LegacyActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags=PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0);
        PendingIntent content=PendingIntent.getActivity(this,0,open,flags);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?Api26.builder(this):new Notification.Builder(this);
        Notification notification=builder.setSmallIcon(R.mipmap.buzin_launcher).setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text)).setContentIntent(content).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29)Api29.foreground(this,notification);else startForeground(NOTIFICATION,notification);
        promoted=true;
    }
    private void demote() {if(promoted) {stopForeground(true);promoted=false;}}
    @Override public void onDestroy() {
        destroyed=true;client=null;observer=null;target=null;
        if(session!=null)session.stop();releaseWakeLock();demote();super.onDestroy();
    }
    @android.annotation.TargetApi(26) private static final class Api26 {
        static void start(Context context,Intent intent) {context.startForegroundService(intent);}
        static Notification.Builder builder(Context context) {
            NotificationManager manager=(NotificationManager)context.getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,context.getString(R.string.notification_channel),NotificationManager.IMPORTANCE_LOW));
            return new Notification.Builder(context,CHANNEL);
        }
    }
    @android.annotation.TargetApi(29) private static final class Api29 {
        static void foreground(Service service,Notification notification) {
            service.startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        }
    }
}
