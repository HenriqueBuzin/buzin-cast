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
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import io.buzincast.fireos3probe.LegacyReceiverSession;
import io.buzincast.fireos3probe.LegacyNetwork;

/** Main-thread owner of the receiver. Native/codec work stays in runtime workers. */
public final class LegacyReceiverService extends Service {
    static final String OPEN="open",START="start",STOP="stop",RESTART="restart",CONFIGURE="configure",BOOT="boot";
    private static final int NOTIFICATION=17001;
    private static final String CHANNEL="buzin-legacy-receiver";
    interface Observer { void changed(State state); }
    static final class State {
        final boolean desired,running,busy,video,networkWaiting;
        final String name,address;
        final int port,width,height;
        final Exception failure;
        final List<String> events;
        State(LegacyReceiverService service) {
            desired=service.desired;running=service.running;busy=service.busy;video=service.video;
            networkWaiting=service.wanted() && service.reconnect.initialized() && !service.reconnect.online();
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
    private final LegacyReconnect reconnect=new LegacyReconnect();
    private LegacyNetworkWatcher watcher;
    private LegacyNetwork selectedNetwork;
    private boolean recovering;
    private String notificationText;
    private final Runnable retry=this::reconcile;
    private final Runnable stable=()-> {if(this.running && !this.stopping)reconnect.resetRetries();};
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
        else if(START.equals(action)) {desired=true;opened=true;failure=null;resetRetries();}
        else if(STOP.equals(action)) {desired=false;opened=true;bootSession=false;failure=null;}
        else if(BOOT.equals(action)) {desired=prefs.bootAutoStart();bootSession=desired;opened=true;}
        else if(RESTART.equals(action)) {name=prefs.name();resetRetries();if(session!=null)stopNative();}
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
    private boolean wanted() {return desired && (visible || prefs.background() || bootSession);}
    private void resetRetries() {main.removeCallbacks(retry);main.removeCallbacks(stable);reconnect.resetRetries();}
    private void closeWatcher() {
        main.removeCallbacks(retry);main.removeCallbacks(stable);
        if(watcher!=null) {watcher.close();watcher=null;}
        selectedNetwork=null;reconnect.clear();recovering=false;
    }
    private void networkChanged(String key,LegacyNetwork network) {
        boolean hadNetworkState=reconnect.initialized(),changed=reconnect.observe(key);
        selectedNetwork=network;
        if(!changed)return;
        resetRetries();failure=null;
        if(hadNetworkState) {
            recovering=true;record(getString(network==null?R.string.network_waiting:R.string.network_available));
            if(!prefs.networkRecovery()) {
                desired=false;prefs.serviceRequested(false);
                failure=new java.net.SocketException("Local network changed; start the receiver again");
            }
            if(session!=null)stopNative();
        }
        reconcile();
    }
    private void reconcile() {
        if(destroyed)return;
        if(wanted() && reconnect.initialized() && !reconnect.online() && !prefs.networkRecovery()) {
            desired=false;prefs.serviceRequested(false);failure=new java.net.SocketException("No local network is available");
        }
        if(wanted()) {
            promote();
            if(watcher==null)watcher=new LegacyNetworkWatcher(this,main,this::networkChanged);
            if(session==null) {
                busy=!reconnect.initialized();
                if(reconnect.canStart(SystemClock.elapsedRealtime()))startNative();
            }
        } else {
            closeWatcher();
            if(session!=null)stopNative();else {busy=false;demote();stopSelf();}
        }
        publish();
    }
    @android.annotation.SuppressLint("WakelockTimeout")
    private void startNative() {
        main.removeCallbacks(retry);main.removeCallbacks(stable);
        busy=true;stopping=false;failure=null;name=prefs.name();final int owner=++generation;
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
        wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"buzincast:legacy-receiver");
        wakeLock.setReferenceCounted(false);wakeLock.acquire();
        session=new LegacyReceiverSession(this,null,prefs.identity(),new LegacyReceiverSession.Listener() {
            private void post(Runnable action) {main.post(()-> {if(!destroyed && owner==generation)action.run();});}
            @Override public void ready(String receiverName) {post(()-> {
                if(stopping)return;running=true;busy=false;name=receiverName;record(getString(R.string.diag_receiver_running));
                if(recovering) {record(getString(R.string.network_recovered));recovering=false;}
                main.removeCallbacks(stable);main.postDelayed(stable,30000);publish();
            });}
            @Override public void failed(Exception error) {post(()-> {
                if(stopping)return;
                main.removeCallbacks(stable);failure=error;record(error.getClass().getSimpleName()+": "+error.getMessage());
                if(!prefs.networkRecovery() || error.getCause() instanceof LinkageError) {
                    desired=false;prefs.serviceRequested(false);main.removeCallbacks(retry);
                } else {
                    recovering=true;long delay=reconnect.failed(SystemClock.elapsedRealtime());
                    main.removeCallbacks(retry);if(delay>=0)main.postDelayed(retry,delay);
                }
                publish();
            });}
            @Override public void stopped() {post(()-> {
                session=null;running=busy=video=stopping=false;width=height=0;releaseWakeLock();
                record(getString(R.string.server_stopped));reconcile();
            });}
            @Override public void videoStarted() {post(()-> {if(!stopping) {video=true;publish();}});}
            @Override public void videoStopped() {post(()-> {video=false;publish();});}
            @Override public void videoSize(int w,int h) {post(()-> {if(!stopping) {width=w;height=h;publish();}});}
        },prefs.options(),selectedNetwork);
        bindSurface();session.start();
    }
    private void stopNative() {main.removeCallbacks(stable);video=false;if(!stopping) {stopping=busy=true;session.stop();}}
    private void record(String text) {if(events.size()==100)events.removeFirst();events.addLast(text);}
    private void publish() {
        if(promoted && !notificationBody().equals(notificationText))
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification());
        if(observer!=null)observer.changed(new State(this));
    }
    private void releaseWakeLock() {if(wakeLock!=null) {if(wakeLock.isHeld())wakeLock.release();wakeLock=null;}}
    private String notificationBody() {
        if(wanted() && reconnect.initialized() && !reconnect.online())return getString(R.string.network_waiting);
        if(failure!=null)return getString(R.string.error_starting_server);
        return getString(busy || !reconnect.initialized()?R.string.waiting_for_connection:R.string.notification_text);
    }
    private Notification notification() {
        Intent open=new Intent(this,LegacyActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags=PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0);
        PendingIntent content=PendingIntent.getActivity(this,0,open,flags);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?Api26.builder(this):new Notification.Builder(this);
        notificationText=notificationBody();
        return builder.setSmallIcon(R.mipmap.buzin_launcher).setContentTitle(getString(R.string.notification_title))
            .setContentText(notificationText).setContentIntent(content).setOngoing(true).build();
    }
    private void promote() {
        if(promoted)return;Notification notification=notification();
        if(Build.VERSION.SDK_INT>=29)Api29.foreground(this,notification);else startForeground(NOTIFICATION,notification);
        promoted=true;
    }
    private void demote() {if(promoted) {stopForeground(true);promoted=false;}}
    @Override public void onDestroy() {
        destroyed=true;client=null;observer=null;target=null;closeWatcher();
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
