package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Platform-only instrumentation: AndroidX's current runner cannot run on API17. */
public final class LegacyGestureChecks extends Instrumentation {
    private Activity activity;
    private boolean controls,metadata,systemMedia;
    @Override public void onCreate(Bundle arguments) {
        androidx.multidex.MultiDex.installInstrumentation(getContext(),getTargetContext());
        systemMedia=arguments!=null && "system".equals(arguments.getString("checks"));
        metadata=systemMedia || arguments!=null && "metadata".equals(arguments.getString("checks"));
        controls=metadata || arguments!=null && "controls".equals(arguments.getString("checks"));
        super.onCreate(arguments);start();
    }
    @Override public void onStart() {
        Bundle result=new Bundle();int status=Activity.RESULT_OK;
        try {
            if(controls) {
                activity=LegacyControlScenario.run(this,metadata,systemMedia);result.putString("status","pass");
                result.putString("controls","native_rtsp_mdns_http_touch_media_keys_and_sender_replacement");
                if(metadata)result.putString("metadata","synthetic_native_worker_packets_artwork_progress_pause_ui_and_sender_cleanup");
                if(systemMedia) {
                    result.putString("system_media","platform_metadata_artwork_background_keys_and_cleanup");
                    result.putString("plain_audio","native_decode_focus_without_metadata_idle_permanent_loss_and_explicit_restart");
                    result.putString("mirror_modes","three_headless_native_packet_rtsp_teardown_metadata_and_focus_cycles");
                }
            } else {
            activity=startActivitySync(new Intent().setClassName(getTargetContext(),"io.buzincast.legacy.LegacyActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            awaitReceiver(true);
            checkGesture(false);checkGesture(true);
            result.putString("status","pass");result.putString("gestures","touch_and_dpad_across_receiver_restart");
            }
        } catch(Throwable failure) {status=Activity.RESULT_CANCELED;result.putString("status","failed");result.putString("error",failure.toString());}
        finally {
            try {
                ui(()-> {
                    if(activity!=null)control("nav-overview").performClick();
                    command("stop");
                });
                if(activity!=null) {awaitReceiver(false);ui(()->activity.finish());}
            } catch(Throwable cleanup) {status=Activity.RESULT_CANCELED;result.putString("cleanup_error",cleanup.toString());}
        }
        finish(status,result);
    }
    private View control(String tag) {
        View found=activity.findViewById(android.R.id.content).findViewWithTag(tag);
        if(found==null)throw new AssertionError("Missing control: "+tag);
        return found;
    }
    private void awaitReceiver(boolean running) {
        long deadline=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<deadline) {
            AtomicBoolean ready=new AtomicBoolean();
            ui(()-> {
                // Keep the settings page open: callbacks must not remove its nav.
                Button toggle=(Button)control("receiver-toggle");
                int label=activity.getResources().getIdentifier(running?"btn_stop":"btn_start","string",activity.getPackageName());
                TextView state=(TextView)control("receiver-status");
                int status=activity.getResources().getIdentifier(running?"buzin_ready":"buzin_stopped","string",activity.getPackageName());
                ready.set(toggle.isEnabled() && toggle.getText().toString().equals(activity.getString(label)) && state.getText().toString().equals(activity.getString(status)));
            });
            if(ready.get())return;
            SystemClock.sleep(10);
        }
        throw new AssertionError("Receiver did not settle: "+running);
    }
    private void command(String action) {
        getTargetContext().startService(new Intent().setClassName(getTargetContext(),"io.buzincast.legacy.LegacyReceiverService").setAction(action));
    }
    private void checkGesture(boolean keyboard) throws Exception {
        ui(()->control("nav-settings").performClick());waitForIdleSync();
        int[] location=new int[2];
        ui(()-> {View target=control("nav-overview");target.requestFocus();target.getLocationOnScreen(location);
            location[0]+=target.getWidth()/2;location[1]+=target.getHeight()/2;});
        try(Socket previous=new Socket("127.0.0.1",7000)) {
            previous.setSoTimeout(20000);options(previous);
            long down=SystemClock.uptimeMillis();
            if(keyboard)sendKeySync(new KeyEvent(down,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,0));
            else pointer(down,MotionEvent.ACTION_DOWN,location);
            ui(()->command("restart"));
            // The old connection must close before the new listener answers.
            try {if(previous.getInputStream().read()!=-1)throw new AssertionError("Old receiver socket did not close");}
            catch(SocketException closed) { /* A reset also proves retirement. */ }
            long deadline=SystemClock.elapsedRealtime()+20000;boolean restarted=false;
            while(SystemClock.elapsedRealtime()<deadline) {
                try(Socket next=new Socket("127.0.0.1",7000)) {next.setSoTimeout(2000);options(next);restarted=true;break;}
                catch(java.io.IOException starting) {SystemClock.sleep(20);}
            }
            if(!restarted)throw new AssertionError("Receiver did not restart while the gesture was held");
            if(keyboard)sendKeySync(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER,0));
            else pointer(down,MotionEvent.ACTION_UP,location);
            waitForIdleSync();
            ui(()-> {if(!control("nav-overview").isSelected())throw new AssertionError("Gesture was lost across receiver restart");});
        }
        awaitReceiver(true);
    }
    private void options(Socket socket) throws Exception {
        socket.getOutputStream().write("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".getBytes("US-ASCII"));
        ByteArrayOutputStream response=new ByteArrayOutputStream();int matched=0;byte[] ending={13,10,13,10};
        while(matched<4 && response.size()<16384) {
            int value=socket.getInputStream().read();if(value<0)throw new java.io.IOException("RTSP closed before reply");
            response.write(value);matched=value==ending[matched]?matched+1:value==13?1:0;
        }
        if(matched!=4 || !response.toString("US-ASCII").startsWith("RTSP/1.0 200 OK\r\n"))throw new java.io.IOException("RTSP did not answer OPTIONS");
    }
    private void ui(Runnable action) {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        runOnMainSync(()-> {try {action.run();}catch(Throwable error) {failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private void pointer(long down,int action,int[] point) {
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,point[0],point[1],0);
        try {sendPointerSync(event);}finally {event.recycle();}
    }
}
