package io.buzincast.legacy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class LegacyBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        String action=intent.getAction();
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action) && !"android.intent.action.QUICKBOOT_POWERON".equals(action))return;
        if(new LegacyPreferences(context).bootAutoStart())LegacyReceiverService.dispatch(context,LegacyReceiverService.BOOT);
    }
}
