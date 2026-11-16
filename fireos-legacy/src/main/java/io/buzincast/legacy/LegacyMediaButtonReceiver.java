package io.buzincast.legacy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Parcelable;
import android.view.KeyEvent;

/** Private PendingIntent target registered with the platform audio service. */
public final class LegacyMediaButtonReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        if(intent==null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction()))return;
        Parcelable payload=intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if(!(payload instanceof KeyEvent))return;
        KeyEvent event=(KeyEvent)payload;
        if(!LegacyMediaKeys.handles(event.getKeyCode()) ||
                event.getAction()!=KeyEvent.ACTION_DOWN && event.getAction()!=KeyEvent.ACTION_UP)return;
        LegacyReceiverService.mediaButton(context,event,intent.getIntExtra("media_epoch",-1));
        if(isOrderedBroadcast())abortBroadcast();
    }
}
