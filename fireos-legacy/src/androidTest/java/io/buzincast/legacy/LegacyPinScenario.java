package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import io.buzincast.fireos3probe.LegacyReceiverSession;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import static io.buzincast.legacy.LegacyApprovalScenario.*;

/** UI/lifecycle half; the host independently verifies actual SRP and AES proofs. */
final class LegacyPinScenario {
    static void write(Activity activity,String name,String value) throws Exception {
        File temporary=new File(activity.getFilesDir(),name+".part"),destination=new File(activity.getFilesDir(),name);
        try(FileOutputStream out=new FileOutputStream(temporary)) {out.write(value.getBytes("UTF-8"));}
        if(!temporary.renameTo(destination))throw new AssertionError("Could not publish PIN test stage");
    }
    static void pinMode(Instrumentation runner,Activity activity,LegacyReceiverService service,boolean enabled) throws Exception {
        ui(runner,()-> {
            View root=activity.findViewById(android.R.id.content);root.findViewWithTag("nav-settings").performClick();
            if(new LegacyPreferences(activity).requirePin()!=enabled)root.findViewWithTag("require_pin").performClick();
        });
        runner.waitForIdleSync();
        await(()->!(Boolean)read(runner,service,"stopping") && !(Boolean)read(runner,service,"busy") && (Boolean)read(runner,service,"running"));
        ui(runner,()->activity.findViewById(android.R.id.content).findViewWithTag("nav-overview").performClick());
        if(new LegacyPreferences(activity).options().requirePin!=enabled)throw new AssertionError("PIN preference did not reach receiver options");
        LegacyReceiverSession session=(LegacyReceiverSession)read(runner,service,"session");
        Class<?> bridge=Class.forName("io.buzincast.fireos3probe.NativeReceiver");
        java.lang.reflect.Method txt=bridge.getDeclaredMethod("receiverTxt",long.class,boolean.class);txt.setAccessible(true);
        Object lock=read(runner,session,"nativeLock");long handle=(Long)read(runner,session,"handle");
        synchronized(lock) {
            for(boolean airplay:new boolean[]{true,false}) {
                String[] records=(String[])txt.invoke(null,handle,airplay);boolean password=false;
                for(int i=0;i+1<records.length;i+=2)if(records[i].equals("pw") && records[i+1].equals(Boolean.toString(enabled)))password=true;
                if(!password)throw new AssertionError("Native DNS-SD password flag differs from PIN mode");
            }
        }
    }
    static void host(Instrumentation runner,Activity activity,LegacyReceiverService service,String stage,boolean expectPin) throws Exception {
        File value=new File(activity.getFilesDir(),"pin-value");if(value.exists() && !value.delete())throw new AssertionError("Stale PIN test file");
        write(activity,"pin-port",Integer.toString(receiverPort(runner,service)));
        write(activity,"pin-request",stage);
        File acknowledged=new File(activity.getFilesDir(),"pin-ack-"+stage);boolean published=false;
        long until=SystemClock.elapsedRealtime()+90000;
        while(!acknowledged.isDirectory() && SystemClock.elapsedRealtime()<until) {
            String code=(String)read(runner,service,"pinCode");
            if(!expectPin && code!=null)throw new AssertionError("Disabled PIN mode displayed a code");
            if(expectPin && code!=null && !published) {
                if(!code.matches("[0-9]{4}"))throw new AssertionError("Native PIN is not four ASCII digits");
                ui(runner,()-> {
                    View root=activity.findViewById(android.R.id.content);TextView shown=root.findViewWithTag("pin-code");View close=root.findViewWithTag("pin-close");
                    if(shown==null || !shown.getText().toString().equals(code) || close==null || !close.isFocused())throw new AssertionError("PIN did not reach the focused native dialog");
                });
                if(!((String)read(runner,service,"notificationText")).contains(code))throw new AssertionError("Foreground PIN notification was not updated");
                if(stage.equals("pairing")) {
                    capture(runner,activity);
                    File rendered=new File(activity.getFilesDir(),"legacy-approval.b64");
                    if(!rendered.renameTo(new File(activity.getFilesDir(),"legacy-pin.b64")))throw new AssertionError("PIN render was not saved");
                }
                write(activity,"pin-value",code);published=true;
            }
            SystemClock.sleep(20);
        }
        if(!acknowledged.isDirectory() || expectPin && !published)throw new AssertionError("Host did not complete PIN stage: "+stage);
    }
    static void dismiss(Instrumentation runner,Activity activity,LegacyReceiverService service) throws Exception {
        ui(runner,()->activity.findViewById(android.R.id.content).findViewWithTag("pin-close").performClick());
        await(()->read(runner,service,"pinCode")==null && read(runner,activity,"pinModal")==null);
    }
    static void check(Instrumentation runner,Activity activity) throws Exception {
        AtomicReference<LegacyReceiverService> owner=new AtomicReference<>();
        await(()-> {owner.set((LegacyReceiverService)read(runner,activity,"service"));return owner.get()!=null;});
        LegacyReceiverService service=owner.get();
        pinMode(runner,activity,service,false);
        try {
            pinMode(runner,activity,service,true);host(runner,activity,service,"pairing",true);dismiss(runner,activity,service);
            ui(runner,()->activity.moveTaskToBack(true));await(()->!(Boolean)read(runner,activity,"foreground"));
            host(runner,activity,service,"background",true);await(()->(Boolean)read(runner,activity,"foreground"));
            ui(runner,activity::onBackPressed);await(()->read(runner,service,"pinCode")==null);
            pinMode(runner,activity,service,false);host(runner,activity,service,"disabled",false);
            pinMode(runner,activity,service,true);host(runner,activity,service,"stop",true);
            ui(runner,()->LegacyReceiverService.dispatch(service,LegacyReceiverService.STOP));
            await(()->read(runner,service,"session")==null && read(runner,service,"pinCode")==null && read(runner,activity,"pinModal")==null);
        } finally {
            ui(runner,()-> {new LegacyPreferences(activity).bool("require_pin",false);LegacyReceiverService.dispatch(service,LegacyReceiverService.START);LegacyReceiverService.dispatch(service,LegacyReceiverService.CONFIGURE);});
            await(()->!(Boolean)read(runner,service,"busy") && (Boolean)read(runner,service,"running"));
        }
    }
}
