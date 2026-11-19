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
import io.github.jqssun.airplay.discovery.DlnaReceiver;
import io.github.jqssun.airplay.media.PhotoActivity;
import io.github.jqssun.airplay.media.ConnectionApproval;
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
        final String name,address,pin;
        final int port,width,height;
        final Exception failure;
        final List<String> events;
        final LegacyDacp.State controls;
        final LegacyMedia.State media;
        final LegacyUrlPlayer.State url;
        final boolean urlError;
        final ConnectionApproval.Request approval;
        State(LegacyReceiverService service) {
            approval=service.approval.getRequest().getValue();
            pin=service.pinCode;
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
    private final ConnectionApproval approval=new ConnectionApproval(()-> {
        main.post(this::showApproval);
        return Unit.INSTANCE;
    });
    private void showApproval() {
        if(destroyed || approvalRequest()==null)return;
        publish();
        if(!visible)startActivity(new Intent(this,LegacyActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    ConnectionApproval.Request approvalRequest() {return approval.getRequest().getValue();}
    void answerApproval(long id,boolean allow) {approval.answer(id,allow);publish();}
    private boolean authorizeConnection(String protocol,String peer) {
        if(!prefs.approveConnections())return true;
        try {return approval.ask(protocol,peer);}
        finally {main.post(()-> {if(!destroyed)publish();});}
    }
    private final ArrayDeque<String> events=new ArrayDeque<>();
    private LegacyPreferences prefs;
    private boolean approvalConfigured,pinConfigured;
    private String pinCode;
    void dismissPin() {pinCode=null;publish();}
    private volatile LegacyReceiverSession session;
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
    private boolean desired,running,busy,video,visible,opened,bootSession,promoted;
    private volatile boolean stopping,destroyed;
    private volatile int generation;
    private int width,height;
    private String name;
    private Exception failure;
    private Exception controlFailure;
    private Exception mediaFailure;
    private LegacySystemMedia systemMedia;
    private LegacyMirrorCapture capture;
    private LegacyDownloads downloads;
    private LegacyUrlRequest currentUrlRequest;
    private io.buzincast.fireos3probe.LegacyUrlDownload.Phase downloadNotice;
    LegacyDownloads downloads() {return downloads;}
    boolean canDownload() {return BuildConfig.VIDEO_DOWNLOAD_ENABLED && urlRequested && currentUrlRequest!=null && !currentUrlRequest.localRecording;}
    void download() {
        if(!canDownload())return;downloads.start(currentUrlRequest);acquireWakeLock();reconcile();
    }
    void playDownload(String name) throws java.io.IOException {loadUrl(LegacyUrlRequest.downloaded(downloads.file(name)));}
    LegacyMirrorCapture capture() {return capture;}
    void playRecording(String name) throws java.io.IOException {
        loadUrl(LegacyUrlRequest.recording(capture.file(name)));
    }
    private LegacyUrlPlayer urlPlayer;
    private LegacyUrlRequest pendingUrl;
    private long urlGeneration;
    private long urlRequestEpoch;
    private long subtitlePicker;
    private final Runnable subtitlePickerTimeout=()-> {subtitlePicker=0;reconcile();};
    private final ExecutorService subtitleWorker=Executors.newSingleThreadExecutor(action->new Thread(action,"buzin-legacy-subtitles"));
    private int urlInstance;
    private boolean urlRequested,urlStopping,urlPlay=true;
    private LegacyReceiverSession parkedNative,detachingNative;
    private int outputEpoch;
    private float systemGain;
    private LegacyReceiverSession urlStreamSession,nativeUrlSession;
    private long urlStreamEpoch,nativeUrlToken;
    private float urlGain=1,urlSpeed=1;
    private Exception urlFailure;
    private Protocol urlOwner;
    private FCastReceiver fcast;
    private volatile int fcastEpoch;
    private boolean fcastClosing;
    private LegacyNetwork fcastNetwork;
    private String fcastName;
    private boolean fcastApproval;
    private DlnaReceiver dlna;
    private volatile int dlnaEpoch;
    private boolean dlnaClosing;
    private LegacyNetwork dlnaNetwork;
    private String dlnaName;
    private boolean dlnaApproval;
    private static final class NetworkState {
        final Protocol owner;
        final boolean active;
        final PlaybackInfo info;
        NetworkState(Protocol owner,boolean active,PlaybackInfo info) {this.owner=owner;this.active=active;this.info=info;}
    }
    private static final NetworkState EMPTY_NETWORK=new NetworkState(null,false,new PlaybackInfo(0,0,false,1,false,false));
    private volatile NetworkState networkState=EMPTY_NETWORK;
    private final ExecutorService protocolWorker=Executors.newSingleThreadExecutor(action->new Thread(action,"buzin-legacy-protocols"));
    LegacyUrlPlayer.State urlState() {
        if(!urlRequested || urlStopping || urlPlayer==null)return null;
        LegacyUrlPlayer.State state=urlPlayer.state();return state.generation==urlGeneration?state:null;
    }
    long subtitleSession() {return urlRequested && !urlStopping?urlRequestEpoch:0;}
    void beginSubtitlePicker(long expected) {
        if(expected==0 || expected!=subtitleSession())return;
        subtitlePicker=expected;main.removeCallbacks(subtitlePickerTimeout);main.postDelayed(subtitlePickerTimeout,5*60*1000);
    }
    void endSubtitlePicker() {if(subtitlePicker==0)return;subtitlePicker=0;main.removeCallbacks(subtitlePickerTimeout);reconcile();}
    void subtitle(android.net.Uri uri,long expected) {
        if(destroyed || expected==0 || expected!=subtitleSession() || urlPlayer==null)return;
        LegacyUrlPlayer owner=urlPlayer;long generation=urlGeneration;
        if(uri==null) {owner.subtitle(generation,null,null);return;}
        subtitleWorker.execute(()-> {
            try {
                kotlin.Pair<byte[],String> imported=LegacySubtitleImport.read(this,uri);
                main.post(()-> {if(!destroyed && expected==subtitleSession() && owner==urlPlayer)owner.subtitle(generation,imported.getFirst(),imported.getSecond());});
            }catch(Exception error) {
                main.post(()-> {if(!destroyed && expected==subtitleSession() && owner==urlPlayer)
                    android.widget.Toast.makeText(this,R.string.subtitle_load_failed,android.widget.Toast.LENGTH_LONG).show();});
            }
        });
    }
    LegacyMedia.State mediaState() {return urlRequested || session==null || stopping || session.mirroring()?LegacyMedia.inactive():session.media();}
    private LegacyDacp.State controlState() {return urlRequested || session==null || stopping || session.mirroring()?LegacyDacp.inactive():session.controls();}

    static void dispatch(Context context,String action) {
        Intent intent=new Intent(context,LegacyReceiverService.class).setAction(action);
        if(Build.VERSION.SDK_INT>=26)Api26.start(context,intent);else context.startService(intent);
    }
    @Override public void onCreate() {
        super.onCreate();prefs=new LegacyPreferences(this);name=prefs.name();
        approvalConfigured=prefs.approveConnections();
        pinConfigured=prefs.requirePin();
        capture=new LegacyMirrorCapture(this,()->main.post(()-> {if(!destroyed)publish();}),BuildConfig.RECORDING_ENABLED);
        downloads=new LegacyDownloads(this,()->main.post(()-> {
            if(destroyed)return;
            io.buzincast.fireos3probe.LegacyUrlDownload.State state=downloads.state();
            if(state!=null && downloadNotice!=state.phase) {
                downloadNotice=state.phase;
                if(state.phase==io.buzincast.fireos3probe.LegacyUrlDownload.Phase.SAVED || state.phase==io.buzincast.fireos3probe.LegacyUrlDownload.Phase.FAILED)
                    android.widget.Toast.makeText(this,downloads.message(),android.widget.Toast.LENGTH_LONG).show();
            }
            reconcile();
        }),BuildConfig.VIDEO_DOWNLOAD_ENABLED);
        systemMedia=new LegacySystemMedia(this,new LegacySystemMedia.Sink() {
            @Override public void command(LegacyDacp.Command command) {control(command,null);}
            @Override public void gain(float gain) {
                systemGain=gain;
                if(session!=null)session.audioFocusGain(urlRequested?0:gain);
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
        else if(STOP.equals(action)) {pinCode=null;approval.close();desired=false;opened=true;bootSession=false;failure=null;urlFailure=null;downloads.cancel();stopUrl();}
        else if(BOOT.equals(action)) {desired=prefs.bootAutoStart();bootSession=desired;opened=true;}
        else if(RESTART.equals(action)) {name=prefs.name();resetRetries();if(session!=null)stopNative();}
        else if(CONFIGURE.equals(action)) {
            approval.close();
            if(approvalConfigured!=prefs.approveConnections() || pinConfigured!=prefs.requirePin()) {
                approvalConfigured=prefs.approveConnections();
                pinConfigured=prefs.requirePin();pinCode=null;
                if(session!=null)stopNative();
            } else if(wanted())approval.open();
        }
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
            if(pendingUrl!=null && (command==LegacyDacp.Command.PLAY || command==LegacyDacp.Command.PAUSE)) {
                if(!systemMedia.prepare(command)) {if(failed!=null)failed.run();return;}
                urlPlay=command==LegacyDacp.Command.PLAY;publish();return;
            }
            if(urlPlayer==null || urlStopping || !systemMedia.prepare(command)) {if(failed!=null)failed.run();return;}
            LegacyUrlPlayer.State state=urlState();
            switch(command) {
                case PLAY: urlPlayer.playing(true);break;
                case PAUSE: urlPlayer.playing(false);break;
                case FAST_FORWARD: case REWIND:
                    if(state!=null && state.durationMs>0)urlPlayer.seek(Math.min(LegacyUrlRequest.MAX_POSITION_MS,Math.min(state.durationMs,Math.max(0,state.positionMs+(command==LegacyDacp.Command.REWIND?-10000:10000)))));
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
        if(urlRequested) {
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
    private void prepareUrlOutput() {
        if(urlStopping)return;
        LegacyReceiverSession nativeOwner=session;
        if(nativeOwner!=null && parkedNative!=nativeOwner) {
            if(detachingNative==nativeOwner)return;
            detachingNative=nativeOwner;int owner=++outputEpoch;
            nativeOwner.audioFocusGain(0);
            nativeOwner.detachVideoOutput(()->main.post(()-> {
                if(destroyed || owner!=outputEpoch)return;
                detachingNative=null;
                if(session==nativeOwner)parkedNative=nativeOwner;
                if(urlRequested && !urlStopping)reconcile();
            }));
            return;
        }
        startPendingUrl();
    }
    private void restoreNativeOutput() {
        ++outputEpoch;parkedNative=detachingNative=null;
        if(session!=null)session.audioFocusGain(systemGain);
        bindSurface();
    }
    private boolean wanted() {return (desired || urlRequested || downloads!=null && downloads.active()) && (visible || prefs.background() || bootSession || subtitlePicker!=0);}
    /** Internal protocol entry point. Validate before disturbing the active receiver. */
    void loadUrl(LegacyUrlRequest request) {
        loadUrl(request,Protocol.LOCAL);
    }
    private void loadUrl(LegacyUrlRequest request,Protocol protocol) {
        if(request==null)throw new IllegalArgumentException("Missing media request");
        if(destroyed || !(visible || prefs.background() || subtitlePicker!=0))throw new IllegalStateException("Receiver is unavailable");
        currentUrlRequest=request;
        urlRequestEpoch++;pendingUrl=request;urlPlay=true;urlRequested=true;urlFailure=null;urlGain=urlSpeed=1;urlOwner=protocol;
        urlStreamSession=session;urlStreamEpoch=session==null?0:session.streamEpoch();
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
                    urlPlayer=null;urlStopping=false;urlGeneration=0;
                    // Stop followed immediately by Play keeps only the latest
                    // validated request, without reattaching native output in between.
                    if(pendingUrl!=null && state.failure==null) {reconcile();return;}
                    pendingUrl=null;urlRequested=false;urlOwner=null;
                    restoreNativeOutput();if(session==null || stopping)releaseWakeLock();
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
            acquireWakeLock();urlPlayer.focusGain(systemGain);bindSurface();
        }
        LegacyUrlRequest request=pendingUrl;pendingUrl=null;
        urlPlayer.gain(urlGain);urlGeneration=urlPlayer.load(request,urlPlay);urlPlayer.speed(urlSpeed);
    }
    void urlSpeed(float speed) {
        if(Float.isNaN(speed) || Float.isInfinite(speed) || speed<.25f || speed>4)throw new IllegalArgumentException("Invalid playback speed");
        if(!urlRequested)return;
        urlSpeed=speed;if(urlPlayer!=null && !urlStopping)urlPlayer.speed(speed);
    }
    void stopUrl() {
        pendingUrl=null;
        if(!urlRequested || urlStopping)return;
        if(urlPlayer==null) {urlRequested=false;urlOwner=null;restoreNativeOutput();reconcile();return;}
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
            if(session==null && desired) {
                busy=!reconnect.initialized();
                if(reconnect.canStart(SystemClock.elapsedRealtime()))startNative();
            }
            if(urlRequested)prepareUrlOutput();
        } else {
            closeWatcher();
            if(urlRequested)stopUrl();
            else if(session!=null)stopNative();else {busy=false;demote();stopSelf();}
        }
        reconcileFcast();
        reconcileDlna();
        publish();
    }
    private void reconcileFcast() {
        boolean enabled=wanted() && reconnect.online() && selectedNetwork!=null && prefs.fcast();
        if(fcast!=null && (!enabled || !selectedNetwork.equals(fcastNetwork) || !prefs.name().equals(fcastName) || prefs.approveConnections()!=fcastApproval)) {
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
            fcastApproval=prefs.approveConnections();
            fcast=new FCastReceiver(this,new NetworkPlayback(Protocol.FCAST,owner),fcastName,message-> {
                // Shared transport log text may contain a LAN address; keep it out
                // of the diagnostics/screenshots exported from the app.
                main.post(()-> {if(!destroyed && owner==fcastEpoch && message.contains("failed"))record("FCast: "+getString(R.string.error_label));});
                return Unit.INSTANCE;
            },46899);
            fcast.start();
        }
    }
    private void reconcileDlna() {
        boolean enabled=wanted() && reconnect.online() && selectedNetwork!=null && prefs.dlna();
        if(dlna!=null && (!enabled || !selectedNetwork.equals(dlnaNetwork) || !prefs.name().equals(dlnaName) || prefs.approveConnections()!=dlnaApproval)) {
            DlnaReceiver previous=dlna;dlna=null;dlnaClosing=true;dlnaEpoch++;
            if(urlOwner==Protocol.DLNA)stopUrl();
            protocolWorker.execute(()-> {
                try {previous.close();}
                catch(RuntimeException error) {main.post(()-> {if(!destroyed)record("DLNA shutdown: "+error.getClass().getSimpleName());});}
                finally {main.post(()-> {dlnaClosing=false;if(!destroyed)reconcile();});}
            });
        }
        if(enabled && dlna==null && !dlnaClosing) {
            int owner=++dlnaEpoch;dlnaNetwork=selectedNetwork;dlnaName=prefs.name();
            dlnaApproval=prefs.approveConnections();
            dlna=new DlnaReceiver(this,new NetworkPlayback(Protocol.DLNA,owner),dlnaName,message-> {
                main.post(()-> {if(!destroyed && owner==dlnaEpoch && message.contains("failed"))record("DLNA: "+getString(R.string.error_label));});
                return Unit.INSTANCE;
            });
            dlna.start();
        }
    }
    /** Commands run on main; SOAP worker reads use an immutable published snapshot. */
    private final class NetworkPlayback implements ReceiverPlaybackPort {
        private final int owner;
        private final Protocol kind;
        NetworkPlayback(Protocol kind,int owner) {this.kind=kind;this.owner=owner;}
        private boolean valid() {return owner==(kind==Protocol.FCAST?fcastEpoch:dlnaEpoch);}
        private void current() {if(!valid() || destroyed)throw new IllegalStateException("Receiver was replaced");}
        private void owns(Protocol protocol) {
            current();if(protocol!=kind || urlOwner!=protocol || !urlRequested || urlStopping && pendingUrl==null)throw new IllegalStateException("No owned media is playing");
        }
        @Override public int getActiveConnections() {
            if(!valid())return 1;
            LegacyReceiverSession receiver=session;
            return receiver==null?0:Math.max(receiver.connectionCount(),
                receiver.mirroring() || receiver.audioActive() || receiver.media().active || receiver.controls().identified?1:0);
        }
        @Override public boolean getRelayReceiving() {return false;}
        @Override public Protocol getMediaOwner() {return valid()?networkState.owner:null;}
        @Override public boolean getMediaActive() {return valid() && networkState.active;}
        @Override public PlaybackInfo getPlaybackInfo() {return valid()?networkState.info:EMPTY_NETWORK.info;}
        @Override public boolean authorize(String protocol,String peer) {
            if(!valid() || !(kind==Protocol.FCAST?prefs.fcast():prefs.dlna()))return false;
            try {
                java.net.InetAddress address=java.net.InetAddress.getByName(peer);
                if(!address.isSiteLocalAddress() && !address.isLoopbackAddress())return false;
                return authorizeConnection(kind==Protocol.FCAST?"FCast":"DLNA",peer) && valid() && !destroyed;
            }
            catch(java.net.UnknownHostException invalid) {return false;}
        }
        @Override public void load(MediaRequest request) {
            current();if(request.getProtocol()!=kind || getActiveConnections()!=0)throw new IllegalStateException("Another receiver session is active");
            float seconds=request.getTime();
            if(Float.isNaN(seconds) || Float.isInfinite(seconds) || seconds<0 || seconds*1000.0>LegacyUrlRequest.MAX_POSITION_MS)throw new IllegalArgumentException("Unsupported media position");
            LegacyUrlRequest media=new LegacyUrlRequest(request.getLocation(),(long)(seconds*1000.0),request.getHeaders(),request.getMime());
            PhotoActivity.Companion.closePhoto();loadUrl(media,request.getProtocol());
        }
        @Override public void stop(Protocol protocol) {owns(protocol);stopUrl();}
        @Override public void playing(Protocol protocol,boolean playing) {owns(protocol);control(playing?LegacyDacp.Command.PLAY:LegacyDacp.Command.PAUSE,null);}
        @Override public void seek(Protocol protocol,float seconds) {
            owns(protocol);
            if(Float.isNaN(seconds) || Float.isInfinite(seconds) || seconds<0 || seconds*1000.0>LegacyUrlRequest.MAX_POSITION_MS)throw new IllegalArgumentException("Unsupported media position");
            long position=(long)(seconds*1000.0);
            if(pendingUrl!=null)pendingUrl=pendingUrl.withPosition(position);
            else if(urlPlayer!=null && !urlStopping)urlPlayer.seek(position);
        }
        @Override public void gain(Protocol protocol,float gain) {
            owns(protocol);
            if(Float.isNaN(gain) || Float.isInfinite(gain) || gain<0 || gain>1)throw new IllegalArgumentException("Invalid media gain");
            urlGain=gain;if(urlPlayer!=null && !urlStopping)urlPlayer.gain(gain);
        }
        @Override public void speed(Protocol protocol,float speed) {
            owns(protocol);
            urlSpeed(speed);
        }
        @Override public void photo(String location) {
            current();if(kind!=Protocol.DLNA || getActiveConnections()!=0)throw new IllegalStateException("Photo receiver is unavailable");
            String validated=new LegacyUrlRequest(location,0,null).location;
            stopUrl();
            startActivity(new Intent(LegacyReceiverService.this,PhotoActivity.class).putExtra("url",validated)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
    }
    private void startNative() {
        approval.open();
        main.removeCallbacks(retry);main.removeCallbacks(stable);
        busy=true;stopping=false;failure=null;name=prefs.name();final int owner=++generation;
        acquireWakeLock();
        session=new LegacyReceiverSession(this,null,prefs.identity(),new LegacyReceiverSession.Listener() {
            @Override public void pin(String code) {post(()-> {
                if(stopping || !prefs.requirePin())return;
                pinCode=code;publish();
                if(code!=null && !visible)startActivity(new Intent(LegacyReceiverService.this,LegacyActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
            });}
            @Override public boolean authorize(String peer) {
                return owner==generation && !stopping && !destroyed && authorizeConnection("AirPlay",peer)
                    && owner==generation && !stopping && !destroyed;
            }
            private void post(Runnable action) {main.post(()-> {if(!destroyed && owner==generation)action.run();});}
            @Override public void ready(String receiverName) {post(()-> {
                if(stopping)return;running=true;busy=false;name=receiverName;record(getString(R.string.diag_receiver_running));
                if(recovering) {record(getString(R.string.network_recovered));recovering=false;}
                main.removeCallbacks(stable);main.postDelayed(stable,30000);publish();
            });}
            @Override public void failed(Exception error) {post(()-> {
                if(stopping)return;
                pinCode=null;
                if(nativeUrlSession==session && urlOwner==Protocol.AIRPLAY)stopUrl();
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
                pinCode=null;
                if(nativeUrlSession==session && urlOwner==Protocol.AIRPLAY)stopUrl();
                session=null;parkedNative=detachingNative=null;++outputEpoch;
                running=busy=video=stopping=false;width=height=0;if(!urlRequested)releaseWakeLock();
                record(getString(R.string.server_stopped));reconcile();
            });}
            @Override public void videoStarted() {post(()-> {if(!stopping) {video=true;publish();}});}
            @Override public void videoStopped() {post(()-> {video=false;publish();});}
            @Override public void streamChanged() {post(()-> {
                if(stopping)return;
                if(session!=null && (session.mirroring() || session.audioActive()))pinCode=null;
                if(urlRequested && session!=null && (session!=urlStreamSession || session.streamEpoch()>urlStreamEpoch) &&
                    (session.mirroring() || session.audioActive()))stopUrl();
                publish();
            });}
            @Override public boolean acceptsUrl() {return true;}
            @Override public void urlPlay(long token,LegacyUrlRequest request) {post(()-> {
                if(stopping || session==null || failure!=null || session.isClosed()) {if(session!=null)session.finishUrl(token);return;}
                nativeUrlSession=session;nativeUrlToken=token;
                pinCode=null;
                try {PhotoActivity.Companion.closePhoto();loadUrl(request,Protocol.AIRPLAY);}
                catch(RuntimeException error) {
                    session.finishUrl(token);nativeUrlToken=0;nativeUrlSession=null;
                    record("Native URL: "+error.getClass().getSimpleName());publish();
                }
            });}
            private boolean ownsNativeUrl(long token) {return !stopping && nativeUrlSession==session && nativeUrlToken==token && urlRequested && urlOwner==Protocol.AIRPLAY;}
            @Override public void urlSeek(long token,float seconds) {post(()-> {
                if(!ownsNativeUrl(token))return;
                long position=(long)(seconds*1000.0);
                if(pendingUrl!=null)pendingUrl=pendingUrl.withPosition(position);
                else if(urlPlayer!=null && !urlStopping)urlPlayer.seek(position);
            });}
            @Override public void urlRate(long token,float rate) {post(()-> {
                if(!ownsNativeUrl(token))return;
                if(rate>0)urlSpeed(rate);
                control(rate>0?LegacyDacp.Command.PLAY:LegacyDacp.Command.PAUSE,null);
            });}
            @Override public void urlStop(long token) {post(()-> {if(ownsNativeUrl(token))stopUrl();});}
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
        session.capture(capture);session.audioFocusGain(0);bindSurface();session.start();
    }
    private void stopNative() {pinCode=null;approval.close();main.removeCallbacks(stable);video=false;if(!stopping) {stopping=busy=true;session.stop();}}
    private void record(String text) {if(events.size()==100)events.removeFirst();events.addLast(text);}
    private void publish() {
        LegacyUrlPlayer.State url=urlState();
        boolean mediaPending=urlRequested && (!urlStopping || pendingUrl!=null);
        if(nativeUrlSession!=null) {
            if(!mediaPending || urlOwner!=Protocol.AIRPLAY || nativeUrlSession!=session) {
                nativeUrlSession.finishUrl(nativeUrlToken);nativeUrlSession=null;nativeUrlToken=0;
            } else {
                nativeUrlSession.urlPlayback(nativeUrlToken,url==null?0:url.positionMs,url==null?0:url.durationMs,
                    url!=null && url.playing?url.speed:0,url!=null && url.phase==LegacyUrlPlayer.Phase.READY,url==null || url.buffering);
            }
        }
        PlaybackInfo info=url==null?new PlaybackInfo(0,0,false,urlSpeed,false,mediaPending):
            new PlaybackInfo(url.positionMs,url.durationMs,url.playing,url.speed,false,url.buffering);
        networkState=new NetworkState(mediaPending?urlOwner:null,mediaPending,info);
        systemMedia.update(mediaState(),controlState(),url!=null || running && !stopping && session!=null,
            !urlRequested && session!=null && session.mirroring(),session!=null && session.audioActive(),url);
        if(promoted && !notificationBody().equals(notificationText))
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification());
        if(observer!=null)observer.changed(new State(this));
    }
    private void releaseWakeLock() {
        if(!destroyed && downloads!=null && downloads.active())return;
        if(wakeLock!=null) {if(wakeLock.isHeld())wakeLock.release();wakeLock=null;}
    }
    @android.annotation.SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        if(wakeLock==null) {
            PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
            wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"buzincast:legacy-receiver");wakeLock.setReferenceCounted(false);
        }
        if(!wakeLock.isHeld())wakeLock.acquire();
    }
    private String notificationBody() {
        if(pinCode!=null)return getString(R.string.notification_pin_text,pinCode);
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
        return builder.setSmallIcon(R.mipmap.buzin_launcher).setContentTitle(getString(pinCode==null?R.string.notification_title:R.string.notification_pin_title))
            .setContentText(notificationText).setContentIntent(content).setOngoing(true).build();
    }
    private void promote() {
        if(promoted)return;Notification notification=notification();
        if(Build.VERSION.SDK_INT>=29)Api29.foreground(this,notification);else startForeground(NOTIFICATION,notification);
        promoted=true;
    }
    private void demote() {if(promoted) {stopForeground(true);promoted=false;}}
    @Override public void onDestroy() {
        destroyed=true;++outputEpoch;client=null;observer=null;target=null;closeWatcher();
        pinCode=null;
        approval.close();
        if(capture!=null)capture.close();
        if(downloads!=null)downloads.close();
        if(systemMedia!=null)systemMedia.close();
        fcastEpoch++;
        dlnaEpoch++;networkState=EMPTY_NETWORK;
        if(fcast!=null) {FCastReceiver previous=fcast;fcast=null;protocolWorker.execute(previous::close);}
        if(dlna!=null) {DlnaReceiver previous=dlna;dlna=null;protocolWorker.execute(previous::close);}
        protocolWorker.shutdown();
        subtitleWorker.shutdownNow();
        main.removeCallbacks(subtitlePickerTimeout);
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
