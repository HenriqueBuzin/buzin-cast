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
import android.view.KeyEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import io.buzincast.fireos3probe.LegacyReceiverSession;
import io.buzincast.fireos3probe.LegacyNetwork;
import io.buzincast.fireos3probe.LegacyDacp;
import io.buzincast.fireos3probe.LegacyMedia;
import io.buzincast.fireos3probe.LegacyUrlPlayer;
import io.buzincast.fireos3probe.LegacyUrlRequest;
import io.buzincast.core.MediaRequest;
import io.buzincast.core.PlaybackInfo;
import io.buzincast.core.Protocol;
import io.buzincast.core.ReceiverPlaybackPort;
import io.github.jqssun.airplay.discovery.FCastReceiver;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kotlin.Unit;

/** Main-thread owner of the receiver. Native/codec work stays in runtime workers. */
public final class LegacyReceiverService extends Service {
    static final String OPEN="open",START="start",STOP="stop",RESTART="restart",CONFIGURE="configure",BOOT="boot";
    private static final int NOTIFICATION=17001;
    private static final String CHANNEL="buzin-legacy-receiver";
    interface Observer { void changed(State state); }
    static final class State {
        final boolean desired,running,busy,video,networkWaiting,audioSuppressed;
        final String name,address;
        final int port,width,height;
        final Exception failure;
        final List<String> events;
        final LegacyDacp.State controls;
        final LegacyMedia.State media;
        final LegacyUrlPlayer.State url;
        final boolean urlError;
        State(LegacyReceiverService service) {
            url=service.urlState();urlError=service.urlFailure!=null;
            desired=service.desired;running=service.running;busy=service.busy;
            video=url==null?service.video:url.width>0 && url.height>0;
            audioSuppressed=service.systemMedia.suppressed();
            networkWaiting=service.wanted() && service.reconnect.initialized() && !service.reconnect.online();
            name=service.name;failure=urlError?service.urlFailure:service.failure;
            width=url==null?service.width:url.width;height=url==null?service.height:url.height;
            address=service.session==null?null:service.session.localAddress();
            port=service.session==null?0:service.session.listeningPort();
            controls=service.controlState();
            media=service.mediaState();
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
    private Exception controlFailure;
    private Exception mediaFailure;
    private LegacySystemMedia systemMedia;
    private LegacyUrlPlayer urlPlayer;
    private LegacyUrlRequest pendingUrl;
    private long urlGeneration;
    private int urlInstance;
    private boolean urlRequested,urlStopping;
    private float urlGain=1;
    private Exception urlFailure;
    private Protocol urlOwner;
    private FCastReceiver fcast;
    private volatile int fcastEpoch;
    private boolean fcastClosing;
    private LegacyNetwork fcastNetwork;
    private String fcastName;
    private final ExecutorService protocolWorker=Executors.newSingleThreadExecutor(action->new Thread(action,"buzin-legacy-protocols"));
    LegacyUrlPlayer.State urlState() {
        if(!urlRequested || urlStopping || urlPlayer==null)return null;
        LegacyUrlPlayer.State state=urlPlayer.state();return state.generation==urlGeneration?state:null;
    }
    LegacyMedia.State mediaState() {return urlRequested || session==null || stopping || session.mirroring()?LegacyMedia.inactive():session.media();}
    private LegacyDacp.State controlState() {return urlRequested || session==null || stopping || session.mirroring()?LegacyDacp.inactive():session.controls();}

    static void dispatch(Context context,String action) {
        Intent intent=new Intent(context,LegacyReceiverService.class).setAction(action);
        if(Build.VERSION.SDK_INT>=26)Api26.start(context,intent);else context.startService(intent);
    }
    @Override public void onCreate() {
        super.onCreate();prefs=new LegacyPreferences(this);name=prefs.name();
        systemMedia=new LegacySystemMedia(this,new LegacySystemMedia.Sink() {
            @Override public void command(LegacyDacp.Command command) {control(command,null);}
            @Override public void gain(float gain) {
                if(session!=null)session.audioFocusGain(gain);
                if(urlPlayer!=null && !urlStopping)urlPlayer.focusGain(gain);
            }
            @Override public void stopLocal() {stopUrl();}
            @Override public void failure(RuntimeException error) {record("System media: "+error.getClass().getSimpleName());}
            @Override public void changed() {publish();}
        });
    }
    static void mediaButton(Context context,KeyEvent event,int epoch) {
        Intent command=new Intent(context,LegacyReceiverService.class).setAction(Intent.ACTION_MEDIA_BUTTON)
            .putExtra(Intent.EXTRA_KEY_EVENT,event).putExtra("media_epoch",epoch);
        if(Build.VERSION.SDK_INT>=26)Api26.start(context,command);else context.startService(command);
    }
    @Override public IBinder onBind(Intent intent) {return binder;}
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        // Fulfil startForegroundService's deadline before doing network work.
        promote();String action=intent==null?null:intent.getAction();
        if(action==null) {if(!opened) {desired=prefs.serviceRequested();bootSession=prefs.bootAutoStart();opened=true;}}
        else if(OPEN.equals(action)) {if(!opened) {desired=prefs.autoStart();opened=true;}}
        else if(START.equals(action)) {desired=true;opened=true;failure=null;urlFailure=null;resetRetries();}
        else if(STOP.equals(action)) {desired=false;opened=true;bootSession=false;failure=null;urlFailure=null;stopUrl();}
        else if(BOOT.equals(action)) {desired=prefs.bootAutoStart();bootSession=desired;opened=true;}
        else if(RESTART.equals(action)) {name=prefs.name();resetRetries();if(session!=null)stopNative();}
        else if(Intent.ACTION_MEDIA_BUTTON.equals(action)) {
            Object event=intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if(event instanceof KeyEvent)systemMedia.key((KeyEvent)event,intent.getIntExtra("media_epoch",-1));
        }
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
    void control(LegacyDacp.Command command,Runnable failed) {
        if(urlRequested) {
            // URL seeks are discrete. A remote seek-key release must not acquire
            // audio focus or resume playback; RESUME belongs to native DACP.
            if(command==LegacyDacp.Command.RESUME || command==LegacyDacp.Command.NEXT || command==LegacyDacp.Command.PREVIOUS)return;
            if(urlPlayer==null || urlStopping || !systemMedia.prepare(command)) {if(failed!=null)failed.run();return;}
            LegacyUrlPlayer.State state=urlState();
            switch(command) {
                case PLAY: urlPlayer.playing(true);break;
                case PAUSE: urlPlayer.playing(false);break;
                case FAST_FORWARD: case REWIND:
                    if(state!=null && state.durationMs>0)urlPlayer.seek(Math.min(state.durationMs,Math.max(0,state.positionMs+(command==LegacyDacp.Command.REWIND?-10000:10000))));
                    break;
                case VOLUME_UP: case VOLUME_DOWN:
                    urlGain=Math.max(0,Math.min(1,urlGain+(command==LegacyDacp.Command.VOLUME_UP?.05f:-.05f)));urlPlayer.gain(urlGain);break;
                default: break;
            }
            return;
        }
        if(session==null || stopping || session.mirroring())return;
        if(!systemMedia.prepare(command)) {if(failed!=null)failed.run();return;}
        int owner=generation;
        session.control(command,error->main.post(()-> {
            if(destroyed || owner!=generation || stopping)return;
            if(error!=null) {record("DACP "+command+": "+error.getClass().getSimpleName());if(failed!=null)failed.run();}
            publish();
        }));
    }
    void detach(Object owner) {
        if(client!=owner)return;
        client=null;observer=null;visible=false;target=null;bindSurface();reconcile();
    }
    private void bindSurface() {
        if(urlRequested && session==null) {
            if(urlPlayer!=null && !urlStopping) {
                if(target==null)urlPlayer.surface(null,null);
                else {TextureTarget owned=target;urlPlayer.surface(owned.acquire(),owned::retired);}
            }
            return;
        }
        if(session==null)return;
        if(target==null)session.setVideoSurface(null,null);
        else {TextureTarget owned=target;session.setVideoSurface(owned.acquire(),owned::retired);}
    }
    private boolean wanted() {return (desired || urlRequested) && (visible || prefs.background() || bootSession);}
    /** Internal protocol entry point. Validate before disturbing the active receiver. */
    void loadUrl(LegacyUrlRequest request) {
        loadUrl(request,Protocol.LOCAL);
    }
    private void loadUrl(LegacyUrlRequest request,Protocol protocol) {
        if(request==null)throw new IllegalArgumentException("Missing media request");
        if(destroyed || !(visible || prefs.background()))throw new IllegalStateException("Receiver is unavailable");
        if(urlStopping)throw new IllegalStateException("Previous media is still closing");
        pendingUrl=request;urlRequested=true;urlFailure=null;urlGain=1;urlOwner=protocol;
        if(session!=null)stopNative();
        reconcile();
    }
    private void startPendingUrl() {
        if(pendingUrl==null || urlStopping)return;
        if(urlPlayer==null) {
            final int owner=++urlInstance;
            urlPlayer=new LegacyUrlPlayer(this,state->main.post(()-> {
                if(destroyed || owner!=urlInstance)return;
                if(urlStopping) {
                    if(state.phase!=LegacyUrlPlayer.Phase.CLOSED)return;
                    urlPlayer=null;urlRequested=urlStopping=false;urlGeneration=0;urlOwner=null;releaseWakeLock();
                    if(state.failure!=null) {urlFailure=state.failure;desired=false;prefs.serviceRequested(false);}
                    reconcile();return;
                }
                if(state.generation!=urlGeneration)return;
                if(state.phase==LegacyUrlPlayer.Phase.FAILED) {
                    urlFailure=state.failure;record("URL player: "+state.failure.getClass().getSimpleName());
                    if(urlOwner==Protocol.FCAST && fcast!=null)fcast.playbackError(getString(R.string.error_label));
                    stopUrl();
                } else if(state.phase==LegacyUrlPlayer.Phase.ENDED) {
                    stopUrl();
                } else {
                    publish();
                }
            }));
            acquireWakeLock();bindSurface();
        }
        LegacyUrlRequest request=pendingUrl;pendingUrl=null;
        urlPlayer.gain(urlGain);urlGeneration=urlPlayer.load(request,true);
    }
    void stopUrl() {
        pendingUrl=null;
        if(!urlRequested || urlStopping)return;
        if(urlPlayer==null) {urlRequested=false;urlOwner=null;reconcile();return;}
        urlStopping=true;urlPlayer.close();publish();
    }
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
            if(urlRequested && session==null)startPendingUrl();
            else if(session==null) {
                busy=!reconnect.initialized();
                if(reconnect.canStart(SystemClock.elapsedRealtime()))startNative();
            }
        } else {
            closeWatcher();
            if(urlRequested)stopUrl();
            else if(session!=null)stopNative();else {busy=false;demote();stopSelf();}
        }
        reconcileFcast();
        publish();
    }
    private void reconcileFcast() {
        boolean enabled=wanted() && reconnect.online() && selectedNetwork!=null && prefs.fcast();
        if(fcast!=null && (!enabled || !selectedNetwork.equals(fcastNetwork) || !prefs.name().equals(fcastName))) {
            FCastReceiver previous=fcast;fcast=null;fcastClosing=true;fcastEpoch++;
            if(urlOwner==Protocol.FCAST)stopUrl();
            protocolWorker.execute(()-> {
                try {previous.close();}
                catch(RuntimeException error) {main.post(()-> {if(!destroyed)record("FCast shutdown: "+error.getClass().getSimpleName());});}
                finally {main.post(()-> {fcastClosing=false;if(!destroyed)reconcile();});}
            });
        }
        if(enabled && fcast==null && !fcastClosing) {
            int owner=++fcastEpoch;
            fcastNetwork=selectedNetwork;fcastName=prefs.name();
            fcast=new FCastReceiver(this,new NetworkPlayback(owner),fcastName,message-> {
                // Shared transport log text may contain a LAN address; keep it out
                // of the diagnostics/screenshots exported from the app.
                main.post(()-> {if(!destroyed && owner==fcastEpoch && message.contains("failed"))record("FCast: "+getString(R.string.error_label));});
                return Unit.INSTANCE;
            },46899);
            fcast.start();
        }
    }
    /** Main-thread playback contract shared with the modern FCast receiver. */
    private final class NetworkPlayback implements ReceiverPlaybackPort {
        private final int owner;
        NetworkPlayback(int owner) {this.owner=owner;}
        private void current() {if(owner!=fcastEpoch || destroyed)throw new IllegalStateException("Receiver was replaced");}
        private void owns(Protocol protocol) {
            current();if(protocol!=Protocol.FCAST || urlOwner!=protocol || !urlRequested || urlStopping)throw new IllegalStateException("No FCast media is playing");
        }
        @Override public int getActiveConnections() {
            current();return session==null?0:Math.max(session.connectionCount(),
                session.mirroring() || session.audioActive() || session.media().active || session.controls().identified?1:0);
        }
        @Override public boolean getRelayReceiving() {return false;}
        @Override public Protocol getMediaOwner() {return urlRequested && !urlStopping?urlOwner:null;}
        @Override public boolean getMediaActive() {return urlRequested && !urlStopping;}
        @Override public PlaybackInfo getPlaybackInfo() {
            LegacyUrlPlayer.State state=urlState();
            return state==null?new PlaybackInfo(0,0,false,1,false,false):new PlaybackInfo(state.positionMs,state.durationMs,state.playing,1,false,state.buffering);
        }
        @Override public boolean authorize(String protocol,String peer) {return owner==fcastEpoch && prefs.fcast();}
        @Override public void load(MediaRequest request) {
            current();if(request.getProtocol()!=Protocol.FCAST || getActiveConnections()!=0)throw new IllegalStateException("Another receiver session is active");
            float seconds=request.getTime();
            if(Float.isNaN(seconds) || Float.isInfinite(seconds) || seconds<0 || seconds*1000.0>Integer.MAX_VALUE)throw new IllegalArgumentException("Unsupported media position");
            loadUrl(new LegacyUrlRequest(request.getLocation(),(long)(seconds*1000.0),request.getHeaders()),request.getProtocol());
        }
        @Override public void stop(Protocol protocol) {owns(protocol);stopUrl();}
        @Override public void playing(Protocol protocol,boolean playing) {owns(protocol);control(playing?LegacyDacp.Command.PLAY:LegacyDacp.Command.PAUSE,null);}
        @Override public void seek(Protocol protocol,float seconds) {
            owns(protocol);
            if(Float.isNaN(seconds) || Float.isInfinite(seconds) || seconds<0 || seconds*1000.0>Integer.MAX_VALUE)throw new IllegalArgumentException("Unsupported media position");
            if(urlPlayer!=null)urlPlayer.seek((long)(seconds*1000.0));
        }
        @Override public void gain(Protocol protocol,float gain) {
            owns(protocol);
            if(Float.isNaN(gain) || Float.isInfinite(gain) || gain<0 || gain>1)throw new IllegalArgumentException("Invalid media gain");
            urlGain=gain;if(urlPlayer!=null)urlPlayer.gain(gain);
        }
        @Override public void speed(Protocol protocol,float speed) {
            owns(protocol);
            if(speed!=1) {stopUrl();throw new UnsupportedOperationException("Playback speed is unavailable on this backend");}
        }
        @Override public void photo(String location) {throw new UnsupportedOperationException("FCast does not receive photos");}
    }
    private void startNative() {
        main.removeCallbacks(retry);main.removeCallbacks(stable);
        busy=true;stopping=false;failure=null;name=prefs.name();final int owner=++generation;
        acquireWakeLock();
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
            @Override public void streamChanged() {post(()-> {if(!stopping)publish();});}
            @Override public void videoSize(int w,int h) {post(()-> {if(!stopping) {width=w;height=h;publish();}});}
            @Override public void controlsChanged() {post(()-> {
                if(stopping)return;
                Exception current=session==null?null:session.controls().failure;
                if(current!=null && current!=controlFailure)record("DACP: "+current.getClass().getSimpleName());
                controlFailure=current;publish();
            });}
            @Override public void mediaChanged() {post(()-> {
                if(stopping)return;
                Exception current=mediaState().failure;
                if(current!=null && current!=mediaFailure)record("Metadata: "+current.getClass().getSimpleName());
                mediaFailure=current;publish();
            });}
        },prefs.options(),selectedNetwork);
        session.audioFocusGain(0);bindSurface();session.start();
    }
    private void stopNative() {main.removeCallbacks(stable);video=false;if(!stopping) {stopping=busy=true;session.stop();}}
    private void record(String text) {if(events.size()==100)events.removeFirst();events.addLast(text);}
    private void publish() {
        LegacyUrlPlayer.State url=urlState();
        systemMedia.update(mediaState(),controlState(),url!=null || running && !stopping && session!=null,
            session!=null && session.mirroring(),session!=null && session.audioActive(),url);
        if(promoted && !notificationBody().equals(notificationText))
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification());
        if(observer!=null)observer.changed(new State(this));
    }
    private void releaseWakeLock() {if(wakeLock!=null) {if(wakeLock.isHeld())wakeLock.release();wakeLock=null;}}
    @android.annotation.SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        if(wakeLock==null) {
            PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
            wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"buzincast:legacy-receiver");wakeLock.setReferenceCounted(false);
        }
        if(!wakeLock.isHeld())wakeLock.acquire();
    }
    private String notificationBody() {
        if(wanted() && reconnect.initialized() && !reconnect.online())return getString(R.string.network_waiting);
        if(failure!=null)return getString(R.string.error_starting_server);
        if(urlFailure!=null)return getString(R.string.error_label);
        if(urlRequested)return getString(R.string.waiting_for_playback);
        LegacyMedia.State media=mediaState();
        if(media.active) {
            String title=media.track.title.isEmpty()?getString(R.string.waiting_for_playback):media.track.title;
            return media.track.artist.isEmpty()?title:title+" · "+media.track.artist;
        }
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
        if(systemMedia!=null)systemMedia.close();
        fcastEpoch++;
        if(fcast!=null) {FCastReceiver previous=fcast;fcast=null;protocolWorker.execute(previous::close);}
        protocolWorker.shutdown();
        urlInstance++;if(urlPlayer!=null) {urlStopping=true;urlPlayer.close();}
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
