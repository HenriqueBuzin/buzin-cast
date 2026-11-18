package io.buzincast.legacy;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import io.buzincast.fireos3probe.LegacyUrlPlayer;
import io.buzincast.fireos3probe.LegacyDacp;
import java.util.concurrent.atomic.AtomicReference;

final class LegacySubtitleScenario {
    private interface Check {boolean ready() throws Exception;}
    private static void await(Check check) throws Exception {
        long end=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<end) {if(check.ready())return;SystemClock.sleep(20);}
        throw new AssertionError("Subtitle did not settle at "+new Throwable().getStackTrace()[1]);
    }
    private static void ui(Instrumentation runner,Runnable action) {
        AtomicReference<Throwable> failure=new AtomicReference<>();runner.runOnMainSync(()-> {try {action.run();}catch(Throwable error) {failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private static LegacyUrlPlayer.State state(Instrumentation runner,LegacyReceiverService service) {
        AtomicReference<LegacyUrlPlayer.State> result=new AtomicReference<>();ui(runner,()->result.set(service.urlState()));return result.get();
    }
    private static Uri fixture(String name) {return Uri.parse("content://com.buzincast.legacy.subtitle.fixture/"+name);}
    private static void choose(Instrumentation runner,Activity activity,String name) throws Exception {
        IntentFilter filter=new IntentFilter(Intent.ACTION_GET_CONTENT);filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("*/*");
        Instrumentation.ActivityMonitor monitor=runner.addMonitor(filter,new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(fixture(name))),true);
        try {
            ui(runner,()->activity.findViewById(android.R.id.content).findViewWithTag("url-subtitles").performClick());
            runner.waitForIdleSync();
            ui(runner,()->activity.findViewById(android.R.id.content).findViewWithTag("subtitle-choose").performClick());
            await(()->monitor.getHits()==1);
        }finally {runner.removeMonitor(monitor);}
    }
    static void check(Instrumentation runner,Activity activity,LegacyReceiverService service) throws Exception {
        boolean background=new LegacyPreferences(activity).background();
        long position=state(runner,service).positionMs;
        ui(runner,()->service.urlSpeed(1.5f));
        await(()->state(runner,service).speed==1.5f);
        choose(runner,activity,"srt");
        await(()-> {LegacyUrlPlayer.State value=state(runner,service);return value!=null && value.phase==LegacyUrlPlayer.Phase.READY && value.externalSubtitle;});
        if(state(runner,service).playing || state(runner,service).speed!=1.5f || Math.abs(state(runner,service).positionMs-position)>200)throw new AssertionError("Import changed pause/position/speed");
        // Media3's legacy text decoder delivers cues after the renderer starts;
        // importing while paused must not force playback just to obtain a cue.
        ui(runner,()->service.control(LegacyDacp.Command.PLAY,null));
        await(()->state(runner,service).caption.equals("Synthetic SRT caption"));
        ui(runner,()->service.control(LegacyDacp.Command.PAUSE,null));await(()->!state(runner,service).playing);
        ui(runner,()-> {
            View root=activity.findViewById(android.R.id.content);TextView caption=root.findViewWithTag("url-caption");
            if(!caption.isShown() || !caption.getText().toString().equals("Synthetic SRT caption"))throw new AssertionError("Caption was not displayed");
            if(!root.findViewWithTag("url-subtitles").isSelected())throw new AssertionError("CC active state is missing");
        });
        choose(runner,activity,"vtt");
        await(()-> {LegacyUrlPlayer.State value=state(runner,service);return value!=null && value.phase==LegacyUrlPlayer.Phase.READY && value.externalSubtitle && value.caption.isEmpty();});
        if(state(runner,service).playing)throw new AssertionError("VTT resumed paused playback");
        ui(runner,()->service.control(LegacyDacp.Command.PLAY,null));
        await(()->state(runner,service).caption.equals("Synthetic VTT caption"));
        ui(runner,()->service.control(LegacyDacp.Command.PAUSE,null));await(()->!state(runner,service).playing);
        for(String invalid:new String[]{"invalid","malformed","oversized"}) {
            try {LegacySubtitleImport.read(activity,fixture(invalid));throw new AssertionError("Invalid subtitle accepted: "+invalid);}
            catch(IllegalArgumentException | java.nio.charset.CharacterCodingException expected) { }
        }
        try {LegacySubtitleImport.read(activity,Uri.parse("https://example.invalid/sub.srt"));throw new AssertionError("Remote import accepted");}
        catch(IllegalArgumentException expected) { }
        ui(runner,()-> {
            View root=activity.findViewById(android.R.id.content);root.findViewWithTag("url-subtitles").performClick();root.findViewWithTag("subtitle-disable").performClick();
        });
        await(()-> {LegacyUrlPlayer.State value=state(runner,service);return value!=null && value.phase==LegacyUrlPlayer.Phase.READY && !value.externalSubtitle && value.caption.isEmpty();});
        if(state(runner,service).playing)throw new AssertionError("Disable resumed paused playback");
        ui(runner,()->service.urlSpeed(1));await(()->state(runner,service).speed==1);
        ui(runner,()-> {
            if(activity.findViewById(android.R.id.content).findViewWithTag("url-caption").getVisibility()!=View.GONE)throw new AssertionError("Caption remained after disable");
            new LegacyPreferences(activity).bool("run_in_background",false);
            try {
                java.lang.reflect.Field owner=activity.getClass().getDeclaredField("serviceOwner");owner.setAccessible(true);
                service.beginSubtitlePicker(service.subtitleSession());service.detach(owner.get(activity));
            }catch(Exception error) {throw new AssertionError(error);}
        });
        SystemClock.sleep(300);
        if(state(runner,service)==null)throw new AssertionError("Picker stopped playback with background disabled");
        ui(runner,()-> {try {
            java.lang.reflect.Method attach=activity.getClass().getDeclaredMethod("startIfNeeded");attach.setAccessible(true);attach.invoke(activity);
            new LegacyPreferences(activity).bool("run_in_background",background);
        }catch(Exception error) {throw new AssertionError(error);}});
    }
}
