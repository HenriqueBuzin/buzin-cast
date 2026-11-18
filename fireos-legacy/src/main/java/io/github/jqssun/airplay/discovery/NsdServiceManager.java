package io.github.jqssun.airplay.discovery;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;
import io.buzincast.fireos3probe.LegacyNetwork;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/** API17 DNS-SD with TXT records, unavailable through that platform's public NSD API. */
public final class NsdServiceManager {
    private final WifiManager wifi;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(action->new Thread(action,"buzin-legacy-fcast-mdns"));
    private WifiManager.MulticastLock multicast;
    private volatile boolean closed;
    private JmDNS dns;
    public NsdServiceManager(Context context) {wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);}
    public synchronized void acquireMulticastLock() {
        if(closed)throw new IllegalStateException("Discovery is closed");
        if(multicast!=null)return;
        multicast=wifi.createMulticastLock("buzin-legacy-fcast");multicast.setReferenceCounted(false);multicast.acquire();
    }
    public void register(List<MdnsService> services) {
        worker.execute(()-> {
            try {
                if(closed)return;
                dns=JmDNS.create(LegacyNetwork.select().address,"buzincast-fcast-"+UUID.randomUUID().toString().substring(0,8));
                for(MdnsService service:services) {
                    if(closed)break;
                    dns.registerService(ServiceInfo.create(service.type+".local.",service.name,service.port,0,0,service.txt));
                }
            }catch(IOException | RuntimeException error) {
                Log.w("BuzinLegacyFCast","Discovery registration failed: "+error.getClass().getSimpleName());
            }
        });
    }
    /** Called by the service's protocol worker, never by its UI thread. */
    public synchronized void release() {
        if(closed)return;closed=true;
        worker.execute(()-> {
            try {if(dns!=null)dns.close();}
            catch(IOException error) {Log.w("BuzinLegacyFCast","Discovery shutdown failed: "+error.getClass().getSimpleName());}
            finally {
                dns=null;
                if(multicast!=null) {if(multicast.isHeld())multicast.release();multicast=null;}
            }
        });
        worker.shutdown();
        try {if(!worker.awaitTermination(10000,TimeUnit.MILLISECONDS))throw new IllegalStateException("Discovery shutdown timed out");}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new IllegalStateException("Discovery shutdown interrupted",interrupted);}
    }
}
