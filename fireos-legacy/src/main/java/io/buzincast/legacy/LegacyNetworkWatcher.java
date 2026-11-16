package io.buzincast.legacy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import io.buzincast.fireos3probe.LegacyNetwork;

/** Interface scans stay off the UI thread; unchanged broadcasts do not restart sessions. */
final class LegacyNetworkWatcher implements java.io.Closeable {
    interface Observer {void changed(String fingerprint,LegacyNetwork network);}
    private final Context context;
    private final Handler main;
    private final Observer observer;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(action-> {
        Thread thread=new Thread(action,"buzin-legacy-network");thread.setDaemon(true);return thread;
    });
    private final AtomicBoolean queued=new AtomicBoolean();
    private volatile boolean closed;
    private LegacyNetwork preferred;
    private String previous;
    private boolean initialized;
    private final BroadcastReceiver receiver=new BroadcastReceiver() {
        @Override public void onReceive(Context unused,Intent intent) {checkSoon();}
    };
    LegacyNetworkWatcher(Context context,Handler main,Observer observer) {
        this.context=context.getApplicationContext();this.main=main;this.observer=observer;
        IntentFilter filter=new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        if(Build.VERSION.SDK_INT>=33)Api33.register(this.context,receiver,filter);else this.context.registerReceiver(receiver,filter);
        worker.scheduleWithFixedDelay(this::scan,0,2,TimeUnit.SECONDS);
    }
    void checkSoon() {
        if(closed || !queued.compareAndSet(false,true))return;
        try {worker.execute(()-> {queued.set(false);scan();});}
        catch(java.util.concurrent.RejectedExecutionException stopping) {queued.set(false);}
    }
    private void scan() {
        if(closed)return;
        LegacyNetwork network=null;String key=null;
        try {
            network=LegacyNetwork.select(preferred);preferred=network;
            // Route identity remains private in memory; no SSID/address is logged.
            String route="";
            try {
                ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo active=manager==null?null:manager.getActiveNetworkInfo();
                if(active!=null && active.getType()==ConnectivityManager.TYPE_WIFI)route=active.getExtraInfo();
            } catch(SecurityException restricted) { /* Local interface remains usable. */ }
            key=network.fingerprint()+":"+route;
        } catch(IOException | RuntimeException unavailable) {preferred=null;network=null;key=null;}
        if(initialized && (previous==null?key==null:previous.equals(key)))return;
        initialized=true;previous=key;final String observed=key;final LegacyNetwork binding=network;
        main.post(()-> {if(!closed)observer.changed(observed,binding);});
    }
    @Override public void close() {
        closed=true;try {context.unregisterReceiver(receiver);}finally {worker.shutdownNow();}
    }
    @android.annotation.TargetApi(33) private static final class Api33 {
        static void register(Context context,BroadcastReceiver receiver,IntentFilter filter) {
            context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);
        }
    }
}
