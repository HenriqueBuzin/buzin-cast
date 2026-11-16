package io.buzincast.legacy;

import android.app.Activity;
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
            service=null;running=busy=video=false;shell.setVisibility(View.VISIBLE);refreshReceiverUi();updateAwake();
        }
    };
    private LegacyCalibrationView calibration;
    private final ArrayDeque<String> events=new ArrayDeque<>();
    private final ArrayDeque<Modal> modals=new ArrayDeque<>();
    private Locale displayLocale;
    private String destination="overview", shownName, lastFocus="nav-overview";
    private boolean foreground,desired,running,busy,video;
    private int videoWidth,videoHeight;
    private String error;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); applyEmulatorLocale();
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        displayLocale=getResources().getConfiguration().locale;
        prefs=new LegacyPreferences(this); shownName=prefs.name();
        desired=state==null?prefs.autoStart():state.getBoolean("desired");
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
        foreground=false;if(service!=null)service.detach(serviceOwner);video=false;updateAwake();super.onStop();
    }
    @Override protected void onDestroy() {
        if(service!=null)service.detach(serviceOwner);
        if(bound) {unbindService(connection);bound=false;}
        service=null;super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("desired",desired);
        if(calibration!=null)state.putInt("calibration_margin",calibration.margin());
        super.onSaveInstanceState(state);
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if(!config.locale.equals(displayLocale)) {
            while(!modals.isEmpty())modals.peekLast().dismiss();
            finishCalibration(false);displayLocale=config.locale;
        }
        buildShell();fitVideo();
    }
    // This frontend targets pre-API21 Fire Sticks and their hardware Back key.
    // AndroidX's current activity stack cannot be linked on API17.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        if(calibration!=null) {finishCalibration(false);return;}
        if(!modals.isEmpty()) {modals.peekLast().dismiss();return;}
        if (video) { videoDismissed=true;video=false;shell.setVisibility(View.VISIBLE);updateAwake();return; }
        if (!destination.equals("overview")) { destination="overview";render();return; }
        super.onBackPressed();
    }
    private void startIfNeeded() {
        if(!foreground || service==null)return;
        service.attach(serviceOwner,state-> {
            receiverState=state;desired=state.desired;running=state.running;busy=state.busy;shownName=state.name;
            error=state.failure==null?null:getString(R.string.error_starting_server);
            events.clear();events.addAll(state.events);
            if(!state.video)videoDismissed=false;
            video=state.video && !videoDismissed;videoWidth=state.width;videoHeight=state.height;
            shell.setVisibility(video?View.GONE:View.VISIBLE);refreshReceiverUi();fitVideo();updateAwake();
        });
        if(textureTarget!=null)service.showSurface(serviceOwner,textureTarget);
    }
    private void toggleReceiver() {
        if(busy)return;
        desired=!desired;busy=true;render();
        LegacyReceiverService.dispatch(this,desired?LegacyReceiverService.START:LegacyReceiverService.STOP);
    }
    private void restart() {
        shownName=prefs.name();render();LegacyReceiverService.dispatch(this,LegacyReceiverService.RESTART);
    }
    private String statusLabel() {
        return error!=null?error:getString(busy?R.string.waiting_for_connection:running?R.string.buzin_ready:R.string.buzin_stopped);
    }
    // Runtime callbacks must not replace controls between ACTION_DOWN and UP.
    private void refreshReceiverUi() {
        if(receiverStatus!=null) {receiverStatus.setText(statusLabel());receiverStatus.setTextColor(error!=null?0xffffab91:MINT);}
        if(receiverLabel!=null)receiverLabel.setText(shownName);
        if(receiverToggle!=null) {
            receiverToggle.setText(running?R.string.btn_stop:R.string.btn_start);receiverToggle.setEnabled(!busy);
            if(toggleFilled!=!running) {toggleFilled=!running;focus(receiverToggle,toggleFilled);receiverToggle.setTextColor(toggleFilled?0xff082b27:WHITE);}
        }
        if(diagnosticView!=null)diagnosticView.setText(diagnosticText(true));
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
        receiverStatus=receiverLabel=diagnosticView=null;receiverToggle=null;
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
        TextView status=text(this,statusLabel(),12,error!=null?0xffffab91:MINT,false);receiverStatus=status;
        status.setGravity(Gravity.END);
        LinearLayout.LayoutParams statusSize=new LinearLayout.LayoutParams(0,-2,1);statusSize.leftMargin=dp(this,14);header.addView(status,statusSize);addSpaced(page,header,-2);
        LinearLayout columns=new LinearLayout(this);columns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout identity=column(this),steps=column(this);identity.setPadding(0,dp(this,24),dp(this,28),0);steps.setPadding(dp(this,22),dp(this,22),dp(this,22),dp(this,22));
        columns.addView(identity,new LinearLayout.LayoutParams(0,-2,1.1f));columns.addView(steps,new LinearLayout.LayoutParams(0,-2,1));
        addSpaced(identity,text(this,getString(R.string.buzin_hero),32,WHITE,true),-2);
        addSpaced(identity,text(this,getString(R.string.buzin_hero_detail),13,MUTED,false),-2);
        LinearLayout name=column(this);name.setBackground(shape(this,0xff14282d,0xff28554e,1,18));name.setPadding(dp(this,16),dp(this,16),dp(this,16),dp(this,16));
        name.addView(text(this,getString(R.string.buzin_receiver_label),10,MINT,true));receiverLabel=text(this,shownName,23,WHITE,true);name.addView(receiverLabel);name.addView(text(this,getString(R.string.buzin_rename_hint),11,MUTED,false));addSpaced(identity,name,-2);
        LinearLayout actions=new LinearLayout(this);
        Button toggle=button(this,running?R.string.btn_stop:R.string.btn_start,!running,this::toggleReceiver);toggle.setEnabled(!busy);
        receiverToggle=toggle;toggleFilled=!running;
        toggle.setTag("receiver-toggle");
        actions.addView(toggle,new LinearLayout.LayoutParams(-2,dp(this,46)));
        Button about=button(this,R.string.buzin_about,false,this::about);about.setTag("about");LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(-2,dp(this,46));a.leftMargin=dp(this,10);actions.addView(about,a);identity.addView(actions);
        steps.setBackground(shape(this,PANEL,0xff25333e,1,22));addSpaced(steps,text(this,getString(R.string.buzin_connect_heading),18,WHITE,true),-2);
        int[] lines={R.string.buzin_step_wifi,R.string.buzin_step_mirror,R.string.buzin_step_choose};
        for(int i=0;i<lines.length;i++)addSpaced(steps,text(this,String.format(Locale.getDefault(),"%02d  ",i+1)+getString(lines[i]),13,MUTED,false),-2);
        page.addView(columns);
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
        toggle(body,R.string.setting_keep_screen_on,R.string.setting_keep_screen_on_desc,"keep_screen_on",prefs.keepScreenOn());
        toggle(body,R.string.setting_developer_options,R.string.setting_developer_options_desc,"developer_options",prefs.advanced());
    }
    private void toggle(LinearLayout body,int title,int detail,String key,boolean value) {
        LinearLayout row=row(this,getString(title)+" · "+getString(value?R.string.chip_on:R.string.chip_off),getString(detail),()-> {
            prefs.bool(key,!value);updateAwake();render();
            LegacyReceiverService.dispatch(this,LegacyReceiverService.CONFIGURE);
        });row.setTag(key);addSpaced(body,row,-2);
    }
    private void tools() {
        LinearLayout body=page(R.string.tab_tools);
        LinearLayout calibrate=row(this,getString(R.string.tools_calibrate),getString(R.string.setting_tv_margin_desc),()->calibrate(prefs.margin()));
        calibrate.setTag("calibration");addSpaced(body,calibrate,-2);
        addSpaced(body,row(this,getString(R.string.tools_connection_diagnostics),null,()->diagnostics(true)),-2);
        addSpaced(body,row(this,getString(R.string.tools_native_open),getString(R.string.tools_native_help),()-> {
            try {startActivity(new Intent().setComponent(new ComponentName("com.amazon.cast.sink","com.amazon.cast.sink.DisplayMirroringSinkActivity")));}
            catch(ActivityNotFoundException unavailable){Toast.makeText(this,R.string.tools_native_unavailable,Toast.LENGTH_LONG).show();}
        }),-2);
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
        SpannableString brand=new SpannableString("Buzin Solutions\u2009®");brand.setSpan(new SuperscriptSpan(),brand.length()-1,brand.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);brand.setSpan(new RelativeSizeSpan(.7f),brand.length()-1,brand.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);company.addView(text(this,brand,14,WHITE,true));card.addView(company);
        aboutLink(card,R.string.buzin_website,"buzin.solutions",()->open("https://buzin.solutions"));
        aboutLink(card,R.string.buzin_source,"GitHub · HenriqueBuzin/buzin-cast",()->open("https://github.com/HenriqueBuzin/buzin-cast"));
        aboutLink(card,R.string.buzin_licenses,getString(R.string.buzin_licenses_detail),()->message(R.string.buzin_licenses,getString(R.string.buzin_credits)));
        TextView footer=text(this,"© "+Calendar.getInstance().get(Calendar.YEAR)+" Buzin Solutions LTDA",10,MUTED,false);footer.setGravity(Gravity.CENTER);footer.setPadding(0,dp(this,10),0,dp(this,10));outer.addView(footer);
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
        void show() {
            previous=getCurrentFocus();
            if(!modals.isEmpty())modals.peekLast().layer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            shell.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
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
            if(previous!=null && previous.isShown())previous.requestFocus();
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
