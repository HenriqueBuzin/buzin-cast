package io.buzincast.legacy;

import android.app.Activity;
import io.buzincast.fireos3probe.LegacyDacp;
import io.buzincast.fireos3probe.LegacyMedia;
import io.buzincast.fireos3probe.LegacyUrlPlayer;
import android.graphics.Bitmap;
import android.widget.ImageView;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.InputType;
import android.text.style.RelativeSizeSpan;
import android.text.style.SuperscriptSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.Locale;
import io.buzincast.media.DisplayFit;
import static io.buzincast.legacy.LegacyUi.*;

/** Native TV frontend for the same API17 receiver exercised by the separate probe APK. */
public final class LegacyActivity extends Activity {
    private LegacyDacp.State controlState=LegacyDacp.inactive();
    private LinearLayout senderControls;
    private LegacyUrlPlayer.State urlState;
    private LinearLayout urlControls;
    private TextView urlTime;
    private TextView urlCaption;
    private long subtitleRequest;
    private static final int SUBTITLE_FILE=6509;
    private Button urlToggle,urlRewind,urlForward,urlSpeed;
    private Button captureToggle;
    private TextView captureStatus;
    private LinearLayout captureLibrary;
    private String captureFilesKey;
    private LegacyMirrorCapture.State captureSnapshot;
    private Button urlDownload,downloadCancel;
    private TextView downloadStatus;
    private LinearLayout downloadLibrary;
    private String downloadFilesKey;
    private LegacyMedia.State mediaState=LegacyMedia.inactive();
    private LinearLayout mediaPanel,connectionSteps;
    private TextView trackTitle,trackArtist,trackAlbum,trackTime;
    private ImageView trackArtwork;
    private View emptyArtwork;
    private Bitmap shownArtwork;
    private final Runnable mediaTick=()-> {
        if(this.foreground && this.service!=null) {mediaState=this.service.mediaState();refreshMediaUi();}
    };
    private LegacyPreferences prefs;
    private FrameLayout root,pane;
    private LinearLayout shell,sidebar;
    private TextureView texture;
    private LegacyReceiverService service;
    private LegacyReceiverService.State receiverState;
    private TextureTarget textureTarget;
    private final Object serviceOwner=new Object();
    private boolean bound,videoDismissed;
    private TextView receiverStatus,receiverLabel,diagnosticView;
    private Button receiverToggle;
    private boolean toggleFilled;
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName component,IBinder binder) {
            service=((LegacyReceiverService.LocalBinder)binder).service();startIfNeeded();
        }
        @Override public void onServiceDisconnected(ComponentName component) {
            service=null;running=busy=video=false;urlState=null;controlState=LegacyDacp.inactive();mediaState=LegacyMedia.inactive();shell.setVisibility(View.VISIBLE);refreshReceiverUi();refreshUrlUi();updateAwake();
        }
    };
    private LegacyCalibrationView calibration;
    private final ArrayDeque<String> events=new ArrayDeque<>();
    private final ArrayDeque<Modal> modals=new ArrayDeque<>();
    private Modal approvalModal;
    private long approvalId;
    private Modal pinModal;
    private String displayedPin;
    private Locale displayLocale;
    private String destination="overview", shownName, lastFocus="nav-overview";
    private boolean foreground,desired,running,busy,video,networkWaiting,audioSuppressed;
    private int videoWidth,videoHeight;
    private String error;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); applyEmulatorLocale();
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        displayLocale=getResources().getConfiguration().locale;
        prefs=new LegacyPreferences(this); shownName=prefs.name();
        desired=state==null?prefs.autoStart():state.getBoolean("desired");
        subtitleRequest=state==null?0:state.getLong("subtitle_request");
        root=new FrameLayout(this);root.setBackgroundColor(BG);
        texture=new TextureView(this);root.addView(texture,new FrameLayout.LayoutParams(-1,-1));
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface,int width,int height) { textureTarget=new TextureTarget(surface);startIfNeeded();fitVideo(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface,int width,int height) { fitVideo(); }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if(service!=null)service.showSurface(serviceOwner,null);
                if(textureTarget!=null && textureTarget.texture==surface) {textureTarget.destroyView();textureTarget=null;}
                else surface.release();
                return false;
            }
        });
        setContentView(root);buildShell();
        bound=bindService(new Intent(this,LegacyReceiverService.class),connection,Context.BIND_AUTO_CREATE);
        if(state!=null && state.containsKey("calibration_margin"))calibrate(state.getInt("calibration_margin"));
    }
    @Override protected void onResume() {
        super.onResume();foreground=true;LegacyReceiverService.dispatch(this,LegacyReceiverService.OPEN);startIfNeeded();
    }
    @Override protected void onStop() {
        foreground=false;if(approvalModal!=null)approvalModal.dismiss();if(pinModal!=null)pinModal.dismiss();root.removeCallbacks(mediaTick);if(service!=null)service.detach(serviceOwner);video=false;updateAwake();super.onStop();
    }
    @Override protected void onDestroy() {
        if(isFinishing() && service!=null)service.endSubtitlePicker();
        if(service!=null)service.detach(serviceOwner);
        if(bound) {unbindService(connection);bound=false;}
        service=null;super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("desired",desired);
        state.putLong("subtitle_request",subtitleRequest);
        if(calibration!=null)state.putInt("calibration_margin",calibration.margin());
        super.onSaveInstanceState(state);
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if(!config.locale.equals(displayLocale)) {
            while(!modals.isEmpty())modals.peekLast().dismiss();
            finishCalibration(false);displayLocale=config.locale;
        }
        buildShell();fitVideo();refreshUrlUi();
    }
    // This frontend targets pre-API21 Fire Sticks and their hardware Back key.
    // AndroidX's current activity stack cannot be linked on API17.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        if(calibration!=null) {finishCalibration(false);return;}
        if(!modals.isEmpty()) {modals.peekLast().dismiss();return;}
        if(urlState!=null && service!=null) {service.stopUrl();return;}
        if (video) { videoDismissed=true;video=false;shell.setVisibility(View.VISIBLE);updateAwake();return; }
        if (!destination.equals("overview")) { destination="overview";render();return; }
        super.onBackPressed();
    }
    private void senderCommand(LegacyDacp.Command command) {
        if(service!=null && (controlState.ready || urlState!=null))service.control(command,()-> {
            if(foreground && !isFinishing())Toast.makeText(this,R.string.error_label,Toast.LENGTH_SHORT).show();
        });
    }
    @Override public boolean onKeyDown(int key,KeyEvent event) {
        if(urlState!=null && key==KeyEvent.KEYCODE_MEDIA_STOP) {if(event.getRepeatCount()==0 && service!=null)service.stopUrl();return true;}
        if((controlState.ready || urlState!=null) && LegacyMediaKeys.handles(key)) {
            LegacyDacp.Command command=LegacyMediaKeys.command(key,true,event.getRepeatCount(),senderPlaying());
            if(command!=null)senderCommand(command);return true;
        }
        return super.onKeyDown(key,event);
    }
    @Override public boolean onKeyUp(int key,KeyEvent event) {
        if(urlState!=null && key==KeyEvent.KEYCODE_MEDIA_STOP)return true;
        if((controlState.ready || urlState!=null) && LegacyMediaKeys.handles(key)) {
            LegacyDacp.Command command=LegacyMediaKeys.command(key,false,event.getRepeatCount(),senderPlaying());
            if(command!=null)senderCommand(command);
            return true;
        }
        return super.onKeyUp(key,event);
    }
    private void startIfNeeded() {
        if(!foreground || service==null)return;
        service.attach(serviceOwner,state-> {
            receiverState=state;desired=state.desired;running=state.running;busy=state.busy;shownName=state.name;
            networkWaiting=state.networkWaiting;audioSuppressed=state.audioSuppressed;
            controlState=state.controls;
            mediaState=state.media;
            urlState=state.url;
            error=state.failure==null?null:getString(state.urlError?R.string.error_label:R.string.error_starting_server);
            events.clear();events.addAll(state.events);
            if(!state.video)videoDismissed=false;
            video=state.video && !videoDismissed;videoWidth=state.width;videoHeight=state.height;
            shell.setVisibility(video?View.GONE:View.VISIBLE);refreshReceiverUi();refreshUrlUi();refreshCaptureUi();refreshDownloadUi();fitVideo();updateAwake();
            refreshApproval(state.approval);
            if(state.approval==null || state.pin==null)refreshPin(state.pin);
        });
        if(textureTarget!=null)service.showSurface(serviceOwner,textureTarget);
        service.endSubtitlePicker();
    }
    private void toggleReceiver() {
        if(busy && !desired)return;
        desired=!desired;busy=true;refreshReceiverUi();
        LegacyReceiverService.dispatch(this,desired?LegacyReceiverService.START:LegacyReceiverService.STOP);
    }
    private void refreshApproval(io.github.jqssun.airplay.media.ConnectionApproval.Request request) {
        if(request!=null && approvalModal!=null && approvalId==request.getId())return;
        if(approvalModal!=null) {
            Modal previous=approvalModal;approvalModal=null;previous.onDismiss=null;previous.dismiss();
        }
        if(request==null || !foreground)return;
        approvalId=request.getId();Modal modal=dialog();approvalModal=modal;
        LinearLayout body=dialogBody(modal,-2);
        addSpaced(body,text(this,getString(R.string.connection_approval_title),22,WHITE,true),-2);
        addSpaced(body,text(this,request.getProtocol()+" · "+request.getPeer(),18,WHITE,false),-2);
        addSpaced(body,text(this,getString(R.string.connection_approval_help),14,MUTED,false),-2);
        Button allow=button(this,R.string.btn_allow,false,()->answerApproval(modal,request.getId(),true));allow.setTag("approval-allow");addSpaced(body,allow,-2);
        Button decline=button(this,R.string.connection_decline,false,()->answerApproval(modal,request.getId(),false));decline.setTag("approval-decline");addSpaced(body,decline,-2);
        modal.onDismiss=()-> {
            if(approvalModal!=modal)return;approvalModal=null;
            if(service!=null)service.answerApproval(request.getId(),false);
        };
        show(modal);decline.requestFocus();
    }
    private void refreshPin(String code) {
        if(code!=null && pinModal!=null && code.equals(displayedPin))return;
        if(pinModal!=null) {Modal previous=pinModal;pinModal=null;previous.onDismiss=null;previous.dismiss();}
        if(code==null || !foreground)return;
        displayedPin=code;Modal modal=dialog();pinModal=modal;LinearLayout body=dialogBody(modal,-2);
        addSpaced(body,text(this,getString(R.string.dialog_pin_title),22,WHITE,true),-2);
        TextView digits=text(this,code,48,WHITE,true);digits.setTag("pin-code");digits.setGravity(Gravity.CENTER);addSpaced(body,digits,-2);
        Button close=button(this,R.string.btn_ok,true,modal::dismiss);close.setTag("pin-close");addSpaced(body,close,-2);
        modal.onDismiss=()-> {if(pinModal==modal) {pinModal=null;if(service!=null)service.dismissPin();}};
        show(modal);close.requestFocus();
    }
    private void answerApproval(Modal modal,long id,boolean allow) {
        if(approvalModal!=modal)return;
        approvalModal=null;modal.onDismiss=null;modal.dismiss();
        if(service!=null)service.answerApproval(id,allow);
    }
    private void restart() {
        shownName=prefs.name();render();LegacyReceiverService.dispatch(this,LegacyReceiverService.RESTART);
    }
    private String statusLabel() {
        if(networkWaiting)return getString(R.string.network_waiting);
        return error!=null?error:getString(busy?R.string.waiting_for_connection:running?R.string.buzin_ready:R.string.buzin_stopped);
    }
    private boolean senderPlaying() {return (urlState==null?controlState.playing:urlState.playing) && !audioSuppressed;}
    private void refreshUrlUi() {
        if(urlControls==null) {
            urlControls=column(this);urlControls.setTag("url-controls");
            urlControls.setPadding(dp(this,16),dp(this,8),dp(this,16),dp(this,8));urlControls.setBackgroundColor(0xee111e2a);
            urlTime=text(this,"",13,WHITE,false);urlTime.setTag("url-time");urlControls.addView(urlTime);
            LinearLayout buttons=new LinearLayout(this);urlControls.addView(buttons);
            urlRewind=button(this,R.string.cd_rewind,false,()->senderCommand(LegacyDacp.Command.REWIND));urlRewind.setTag("url-rewind");
            urlToggle=button(this,R.string.cd_play_pause,false,()->senderCommand(senderPlaying()?LegacyDacp.Command.PAUSE:LegacyDacp.Command.PLAY));urlToggle.setTag("url-toggle");
            urlForward=button(this,R.string.cd_fast_forward,false,()->senderCommand(LegacyDacp.Command.FAST_FORWARD));urlForward.setTag("url-forward");
            urlSpeed=button(this,R.string.select_playback_speed,false,this::urlSpeedDialog);urlSpeed.setTag("url-speed");
            Button subtitles=button(this,R.string.subtitle_external,false,this::subtitleDialog);subtitles.setText("CC");subtitles.setTag("url-subtitles");subtitles.setContentDescription(getString(R.string.subtitle_external));
            Button stop=button(this,R.string.btn_stop,false,()-> {if(service!=null)service.stopUrl();});stop.setTag("url-stop");
            for(Button button:new Button[]{urlRewind,urlToggle,urlForward,urlSpeed,subtitles,stop})buttons.addView(button,new LinearLayout.LayoutParams(0,dp(this,48),1));
            if(BuildConfig.VIDEO_DOWNLOAD_ENABLED) {
                urlDownload=button(this,R.string.cd_download,false,()-> {
                    if(service==null)return;
                    if(service.downloads().busy())service.downloads().cancel();else service.download();refreshUrlUi();
                });urlDownload.setTag("url-download");buttons.addView(urlDownload,new LinearLayout.LayoutParams(0,dp(this,48),1));
            }
            FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);params.setMargins(dp(this,24),0,dp(this,24),dp(this,16));root.addView(urlControls,params);
            urlControls.setVisibility(View.GONE);
            urlCaption=text(this,"",22,WHITE,true);urlCaption.setTag("url-caption");urlCaption.setGravity(Gravity.CENTER);
            urlCaption.setPadding(dp(this,12),dp(this,8),dp(this,12),dp(this,8));urlCaption.setBackground(shape(this,0xbf000000,0,0,8));
            FrameLayout.LayoutParams captionParams=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);captionParams.setMargins(dp(this,48),0,dp(this,48),dp(this,124));root.addView(urlCaption,captionParams);
            urlCaption.setVisibility(View.GONE);
        }
        boolean wasVisible=urlControls.getVisibility()==View.VISIBLE;
        if(urlState==null) {
            urlCaption.setText("");urlCaption.setVisibility(View.GONE);
            if(urlControls.hasFocus()) {View nav=shell.findViewWithTag("nav-overview");if(nav!=null)nav.requestFocus();}
            urlControls.setVisibility(View.GONE);return;
        }
        urlControls.setVisibility(View.VISIBLE);
        urlCaption.setText(urlState.caption);urlCaption.setVisibility(urlState.caption.isEmpty()?View.GONE:View.VISIBLE);
        urlControls.findViewWithTag("url-subtitles").setSelected(urlState.externalSubtitle);
        urlRewind.setText(R.string.cd_rewind);urlForward.setText(R.string.cd_fast_forward);
        ((Button)urlControls.findViewWithTag("url-stop")).setText(R.string.btn_stop);
        urlTime.setText("\u202a"+clock(urlState.positionMs)+" / "+clock(urlState.durationMs)+"\u202c");
        urlToggle.setText(senderPlaying()?"Ⅱ":"▶");urlToggle.setContentDescription(getString(R.string.cd_play_pause));
        urlSpeed.setText(String.format(Locale.getDefault(),"%.2f×",urlState.speed));urlSpeed.setContentDescription(getString(R.string.select_playback_speed));
        boolean ready=urlState.phase==LegacyUrlPlayer.Phase.READY;
        if(urlDownload!=null && service!=null) {
            boolean downloading=service.downloads().busy();io.buzincast.fireos3probe.LegacyUrlDownload.State progress=service.downloads().state();
            urlDownload.setEnabled(downloading || ready && service.canDownload());
            urlDownload.setText(downloading?progress!=null && progress.progress>=0?progress.progress+"%":"■":"↓");
            urlDownload.setContentDescription(getString(downloading?R.string.cd_cancel_download:R.string.cd_download));
        }
        urlToggle.setEnabled(ready);urlRewind.setEnabled(ready && urlState.durationMs>0);urlForward.setEnabled(ready && urlState.durationMs>0);
        if(!wasVisible) {
            if(ready)urlToggle.requestFocus();else urlControls.findViewWithTag("url-stop").requestFocus();
        }
    }
    private void subtitleDialog() {
        if(service==null || urlState==null)return;
        Modal modal=dialog();LinearLayout outer=dialogBody(modal,Math.min(dp(this,400),Math.round(getResources().getDisplayMetrics().heightPixels*.85f)));
        addSpaced(outer,text(this,getString(R.string.subtitle_external),20,WHITE,true),-2);
        addSpaced(outer,text(this,getString(R.string.subtitle_picker_help),14,MUTED,false),-2);
        Button choose=button(this,R.string.subtitle_choose_file,false,()-> {
            if(service==null)return;subtitleRequest=service.subtitleSession();
            service.beginSubtitlePicker(subtitleRequest);
            // ACTION_OPEN_DOCUMENT starts at API19. GET_CONTENT keeps the same
            // local-provider workflow available on the original API17 Stick.
            try {startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),SUBTITLE_FILE);modal.dismiss();}
            catch(ActivityNotFoundException unavailable) {subtitleRequest=0;service.endSubtitlePicker();Toast.makeText(this,R.string.subtitle_picker_unavailable,Toast.LENGTH_LONG).show();}
        });choose.setTag("subtitle-choose");addSpaced(outer,choose,48);
        Button disable=button(this,R.string.subtitle_disable,false,()-> {if(service!=null)service.subtitle(null,service.subtitleSession());modal.dismiss();});disable.setTag("subtitle-disable");addSpaced(outer,disable,48);
        outer.addView(button(this,R.string.buzin_close,true,modal::dismiss));show(modal);choose.requestFocus();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==SUBTITLE_FILE && result==RESULT_OK && data!=null && data.getData()!=null && service!=null)service.subtitle(data.getData(),subtitleRequest);
        if(request==SUBTITLE_FILE)subtitleRequest=0;
    }
    private void urlSpeedDialog() {
        if(service==null || urlState==null)return;
        Modal dialog=dialog();LinearLayout outer=dialogBody(dialog,Math.min(dp(this,520),Math.round(getResources().getDisplayMetrics().heightPixels*.9f)));
        addSpaced(outer,text(this,getString(R.string.select_playback_speed),20,WHITE,true),-2);
        ScrollView scroll=new ScrollView(this);LinearLayout choices=column(this);scroll.addView(choices);outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Button selected=null;
        for(float rate:new float[]{.25f,.5f,.75f,1,1.25f,1.5f,2,3,4}) {
            Button choice=button(this,R.string.select_playback_speed,false,()-> {if(service!=null)service.urlSpeed(rate);dialog.dismiss();});
            choice.setText(String.format(Locale.getDefault(),"%.2f×",rate));choice.setTag("url-speed-"+rate);choice.setSelected(rate==urlState.speed);
            addSpaced(choices,choice,48);if(rate==urlState.speed)selected=choice;
        }
        Button close=button(this,R.string.buzin_close,true,dialog::dismiss);outer.addView(close);show(dialog);
        if(selected!=null)selected.requestFocus();else close.requestFocus();
    }
    // Runtime callbacks must not replace controls between ACTION_DOWN and UP.
    private void refreshReceiverUi() {
        refreshMediaUi();
        if(senderControls!=null) {
            if(!controlState.ready && senderControls.hasFocus()) {View nav=shell.findViewWithTag("nav-overview");if(nav!=null)nav.requestFocus();}
            senderControls.setVisibility(controlState.ready?View.VISIBLE:View.GONE);
            Button toggle=senderControls.findViewWithTag("sender-1");if(toggle!=null)toggle.setText(senderPlaying()?"Ⅱ":"▶");
        }
        if(receiverStatus!=null) {receiverStatus.setText(statusLabel());receiverStatus.setTextColor(error!=null?0xffffab91:MINT);}
        if(receiverLabel!=null)receiverLabel.setText(shownName);
        if(receiverToggle!=null) {
            receiverToggle.setText(desired?R.string.btn_stop:R.string.btn_start);receiverToggle.setEnabled(!busy || desired);
            if(toggleFilled!=!desired) {toggleFilled=!desired;focus(receiverToggle,toggleFilled);receiverToggle.setTextColor(toggleFilled?0xff082b27:WHITE);}
        }
        if(diagnosticView!=null)diagnosticView.setText(diagnosticText(true));
    }
    private static String clock(long millis) {long seconds=Math.max(0,millis)/1000;return String.format(Locale.US,"%d:%02d",seconds/60,seconds%60);}
    private void refreshMediaUi() {
        if(root!=null)root.removeCallbacks(mediaTick);
        if(mediaPanel==null)return;
        mediaPanel.setVisibility(mediaState.active?View.VISIBLE:View.GONE);
        connectionSteps.setVisibility(mediaState.active?View.GONE:View.VISIBLE);
        trackTitle.setText(mediaState.track.title.isEmpty()?getString(R.string.waiting_for_playback):mediaState.track.title);
        trackArtist.setText(mediaState.track.artist);trackAlbum.setText(mediaState.track.album);
        trackTime.setText("\u202a"+clock(mediaState.positionMs)+" / "+clock(mediaState.durationMs)+"\u202c");
        emptyArtwork.setVisibility(mediaState.artwork==null?View.VISIBLE:View.GONE);
        if(shownArtwork!=mediaState.artwork) {
            shownArtwork=mediaState.artwork;
            if(shownArtwork==null)trackArtwork.setImageDrawable(null);else trackArtwork.setImageBitmap(shownArtwork);
        }
        if(foreground && mediaState.active && mediaState.playing && mediaState.durationMs>0)root.postDelayed(mediaTick,500);
    }
    private void updateAwake() {
        if (calibration!=null || (video && prefs.keepScreenOn())) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void fitVideo() {
        int w=texture.getWidth(),h=texture.getHeight();if(w==0||h==0||videoWidth==0||videoHeight==0)return;
        DisplayFit.Viewport viewport=DisplayFit.INSTANCE.viewport(w,h,videoWidth,videoHeight,prefs.margin());
        Matrix fit=new Matrix();fit.setRectToRect(new RectF(0,0,w,h),
                new RectF(viewport.getX(),viewport.getY(),viewport.getX()+viewport.getWidth(),viewport.getY()+viewport.getHeight()),Matrix.ScaleToFit.FILL);
        texture.setTransform(fit);
    }
    private void record(String value) { if(events.size()==100)events.removeFirst();events.addLast(value); }

    private void buildShell() {
        if(shell!=null)root.removeView(shell);
        shell=new LinearLayout(this);shell.setOrientation(LinearLayout.HORIZONTAL);
        sidebar=column(this);sidebar.setPadding(dp(this,12),dp(this,20),dp(this,12),dp(this,12));sidebar.setBackgroundColor(0xff080f18);
        shell.addView(sidebar,new LinearLayout.LayoutParams(dp(this,172),-1));
        pane=new FrameLayout(this);pane.setBackground(new GradientDrawableCompat());shell.addView(pane,new LinearLayout.LayoutParams(0,-1,1));
        root.addView(shell,1,new FrameLayout.LayoutParams(-1,-1));
        if(!modals.isEmpty() || calibration!=null)shell.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        render();shell.setVisibility(video?View.GONE:View.VISIBLE);
    }
    private void render() {
        if(pane==null)return;
        captureToggle=null;captureStatus=null;captureLibrary=null;captureFilesKey=null;captureSnapshot=null;
        downloadStatus=null;downloadLibrary=null;downloadCancel=null;downloadFilesKey=null;
        receiverStatus=receiverLabel=diagnosticView=null;receiverToggle=null;
        senderControls=null;
        root.removeCallbacks(mediaTick);mediaPanel=connectionSteps=null;shownArtwork=null;
        View previous=getCurrentFocus();if(previous!=null && previous.getTag() instanceof String)lastFocus=(String)previous.getTag();
        sidebar.removeAllViews();
        nav("overview",R.string.tab_overview);nav("tools",R.string.tab_tools);nav("settings",R.string.tab_settings);
        if(prefs.advanced())nav("logs",R.string.tab_logs);
        else if(destination.equals("logs"))destination="settings";
        pane.removeAllViews();
        if(destination.equals("overview"))overview();
        else if(destination.equals("settings"))settings();
        else if(destination.equals("tools"))tools();
        else diagnostics(false);
        View focused=shell.findViewWithTag(lastFocus);if(focused!=null && focused.isEnabled())focused.requestFocus();
    }
    private void nav(String key,int label) {
        Button button=button(this,label,false,()-> {destination=key;render();});button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        button.setTag("nav-"+key);
        button.setSelected(destination.equals(key));addSpaced(sidebar,button,54);
    }
    private LinearLayout page(int title) {
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout body=column(this);body.setPadding(dp(this,28),dp(this,24),dp(this,28),dp(this,20));scroll.addView(body);
        pane.addView(scroll,new FrameLayout.LayoutParams(-1,-1));if(title!=0)addSpaced(body,text(this,getString(title),22,WHITE,true),-2);return body;
    }
    private void overview() {
        pane.addView(new SignalBackground(this),new FrameLayout.LayoutParams(-1,-1));
        LinearLayout page=page(0);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(new BrandMark(this),new LinearLayout.LayoutParams(dp(this,36),dp(this,36)));
        TextView appName=text(this,getString(R.string.app_name),18,WHITE,true);LinearLayout.LayoutParams appSize=new LinearLayout.LayoutParams(-2,-2);appSize.setMarginStart(dp(this,10));header.addView(appName,appSize);
        TextView status=text(this,statusLabel(),12,error!=null?0xffffab91:MINT,false);receiverStatus=status;status.setTag("receiver-status");
        status.setGravity(Gravity.END);
        LinearLayout.LayoutParams statusSize=new LinearLayout.LayoutParams(0,-2,1);statusSize.leftMargin=dp(this,14);header.addView(status,statusSize);addSpaced(page,header,-2);
        LinearLayout columns=new LinearLayout(this);columns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout identity=column(this),steps=column(this);identity.setPadding(0,dp(this,24),dp(this,28),0);steps.setPadding(dp(this,22),dp(this,22),dp(this,22),dp(this,22));
        FrameLayout right=new FrameLayout(this);
        columns.addView(identity,new LinearLayout.LayoutParams(0,-2,1.1f));columns.addView(right,new LinearLayout.LayoutParams(0,-2,1));
        right.addView(steps,new FrameLayout.LayoutParams(-1,-2));connectionSteps=steps;
        addSpaced(identity,text(this,getString(R.string.buzin_hero),32,WHITE,true),-2);
        addSpaced(identity,text(this,getString(R.string.buzin_hero_detail),13,MUTED,false),-2);
        LinearLayout name=column(this);name.setBackground(shape(this,0xff14282d,0xff28554e,1,18));name.setPadding(dp(this,16),dp(this,16),dp(this,16),dp(this,16));
        name.addView(text(this,getString(R.string.buzin_receiver_label),10,MINT,true));receiverLabel=text(this,shownName,23,WHITE,true);name.addView(receiverLabel);name.addView(text(this,getString(R.string.buzin_rename_hint),11,MUTED,false));addSpaced(identity,name,-2);
        LinearLayout actions=new LinearLayout(this);
        Button toggle=button(this,desired?R.string.btn_stop:R.string.btn_start,!desired,this::toggleReceiver);toggle.setEnabled(!busy || desired);
        receiverToggle=toggle;toggleFilled=!desired;
        toggle.setTag("receiver-toggle");
        actions.addView(toggle,new LinearLayout.LayoutParams(-2,dp(this,46)));
        Button about=button(this,R.string.buzin_about,false,this::about);about.setTag("about");LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(-2,dp(this,46));a.leftMargin=dp(this,10);actions.addView(about,a);identity.addView(actions);
        steps.setBackground(shape(this,PANEL,0xff25333e,1,22));addSpaced(steps,text(this,getString(R.string.buzin_connect_heading),18,WHITE,true),-2);
        int[] lines={R.string.buzin_step_wifi,R.string.buzin_step_mirror,R.string.buzin_step_choose};
        for(int i=0;i<lines.length;i++)addSpaced(steps,text(this,String.format(Locale.getDefault(),"%02d  ",i+1)+getString(lines[i]),13,MUTED,false),-2);
        mediaPanel=column(this);mediaPanel.setTag("now-playing");mediaPanel.setPadding(dp(this,20),dp(this,18),dp(this,20),dp(this,18));
        mediaPanel.setBackground(shape(this,PANEL,0xff25333e,1,22));
        FrameLayout cover=new FrameLayout(this);trackArtwork=new ImageView(this);trackArtwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
        trackArtwork.setTag("track-artwork");cover.addView(trackArtwork,new FrameLayout.LayoutParams(-1,-1));
        emptyArtwork=new BrandMark(this);cover.addView(emptyArtwork,new FrameLayout.LayoutParams(-1,-1));
        mediaPanel.addView(cover,new LinearLayout.LayoutParams(dp(this,112),dp(this,112)));
        trackTitle=text(this,"",20,WHITE,true);trackTitle.setMaxLines(2);trackTitle.setTag("track-title");addSpaced(mediaPanel,trackTitle,-2);
        trackArtist=text(this,"",14,MINT,false);trackArtist.setMaxLines(1);trackArtist.setTag("track-artist");mediaPanel.addView(trackArtist);
        trackAlbum=text(this,"",13,MUTED,false);trackAlbum.setMaxLines(1);trackAlbum.setTag("track-album");mediaPanel.addView(trackAlbum);
        trackTime=text(this,"",12,MUTED,false);trackTime.setTag("track-time");mediaPanel.addView(trackTime);
        right.addView(mediaPanel,new FrameLayout.LayoutParams(-1,-2));refreshMediaUi();
        page.addView(columns);
        senderControls=column(this);senderControls.setVisibility(controlState.ready?View.VISIBLE:View.GONE);
        LinearLayout transport=new LinearLayout(this);
        int[] labels={R.string.media_previous,R.string.cd_play_pause,R.string.media_next,R.string.volume_client,R.string.volume_client};
        LegacyDacp.Command[] commands={LegacyDacp.Command.PREVIOUS,null,
            LegacyDacp.Command.NEXT,LegacyDacp.Command.VOLUME_DOWN,LegacyDacp.Command.VOLUME_UP};
        for(int i=0;i<labels.length;i++) {
            final int index=i;Button control=button(this,labels[i],false,()->senderCommand(commands[index]==null?
                (senderPlaying()?LegacyDacp.Command.PAUSE:LegacyDacp.Command.PLAY):commands[index]));
            control.setTag("sender-"+i);
            if(i==1) {control.setText(senderPlaying()?"Ⅱ":"▶");control.setContentDescription(getString(R.string.cd_play_pause));}
            if(i>=3) {String label=getString(R.string.volume_client)+(i==3?" \u2212":" +");control.setText(label);control.setContentDescription(label);}
            LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(0,dp(this,54),1);size.setMarginEnd(dp(this,8));transport.addView(control,size);
        }
        senderControls.addView(transport);page.addView(senderControls);
    }
    private void settings() {
        LinearLayout body=page(R.string.tab_settings);
        addSpaced(body,text(this,getString(R.string.setting_streaming_profile),18,WHITE,true),-2);
        addSpaced(body,text(this,getString(R.string.setting_streaming_profile_desc),12,MUTED,false),-2);
        LinearLayout profiles=new LinearLayout(this);
        int[] labels={R.string.setting_profile_default,R.string.setting_profile_cinema,R.string.setting_profile_presentation,R.string.setting_profile_games};
        for(int i=0;i<labels.length;i++) {
            final String key=LegacyProfiles.KEYS[i];
            Button choice=button(this,labels[i],false,()-> {
                try {prefs.profile(key);restart();}catch(IllegalArgumentException | IllegalStateException unavailable) {
                    Toast.makeText(this,R.string.setting_profile_unavailable,Toast.LENGTH_LONG).show();
                }
            });choice.setTag("profile-"+i);choice.setSelected(prefs.profile().equals(key));
            LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(0,dp(this,50),1);size.setMargins(0,0,dp(this,8),0);profiles.addView(choice,size);
        }
        addSpaced(body,profiles,-2);
        LinearLayout name=row(this,getString(R.string.setting_server_name),prefs.name(),()->edit(false));name.setTag("server-name");addSpaced(body,name,-2);
        LinearLayout port=row(this,getString(R.string.setting_server_port),String.valueOf(prefs.port()),()->edit(true));port.setTag("server-port");addSpaced(body,port,-2);
        toggle(body,R.string.setting_auto_start,R.string.setting_auto_start_desc,"auto_start",prefs.autoStart());
        toggle(body,R.string.setting_run_in_background,R.string.setting_run_in_background_desc,"run_in_background",prefs.background());
        toggle(body,R.string.setting_boot_auto_start,R.string.setting_boot_auto_start_desc,"boot_auto_start",prefs.bootAutoStart());
        toggle(body,R.string.setting_network_recovery,R.string.setting_network_recovery_desc,"network_recovery",prefs.networkRecovery());
        toggle(body,R.string.setting_keep_screen_on,R.string.setting_keep_screen_on_desc,"keep_screen_on",prefs.keepScreenOn());
        toggle(body,R.string.setting_fcast,R.string.setting_fcast_desc,"fcast_enabled",prefs.fcast());
        toggle(body,R.string.setting_dlna,R.string.setting_dlna_desc,"dlna_enabled",prefs.dlna());
        toggle(body,R.string.setting_castv2,R.string.setting_castv2_desc,"castv2_enabled",prefs.castV2());
        toggle(body,R.string.setting_approve_connections,R.string.setting_approve_connections_desc,"approve_connections",prefs.approveConnections());
        toggle(body,R.string.setting_require_pin,R.string.setting_require_pin_desc,"require_pin",prefs.requirePin());
        toggle(body,R.string.setting_developer_options,R.string.setting_developer_options_desc,"developer_options",prefs.advanced());
    }
    private void toggle(LinearLayout body,int title,int detail,String key,boolean value) {
        LinearLayout row=row(this,getString(title)+" \u00b7 "+getString(value?R.string.chip_on:R.string.chip_off),getString(detail),()-> {
            prefs.bool(key,!value);updateAwake();render();
            LegacyReceiverService.dispatch(this,LegacyReceiverService.CONFIGURE);
        });row.setTag(key);addSpaced(body,row,-2);
    }
    private void tools() {
        LinearLayout body=page(R.string.tab_tools);
        if(BuildConfig.RECORDING_ENABLED) {
            addSpaced(body,text(this,getString(R.string.tools_record_title),20,WHITE,true),-2);
            addSpaced(body,text(this,getString(R.string.tools_record_help),13,MUTED,false),-2);
            captureToggle=button(this,R.string.tools_record_prepare,false,()-> {
                if(service==null)return;
                if(service.capture().active())service.capture().stop();else service.capture().start();
                refreshCaptureUi();
            });captureToggle.setTag("capture-toggle");addSpaced(body,captureToggle,-2);
            captureStatus=text(this,"",13,MUTED,false);captureStatus.setTag("capture-status");addSpaced(body,captureStatus,-2);
            addSpaced(body,text(this,getString(R.string.tools_recordings),18,WHITE,true),-2);
            Button refresh=button(this,R.string.tools_refresh,false,()-> {captureFilesKey=null;refreshCaptureUi();});refresh.setTag("capture-refresh");addSpaced(body,refresh,-2);
            captureLibrary=column(this);addSpaced(body,captureLibrary,-2);refreshCaptureUi();
        }
        if(BuildConfig.VIDEO_DOWNLOAD_ENABLED) {
            addSpaced(body,text(this,getString(R.string.tools_downloads),18,WHITE,true),-2);
            downloadStatus=text(this,"",13,MUTED,false);downloadStatus.setTag("download-status");addSpaced(body,downloadStatus,-2);
            downloadCancel=button(this,R.string.cd_cancel_download,false,()-> {if(service!=null)service.downloads().cancel();});
            downloadCancel.setTag("download-cancel");addSpaced(body,downloadCancel,-2);
            Button refresh=button(this,R.string.tools_refresh,false,()-> {downloadFilesKey=null;refreshDownloadUi();});refresh.setTag("download-refresh");addSpaced(body,refresh,-2);
            downloadLibrary=column(this);addSpaced(body,downloadLibrary,-2);refreshDownloadUi();
        }
        LinearLayout calibrate=row(this,getString(R.string.tools_calibrate),getString(R.string.setting_tv_margin_desc),()->calibrate(prefs.margin()));
        calibrate.setTag("calibration");addSpaced(body,calibrate,-2);
        addSpaced(body,row(this,getString(R.string.tools_connection_diagnostics),null,()->diagnostics(true)),-2);
        addSpaced(body,row(this,getString(R.string.tools_native_open),getString(R.string.tools_native_help),()-> {
            try {startActivity(new Intent().setComponent(new ComponentName("com.amazon.cast.sink","com.amazon.cast.sink.DisplayMirroringSinkActivity")));}
            catch(ActivityNotFoundException unavailable){Toast.makeText(this,R.string.tools_native_unavailable,Toast.LENGTH_LONG).show();}
        }),-2);
    }
    private void refreshCaptureUi() {
        if(captureToggle==null || service==null)return;
        LegacyMirrorCapture capture=service.capture();LegacyMirrorCapture.State state=capture.state();
        if(state==captureSnapshot && captureFilesKey!=null)return;captureSnapshot=state;
        captureToggle.setEnabled(!state.finishing);
        captureToggle.setText(state.armed?R.string.tools_record_stop:R.string.tools_record_prepare);
        captureStatus.setText(state.message);
        java.io.File[] files=capture.files();StringBuilder key=new StringBuilder().append(capture.busy());
        for(java.io.File file:files)key.append('|').append(file.getName()).append(':').append(file.length());
        if(key.toString().equals(captureFilesKey))return;captureFilesKey=key.toString();captureLibrary.removeAllViews();
        if(files.length==0)captureLibrary.addView(text(this,getString(R.string.tools_recordings_empty),13,MUTED,false));
        for(java.io.File file:files) {
            String name=file.getName();captureLibrary.addView(text(this,name+" · "+file.length()/1024+" KB",13,WHITE,false));
            LinearLayout actions=new LinearLayout(this);captureLibrary.addView(actions);
            Button play=button(this,R.string.tools_play,false,()->recordingAction(()->service.playRecording(name)));
            Button share=button(this,R.string.btn_export,false,()->recordingAction(()-> {
                Uri uri=androidx.core.content.FileProvider.getUriForFile(this,getPackageName()+".recordings",service.capture().file(name));
                shareLocal(uri,name,"video/x-matroska");
            }));
            Button delete=button(this,R.string.tools_delete,false,()->deleteRecording(name));
            play.setTag("capture-play:"+name);share.setTag("capture-share:"+name);delete.setTag("capture-delete:"+name);
            for(Button action:new Button[]{play,share,delete}) {action.setEnabled(!capture.busy());actions.addView(action,new LinearLayout.LayoutParams(0,dp(this,48),1));}
        }
    }
    private interface RecordingAction {void run() throws Exception;}
    private void shareLocal(Uri uri,String name,String mime) {
        Intent send=new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(android.content.ClipData.newRawUri(name,uri));startActivity(Intent.createChooser(send,getString(R.string.btn_export)));
    }
    private void refreshDownloadUi() {
        if(downloadStatus==null || service==null)return;
        LegacyDownloads downloads=service.downloads();downloadStatus.setText(downloads.message());
        downloadCancel.setVisibility(downloads.busy()?View.VISIBLE:View.GONE);
        io.buzincast.fireos3probe.LegacyUrlDownload.State state=downloads.state();
        String key=downloads.busy()+"|"+(state==null?"":state.phase)+"|"+(state==null || state.file==null?"":state.file.getName());
        if(key.equals(downloadFilesKey))return;downloadFilesKey=key;downloadLibrary.removeAllViews();
        java.io.File[] files=downloads.files();
        if(files.length==0)downloadLibrary.addView(text(this,getString(R.string.tools_downloads_empty),13,MUTED,false));
        for(java.io.File file:files) {
            String name=file.getName();downloadLibrary.addView(text(this,name+" · "+file.length()/1024+" KB",13,WHITE,false));
            LinearLayout actions=new LinearLayout(this);downloadLibrary.addView(actions);
            Button play=button(this,R.string.tools_play,false,()->recordingAction(()->service.playDownload(name)));
            Button share=button(this,R.string.btn_export,false,()->recordingAction(()->shareLocal(
                androidx.core.content.FileProvider.getUriForFile(this,getPackageName()+".recordings",downloads.file(name)),name,"video/mp4")));
            Button delete=button(this,R.string.tools_delete,false,()->deleteDownload(name));
            play.setTag("download-play:"+name);share.setTag("download-share:"+name);delete.setTag("download-delete:"+name);
            for(Button action:new Button[]{play,share,delete}) {action.setEnabled(!downloads.busy());actions.addView(action,new LinearLayout.LayoutParams(0,dp(this,48),1));}
        }
    }
    private void deleteDownload(String name) {
        Modal modal=dialog();LinearLayout body=dialogBody(modal,dp(this,260));
        addSpaced(body,text(this,getString(R.string.tools_delete_download_confirm),20,WHITE,true),-2);
        addSpaced(body,text(this,name,13,MUTED,false),-2);
        Button confirm=button(this,R.string.tools_delete,false,()-> {
            recordingAction(()->service.downloads().delete(name));modal.dismiss();downloadFilesKey=null;refreshDownloadUi();
        });confirm.setTag("download-delete-confirm");addSpaced(body,confirm,-2);
        Button cancel=button(this,android.R.string.cancel,false,modal::dismiss);addSpaced(body,cancel,-2);show(modal);cancel.requestFocus();
    }
    private void recordingAction(RecordingAction action) {
        try {if(service!=null)action.run();}
        catch(Exception error) {Toast.makeText(this,R.string.error_label,Toast.LENGTH_SHORT).show();}
    }
    private void deleteRecording(String name) {
        Modal modal=dialog();LinearLayout body=dialogBody(modal,dp(this,260));
        addSpaced(body,text(this,getString(R.string.tools_delete_confirm),20,WHITE,true),-2);
        addSpaced(body,text(this,name,13,MUTED,false),-2);
        Button confirm=button(this,R.string.tools_delete,false,()-> {
            recordingAction(()->service.capture().delete(name));modal.dismiss();refreshCaptureUi();
        });confirm.setTag("capture-delete-confirm");addSpaced(body,confirm,-2);
        Button cancel=button(this,android.R.string.cancel,false,modal::dismiss);addSpaced(body,cancel,-2);show(modal);cancel.requestFocus();
    }
    private void calibrate(int margin) {
        if(calibration!=null)return;
        shell.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        calibration=new LegacyCalibrationView(this,margin,this::finishCalibration);
        root.addView(calibration,new FrameLayout.LayoutParams(-1,-1));
        calibration.focusSave();updateAwake();
    }
    private void finishCalibration(boolean save) {
        if(calibration==null)return;
        if(save)prefs.margin(calibration.margin());
        root.removeView(calibration);calibration=null;
        shell.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View previous=shell.findViewWithTag("calibration");if(previous!=null)previous.requestFocus();
        fitVideo();updateAwake();
    }
    private String diagnosticText(boolean includeEvents) {
        StringBuilder value=new StringBuilder(getString(running?R.string.diag_receiver_running:R.string.diag_receiver_stopped));
        if(receiverState!=null && receiverState.address!=null) value.append("\n\n").append(getString(R.string.diag_tv_ip,receiverState.address))
                .append("\n").append(getString(R.string.diag_airplay,shownName,receiverState.port));
        if(includeEvents)for(String event:events)value.append("\n\n").append(event);
        return value.toString();
    }
    private void diagnostics(boolean modal) {
        if(modal)message(R.string.tools_connection_diagnostics,diagnosticText(false));
        else {diagnosticView=text(this,diagnosticText(true),14,MUTED,false);page(R.string.tab_logs).addView(diagnosticView);}
    }
    private void edit(boolean port) {
        Modal dialog=dialog();LinearLayout body=dialogBody(dialog,-2);
        addSpaced(body,text(this,getString(port?R.string.setting_server_port:R.string.setting_server_name),20,WHITE,true),-2);
        EditText input=new EditText(this);input.setSingleLine();input.setTextColor(WHITE);input.setTextSize(18);
        input.setInputType(port?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setSelectAllOnFocus(true);
        input.setText(port?String.valueOf(prefs.port()):prefs.name());input.selectAll();addSpaced(body,input,54);
        addSpaced(body,button(this,R.string.tv_edit_save,true,()-> {
            try { if(port)prefs.port(Integer.parseInt(input.getText().toString()));else prefs.name(input.getText().toString());dialog.dismiss();restart(); }
            catch(IllegalArgumentException invalid){input.setError(getString(R.string.error_label));}
        }),48);
        body.addView(button(this,R.string.tv_edit_cancel,false,dialog::dismiss));show(dialog);input.requestFocus();
        dialog.keyboard=true;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        input.post(()-> {
            if(!dialog.dismissed && input.isShown() && input.hasFocus())
                ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);
        });
    }
    private void about() {
        Modal dialog=dialog();int height=Math.min(dp(this,540),Math.round(getResources().getDisplayMetrics().heightPixels*.92f));
        LinearLayout outer=dialogBody(dialog,height);ScrollView scroll=new ScrollView(this);LinearLayout card=column(this);
        card.setPadding(dp(this,16),dp(this,12),dp(this,16),dp(this,12));card.setBackground(shape(this,0xff202b35,0,0,18));scroll.addView(card);
        outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);heading.setPadding(dp(this,12),0,0,dp(this,12));
        heading.addView(new BrandMark(this),new LinearLayout.LayoutParams(dp(this,44),dp(this,44)));
        LinearLayout names=column(this);names.setPaddingRelative(dp(this,12),0,0,0);names.addView(text(this,getString(R.string.app_name),22,WHITE,true));
        names.addView(text(this,getString(R.string.buzin_about_tagline),12,MUTED,false));names.addView(text(this,getString(R.string.buzin_version,"\u202a"+BuildConfig.VERSION_NAME+"\u202c"),11,MINT,true));heading.addView(names);card.addView(heading);
        divider(card);LinearLayout company=column(this);company.setPadding(dp(this,16),dp(this,12),dp(this,16),dp(this,12));company.addView(text(this,getString(R.string.buzin_maintained_by),11,MUTED,false));
        SpannableString brand=new SpannableString("Buzin Solutions\u2009\u00ae");brand.setSpan(new SuperscriptSpan(),brand.length()-1,brand.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);brand.setSpan(new RelativeSizeSpan(.7f),brand.length()-1,brand.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);company.addView(text(this,brand,14,WHITE,true));card.addView(company);
        aboutLink(card,R.string.buzin_website,"buzin.solutions",()->open("https://buzin.solutions"));
        aboutLink(card,R.string.buzin_source,"GitHub \u00b7 HenriqueBuzin/buzin-cast",()->open("https://github.com/HenriqueBuzin/buzin-cast"));
        aboutLink(card,R.string.buzin_licenses,getString(R.string.buzin_licenses_detail),()->message(R.string.buzin_licenses,getString(R.string.buzin_credits)));
        TextView footer=text(this,"\u00a9 "+Calendar.getInstance().get(Calendar.YEAR)+" Buzin Solutions LTDA",10,MUTED,false);footer.setGravity(Gravity.CENTER);footer.setPadding(0,dp(this,10),0,dp(this,10));outer.addView(footer);
        Button close=button(this,R.string.buzin_close,true,dialog::dismiss);outer.addView(close,new LinearLayout.LayoutParams(-1,dp(this,48)));show(dialog);close.requestFocus();
    }
    private void aboutLink(LinearLayout card,int title,String detail,Runnable click) {divider(card);LinearLayout row=row(this,getString(title),detail,click);focus(row,false,0,0);LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,-2);size.setMargins(dp(this,4),dp(this,5),dp(this,4),dp(this,5));card.addView(row,size);}
    private void divider(LinearLayout body) {View line=new View(this);line.setBackgroundColor(0xff34434e);body.addView(line,new LinearLayout.LayoutParams(-1,dp(this,1)));}
    private Modal dialog() {return new Modal();}
    private LinearLayout dialogBody(Modal dialog,int height) {
        LinearLayout body=column(this);body.setId(R.id.legacy_modal_panel);body.setPadding(dp(this,16),dp(this,16),dp(this,16),dp(this,16));body.setBackground(shape(this,0xff101922,0,0,24));dialog.body=body;dialog.height=height;return body;
    }
    private void show(Modal dialog) {dialog.show();}
    private final class Modal {
        LinearLayout body;FrameLayout layer;View previous;int height;boolean keyboard,dismissed;
        Runnable onDismiss;
        void show() {
            previous=getCurrentFocus();
            if(!modals.isEmpty())modals.peekLast().layer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            shell.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            if(urlControls!=null)urlControls.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            layer=new FrameLayout(LegacyActivity.this);layer.setBackgroundColor(0x66000000);layer.setClickable(true);layer.setOnClickListener(v->dismiss());
            body.setClickable(true);
            int width=Math.min(dp(LegacyActivity.this,620),Math.round(getResources().getDisplayMetrics().widthPixels*.94f));
            FrameLayout.LayoutParams size=new FrameLayout.LayoutParams(width,height,Gravity.CENTER);layer.addView(body,size);
            root.addView(layer,new FrameLayout.LayoutParams(-1,-1));modals.addLast(this);
        }
        void dismiss() {
            if(dismissed)return;dismissed=true;
            if(keyboard) {
                // Hide while the editor still belongs to the window, then drop
                // its focus before detaching it. API17 can otherwise reopen its
                // extract keyboard after a later fullscreen/layout change.
                ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(layer.getWindowToken(),0);
                body.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                body.clearFocus();
                getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
            root.removeView(layer);modals.remove(this);
            if(modals.isEmpty())shell.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            else modals.peekLast().layer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            if(modals.isEmpty() && urlControls!=null)urlControls.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            if(previous!=null && previous.isShown())previous.requestFocus();
            if(onDismiss!=null)onDismiss.run();
        }
    }
    private void message(int title,String value) {
        Modal dialog=dialog();LinearLayout outer=dialogBody(dialog,Math.min(dp(this,500),Math.round(getResources().getDisplayMetrics().heightPixels*.9f)));
        addSpaced(outer,text(this,getString(title),20,WHITE,true),-2);ScrollView scroll=new ScrollView(this);scroll.addView(text(this,value,13,MUTED,false));outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));Button close=button(this,R.string.buzin_close,true,dialog::dismiss);outer.addView(close);show(dialog);close.requestFocus();
    }
    private void open(String url) {try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(ActivityNotFoundException unavailable){Toast.makeText(this,R.string.buzin_no_browser,Toast.LENGTH_SHORT).show();}}
    private void applyEmulatorLocale() {
        if (!BuildConfig.DEBUG || !(Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu")))return;
        String tag=getIntent().getStringExtra("buzin_test_locale");if(tag==null || !tag.matches("[a-z]{2}(-[A-Z]{2})?"))return;
        String[] parts=tag.split("-");Locale locale=new Locale(parts[0],parts.length>1?parts[1]:"");Locale.setDefault(locale);
        Configuration config=new Configuration(getResources().getConfiguration());config.locale=locale;config.setLayoutDirection(locale);getResources().updateConfiguration(config,getResources().getDisplayMetrics());
    }
    private final class GradientDrawableCompat extends android.graphics.drawable.GradientDrawable {
        GradientDrawableCompat(){super(Orientation.TL_BR,new int[]{0xff0d1d26,BG});}
    }
    private static final class SignalBackground extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        SignalBackground(android.content.Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);paint.setColor(0x142dc4be);for(int i=1;i<=3;i++)canvas.drawCircle(getWidth()*.78f,getHeight()*.55f,getWidth()*(.19f+i*.08f),paint);}
    }
}
